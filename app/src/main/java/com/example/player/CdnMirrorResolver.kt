package com.example.player

import android.net.Uri
import java.util.Locale

/**
 * Pure, TV-safe helpers for CineStream's 3-layer in-app CDN engine:
 *  1. Multi-CDN / mirror URL candidate resolution (per-item mirrors + path-preserving host swaps)
 *  2. Local edge disk cache eligibility (caches VOD media segments, skips live/HLS/DASH manifests)
 *  3. Storage-safe cache caps for Android TV vs handheld devices
 */
object CdnMirrorResolver {

    const val TV_DISK_CACHE_MAX_BYTES: Long = 64L * 1024L * 1024L
    const val MOBILE_DISK_CACHE_MAX_BYTES: Long = 128L * 1024L * 1024L

    /**
     * Builds an ordered, deduplicated list of playable URLs:
     *  1. Primary URL
     *  2. Explicit per-item / per-episode mirror URLs
     *  3. Path-preserving origin swaps generated from playlist-level [playlistCdnMirrors]
     *     (e.g., primary `https://worker1.workers.dev/pub/movie/master.m3u8` +
     *     mirror host `https://cdn2.example.com` -> `https://cdn2.example.com/pub/movie/master.m3u8`)
     */
    fun buildCandidateUrls(
        primaryUrl: String,
        itemMirrors: List<String> = emptyList(),
        playlistCdnMirrors: List<String> = emptyList()
    ): List<String> {
        val result = LinkedHashSet<String>()
        val cleanPrimary = primaryUrl.trim()
        if (cleanPrimary.isNotBlank()) {
            result.add(cleanPrimary)
        }

        for (mirror in itemMirrors) {
            val trimmed = mirror.trim()
            if (MediaFormatDetector.isValidMediaLink(trimmed)) {
                result.add(trimmed)
            }
        }

        if (playlistCdnMirrors.isNotEmpty() && isHttpUrl(cleanPrimary)) {
            val primaryUri = runCatching { Uri.parse(cleanPrimary) }.getOrNull()
            val primaryPath = primaryUri?.encodedPath.orEmpty()
            val primaryQuery = primaryUri?.encodedQuery
            if (primaryUri != null && primaryPath.isNotBlank()) {
                for (mirrorBase in playlistCdnMirrors) {
                    val swapped = buildHostSwappedUrl(
                        mirrorBase = mirrorBase,
                        encodedPath = primaryPath,
                        encodedQuery = primaryQuery
                    )
                    if (!swapped.isNullOrBlank()) {
                        result.add(swapped)
                    }
                }
            }
        }

        return result.toList()
    }

    /**
     * Combines a CDN mirror base origin (with optional base path prefix) and the original URL's
     * path + query string while preserving relative HLS structure.
     */
    fun buildHostSwappedUrl(
        mirrorBase: String,
        encodedPath: String,
        encodedQuery: String?
    ): String? {
        val trimmedBase = mirrorBase.trim().trimEnd('/')
        if (!isHttpUrl(trimmedBase)) return null
        val normalizedPath = if (encodedPath.startsWith("/")) encodedPath else "/$encodedPath"
        val querySuffix = if (!encodedQuery.isNullOrBlank()) "?$encodedQuery" else ""
        return "$trimmedBase$normalizedPath$querySuffix"
    }

    /**
     * Determines whether a request URI should pass through the on-device Media3 [SimpleCache].
     *
     * Rules:
     *  - Local files (`content://`, `file://`, `asset://`) are NEVER duplicated into disk cache.
     *  - HLS/DASH manifests (`.m3u8`, `.m3u`, `.mpd`, `/manifest`) bypass cache so live playlists
     *    and expiring CDN tokens never go stale.
     *  - Media segments and progressive files (`.ts`, `.m4s`, `.mp4`, `.mkv`, `.webm`, `.aac`, etc.)
     *    are cached for instant seek-back, pause/resume, and jitter smoothing.
     */
    fun shouldCacheUri(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "http" && scheme != "https") return false

        val pathLower = uri.path?.lowercase(Locale.ROOT).orEmpty()
        if (pathLower.endsWith(".m3u8") ||
            pathLower.endsWith(".m3u") ||
            pathLower.endsWith(".mpd") ||
            pathLower.endsWith(".ism/manifest") ||
            pathLower.endsWith(".srt") ||
            pathLower.endsWith(".vtt") ||
            pathLower.endsWith(".ass") ||
            pathLower.endsWith(".ssa") ||
            pathLower.endsWith(".ttml")
        ) {
            return false
        }
        return true
    }

    fun computeSafeDiskCacheBytes(isConstrainedOrTv: Boolean): Long {
        return if (isConstrainedOrTv) TV_DISK_CACHE_MAX_BYTES else MOBILE_DISK_CACHE_MAX_BYTES
    }

    private fun isHttpUrl(url: String): Boolean {
        return url.startsWith("http://", ignoreCase = true) ||
            url.startsWith("https://", ignoreCase = true)
    }
}
