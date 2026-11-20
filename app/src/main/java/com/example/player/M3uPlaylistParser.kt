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
    data class ChannelPlaylist(val entries: List<M3uEntry>) : M3uInspectionResult()
    data object FallbackDirectPlay : M3uInspectionResult()
}

class M3uPlaylistParser(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val groupTitleRegex = Regex("""group-title="([^"]*)"""")

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
        var pendingTitle: String? = null
        var pendingGroup = "Playlist"

        for (line in lines) {
            when {
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
                            format = if (detected == StreamFormat.AUTO) StreamFormat.HLS else detected
                        )
                    )
                    pendingTitle = null
                    pendingGroup = "Playlist"
                }
            }
        }

        return if (entries.isNotEmpty()) {
            M3uInspectionResult.ChannelPlaylist(entries)
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
            // Cap inspection read at 256 KB so huge media files aren't downloaded
            source.request(262_144L)
            val bytes = source.buffer.clone().readByteArray(minOf(source.buffer.size, 262_144L))
            bytes.toString(Charsets.UTF_8)
        }
    }
}
