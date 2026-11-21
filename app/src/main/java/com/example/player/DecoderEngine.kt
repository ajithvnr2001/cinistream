package com.example.player

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.util.Locale

/**
 * User-selectable & auto-managed video decoding engine preference.
 *
 * - [AUTO]: Prefers device hardware decoders (e.g., c2.qti.hevc.decoder) for lowest power,
 *   and automatically fails over to Android Software decoders (e.g., c2.android.hevc.decoder)
 *   at the exact playback timestamp if a hardware codec throws a mid-stream decoding error.
 * - [HARDWARE]: Always prefers hardware GPU/DSP decoders first (still skipping session-blacklisted codecs).
 * - [SOFTWARE]: Prioritizes Android CPU software decoders (c2.android.* / OMX.google.*) for
 *   problematic 10-bit HEVC/x265, Dolby Vision, high-profile MKVs, or buggy Qualcomm/MediaTek drivers.
 */
enum class VideoDecoderMode(
    val label: String,
    val shortLabel: String,
    val description: String
) {
    AUTO(
        label = "Auto (HW + FFmpeg SW)",
        shortLabel = "AUTO",
        description = "Hardware first; auto-switches to FFmpeg 10-bit SW if hardware fails"
    ),
    HARDWARE(
        label = "Hardware (HW)",
        shortLabel = "HW",
        description = "Use device GPU/DSP video decoders for lowest battery and heat"
    ),
    SOFTWARE(
        label = "Software (FFmpeg SW)",
        shortLabel = "SW",
        description = "Use native FFmpeg 10-bit HEVC/x265 & CPU decoders for max compatibility"
    )
}

/**
 * Pure, unit-testable helper for Media3 codec classification, failure detection, and ordering.
 */
object DecoderFallbackHelper {

    private val CODEC_NAME_REGEX = Regex(
        pattern = """\b((?:c2|omx)\.[a-z0-9_.-]+|ffmpeg[a-z0-9_.-]*|libffmpeg[a-z0-9_.-]*|dav1d[a-z0-9_.-]*)\b""",
        option = RegexOption.IGNORE_CASE
    )

    fun isDecoderError(error: PlaybackException): Boolean {
        if (isDecoderErrorCode(error.errorCode)) return true
        var cursor: Throwable? = error.cause
        var depth = 0
        while (cursor != null && depth < 6) {
            val className = cursor.javaClass.name
            if (className.contains("MediaCodecDecoderException") ||
                className.contains("DecoderInitializationException") ||
                className.contains("CodecException") ||
                className.contains("FfmpegDecoderException")
            ) {
                return true
            }
            val msg = cursor.message.orEmpty()
            if (msg.contains("Decoder failed", ignoreCase = true) ||
                msg.contains("Decoder init failed", ignoreCase = true)
            ) {
                return true
            }
            cursor = cursor.cause
            depth++
        }
        return false
    }

    fun isDecoderErrorCode(errorCode: Int): Boolean {
        return when (errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> true
            else -> false
        }
    }

    @OptIn(UnstableApi::class)
    fun extractFailedDecoderName(error: PlaybackException): String? {
        var cursor: Throwable? = error.cause
        var depth = 0
        while (cursor != null && depth < 6) {
            when (cursor) {
                is MediaCodecDecoderException -> {
                    cursor.codecInfo?.name?.takeIf { it.isNotBlank() }?.let { return it }
                }
                is MediaCodecRenderer.DecoderInitializationException -> {
                    cursor.codecInfo?.name?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
            extractCodecNameFromText(cursor.message)?.let { return it }
            cursor = cursor.cause
            depth++
        }
        return extractCodecNameFromText(error.message)
    }

    fun extractCodecNameFromText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return CODEC_NAME_REGEX.find(text)?.groupValues?.getOrNull(1)
    }

    /**
     * Qualcomm/vendor `.low_latency` codecs (e.g., `c2.qti.hevc.decoder.low_latency`) are designed
     * for zero-B-frame WebRTC/video calls and routinely fail with `Decoder init failed` on 4K
     * 10-bit HDR10 movie streams with B-frame reordering.
     */
    fun isLowLatencyCodecName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.contains(".low_latency") ||
            lower.contains("_low_latency") ||
            lower.contains(".lowlatency")
    }

    fun isSoftwareCodecName(name: String, softwareOnlyFlag: Boolean = false): Boolean {
        if (softwareOnlyFlag) return true
        val lower = name.lowercase(Locale.ROOT)
        return lower.startsWith("c2.android.") ||
            lower.startsWith("omx.google.") ||
            lower.startsWith("c2.google.") ||
            lower.contains(".sw.") ||
            lower.contains("ffmpeg") ||
            lower.contains("dav1d") ||
            lower.contains("nextlib")
    }

    fun formatDecoderTag(decoderName: String?): String {
        val clean = decoderName?.trim().orEmpty()
        if (clean.isBlank()) return "—"
        if (clean.contains("ffmpeg", ignoreCase = true) || clean.contains("dav1d", ignoreCase = true)) {
            return "SW · FFmpeg ($clean)"
        }
        val prefix = if (isSoftwareCodecName(clean)) "SW" else "HW"
        return "$prefix · $clean"
    }

