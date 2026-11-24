package com.example.player

import android.content.Context
import android.net.Uri
import com.example.model.M3uEntry
import com.example.model.StreamFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID

sealed class M3uInspectionResult {
    data object DirectHlsStream : M3uInspectionResult()
    data class ChannelPlaylist(
        val entries: List<M3uEntry>,
        val epgUrl: String? = null
    ) : M3uInspectionResult()
    data object FallbackDirectPlay : M3uInspectionResult()
}

class M3uPlaylistParser(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val groupTitleRegex = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val tvgIdRegex = Regex("""tvg-id="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val tvgLogoRegex = Regex("""tvg-logo="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val headerEpgRegex = Regex("""(?:url-tvg|x-tvg-url)="([^"]*)"""", RegexOption.IGNORE_CASE)

    suspend fun inspect(uriString: String, isLocalFile: Boolean): M3uInspectionResult =
        withContext(Dispatchers.IO) {
            val content = runCatching {
                if (isLocalFile || uriString.startsWith("content://") || uriString.startsWith("file://")) {
                    readLocalText(Uri.parse(uriString))
                } else {
                    fetchRemoteText(uriString)
                }
            }.getOrNull()

            if (content.isNullOrBlank()) {
                return@withContext M3uInspectionResult.FallbackDirectPlay
            }
            parseContent(content)
        }

    fun parseContent(content: String): M3uInspectionResult {
        val lines = content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        if (lines.isEmpty()) return M3uInspectionResult.FallbackDirectPlay

        // Distinguish HLS manifests from M3U IPTV / multi-video playlists
        val isHlsManifest = lines.any { line ->
            line.startsWith("#EXT-X-TARGETDURATION") ||
                line.startsWith("#EXT-X-STREAM-INF") ||
                line.startsWith("#EXT-X-MEDIA-SEQUENCE") ||
                line.startsWith("#EXT-X-PLAYLIST-TYPE") ||
                line.startsWith("#EXT-X-INDEPENDENT-SEGMENTS")
        }
        if (isHlsManifest) {
            return M3uInspectionResult.DirectHlsStream
        }

        val entries = mutableListOf<M3uEntry>()
        var headerEpgUrl: String? = null
        var pendingTitle: String? = null
        var pendingGroup = "Playlist"
        var pendingTvgId: String? = null
        var pendingTvgLogo: String? = null

        for (line in lines) {
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val found = headerEpgRegex.find(line)?.groupValues?.getOrNull(1)?.trim()
                    if (!found.isNullOrBlank() && (found.startsWith("http://", true) || found.startsWith("https://", true))) {
                        headerEpgUrl = found
                    }
                }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val commaIdx = line.indexOf(',')
                    pendingTitle = if (commaIdx != -1 && commaIdx + 1 < line.length) {
                        line.substring(commaIdx + 1).trim()
                    } else {
                        null
                    }
                    pendingGroup = groupTitleRegex.find(line)?.groupValues?.getOrNull(1)
                        ?.takeIf { it.isNotBlank() }
                        ?: "Playlist"
                    pendingTvgId = tvgIdRegex.find(line)?.groupValues?.getOrNull(1)
                        ?.takeIf { it.isNotBlank() }
                    pendingTvgLogo = tvgLogoRegex.find(line)?.groupValues?.getOrNull(1)
                        ?.takeIf { it.isNotBlank() }
                }
                line.startsWith("#") -> {
                    // Ignore other M3U directives/comments
                }
                line.startsWith("http://", ignoreCase = true) ||
                    line.startsWith("https://", ignoreCase = true) -> {
                    val detected = MediaFormatDetector.detectFormat(line)
                    val title = pendingTitle?.takeIf { it.isNotBlank() }
                        ?: MediaFormatDetector.inferTitle(line)
                    entries.add(
                        M3uEntry(
                            id = UUID.randomUUID().toString(),
                            title = title,
                            url = line,
                            groupTitle = pendingGroup,
                            format = if (detected == StreamFormat.AUTO) StreamFormat.HLS else detected,
                            tvgId = pendingTvgId,
                            tvgLogo = pendingTvgLogo
                        )
                    )
                    pendingTitle = null
                    pendingGroup = "Playlist"
                    pendingTvgId = null
                    pendingTvgLogo = null
                }
            }
        }

        return if (entries.isNotEmpty()) {
            M3uInspectionResult.ChannelPlaylist(entries = entries, epgUrl = headerEpgUrl)
        } else {
            M3uInspectionResult.FallbackDirectPlay
        }
    }

    private fun readLocalText(uri: Uri): String? {
        val stream = when (uri.scheme) {
            "file" -> uri.path?.let { java.io.File(it) }?.takeIf { it.exists() }?.inputStream()
            else -> context.contentResolver.openInputStream(uri)
        }
        return stream?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                val sb = StringBuilder()
                var count = 0
                var line = reader.readLine()
                // Read up to 2500 lines to handle large playlists safely without OOM
                while (line != null && count < 2500) {
                    sb.append(line).append('\n')
                    count++
                    line = reader.readLine()
                }
                sb.toString()
            }
        }
    }

    private fun fetchRemoteText(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
            .build()
        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            val source = body.source()
            // Cap inspection read at 256 KB without cloning the okio buffer
            source.request(262_144L)
            val toRead = minOf(source.buffer.size, 262_144L)
            source.readByteArray(toRead).toString(Charsets.UTF_8)
        }
    }
}
