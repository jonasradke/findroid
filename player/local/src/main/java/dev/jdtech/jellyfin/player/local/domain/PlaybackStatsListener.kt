package dev.jdtech.jellyfin.player.local.domain

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener

/** Collects ExoPlayer details for the playback info screen that the player doesn't expose. */
@OptIn(UnstableApi::class)
class PlaybackStatsListener : AnalyticsListener {
    var videoDecoderName: String? = null
        private set

    var audioDecoderName: String? = null
        private set

    /** Estimated network speed in bits per second. */
    var bandwidthEstimate: Long? = null
        private set

    var droppedFrames = 0
        private set

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        videoDecoderName = decoderName
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        audioDecoderName = decoderName
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        bandwidthEstimate = bitrateEstimate
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        this.droppedFrames += droppedFrames
    }
}