    /**
     * Pure ordering function shared by [SmartMediaCodecSelector] and JVM unit tests:
     * 1. Drops `.low_latency` WebRTC codecs (e.g. `c2.qti.hevc.decoder.low_latency`) so they never
     *    hijack movie/series VOD playback. When [allowEmptyAfterFiltering] is true (FFmpeg renderer
     *    is standing by), returning an empty list lets Media3 cleanly hand off to `FfmpegVideoRenderer`.
     * 2. Removes session-blocked codecs (e.g., `c2.qti.hevc.decoder` after failure).
     * 3. Reorders remaining codecs based on [mode] ([VideoDecoderMode.SOFTWARE] puts software decoders first;
     *    [VideoDecoderMode.HARDWARE] / [VideoDecoderMode.AUTO] keep hardware decoders first).
     */
    fun <T> reorderCodecs(
        codecs: List<T>,
        mode: VideoDecoderMode,
        blockedCodecNames: Set<String>,
        nameOf: (T) -> String,
        isSoftwareOf: (T) -> Boolean,
        excludeLowLatency: Boolean = true,
        allowEmptyAfterFiltering: Boolean = false
    ): List<T> {
        if (codecs.isEmpty()) return emptyList()

        val withoutLowLatency = if (excludeLowLatency) {
            val filtered = codecs.filterNot { isLowLatencyCodecName(nameOf(it)) }
            if (filtered.isNotEmpty() || allowEmptyAfterFiltering) filtered else codecs
        } else {
            codecs
        }

        val normalizedBlocked = blockedCodecNames.map { it.lowercase(Locale.ROOT) }.toSet()
        val nonBlocked = if (normalizedBlocked.isEmpty()) {
            withoutLowLatency
        } else {
            withoutLowLatency.filterNot { nameOf(it).lowercase(Locale.ROOT) in normalizedBlocked }
        }
        val candidates = if (nonBlocked.isNotEmpty() || allowEmptyAfterFiltering) nonBlocked else withoutLowLatency

        return when (mode) {
            VideoDecoderMode.SOFTWARE -> {
                val (sw, hw) = candidates.partition(isSoftwareOf)
                sw + hw
            }
            VideoDecoderMode.HARDWARE, VideoDecoderMode.AUTO -> {
                val (hw, sw) = candidates.partition { !isSoftwareOf(it) }
                hw + sw
            }
        }
    }

    data class VideoTrackCandidate(
        val id: String,
        val groupIndex: Int,
        val trackIndex: Int,
        val mimeType: String?,
        val height: Int,
        val bitrate: Int,
        val isSelected: Boolean,
        val label: String
    )

    /**
     * When both HW and SW HEVC decoders fail on an adaptive HLS/DASH stream, selects the best
     * untried compatible fallback video track:
     *  1. Prefer H.264 (AVC) or non-HEVC tracks (highest resolution first)
     *  2. Otherwise pick the next lower-resolution track below the failing track
     */
    fun pickCompatibleFallbackTrack(
        candidates: List<VideoTrackCandidate>,
        triedTrackIds: Set<String>
    ): VideoTrackCandidate? {
        val untried = candidates.filterNot { it.id in triedTrackIds || it.isSelected }
        if (untried.isEmpty()) return null

        val nonHevc = untried.filter { candidate ->
            val mime = candidate.mimeType?.lowercase(Locale.ROOT).orEmpty()
            mime.isNotBlank() && !mime.contains("hevc") && !mime.contains("h265") && !mime.contains("dolby-vision")
        }
        if (nonHevc.isNotEmpty()) {
            return nonHevc.sortedWith(compareByDescending<VideoTrackCandidate> { it.height }.thenByDescending { it.bitrate }).first()
        }

        val currentHeight = candidates.firstOrNull { it.isSelected }?.height ?: Int.MAX_VALUE
        val lowerRes = untried.filter { it.height in 1 until currentHeight }
        if (lowerRes.isNotEmpty()) {
            return lowerRes.sortedWith(compareByDescending<VideoTrackCandidate> { it.height }.thenByDescending { it.bitrate }).first()
        }

        return untried.minByOrNull { if (it.height > 0) it.height else Int.MAX_VALUE }
    }

