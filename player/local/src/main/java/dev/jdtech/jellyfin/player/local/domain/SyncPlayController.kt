package dev.jdtech.jellyfin.player.local.domain

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.jdtech.jellyfin.player.local.domain.SyncPlayManager.QueueItem
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.SendCommand
import org.jellyfin.sdk.model.api.SendCommandType
import timber.log.Timber

/**
 * Connects a player to the app-wide [SyncPlayManager] while it plays.
 *
 * Commands from the group (play, pause, seek) are applied at the time the server asks for. Play,
 * pause and seek done by the user are sent to the group instead of being applied directly, so
 * everyone stays in sync. Starting a title while in a group makes it the group's title. When the
 * group moves to another title, [onGroupChangedTitle] closes the player so the app can open it.
 */
class SyncPlayController(
    private val manager: SyncPlayManager,
    private val player: Player,
    private val scope: CoroutineScope,
    private val onGroupChangedTitle: () -> Unit,
) : Player.Listener {
    // Opened while already in a group: a different title is the user's choice for the group
    private val startedInGroup = manager.groupId != null

    // The group's playlist item we are following, null when not following
    private var playlistItemId: UUID? = null
    private var queueRequestedFor: UUID? = null
    private var lastCommand: SendCommand? = null

    // Changes we make to the player ourselves, so they are not sent to the group as user actions.
    private var expectedPlayWhenReady: Boolean? = null
    private var expectedSeekPositionMs: Long? = null

    private var waitingForReady = false
    private var reportedBuffering = false
    private var ignoreBuffering = false

    private val jobs = mutableListOf<Job>()
    private var commandJob: Job? = null
    private var seekRequestJob: Job? = null
    private var readyJob: Job? = null

    private val isFollowingGroup: Boolean
        get() = manager.groupId != null && playlistItemId != null

    init {
        player.addListener(this)
        manager.playerItemId = currentItemId()
        jobs += scope.launch { manager.commands.collect { onCommand(it) } }
        // Re-check the queue when joining or leaving, not only when it changes
        val inGroup = manager.state.map { it != null }.distinctUntilChanged()
        jobs += scope.launch {
            combine(inGroup, manager.queue) { _, queue -> queue }.collect { onQueue(it) }
        }
    }

    fun release() {
        jobs.forEach { it.cancel() }
        commandJob?.cancel()
        seekRequestJob?.cancel()
        readyJob?.cancel()
        player.removeListener(this)
        if (manager.playerItemId == currentItemId()) manager.playerItemId = null
    }

    private fun onQueue(queue: QueueItem?) {
        if (manager.groupId == null) {
            stopFollowing()
            return
        }
        val current = currentItemId() ?: return
        when {
            // Nothing queued in the group yet, share what we are watching
            queue == null -> requestQueue(current)
            queue.itemId == current -> follow(queue)
            // We opened another title while in the group, make it the group's title
            startedInGroup && playlistItemId == null -> requestQueue(current)
            // The group moved on to another title
            else -> {
                stopFollowing()
                jobs += scope.launch {
                    if (manager.isPlayableVideo(queue.itemId)) {
                        Timber.tag(SyncPlayManager.TAG).d("Group moved to ${queue.itemId}, closing")
                        onGroupChangedTitle()
                    } else {
                        Timber.tag(SyncPlayManager.TAG)
                            .d("Group moved to ${queue.itemId}, ignoring")
                    }
                }
            }
        }
    }

    private fun requestQueue(itemId: UUID) {
        if (queueRequestedFor == itemId) return
        queueRequestedFor = itemId
        manager.setQueue(itemId, player.currentPosition * 10_000)
    }

    private fun follow(queue: QueueItem) {
        if (queue.playlistItemId == playlistItemId) return
        playlistItemId = queue.playlistItemId
        lastCommand = null

        // Jump to the group's position and wait until the group starts playback
        var positionMs = queue.startPositionTicks / 10_000
        if (queue.isPlaying) positionMs += System.currentTimeMillis() - queue.lastUpdateMillis
        setPlayWhenReady(false)
        seekTo(positionMs)
        waitingForReady = true
        sendReadyIfLoaded()
    }

    private fun stopFollowing() {
        playlistItemId = null
        queueRequestedFor = null
        lastCommand = null
        commandJob?.cancel()
        readyJob?.cancel()
        waitingForReady = false
        reportedBuffering = false
    }

    private fun onCommand(command: SendCommand) {
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
        if (command.playlistItemId != playlistItemId) return
        lastCommand = command

        commandJob?.cancel()
        val delayMs = manager.toLocalMillis(command.`when`) - System.currentTimeMillis()
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

    /** How far ahead (positive) or behind the group we are, while the group plays. */
    fun offsetFromGroupMs(): Long? {
        if (!isFollowingGroup || manager.state.value?.groupState != GroupStateType.PLAYING) {
            return null
        }
        return groupPositionMs()?.let { player.currentPosition - it }
    }

    /** Where the group is now, when it is playing and we know since when. */
    private fun groupPositionMs(): Long? {
        val last = lastCommand ?: return null
        if (last.command != SendCommandType.UNPAUSE) return null
        val positionMs = last.positionTicks?.div(10_000) ?: return null
        val sinceMs = System.currentTimeMillis() - manager.toLocalMillis(last.`when`)
        return positionMs + sinceMs.coerceAtLeast(0)
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
        if (readyJob?.isActive == true) return
        // The player is ready with only a little buffered. Wait for some more so playback doesn't
        // stall again right after the group starts, which makes everyone wait.
        readyJob = scope.launch {
            val startMs = System.currentTimeMillis()
            while (
                player.bufferedPosition - player.currentPosition < READY_BUFFER_MS &&
                    player.bufferedPosition < player.duration &&
                    System.currentTimeMillis() - startMs < READY_BUFFER_TIMEOUT_MS
            ) {
                delay(200)
            }
            if (!waitingForReady || player.playbackState != Player.STATE_READY) return@launch
            waitingForReady = false
            reportedBuffering = false
            sendReady()
        }
    }

    private fun sendReady() {
        val playlistItemId = playlistItemId ?: return
        manager.sendReady(player.currentPosition * 10_000, player.playWhenReady, playlistItemId)
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
                    val playlistItemId = playlistItemId ?: return
                    manager.sendBuffering(player.currentPosition * 10_000, true, playlistItemId)
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
        Timber.tag(SyncPlayManager.TAG).d("User playWhenReady=$playWhenReady")
        if (playWhenReady) {
            val groupPosition = groupPositionMs()
            if (
                manager.state.value?.groupState == GroupStateType.PLAYING && groupPosition != null
            ) {
                // The group is playing and only we were paused (e.g. a phone call): catch up
                // locally, the server ignores unpause requests while the group plays
                seekTo(groupPosition)
            } else {
                // Wait for the group to start playback together
                expectedPlayWhenReady = false
                player.pause()
                manager.requestUnpause()
            }
        } else {
            manager.requestPause()
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
        // Wait here for the group's seek instead of playing ahead, buffering at the new position
        // is not something the group has to wait for yet
        ignoreBuffering = true
        setPlayWhenReady(false)
        val positionTicks = newPosition.positionMs * 10_000
        // Seek gestures produce many seeks, only send where the user ends up
        seekRequestJob?.cancel()
        seekRequestJob = scope.launch {
            delay(300)
            manager.requestSeek(positionTicks)
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val itemId = currentItemId()
        manager.playerItemId = itemId
        // The player moved on (e.g. the next episode), take the group along
        if (isFollowingGroup && itemId != null && itemId != manager.queue.value?.itemId) {
            stopFollowing()
            requestQueue(itemId)
        }
    }

    private fun currentItemId(): UUID? {
        val mediaId = player.currentMediaItem?.mediaId ?: return null
        return try {
            UUID.fromString(mediaId)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    companion object {
        private const val SEEK_THRESHOLD_MS = 500L
        private const val READY_BUFFER_MS = 5_000L
        private const val READY_BUFFER_TIMEOUT_MS = 10_000L
    }
}
