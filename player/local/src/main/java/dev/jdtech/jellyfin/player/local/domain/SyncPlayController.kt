package dev.jdtech.jellyfin.player.local.domain

import android.app.Application
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.player.local.R
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jellyfin.sdk.api.client.extensions.syncPlayApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.BufferRequestDto
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.GroupUpdate
import org.jellyfin.sdk.model.api.JoinGroupRequestDto
import org.jellyfin.sdk.model.api.NewGroupRequestDto
import org.jellyfin.sdk.model.api.PingRequestDto
import org.jellyfin.sdk.model.api.PlayQueueUpdate
import org.jellyfin.sdk.model.api.PlayRequestDto
import org.jellyfin.sdk.model.api.ReadyRequestDto
import org.jellyfin.sdk.model.api.SeekRequestDto
import org.jellyfin.sdk.model.api.SendCommand
import org.jellyfin.sdk.model.api.SendCommandType
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupDoesNotExistUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupLeftUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage
import org.jellyfin.sdk.model.api.SyncPlayLibraryAccessDeniedUpdate
import org.jellyfin.sdk.model.api.SyncPlayNotInGroupUpdate
import org.jellyfin.sdk.model.api.SyncPlayPlayQueueUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserLeftUpdate
import timber.log.Timber

/**
 * Basic SyncPlay support: joins a SyncPlay group for the item that is currently playing and keeps
 * playback in sync with the group.
 *
 * Commands from the group (play, pause, seek) are applied at the time the server asks for, using
 * the clock offset to the server. Play, pause and seek done by the user are sent to the group
 * instead of being applied directly, so everyone stays in sync. Only a single item is supported:
 * when the group or the player moves to another item, we leave the group.
 */
