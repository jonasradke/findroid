package dev.jdtech.jellyfin.presentation.film.components

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.FindroidMediaStream
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.FindroidSourceType
import dev.jdtech.jellyfin.presentation.components.BaseDialog
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType

@Composable
fun VersionSelectionDialog(
    mediaSources: List<FindroidSource>,
    onSelect: (mediaSourceId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    BaseDialog(
        title = stringResource(CoreR.string.select_video_version_title),
        onDismiss = onDismiss,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = MaterialTheme.spacings.default)
        ) {
            mediaSources.forEach { mediaSource ->
                VersionItem(mediaSource = mediaSource, onClick = { onSelect(mediaSource.id) })
            }
        }
    }
}

@Composable
private fun VersionItem(mediaSource: FindroidSource, onClick: () -> Unit) {
    val context = LocalContext.current
    val videoStream = mediaSource.mediaStreams.firstOrNull { it.type == MediaStreamType.VIDEO }
    val audioStream = mediaSource.mediaStreams.firstOrNull { it.type == MediaStreamType.AUDIO }

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(
                    horizontal = MaterialTheme.spacings.default,
                    vertical = MaterialTheme.spacings.small,
                ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall),
    ) {
        Text(
            text = mediaSource.name,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.small),
        ) {
            videoStream?.resolutionLabel()?.let { VideoMetadataBarItem(text = it) }
            videoStream?.let { VideoMetadataBarItem(text = it.codec.videoCodecLabel()) }
            videoStream?.dynamicRange()?.let { (label, icon) ->
                VideoMetadataBarItem(text = label, icon = icon)
            }
            audioStream?.let { stream ->
                val channels = stream.channelLayout?.let { " $it" }.orEmpty()
                VideoMetadataBarItem(text = stream.codec.audioCodecLabel() + channels)
            }
            if (mediaSource.size > 0) {
                VideoMetadataBarItem(text = Formatter.formatShortFileSize(context, mediaSource.size))
            }
            if (mediaSource.type == FindroidSourceType.LOCAL) {
                VideoMetadataBarItem(
                    text = stringResource(CoreR.string.downloaded),
                    icon = CoreR.drawable.ic_download,
                )
            }
        }
    }
}

private fun FindroidMediaStream.resolutionLabel(): String? {
    val width = width ?: 0
    val height = height ?: 0
    // Width first, cinema aspect ratios make 4K and 1080p files less tall
    return when {
        width >= 3200 || height >= 2000 -> "4K"
        width >= 1800 || height >= 1000 -> "1080p"
        width >= 1200 || height >= 700 -> "720p"
        height > 0 -> "${height}p"
        else -> null
    }
}

private fun String.videoCodecLabel(): String =
    when (lowercase()) {
        "hevc",
        "h265" -> "HEVC"
        "h264",
        "avc" -> "H.264"
        "av1" -> "AV1"
        "vvc" -> "VVC"
        else -> uppercase()
    }

private fun String.audioCodecLabel(): String =
    when (lowercase()) {
        "truehd" -> "TrueHD"
        "eac3" -> "DD+"
        "ac3" -> "DD"
        "dts" -> "DTS"
        "aac" -> "AAC"
        "flac" -> "FLAC"
        "opus" -> "Opus"
        else -> uppercase()
    }

private fun FindroidMediaStream.dynamicRange(): Pair<String, Int?>? =
    when (videoRangeType) {
        VideoRangeType.HDR10 -> "HDR10" to null
        VideoRangeType.HDR10_PLUS -> "HDR10+" to null
        VideoRangeType.HLG -> "HLG" to null
        VideoRangeType.DOVI,
        VideoRangeType.DOVI_WITH_HDR10,
        VideoRangeType.DOVI_WITH_HLG,
        VideoRangeType.DOVI_WITH_SDR,
        VideoRangeType.DOVI_WITH_EL,
        VideoRangeType.DOVI_WITH_HDR10_PLUS,
        VideoRangeType.DOVI_WITH_ELHDR10_PLUS -> "Vision" to CoreR.drawable.ic_dolby
        else -> null
    }

@Composable
@Preview
private fun VersionSelectionDialogDialogPreview() {
    FindroidTheme {
        VersionSelectionDialog(
            mediaSources =
                listOf(
                    FindroidSource(
                        id = "A",
                        name = "2160p Remux",
                        type = FindroidSourceType.REMOTE,
                        path = "",
                        size = 60_000_000_000,
                        mediaStreams = emptyList(),
                    ),
                    FindroidSource(
                        id = "B",
                        name = "1080p",
                        type = FindroidSourceType.REMOTE,
                        path = "",
                        size = 12_000_000_000,
                        mediaStreams = emptyList(),
                    ),
                ),
            onSelect = {},
            onDismiss = {},
        )
    }
}
