package dev.jdtech.jellyfin.player.local.domain

import android.app.Application
import android.widget.Toast
import androidx.annotation.StringRes
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.player.local.R
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.extensions.syncPlayApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.GroupUpdate
import org.jellyfin.sdk.model.api.JoinGroupRequestDto
import org.jellyfin.sdk.model.api.NewGroupRequestDto
import org.jellyfin.sdk.model.api.PingRequestDto
import org.jellyfin.sdk.model.api.PlayQueueUpdate
import org.jellyfin.sdk.model.api.PlayRequestDto
import org.jellyfin.sdk.model.api.PlaybackRequestType
import org.jellyfin.sdk.model.api.SeekRequestDto
import org.jellyfin.sdk.model.api.SendCommand
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupDoesNotExistUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupLeftUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage
import org.jellyfin.sdk.model.api.SyncPlayLibraryAccessDeniedUpdate
import org.jellyfin.sdk.model.api.SyncPlayNotInGroupUpdate
import org.jellyfin.sdk.model.api.SyncPlayPlayQueueUpdate
import org.jellyfin.sdk.model.api.SyncPlayStateUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserLeftUpdate
import timber.log.Timber

/**
 * App-wide SyncPlay session. Keeps the group membership, the WebSocket subscription and the clock
 * offset to the server, independent of the player, so groups can be created and joined from
 * anywhere and the app stays in the group while browsing.
 *
 * The player attaches a [SyncPlayController] that applies the group's [commands] and reports back.
 * When the group starts a title that is not playing here, [pendingOpen] asks the app to open it.
 */