class SyncPlayController(
    private val application: Application,
    private val jellyfinApi: JellyfinApi,
    private val player: Player,
    private val scope: CoroutineScope,
) : Player.Listener {
    private val api = jellyfinApi.api

    private val _groupName = MutableStateFlow<String?>(null)
    /** Name of the group we are in, or null when not in a group. */
    val groupName = _groupName.asStateFlow()

    private var groupId: UUID? = null
    private var groupItemId: UUID? = null
    private var playlistItemId: UUID? = null
    private var queueRequested = false
    private var lastCommand: SendCommand? = null

    // Server time minus local time, measured like NTP. Samples with the lowest ping are the most
    // accurate.
    private var timeOffsetMs = 0L
    private val timeSamples = ArrayDeque<TimeSample>()

    // Changes we make to the player ourselves, so they are not sent to the group as user actions.
    private var expectedPlayWhenReady: Boolean? = null
    private var expectedSeekPositionMs: Long? = null

    private var waitingForReady = false
    private var reportedBuffering = false
    private var ignoreBuffering = false

    private var socketJob: Job? = null
    private var timeSyncJob: Job? = null
    private var commandJob: Job? = null
    private var seekRequestJob: Job? = null

    private val isFollowingGroup: Boolean
        get() = groupId != null && playlistItemId != null

    suspend fun getGroups(): List<GroupInfoDto> = api.syncPlayApi.syncPlayGetGroups().content

    fun joinGroup(groupId: UUID) {
        launchRequest {
            connect()
            api.syncPlayApi.syncPlayJoinGroup(JoinGroupRequestDto(groupId))
        }
    }

    fun createGroup() {
        launchRequest {
            connect()
            val userName = jellyfinApi.userApi.getCurrentUser().content.name.orEmpty()
            val groupName = application.getString(R.string.syncplay_group_name, userName)
            api.syncPlayApi.syncPlayCreateGroup(NewGroupRequestDto(groupName))
        }
    }

    fun leaveGroup() {
        if (groupId == null) return
        launchRequest { api.syncPlayApi.syncPlayLeaveGroup() }
        reset()
        showMessage(R.string.syncplay_left_group)
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun release() {
        if (groupId != null) {
            GlobalScope.launch {
                try {
                    api.syncPlayApi.syncPlayLeaveGroup()
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
        }
        reset()
    }

    private suspend fun connect() {
        if (socketJob == null) {
            socketJob = scope.launch {
                launch {
                    api.webSocket.subscribe<SyncPlayGroupUpdateMessage>().collect {
                        onGroupUpdate(it.data)
                    }
                }
                launch {
                    api.webSocket.subscribe<SyncPlayCommandMessage>().collect { message ->
                        message.data?.let { onCommand(it) }
                    }
                }
            }
            player.addListener(this)
        }
        withTimeout(10_000) { api.webSocket.state.first { it is SocketApiState.Connected } }
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

    private fun onGroupUpdate(update: GroupUpdate) {
        Timber.d("SyncPlay group update: $update")
        when (update) {
            is SyncPlayGroupJoinedUpdate -> {
                groupId = update.groupId
                _groupName.value = update.data.groupName
                showMessage(R.string.syncplay_joined_group, update.data.groupName)
                // Nothing is playing in the group yet, share what we are watching
                if (update.data.state == GroupStateType.IDLE) setQueueToCurrentItem()
            }
            is SyncPlayUserJoinedUpdate -> showMessage(R.string.syncplay_user_joined, update.data)
            is SyncPlayUserLeftUpdate -> showMessage(R.string.syncplay_user_left, update.data)
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

    private fun onPlayQueueUpdate(update: PlayQueueUpdate) {
        val queueItem = update.playlist.getOrNull(update.playingItemIndex)
        if (queueItem == null) {
            setQueueToCurrentItem()
            return
        }
        if (queueItem.itemId != currentItemId()) {
            showMessage(R.string.syncplay_different_item)
            leaveGroup()
            return
        }
        groupItemId = queueItem.itemId
        if (queueItem.playlistItemId == playlistItemId) return
        playlistItemId = queueItem.playlistItemId

        // Jump to the group's position and wait until the group starts playback
        var positionMs = update.startPositionTicks / 10_000
        if (update.isPlaying) {
            positionMs += System.currentTimeMillis() - update.lastUpdate.toLocalMillis()
        }
        setPlayWhenReady(false)
        seekTo(positionMs)
        waitingForReady = true
        sendReadyIfLoaded()
    }

    private fun setQueueToCurrentItem() {
        val itemId = currentItemId() ?: return
        if (queueRequested) return
        queueRequested = true
        val positionTicks = player.currentPosition * 10_000
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

    private fun onCommand(command: SendCommand) {
        Timber.d("SyncPlay command: $command")
        if (command.groupId != groupId) return
        val last = lastCommand
        if (
            last != null &&
                last.command == command.command &&
                last.`when` == command.`when` &&
                last.positionTicks == command.positionTicks &&
                last.playlistItemId == command.playlistItemId
        ) {
            return
        }
        lastCommand = command
        if (command.playlistItemId != playlistItemId) return

        commandJob?.cancel()
        val delayMs = command.`when`.toLocalMillis() - System.currentTimeMillis()
        val positionMs = command.positionTicks?.div(10_000)
        when (command.command) {
            SendCommandType.UNPAUSE -> {
                if (delayMs > 0) {
                    positionMs?.let { seekTo(it) }
                    commandJob = scope.launch {
                        delay(delayMs)
                        setPlayWhenReady(true)
                    }
                } else {
                    // We are late, start further in to catch up with the group
                    positionMs?.let { seekTo(it - delayMs) }
                    setPlayWhenReady(true)
                }
            }
            SendCommandType.PAUSE -> {
                val pause = {
                    setPlayWhenReady(false)
                    positionMs?.let { seekTo(it) }
                }
                if (delayMs > 0) {
                    commandJob = scope.launch {
                        delay(delayMs)
                        pause()
                    }
                } else {
                    pause()
                }
            }
            SendCommandType.SEEK -> {
                setPlayWhenReady(false)
                positionMs?.let { seekTo(it) }
                waitingForReady = true
                sendReadyIfLoaded()
            }
            SendCommandType.STOP -> setPlayWhenReady(false)
        }
    }

    private fun setPlayWhenReady(playWhenReady: Boolean) {
        if (player.playWhenReady == playWhenReady) return
        expectedPlayWhenReady = playWhenReady
        if (playWhenReady) player.play() else player.pause()
    }

    private fun seekTo(positionMs: Long) {
        // Small differences are not worth the rebuffering a seek causes
        if (abs(player.currentPosition - positionMs) < SEEK_THRESHOLD_MS) return
        expectedSeekPositionMs = positionMs
        ignoreBuffering = true
        player.seekTo(positionMs)
    }

    private fun sendReadyIfLoaded() {
        if (!waitingForReady || player.playbackState != Player.STATE_READY) return
        waitingForReady = false
        reportedBuffering = false
        sendReady()
    }

    private fun sendReady() {
        val playlistItemId = playlistItemId ?: return
        val request =
            ReadyRequestDto(
                `when` = serverNow(),
                positionTicks = player.currentPosition * 10_000,
                isPlaying = player.playWhenReady,
                playlistItemId = playlistItemId,
            )
        launchRequest { api.syncPlayApi.syncPlayReady(request) }
    }

    private fun sendBuffering() {
        val playlistItemId = playlistItemId ?: return
        val request =
            BufferRequestDto(
                `when` = serverNow(),
                positionTicks = player.currentPosition * 10_000,
                isPlaying = player.playWhenReady,
                playlistItemId = playlistItemId,
            )
        launchRequest { api.syncPlayApi.syncPlayBuffering(request) }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (!isFollowingGroup) return
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                // Let the group wait for us when we run out of buffer during playback
                if (
                    player.playWhenReady &&
                        !ignoreBuffering &&
                        !waitingForReady &&
                        !reportedBuffering
                ) {
                    reportedBuffering = true
                    sendBuffering()
                }
            }
            Player.STATE_READY -> {
                ignoreBuffering = false
                if (waitingForReady) {
                    sendReadyIfLoaded()
                } else if (reportedBuffering) {
                    reportedBuffering = false
                    sendReady()
                }
            }
        }
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!isFollowingGroup) return
        val expected = expectedPlayWhenReady
        expectedPlayWhenReady = null
        if (expected == playWhenReady) return
        if (
            reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST &&
                reason != Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
        ) {
            return
        }
        if (playWhenReady) {
            // Wait for the group to start playback together
            expectedPlayWhenReady = false
            player.pause()
            launchRequest { api.syncPlayApi.syncPlayUnpause() }
        } else {
            launchRequest { api.syncPlayApi.syncPlayPause() }
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK || !isFollowingGroup) return
        val expected = expectedSeekPositionMs
        expectedSeekPositionMs = null
        if (expected != null && abs(newPosition.positionMs - expected) < SEEK_THRESHOLD_MS) return
        val positionTicks = newPosition.positionMs * 10_000
        // Seek gestures produce many seeks, only send where the user ends up
        seekRequestJob?.cancel()
        seekRequestJob = scope.launch {
            delay(300)
            try {
                api.syncPlayApi.syncPlaySeek(SeekRequestDto(positionTicks))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val groupItemId = groupItemId ?: return
        if (mediaItem?.mediaId != groupItemId.toString()) {
            showMessage(R.string.syncplay_different_item)
            leaveGroup()
        }
    }

    private fun reset() {
        groupId = null
        groupItemId = null
        playlistItemId = null
        queueRequested = false
        lastCommand = null
        expectedPlayWhenReady = null
        expectedSeekPositionMs = null
        waitingForReady = false
        reportedBuffering = false
        ignoreBuffering = false
        _groupName.value = null
        commandJob?.cancel()
        seekRequestJob?.cancel()
        timeSyncJob?.cancel()
        timeSyncJob = null
        socketJob?.cancel()
        socketJob = null
        player.removeListener(this)
    }

    private fun currentItemId(): UUID? {
        val mediaId = player.currentMediaItem?.mediaId ?: return null
        return try {
            UUID.fromString(mediaId)
        } catch (_: IllegalArgumentException) {
            null
        }
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

    private fun showMessage(@StringRes resId: Int, vararg formatArgs: Any) {
        val message = application.getString(resId, *formatArgs)
        Toast.makeText(application, message, Toast.LENGTH_SHORT).show()
    }

    private fun serverNow(): LocalDateTime =
        LocalDateTime.ofInstant(
            Instant.ofEpochMilli(System.currentTimeMillis() + timeOffsetMs),
            ZoneId.systemDefault(),
        )

    // The SDK converts server times to the device's time zone
    private fun LocalDateTime.toEpochMilli(): Long =
        atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun LocalDateTime.toLocalMillis(): Long = toEpochMilli() - timeOffsetMs

    private data class TimeSample(val offset: Long, val ping: Long)

    companion object {
        private const val SEEK_THRESHOLD_MS = 500L
    }
}
