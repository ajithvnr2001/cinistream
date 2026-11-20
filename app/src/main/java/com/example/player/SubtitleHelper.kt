package com.example.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MimeTypes
import com.example.model.ExternalSubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.UUID

class SubtitleHelper(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private val srtTimestampRegex =
        Regex("""(\d{1,2}):(\d{2}):(\d{2}),(\d{3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2}),(\d{3})""")
    private val vttTimestampRegex =
        Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})\.(\d{3})\s*-->\s*(?:(\d{1,2}):)?(\d{2}):(\d{2})\.(\d{3})""")

    fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            }
        }
        return uri.lastPathSegment
            ?.substringBefore('?')
            ?.let { Uri.decode(it) }
            ?.takeIf { it.isNotBlank() }
            ?: "External Subtitle"
    }

    fun takePersistableReadPermission(uri: Uri) {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    suspend fun buildFromLocalUri(uri: Uri): ExternalSubtitle = withContext(Dispatchers.IO) {
        takePersistableReadPermission(uri)
        val displayName = resolveDisplayName(uri)
        val detectedMime = detectMimeFromName(displayName)
            ?: sniffLocalSubtitleMime(uri)
            ?: MimeTypes.APPLICATION_SUBRIP
        val id = UUID.randomUUID().toString()
        val durableUri = cacheLocalSubtitleForReliableAccess(uri, id, detectedMime) ?: uri
        ExternalSubtitle(
            id = id,
            uri = durableUri.toString(),
            label = displayName,
            mimeType = detectedMime,
            formatBadge = mimeToBadge(detectedMime),
            isLocalFile = true
        )
    }

    private fun cacheLocalSubtitleForReliableAccess(
        sourceUri: Uri,
        id: String,
        mimeType: String
    ): Uri? {
        return runCatching {
            val ext = when (mimeType) {
                MimeTypes.TEXT_VTT -> "vtt"
                MimeTypes.TEXT_SSA -> "ass"
                MimeTypes.APPLICATION_TTML -> "ttml"
                else -> "srt"
            }
            val dir = File(context.filesDir, "subtitles").apply { mkdirs() }
            val outFile = File(dir, "sub_$id.$ext")
            val input = openLocalInputStream(sourceUri) ?: return null
            input.use { src ->
                outFile.outputStream().use { dst ->
                    src.copyTo(dst)
                }
            }
            if (outFile.length() > 0L) Uri.fromFile(outFile) else null
        }.getOrNull()
    }

    private fun openLocalInputStream(uri: Uri): java.io.InputStream? {
        return when (uri.scheme) {
            "file" -> uri.path?.let { File(it) }?.takeIf { it.exists() }?.inputStream()
            else -> context.contentResolver.openInputStream(uri)
        }
    }

    suspend fun buildFromRemoteUrl(url: String): ExternalSubtitle = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        val parsed = Uri.parse(trimmed)
        val displayName = resolveDisplayName(parsed)
        val detectedMime = detectMimeFromName(displayName)
            ?: detectMimeFromName(trimmed)
            ?: sniffRemoteSubtitleMime(trimmed)
            ?: MimeTypes.APPLICATION_SUBRIP
        ExternalSubtitle(
            id = UUID.randomUUID().toString(),
            uri = trimmed,
            label = displayName,
            mimeType = detectedMime,
            formatBadge = mimeToBadge(detectedMime),
            isLocalFile = false
        )
    }

    fun detectMimeFromName(nameOrUrl: String): String? {
        val clean = nameOrUrl.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return when {
            clean.endsWith(".srt") -> MimeTypes.APPLICATION_SUBRIP
            clean.endsWith(".vtt") || clean.endsWith(".webvtt") -> MimeTypes.TEXT_VTT
            clean.endsWith(".ass") || clean.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            clean.endsWith(".ttml") || clean.endsWith(".xml") || clean.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> null
        }
    }

    fun mimeToBadge(mimeType: String): String {
        return when (mimeType) {
            MimeTypes.APPLICATION_SUBRIP -> "SRT"
            MimeTypes.TEXT_VTT -> "VTT"
            MimeTypes.TEXT_SSA -> "ASS"
            MimeTypes.APPLICATION_TTML -> "TTML"
            else -> "SUB"
        }
    }

    /**
     * Applies a timing offset (in milliseconds) to SRT or VTT external subtitles by creating a shifted
     * cached copy. Returns the original URI if offset == 0 or format is ASS/TTML.
     */
    suspend fun resolvePlayableSubtitleUri(
        subtitle: ExternalSubtitle,
        offsetMs: Long
    ): Uri = withContext(Dispatchers.IO) {
        val originalUri = Uri.parse(subtitle.uri)
        if (offsetMs == 0L) return@withContext originalUri
        if (subtitle.mimeType != MimeTypes.APPLICATION_SUBRIP && subtitle.mimeType != MimeTypes.TEXT_VTT) {
            return@withContext originalUri
        }

        val rawText = runCatching {
            if (subtitle.isLocalFile || originalUri.scheme == "content" || originalUri.scheme == "file") {
                openLocalInputStream(originalUri)?.bufferedReader(Charsets.UTF_8)?.use {
                    it.readText()
                }
            } else {
                val request = Request.Builder()
                    .url(subtitle.uri)
                    .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
                    .build()
                okHttpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }
        }.getOrNull() ?: return@withContext originalUri

        val shiftedText = if (subtitle.mimeType == MimeTypes.TEXT_VTT) {
            shiftVttTimestamps(rawText, offsetMs)
        } else {
            shiftSrtTimestamps(rawText, offsetMs)
        }

        val ext = if (subtitle.mimeType == MimeTypes.TEXT_VTT) "vtt" else "srt"
        val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
        val prefix = "shifted_${subtitle.id}_"
        dir.listFiles()?.forEach { existing ->
            if (existing.name.startsWith(prefix)) existing.delete()
        }
        val outFile = File(dir, "$prefix$offsetMs.$ext")
        outFile.writeText(shiftedText, Charsets.UTF_8)
        Uri.fromFile(outFile)
    }

    private fun shiftSrtTimestamps(text: String, offsetMs: Long): String {
        return srtTimestampRegex.replace(text) { match ->
            val startMs = toMs(
                match.groupValues[1].toLong(),
                match.groupValues[2].toLong(),
                match.groupValues[3].toLong(),
                match.groupValues[4].toLong()
            )
            val endMs = toMs(
                match.groupValues[5].toLong(),
                match.groupValues[6].toLong(),
                match.groupValues[7].toLong(),
                match.groupValues[8].toLong()
            )
            val newStart = (startMs + offsetMs).coerceAtLeast(0L)
            val newEnd = (endMs + offsetMs).coerceAtLeast(newStart + 50L)
            "${formatSrtMs(newStart)} --> ${formatSrtMs(newEnd)}"
        }
    }

    private fun shiftVttTimestamps(text: String, offsetMs: Long): String {
        return vttTimestampRegex.replace(text) { match ->
            val startMs = toMs(
                match.groupValues[1].ifEmpty { "0" }.toLong(),
                match.groupValues[2].toLong(),
                match.groupValues[3].toLong(),
                match.groupValues[4].toLong()
            )
            val endMs = toMs(
                match.groupValues[5].ifEmpty { "0" }.toLong(),
                match.groupValues[6].toLong(),
                match.groupValues[7].toLong(),
                match.groupValues[8].toLong()
            )
            val newStart = (startMs + offsetMs).coerceAtLeast(0L)
            val newEnd = (endMs + offsetMs).coerceAtLeast(newStart + 50L)
            "${formatVttMs(newStart)} --> ${formatVttMs(newEnd)}"
        }
    }

    private fun toMs(h: Long, m: Long, s: Long, ms: Long): Long =
        ((h * 3600L + m * 60L + s) * 1000L) + ms

    private fun formatSrtMs(totalMs: Long): String {
        val h = totalMs / 3_600_000L
        val m = (totalMs % 3_600_000L) / 60_000L
        val s = (totalMs % 60_000L) / 1000L
        val ms = totalMs % 1000L
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, ms)
    }

    private fun formatVttMs(totalMs: Long): String {
        val h = totalMs / 3_600_000L
        val m = (totalMs % 3_600_000L) / 60_000L
        val s = (totalMs % 60_000L) / 1000L
        val ms = totalMs % 1000L
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", h, m, s, ms)
    }

    private fun sniffLocalSubtitleMime(uri: Uri): String? {
        return runCatching {
            openLocalInputStream(uri)?.use { stream ->
                val buf = ByteArray(512)
                val read = stream.read(buf)
                if (read > 0) sniffHeaderBytes(buf.copyOf(read)) else null
            }
        }.getOrNull()
    }

    private fun sniffRemoteSubtitleMime(url: String): String? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
                .header("Range", "bytes=0-511")
                .build()
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val bytes = resp.body?.bytes() ?: return@use null
                sniffHeaderBytes(bytes)
            }
        }.getOrNull()
    }

    private fun sniffHeaderBytes(bytes: ByteArray): String? {
        val header = bytes.toString(Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        return when {
            header.startsWith("WEBVTT", ignoreCase = true) -> MimeTypes.TEXT_VTT
            header.contains("[Script Info]", ignoreCase = true) -> MimeTypes.TEXT_SSA
            header.contains("<tt", ignoreCase = true) || header.contains("ttml", ignoreCase = true) -> MimeTypes.APPLICATION_TTML
            header.contains("-->") -> MimeTypes.APPLICATION_SUBRIP
            else -> null
        }
    }
}
