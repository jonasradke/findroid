package dev.jdtech.jellyfin.presentation.player

import android.view.Display
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.player.local.R as PlayerLocalR
import dev.jdtech.jellyfin.player.local.domain.PlaybackStatsListener
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * Playback details shown over the video, like "stats for nerds": what is playing, how it is
 * decoded, the buffer and network, and how far off the SyncPlay group we are. Refreshes twice a
 * second while shown.
 *
 * @param syncPlayOffsetMs how far ahead (positive) or behind the group we are, null when not in a
 *   group that is playing
 */
@Composable
fun PlaybackInfoOverlay(
    player: Player,
    stats: PlaybackStatsListener,
    syncPlayOffsetMs: () -> Long?,
    isInSyncPlayGroup: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            tick++
        }
    }

    val rows = playbackInfoRows(player, stats, syncPlayOffsetMs(), isInSyncPlayGroup, tick)

    Column(
        modifier =
            modifier
                .width(340.dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(PlayerLocalR.string.playback_info),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_x),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        rows.forEach { (label, value) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(label),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.width(96.dp),
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White,
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
    }
}

@Composable
@Suppress("UNUSED_PARAMETER") // tick only makes the rows recompute
private fun playbackInfoRows(
    player: Player,
    stats: PlaybackStatsListener,
    syncPlayOffsetMs: Long?,
    isInSyncPlayGroup: Boolean,
    tick: Int,
): List<Pair<Int, String>> {
    val rows = mutableListOf<Pair<Int, String>>()
    val video = player.selectedFormat(C.TRACK_TYPE_VIDEO)
    val audio = player.selectedFormat(C.TRACK_TYPE_AUDIO)

    val uri = player.currentMediaItem?.localConfiguration?.uri
    val playMethod =
        if (uri?.scheme == "http" || uri?.scheme == "https") {
            stringResource(PlayerLocalR.string.playback_info_direct_play)
        } else {
            stringResource(PlayerLocalR.string.playback_info_downloaded)
        }
    rows += PlayerLocalR.string.playback_info_play_method to playMethod

    if (video != null) {
        rows +=
            PlayerLocalR.string.playback_info_video to
                listOfNotNull(
                        if (video.width > 0) "${video.width}×${video.height}" else null,
                        codecName(video.sampleMimeType),
                        if (video.frameRate > 0) "%.3f fps".format(Locale.US, video.frameRate)
                        else null,
                    )
                    .joinToString(" · ")
        rows +=
            PlayerLocalR.string.playback_info_dynamic_range to
                dynamicRange(video, LocalView.current.displaySupportsDolbyVision())
        video.bitrateOrNull()?.let { rows += PlayerLocalR.string.playback_info_bitrate to it }
    }
    stats.videoDecoderName?.let {
        rows += PlayerLocalR.string.playback_info_video_decoder to decoderLabel(it)
    }
    rows += PlayerLocalR.string.playback_info_dropped_frames to stats.droppedFrames.toString()
    player.surfaceSize.let {
        if (it.width > 0) {
            rows += PlayerLocalR.string.playback_info_viewport to "${it.width}×${it.height}"
        }
    }

    if (audio != null) {
        rows +=
            PlayerLocalR.string.playback_info_audio to
                listOfNotNull(
                        codecName(audio.sampleMimeType),
                        channelLayout(audio.channelCount),
                        if (audio.sampleRate > 0) "${audio.sampleRate / 1000.0} kHz" else null,
                        audio.language,
                    )
                    .joinToString(" · ")
    }
    stats.audioDecoderName?.let {
        rows += PlayerLocalR.string.playback_info_audio_decoder to decoderLabel(it)
    }

    val bufferMs = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0)
    rows +=
        PlayerLocalR.string.playback_info_buffer to "%.1f s".format(Locale.US, bufferMs / 1000.0)
    stats.bandwidthEstimate?.let {
        rows += PlayerLocalR.string.playback_info_network to formatBitrate(it)
    }

    if (isInSyncPlayGroup) {
        val sync =
            syncPlayOffsetMs?.let {
                val direction =
                    if (it >= 0) PlayerLocalR.string.playback_info_ahead
                    else PlayerLocalR.string.playback_info_behind
                "${abs(it)} ms " + stringResource(direction)
            } ?: stringResource(PlayerLocalR.string.playback_info_not_playing)
        rows += PlayerLocalR.string.syncplay to sync
    }
    return rows
}

private fun Player.selectedFormat(trackType: Int): Format? =
    currentTracks.groups
        .firstOrNull { it.type == trackType && it.isSelected }
        ?.let { group ->
            (0 until group.length)
                .firstOrNull { group.isTrackSelected(it) }
                ?.let(group::getTrackFormat)
        }

private fun Format.bitrateOrNull(): String? =
    listOf(averageBitrate, bitrate, peakBitrate)
        .firstOrNull { it > 0 }
        ?.let { formatBitrate(it.toLong()) }

private fun formatBitrate(bitsPerSecond: Long): String =
    if (bitsPerSecond >= 1_000_000) "%.1f Mbps".format(Locale.US, bitsPerSecond / 1_000_000.0)
    else "${bitsPerSecond / 1000} kbps"

/**
 * The range shown on screen. Dolby Vision only plays as such on displays that support it, others
 * play the HDR10 or HLG base layer the stream carries.
 */
private fun dynamicRange(format: Format, displaySupportsDolbyVision: Boolean): String {
    val base =
        when (format.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> "HDR10"
            C.COLOR_TRANSFER_HLG -> "HLG"
            else -> "SDR"
        }
    if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return base
    // Codecs look like dvhe.08.06, the second part is the profile
    val profile = format.codecs?.split('.')?.getOrNull(1)?.toIntOrNull()
    val dolbyVision = if (profile != null) "Dolby Vision $profile" else "Dolby Vision"
    return if (displaySupportsDolbyVision) dolbyVision else "$base ($dolbyVision base layer)"
}

@Suppress("DEPRECATION") // Display.Mode.supportedHdrTypes needs API 34
private fun View.displaySupportsDolbyVision(): Boolean =
    display?.hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) ==
        true

private fun channelLayout(channels: Int): String? =
    when (channels) {
        Format.NO_VALUE -> null
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "$channels ch"
    }

/** Marks the decoders that run on the CPU, the others use the hardware decoder. */
private fun decoderLabel(name: String): String {
    val isSoftware =
        name.startsWith("c2.android.", ignoreCase = true) ||
            name.startsWith("OMX.google.", ignoreCase = true) ||
            name.contains("ffmpeg", ignoreCase = true)
    return if (isSoftware) "$name (SW)" else "$name (HW)"
}

private fun codecName(mimeType: String?): String? =
    when (mimeType) {
        null -> null
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        MimeTypes.AUDIO_E_AC3_JOC -> "DD+ Atmos"
        MimeTypes.AUDIO_E_AC3 -> "DD+"
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        else -> mimeType.substringAfter('/').uppercase()
    }