    fun buildReadableDecoderErrorHint(
        failedCodec: String?,
        effectiveMode: VideoDecoderMode,
        bothHwAndSwFailed: Boolean = false,
        ffmpegAvailable: Boolean = false
    ): String {
        val codecLabel = failedCodec ?: "device video decoder"
        return when {
            bothHwAndSwFailed && ffmpegAvailable ->
                "Both hardware and FFmpeg software decoders failed ($codecLabel). This stream segment may be corrupted. Try Skip Bad Segment (+5s) or switch Video Quality."
            bothHwAndSwFailed ->
                "Both hardware and Android software HEVC decoders failed ($codecLabel). Android's built-in SW HEVC decoder is 8-bit only — this stream may be 10-bit HEVC (Main10) or contain a corrupted segment. Try Skip Bad Frame (+5s) or switch Video Quality to H.264/lower resolution."
            effectiveMode == VideoDecoderMode.SOFTWARE && ffmpegAvailable ->
                "FFmpeg software decoder failed on $codecLabel. Tap Try Hardware Decoder (HW) or Skip Bad Segment (+5s)."
            effectiveMode == VideoDecoderMode.SOFTWARE ->
                "Software decoder $codecLabel failed (Android's built-in SW HEVC decoder only supports 8-bit Main profile). Tap Try Hardware Decoder (HW) or Skip Bad Frame (+5s)."
            failedCodec != null && !isSoftwareCodecName(failedCodec) ->
                "Hardware codec $failedCodec rejected this stream (common with 10-bit HEVC/x265 or Dolby Vision). Tap Try Software Decoder (SW)."
            else ->
                "Video decoder error ($codecLabel). Tap Try Software Decoder (SW) to decode safely on CPU."
        }
    }
}

/**
 * Dynamic Media3 [MediaCodecSelector] that supports:
 * - Instant HW / FFmpeg-SW decoder handoff without destroying ExoPlayer
 * - Automatic exclusion of `.low_latency` WebRTC codecs (`c2.qti.hevc.decoder.low_latency`) on VOD streams
 * - Per-session failed hardware codec blocklisting (e.g., skipping `c2.qti.hevc.decoder` after failure)
 * - Automatic Dolby Vision (`video/dolby-vision`) -> HEVC (`video/hevc`) fallback when DV fails or SW mode is active
 * - Direct delegation to NextLib's native `FfmpegVideoRenderer` (10-bit HEVC Main10, AV1, H.264, VP9)
 *   whenever [preferExternalFfmpegVideoProvider] is true for video tracks.
 */
@OptIn(UnstableApi::class)
class SmartMediaCodecSelector(
    private val modeProvider: () -> VideoDecoderMode,
    private val blockedCodecNamesProvider: () -> Set<String>,
    private val preferExternalFfmpegVideoProvider: () -> Boolean = { false },
    private val hasFfmpegFallbackProvider: () -> Boolean = { false }
) : MediaCodecSelector {

    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean
    ): MutableList<MediaCodecInfo> {
        val mode = modeProvider()
        val blocked = blockedCodecNamesProvider()
        val isVideo = MimeTypes.isVideo(mimeType)

        // When Software (FFmpeg) mode is active and native FFmpeg video decoding is available
        // (and DRM secure decoding isn't required), return empty list for video/* so Media3's
        // MediaCodecVideoRenderer cleanly steps aside and NextLib's FfmpegVideoRenderer handles
        // 8-bit & 10-bit HEVC Main10, H.264, VP9, and AV1 directly!
        if (isVideo && !requiresSecureDecoder && preferExternalFfmpegVideoProvider()) {
            return mutableListOf()
        }

        val primaryInfos = MediaCodecSelector.DEFAULT.getDecoderInfos(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder
        )

        // Leave audio/text selection untouched unless explicitly blocked
        if (!isVideo) {
            if (blocked.isEmpty()) return primaryInfos.toMutableList()
            return DecoderFallbackHelper.reorderCodecs(
                codecs = primaryInfos,
                mode = VideoDecoderMode.AUTO,
                blockedCodecNames = blocked,
                nameOf = { it.name },
                isSoftwareOf = { DecoderFallbackHelper.isSoftwareCodecName(it.name, it.softwareOnly) },
                excludeLowLatency = false
            ).toMutableList()
        }

        val combined = LinkedHashMap<String, MediaCodecInfo>()
        for (info in primaryInfos) {
            combined[info.name] = info
        }

        // Dolby Vision -> HEVC compatibility fallback (many MKV/MP4 Profile 7/8 streams decode cleanly via HEVC)
        if (mimeType.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true) && !requiresSecureDecoder) {
            val hevcInfos = runCatching {
                MediaCodecSelector.DEFAULT.getDecoderInfos(
                    MimeTypes.VIDEO_H265,
                    false,
                    requiresTunnelingDecoder
                )
            }.getOrDefault(emptyList())
            for (info in hevcInfos) {
                combined.putIfAbsent(info.name, info)
            }
        }

        val allowFfmpegTakeover = !requiresSecureDecoder && hasFfmpegFallbackProvider()
        val ordered = DecoderFallbackHelper.reorderCodecs(
            codecs = combined.values.toList(),
            mode = mode,
            blockedCodecNames = blocked,
            nameOf = { it.name },
            isSoftwareOf = { DecoderFallbackHelper.isSoftwareCodecName(it.name, it.softwareOnly) },
            excludeLowLatency = true,
            allowEmptyAfterFiltering = allowFfmpegTakeover
        )
        return ordered.toMutableList()
    }
}
