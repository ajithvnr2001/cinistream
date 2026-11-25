package com.example.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
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
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body ?: return@use null
                    val source = body.source()
                    // Cap subtitle download at 2 MB so malformed URLs cannot OOM low-RAM devices
                    source.request(2_097_152L)
                    val toRead = minOf(source.buffer.size, 2_097_152L)
                    source.readByteArray(toRead).toString(Charsets.UTF_8)
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
                val body = resp.body ?: return@use null
                val source = body.source()
                // Read at most 512 bytes even if the origin server ignores the Range header
                source.request(512L)
                val toRead = minOf(source.buffer.size, 512L)
                if (toRead <= 0L) return@use null
                sniffHeaderBytes(source.readByteArray(toRead))
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

/**
 * Builds Media3 1.11.1 compliant subtitle [MediaSource] instances for [MergingMediaSource] (HLS/DASH).
 *
 * Media3 1.11.1 disables legacy decoding in `TextRenderer` by default (`application/x-media3-cues`
 * required). Using [ProgressiveMediaSource] + [SubtitleExtractor] transcodes `.srt`, `.vtt`, `.ass`,
 * `.ssa`, and `.ttml` sidecar files into `application/x-media3-cues` during extraction, matching
 * `DefaultMediaSourceFactory` behavior and preventing `Legacy decoding is disabled` crashes.
 */
@OptIn(UnstableApi::class)
object SubtitleMediaSourceBuilder {
    private val subtitleParserFactory = DefaultSubtitleParserFactory()

    fun supportsExtractionTranscoding(mimeType: String?): Boolean {
        if (mimeType.isNullOrBlank()) return false
        val format = Format.Builder().setSampleMimeType(mimeType).build()
        return subtitleParserFactory.supportsFormat(format)
    }

    fun buildMediaSource(
        subConfig: MediaItem.SubtitleConfiguration,
        dataSourceFactory: DataSource.Factory,
        loadErrorHandlingPolicy: LoadErrorHandlingPolicy? = null
    ): MediaSource {
        val rawMime = subConfig.mimeType ?: MimeTypes.APPLICATION_SUBRIP
        val subtitleFormat = Format.Builder()
            .setId(subConfig.id)
            .setSampleMimeType(rawMime)
            .setSelectionFlags(subConfig.selectionFlags)
            .setRoleFlags(subConfig.roleFlags)
            .setLanguage(subConfig.language)
            .setLabel(subConfig.label)
            .build()

        return if (subtitleParserFactory.supportsFormat(subtitleFormat)) {
            val extractorsFactory = ExtractorsFactory {
                arrayOf(
                    SubtitleExtractor(
                        subtitleParserFactory.create(subtitleFormat),
                        subtitleFormat
                    )
                )
            }
            val factory = ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
            if (loadErrorHandlingPolicy != null) {
                factory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            }
            factory.createMediaSource(MediaItem.fromUri(subConfig.uri))
        } else {
            @Suppress("DEPRECATION")
            val factory = SingleSampleMediaSource.Factory(dataSourceFactory)
            if (loadErrorHandlingPolicy != null) {
                factory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            }
            factory.createMediaSource(subConfig, C.TIME_UNSET)
        }
    }

    fun formatSubtitleCodecLabel(sampleMimeType: String?, codecs: String? = null): String {
        val effectiveMime = if (sampleMimeType.equals(MimeTypes.APPLICATION_MEDIA3_CUES, ignoreCase = true) &&
            !codecs.isNullOrBlank()
        ) {
            codecs.trim()
        } else {
            sampleMimeType.orEmpty().trim()
        }
        return when (effectiveMime.lowercase(Locale.ROOT)) {
            MimeTypes.APPLICATION_SUBRIP -> "SRT"
            MimeTypes.TEXT_VTT -> "VTT"
            MimeTypes.TEXT_SSA -> "ASS"
            MimeTypes.APPLICATION_TTML -> "TTML"
            MimeTypes.APPLICATION_CEA608 -> "CEA-608"
            MimeTypes.APPLICATION_CEA708 -> "CEA-708"
            MimeTypes.APPLICATION_MEDIA3_CUES -> "CUES"
            else -> effectiveMime.substringAfter('/', missingDelimiterValue = effectiveMime)
                .uppercase(Locale.US)
                .ifBlank { "TEXT" }
        }
    }
}