@Singleton
class SyncPlayManager
@Inject
constructor(private val application: Application, private val jellyfinApi: JellyfinApi) {
    private val api = jellyfinApi.api
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val socket = SyncPlaySocket(api)

    private val _state = MutableStateFlow<SyncPlayState?>(null)
    /** The group we are in, or null when not in a group. */
    val state = _state.asStateFlow()

    private val _queue = MutableStateFlow<QueueItem?>(null)
    /** What the group is watching, or null when nothing is queued. */
    val queue = _queue.asStateFlow()

    private val _commands = MutableSharedFlow<SendCommand>(extraBufferCapacity = 16)
    /** Playback commands from the group, applied by the attached player. */
    val commands = _commands.asSharedFlow()

    private val _pendingOpen = MutableStateFlow<QueueItem?>(null)
    /** A title the group started that is not open in the player yet. */
    val pendingOpen = _pendingOpen.asStateFlow()

    private val _userImages = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Profile picture URLs by user name, for the users we could look up. */
    val userImages = _userImages.asStateFlow()

    /** The item open in the player, set by the attached [SyncPlayController]. */
    var playerItemId: UUID? = null

    var groupId: UUID? = null
        private set

    // Server time minus local time, measured like NTP. Samples with the lowest ping are the most
    // accurate.
    private var timeOffsetMs = 0L
    private val timeSamples = ArrayDeque<TimeSample>()

    private val _isStuck = MutableStateFlow(false)
    /**
     * The group has been waiting for a while although we are ready, likely for a client that missed
     * a command. [forceStart] gets it going again.
     */
    val isStuck = _isStuck.asStateFlow()

    /**
     * Whether the attached player is ready to play the group's position, set by the
     * [SyncPlayController]. Without a player there is nothing to wait for here.
     */
    var isLocalReady = true

    // Resume playback once the group is ready after a seek that paused it, see [requestSeek]
    private var resumeAfterSeek = false
    // Whether the group played before it started waiting, so it should play again afterwards
    private var playingBeforeWait = false

    // Playback requests are sent one after another so they reach the server in order
    private val requestMutex = Mutex()

    private var socketJob: Job? = null
    private var timeSyncJob: Job? = null
    private var waitJob: Job? = null

    suspend fun getGroups(): List<GroupInfoDto> {
        // Listen for group updates already, the server may still count us as a member of a group
        // from before the app restarted or lost its connection
        subscribe()
        return api.syncPlayApi.syncPlayGetGroups().content
    }

    fun createGroup() {
        launchRequest {
            connect()
            val userName = jellyfinApi.userApi.getCurrentUser().content.name.orEmpty()
            val groupName = application.getString(R.string.syncplay_group_name, userName)
            api.syncPlayApi.syncPlayCreateGroup(NewGroupRequestDto(groupName))
        }
    }

    fun joinGroup(groupId: UUID) {
        launchRequest {
            connect()
            api.syncPlayApi.syncPlayJoinGroup(JoinGroupRequestDto(groupId))
            // Joining a group we are still a member of is a restore, the server sends no joined
            // update then
            delay(1_000)
            if (this.groupId == null) restoreGroup(groupId)
        }
    }

    fun leaveGroup() {
        if (groupId == null) return
        launchRequest { api.syncPlayApi.syncPlayLeaveGroup() }
        reset()
        showMessage(R.string.syncplay_left_group)
    }

    /** Makes [itemId] the group's title, starting at [positionTicks]. */
    fun setQueue(itemId: UUID, positionTicks: Long) {
        launchRequest {
            api.syncPlayApi.syncPlaySetNewQueue(
                PlayRequestDto(
                    playingQueue = listOf(itemId),
                    playingItemPosition = 0,
                    startPositionTicks = positionTicks,
                )
            )
        }
    }

    fun requestUnpause() {
        launchPlaybackRequest { api.syncPlayApi.syncPlayUnpause() }
    }

    fun requestPause() {
        resumeAfterSeek = false
        launchPlaybackRequest { api.syncPlayApi.syncPlayPause() }
    }

    /**
     * Starts playback even though the group is still waiting for someone. The server takes a second
     * unpause while waiting as the signal to stop waiting, the first one only marks that the group
     * should play once everyone is ready.
     */
    fun forceStart() {
        Timber.tag(TAG).d("Forcing playback")
        resumeAfterSeek = false
        _isStuck.value = false
        launchPlaybackRequest {
            api.syncPlayApi.syncPlayUnpause()
            if (_state.value?.isWaiting == true) api.syncPlayApi.syncPlayUnpause()
        }
    }

    fun requestSeek(positionTicks: Long) {
        val isPlaying = _state.value?.groupState == GroupStateType.PLAYING
        if (isPlaying) resumeAfterSeek = true
        launchPlaybackRequest {
            if (isPlaying) {
                // The web client doesn't report ready after a seek while the group plays, so the
                // group waits for it until the server gives up after 30 s. A seek while paused
                // works, so pause first and resume once everyone is ready.
                api.syncPlayApi.syncPlayPause()
                // Let the others finish pausing before seeking. The web client otherwise reports
                // ready for the pause at the old position, the server's correction gets lost and
                // the group keeps waiting.
                withTimeoutOrNull(2_000) {
                    _state.first { it?.groupState == GroupStateType.PAUSED }
                }
                delay(SEEK_AFTER_PAUSE_MS)
            }
            api.syncPlayApi.syncPlaySeek(SeekRequestDto(positionTicks))
        }
    }

    fun sendReady(positionTicks: Long, isPlaying: Boolean, playlistItemId: UUID) {
        isLocalReady = true
        sendPlaybackReport("/SyncPlay/Ready", positionTicks, isPlaying, playlistItemId)
    }

    fun sendBuffering(positionTicks: Long, isPlaying: Boolean, playlistItemId: UUID) {
        isLocalReady = false
        sendPlaybackReport("/SyncPlay/Buffering", positionTicks, isPlaying, playlistItemId)
    }

    /**
     * Reports where we are at the current server time. The SDK writes times with the device's UTC
     * offset, which the server then compares to its UTC clock as if it were UTC when it runs in a
     * time zone other than UTC, so the time is sent in UTC like the web client does.
     */
    private fun sendPlaybackReport(
        path: String,
        positionTicks: Long,
        isPlaying: Boolean,
        playlistItemId: UUID,
    ) {
        launchPlaybackRequest(attempts = 3) {
            // Built right before sending, the server distrusts reports older than 2 s
            val body = buildJsonObject {
                put(
                    "When",
                    Instant.ofEpochMilli(System.currentTimeMillis() + timeOffsetMs).toString(),
                )
                put("PositionTicks", positionTicks)
                put("IsPlaying", isPlaying)
                put("PlaylistItemId", playlistItemId.toString())
            }
            api.request(HttpMethod.POST, path, requestBody = body)
        }
    }

    /**
     * Whether the app can open [itemId] in the player. The web client puts theme songs in the group
     * when someone opens an item's page, those are not worth leaving the player for.
     */
    suspend fun isPlayableVideo(itemId: UUID): Boolean =
        try {
            jellyfinApi.libraryApi.getItem(itemId, jellyfinApi.userId).content.type in
                setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e)
            true
        }

    fun consumePendingOpen() {
        _pendingOpen.value = null
    }

    /** Converts a server time to local epoch milliseconds. */
    fun toLocalMillis(serverTime: LocalDateTime): Long =
        serverTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() - timeOffsetMs

    private suspend fun connect() {
        subscribe()
        withTimeout(10_000) { socket.isConnected.first { it } }
        startTimeSync()
    }

    private fun subscribe() {
        if (socketJob == null) {
            socketJob = scope.launch {
                launch {
                    // Updates sent while the socket was down are lost, catch up after reconnecting
                    var wasConnected = false
                    socket.isConnected.collect { connected ->
                        val id = groupId
                        if (connected && wasConnected && id != null) rejoinGroup(id)
                        if (connected) wasConnected = true
                    }
                }
                socket.messages().collect { message ->
                    when (message) {
                        is SyncPlayGroupUpdateMessage -> onGroupUpdate(message.data)
                        is SyncPlayCommandMessage ->
                            message.data?.let { command ->
                                Timber.tag(TAG).d("Command: $command")
                                if (groupId == null) restoreGroup(command.groupId)
                                if (command.groupId == groupId) _commands.emit(command)
                            }
                        else -> Unit
                    }
                }
            }
        }
    }

    private suspend fun startTimeSync() {
        syncTime()
        if (timeSyncJob == null) {
            timeSyncJob = scope.launch {
                repeat(7) {
                    delay(1_000)
                    syncTime()
                }
                while (true) {
                    delay(60_000)
                    syncTime()
                }
            }
        }
    }

    private suspend fun syncTime() {
        try {
            val sendTime = System.currentTimeMillis()
            val response = jellyfinApi.systemApi.getUtcTime().content
            val receiveTime = System.currentTimeMillis()
            val serverReceiveTime = response.requestReceptionTime.toEpochMilli()
            val serverSendTime = response.responseTransmissionTime.toEpochMilli()
            timeSamples.addLast(
                TimeSample(
                    offset = ((serverReceiveTime - sendTime) + (serverSendTime - receiveTime)) / 2,
                    ping = (receiveTime - sendTime) - (serverSendTime - serverReceiveTime),
                )
            )
            if (timeSamples.size > 8) timeSamples.removeFirst()
            val best = timeSamples.minBy { it.ping }
            timeOffsetMs = best.offset
            // The server only accepts pings from group members
            if (groupId != null) {
                api.syncPlayApi.syncPlayPing(PingRequestDto(best.ping.coerceAtLeast(0)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    /** Looks up profile pictures for [names] we don't know yet. SyncPlay only gives user names. */
    private fun loadUserImages(names: List<String>) {
        if (names.all { it in _userImages.value }) return
        scope.launch {
            try {
                val users =
                    try {
                        // Lists all users, only allowed for administrators
                        jellyfinApi.userApi.getUsers().content
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        jellyfinApi.userApi.getPublicUsers().content +
                            jellyfinApi.userApi.getCurrentUser().content
                    }
                _userImages.value =
                    users
                        .filter { it.name != null && it.primaryImageTag != null }
                        .associate {
                            it.name!! to
                                api.createUrl(
                                    "/Users/${it.id}/Images/Primary",
                                    queryParameters = mapOf("tag" to it.primaryImageTag),
                                )
                        }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    private fun onGroupUpdate(update: GroupUpdate) {
        Timber.tag(TAG).d("Group update: $update")
        if (groupId == null && isMemberUpdate(update)) {
            // The server still counts us as a member, e.g. after the app restarted
            restoreGroup(update.groupId)
            if (update !is SyncPlayPlayQueueUpdate) return
        }
        when (update) {
            is SyncPlayGroupJoinedUpdate -> {
                groupId = update.groupId
                _state.value =
                    SyncPlayState(
                        groupName = update.data.groupName,
                        participants = update.data.participants,
                        groupState = update.data.state,
                    )
                showMessage(R.string.syncplay_joined_group, update.data.groupName)
                loadUserImages(update.data.participants)
                onGroupState(update.data.state, null)
            }
            is SyncPlayUserJoinedUpdate -> {
                loadUserImages(listOf(update.data))
                // The server lists users, not devices, so a user joining on a second device is
                // already in the list
                _state.update {
                    if (it == null || update.data in it.participants) it
                    else it.copy(participants = it.participants + update.data)
                }
                showMessage(R.string.syncplay_user_joined, update.data)
            }
            is SyncPlayUserLeftUpdate -> {
                // The user may still be in the group on another device, ask the server
                refreshParticipants(update.groupId)
                showMessage(R.string.syncplay_user_left, update.data)
            }
            is SyncPlayStateUpdate -> {
                val groupState = update.data.state
                val previousState = _state.value?.groupState
                _state.update { it?.copy(groupState = groupState) }
                onGroupState(groupState, previousState)
                if (resumeAfterSeek) {
                    when {
                        // Everyone is ready after our seek, continue playback
                        groupState == GroupStateType.PAUSED &&
                            update.data.reason == PlaybackRequestType.READY -> {
                            resumeAfterSeek = false
                            requestUnpause()
                        }
                        // Someone else started or stopped playback in the meantime
                        groupState == GroupStateType.PLAYING || groupState == GroupStateType.IDLE ->
                            resumeAfterSeek = false
                    }
                }
            }
            is SyncPlayPlayQueueUpdate -> onPlayQueueUpdate(update.data)
            is SyncPlayGroupLeftUpdate,
            is SyncPlayNotInGroupUpdate -> {
                if (groupId != null) showMessage(R.string.syncplay_left_group)
                reset()
            }
            is SyncPlayGroupDoesNotExistUpdate -> {
                showMessage(R.string.syncplay_group_does_not_exist)
                reset()
            }
            is SyncPlayLibraryAccessDeniedUpdate -> {
                showMessage(R.string.syncplay_library_access_denied)
                leaveGroup()
            }
            else -> Unit
        }
    }

    /**
     * Watches for a group that keeps waiting although we are ready. Clients sometimes miss the
     * server's correction after a seek and never report ready, the server then waits for them for a
     * long time. The group is started anyway when it should play, otherwise [isStuck] offers it.
     */
    private fun onGroupState(groupState: GroupStateType, previousState: GroupStateType?) {
        if (groupState != GroupStateType.WAITING) {
            waitJob?.cancel()
            waitJob = null
            _isStuck.value = false
            return
        }
        if (waitJob?.isActive == true) return
        playingBeforeWait = previousState == GroupStateType.PLAYING
        waitJob = scope.launch {
            delay(STUCK_TIMEOUT_MS)
            // We may be the one the group waits for, that is not stuck
            while (!isLocalReady) delay(500)
            Timber.tag(TAG).w("Group still waiting, resume=${resumeAfterSeek || playingBeforeWait}")
            if (resumeAfterSeek || playingBeforeWait) {
                forceStart()
            } else {
                _isStuck.value = true
            }
        }
    }

    /** Joins [id] again after the socket reconnected, the server then sends the group's state. */
    private fun rejoinGroup(id: UUID) {
        Timber.tag(TAG).d("Socket reconnected, rejoining")
        launchRequest {
            api.syncPlayApi.syncPlayJoinGroup(JoinGroupRequestDto(id))
            val group = api.syncPlayApi.syncPlayGetGroup(id).content
            if (groupId != id) return@launchRequest
            _state.update { it?.copy(participants = group.participants, groupState = group.state) }
        }
    }

    private fun refreshParticipants(id: UUID) {
        launchRequest {
            val participants = api.syncPlayApi.syncPlayGetGroup(id).content.participants
            if (groupId == id) _state.update { it?.copy(participants = participants) }
        }
    }

    private fun isMemberUpdate(update: GroupUpdate): Boolean =
        when (update) {
            is SyncPlayUserJoinedUpdate,
            is SyncPlayUserLeftUpdate,
            is SyncPlayStateUpdate,
            is SyncPlayPlayQueueUpdate -> true
            else -> false
        }

    private fun restoreGroup(id: UUID) {
        groupId = id
        launchRequest {
            val group = api.syncPlayApi.syncPlayGetGroup(id).content
            if (groupId != id) return@launchRequest
            _state.value =
                SyncPlayState(
                    groupName = group.groupName,
                    participants = group.participants,
                    groupState = group.state,
                )
            showMessage(R.string.syncplay_joined_group, group.groupName)
            loadUserImages(group.participants)
            onGroupState(group.state, null)
            startTimeSync()
        }
    }

    private fun onPlayQueueUpdate(update: PlayQueueUpdate) {
        val queueItem = update.playlist.getOrNull(update.playingItemIndex)
        if (queueItem == null) {
            _queue.value = null
            return
        }
        val item =
            QueueItem(
                itemId = queueItem.itemId,
                playlistItemId = queueItem.playlistItemId,
                startPositionTicks = update.startPositionTicks,
                isPlaying = update.isPlaying,
                lastUpdateMillis = toLocalMillis(update.lastUpdate),
            )
        val isNewTitle = item.playlistItemId != _queue.value?.playlistItemId
        _queue.value = item
        // The group started a title that is not open here, ask the app to open it
        if (isNewTitle && item.itemId != playerItemId) _pendingOpen.value = item
    }

    private fun reset() {
        groupId = null
        resumeAfterSeek = false
        playingBeforeWait = false
        waitJob?.cancel()
        waitJob = null
        _isStuck.value = false
        _state.value = null
        _queue.value = null
        _pendingOpen.value = null
        timeSyncJob?.cancel()
        timeSyncJob = null
        socketJob?.cancel()
        socketJob = null
    }

    private fun launchRequest(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e)
                showMessage(R.string.syncplay_request_failed)
            }
        }
    }

    /**
     * Sends a playback request after the ones before it finished, so e.g. a pause and the seek
     * after it don't overtake each other on a slow connection. Retries [attempts] times when the
     * request fails.
     */
    private fun launchPlaybackRequest(attempts: Int = 1, block: suspend () -> Unit) {
        launchRequest {
            requestMutex.withLock {
                repeat(attempts - 1) {
                    try {
                        return@withLock block()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Request failed, retrying")
                        delay(500)
                    }
                }
                block()
            }
        }
    }

    private fun showMessage(@StringRes resId: Int, vararg formatArgs: Any) {
        val message = application.getString(resId, *formatArgs)
        Toast.makeText(application, message, Toast.LENGTH_SHORT).show()
    }

    // The SDK converts server times to the device's time zone
    private fun LocalDateTime.toEpochMilli(): Long =
        atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    data class SyncPlayState(
        val groupName: String,
        val participants: List<String>,
        val groupState: GroupStateType,
    ) {
        val isWaiting: Boolean
            get() = groupState == GroupStateType.WAITING
    }

    /** The title a group is watching and where it was at [lastUpdateMillis] (local time). */
    data class QueueItem(
        val itemId: UUID,
        val playlistItemId: UUID,
        val startPositionTicks: Long,
        val isPlaying: Boolean,
        val lastUpdateMillis: Long,
    )

    private data class TimeSample(val offset: Long, val ping: Long)

    companion object {
        const val TAG = "SyncPlay"
        private const val SEEK_AFTER_PAUSE_MS = 700L
        private const val STUCK_TIMEOUT_MS = 8_000L
    }
}
