package dev.jdtech.jellyfin.player.local.domain

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser

/**
 * Plays Dolby Vision profile 7 (dual layer, used by most UHD Blu-ray remuxes) as HDR10.
 *
 * Media3 never falls back to an HEVC decoder for profile 7, so on devices without a Dolby Vision
 * decoder that supports it the video track is dropped and only audio plays. The base layer of
 * profile 7 is plain HEVC Main10 HDR10, so we relabel those tracks as HEVC and let a regular HEVC
 * decoder play the base layer. The enhancement layer is ignored by the decoder.
 */
@OptIn(UnstableApi::class)
class DolbyVisionFallbackExtractorsFactory(
    private val delegate: ExtractorsFactory = DefaultExtractorsFactory()
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::FallbackExtractor).toTypedArray()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::FallbackExtractor).toTypedArray()

    override fun experimentalSetTextTrackTranscodingEnabled(
        textTrackTranscodingEnabled: Boolean
    ): ExtractorsFactory {
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    override fun setSubtitleParserFactory(
        subtitleParserFactory: SubtitleParser.Factory
    ): ExtractorsFactory {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(
        codecsToParseWithinGopSampleDependencies: Int
    ): ExtractorsFactory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(
            codecsToParseWithinGopSampleDependencies
        )
        return this
    }

    override fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory {
        delegate.setParseHagcMetadata(parseHagcMetadata)
        return this
    }
}

@OptIn(UnstableApi::class)
private class FallbackExtractor(private val delegate: Extractor) : Extractor by delegate {
    override fun init(output: ExtractorOutput) {
        delegate.init(FallbackExtractorOutput(output))
    }

    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

@OptIn(UnstableApi::class)
private class FallbackExtractorOutput(private val delegate: ExtractorOutput) : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput {
        val trackOutput = delegate.track(id, type)
        return if (type == C.TRACK_TYPE_VIDEO) FallbackTrackOutput(trackOutput) else trackOutput
    }

    override fun endTracks() = delegate.endTracks()

    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

@OptIn(UnstableApi::class)
private class FallbackTrackOutput(private val delegate: TrackOutput) : TrackOutput {
    override fun format(format: Format) {
        delegate.format(
            if (format.isDolbyVisionProfile7()) {
                format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(null).build()
            } else {
                format
            }
        )
    }

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int = delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
        delegate.sampleData(data, length, sampleDataPart)

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) = delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
}

// Dolby Vision codec strings look like "dvhe.07.06" (or "dvh1.07.06"), the second part is the
// profile.
@OptIn(UnstableApi::class)
private fun Format.isDolbyVisionProfile7(): Boolean {
    if (sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return false
    val parts = codecs?.split(".") ?: return false
    return parts.size >= 2 && parts[0] in setOf("dvhe", "dvh1") && parts[1].toIntOrNull() == 7
}
