package com.example.player

import android.net.Uri
import androidx.media3.common.MimeTypes
import com.example.model.StreamFormat
import java.util.Locale

object MediaFormatDetector {

    fun detectFormat(rawUri: String, displayName: String? = null): StreamFormat {
        val candidate = (displayName?.takeIf { it.isNotBlank() } ?: rawUri)
            .lowercase(Locale.ROOT)
        val pathOnly = runCatching { Uri.parse(candidate).path?.lowercase(Locale.ROOT) }
            .getOrNull()
            .orEmpty()
            .ifBlank { candidate.substringBefore('?').substringBefore('#') }

        return when {
            pathOnly.endsWith(".m3u8") -> StreamFormat.M3U8
            pathOnly.endsWith(".m3u") -> StreamFormat.M3U
            pathOnly.endsWith(".mkv") -> StreamFormat.MKV
            pathOnly.endsWith(".mp4") || pathOnly.endsWith(".m4v") -> StreamFormat.MP4
            pathOnly.endsWith(".mpd") -> StreamFormat.DASH
            pathOnly.endsWith(".webm") -> StreamFormat.WEBM
            candidate.contains(".m3u8") || candidate.contains("format=m3u8") -> StreamFormat.M3U8
            candidate.contains("/hls/") || candidate.contains("hls=1") || candidate.contains("type=hls") -> StreamFormat.HLS
            candidate.contains(".mkv") -> StreamFormat.MKV
            candidate.contains(".mp4") -> StreamFormat.MP4
            candidate.contains(".m3u") -> StreamFormat.M3U
            else -> StreamFormat.AUTO
        }
    }

    /**
     * Returns an explicit Media3 MIME type when adaptive manifest hinting is needed (e.g., extensionless HLS links).
     * For progressive containers (MKV, MP4, WebM), returning null allows ExoPlayer's extractor sniffing to succeed
     * even when server URLs omit or mislabel file extensions.
     */
    fun resolveExoPlayerMimeType(format: StreamFormat): String? {
        return when (format) {
            StreamFormat.HLS, StreamFormat.M3U8 -> MimeTypes.APPLICATION_M3U8
            StreamFormat.DASH -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    fun inferTitle(rawUri: String, displayName: String? = null): String {
        if (!displayName.isNullOrBlank()) {
            return displayName.substringBeforeLast('.').ifBlank { displayName }
        }
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
        val lastSegment = uri?.lastPathSegment
            ?.substringBefore('?')
            ?.takeIf { it.isNotBlank() && it != "/" }
        if (!lastSegment.isNullOrBlank()) {
            val cleaned = Uri.decode(lastSegment)
            val withoutExt = cleaned.substringBeforeLast('.')
            if (withoutExt.isNotBlank() && withoutExt.lowercase(Locale.ROOT) !in setOf("index", "playlist", "master", "manifest", "stream")) {
                return withoutExt.replace('_', ' ').replace('-', ' ')
            }
            val parent = uri.pathSegments.dropLast(1).lastOrNull()?.let { Uri.decode(it) }
            if (!parent.isNullOrBlank()) {
                return "$parent · $cleaned"
            }
            return cleaned
        }
        return uri?.host ?: "Untitled Stream"
    }

    fun isValidMediaLink(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("content://") ||
            lower.startsWith("file://")
    }

    fun shouldInspectAsM3u(rawUri: String, selectedFormat: StreamFormat): Boolean {
        if (selectedFormat == StreamFormat.M3U) return true
        val lower = rawUri.lowercase(Locale.ROOT).substringBefore('?')
        return lower.endsWith(".m3u") || lower.endsWith(".m3u8")
    }

    fun shouldInspectAsJson(rawUri: String, displayName: String? = null): Boolean {
        val candidate = (displayName?.takeIf { it.isNotBlank() } ?: rawUri)
            .lowercase(Locale.ROOT)
            .substringBefore('?')
            .substringBefore('#')
        return candidate.endsWith(".json")
    }
}
