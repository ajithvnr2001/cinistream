package com.example.player

import android.content.Context
import com.example.data.OfflineDownloadDao
import com.example.data.OfflineDownloadEntity
import com.example.model.StreamFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Zero-permission app-private Offline Download Manager (Mobile).
 * Stores downloaded MP4/MKV progressive streams and HLS (.m3u8 + segments + rewritten local manifest)
 * inside `context.filesDir/offline_downloads/`, requiring zero broad Android storage permissions.
 */
class OfflineDownloadManager(
    context: Context,
    private val dao: OfflineDownloadDao,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_DOWNLOADING = "DOWNLOADING"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
        private const val MAX_HLS_SEGMENTS = 1200
        private const val MAX_SINGLE_FILE_BYTES = 8L * 1024L * 1024L * 1024L // 8 GB safety cap

        fun sanitizeFolderName(rawTitle: String, id: Long): String {
            val clean = rawTitle.replace(Regex("[^a-zA-Z0-9._-]+"), "_")
                .trim('_')
                .take(36)
                .ifBlank { "video" }
            return "${id}_$clean"
        }

        fun resolveRelativeUrl(baseUrl: String, relative: String): String {
            if (relative.startsWith("http://", ignoreCase = true) ||
                relative.startsWith("https://", ignoreCase = true)
            ) {
                return relative
            }
            return runCatching {
                URI(baseUrl).resolve(relative).toString()
            }.getOrDefault(relative)
        }

        /**
         * Selects the best media playlist URL from an HLS Master Playlist (prefers <=1080p for offline
         * storage balance, falling back to the highest available bandwidth variant).
         */
        fun pickBestHlsVariantUrl(masterPlaylistUrl: String, masterContent: String): String? {
            val lines = masterContent.lines().map { it.trim() }
            data class Variant(val bandwidth: Long, val height: Int, val url: String)
            val variants = mutableListOf<Variant>()
            var pendingBandwidth = 0L
            var pendingHeight = 0
            var awaitingUri = false
            val bwRegex = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
            val resRegex = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)

            for (line in lines) {
                if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                    pendingBandwidth = bwRegex.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                    pendingHeight = resRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    awaitingUri = true
                } else if (awaitingUri && line.isNotEmpty() && !line.startsWith("#")) {
                    variants.add(
                        Variant(
                            bandwidth = pendingBandwidth,
                            height = pendingHeight,
                            url = resolveRelativeUrl(masterPlaylistUrl, line)
                        )
                    )
                    awaitingUri = false
                }
            }
            if (variants.isEmpty()) return null
            val hdCandidates = variants.filter { it.height in 1..1080 }
            val chosen = if (hdCandidates.isNotEmpty()) {
                hdCandidates.maxByOrNull { it.bandwidth }
            } else {
                variants.maxByOrNull { it.bandwidth }
            }
            return chosen?.url
        }
    }

    private val rootDir: File = File(context.filesDir, "offline_downloads").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<Long, Job>()

    fun observeDownloads(): Flow<List<OfflineDownloadEntity>> = dao.observeDownloads()

    fun enqueueDownload(
        title: String,
        sourceUrl: String,
        format: StreamFormat,
        posterUrl: String? = null,
        headers: Map<String, String> = emptyMap()
    ) {
        val trimmedUrl = sourceUrl.trim()
        if (!trimmedUrl.startsWith("http://", ignoreCase = true) &&
            !trimmedUrl.startsWith("https://", ignoreCase = true)
        ) {
            return
        }
        scope.launch {
            val existing = dao.getBySourceUrl(trimmedUrl)
            if (existing != null && existing.status == STATUS_DOWNLOADING && activeJobs.containsKey(existing.id)) {
                return@launch
            }
            val now = System.currentTimeMillis()
            val entity = OfflineDownloadEntity(
                id = existing?.id ?: 0L,
                sourceUrl = trimmedUrl,
                title = title.ifBlank { MediaFormatDetector.inferTitle(trimmedUrl) },
                formatName = format.name,
                posterUrl = posterUrl,
                localFilePath = "",
                status = STATUS_QUEUED,
                progressPercent = 0,
                bytesDownloaded = 0L,
                totalBytes = 0L,
                errorMessage = null,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
            val id = dao.upsert(entity)
            val resolvedId = if (id > 0L) id else (dao.getBySourceUrl(trimmedUrl)?.id ?: return@launch)
            startDownloadJob(resolvedId, entity.copy(id = resolvedId), format, headers)
        }
    }

    fun cancelDownload(downloadId: Long) {
        activeJobs.remove(downloadId)?.cancel()
        scope.launch {
            val entity = dao.getById(downloadId) ?: return@launch
            if (entity.status == STATUS_DOWNLOADING || entity.status == STATUS_QUEUED) {
                cleanupDownloadFiles(entity)
                dao.updateStatus(
                    id = downloadId,
                    status = STATUS_CANCELLED,
                    progressPercent = 0,
                    bytesDownloaded = 0L,
                    totalBytes = entity.totalBytes,
                    localFilePath = "",
                    errorMessage = "Download cancelled"
                )
            }
        }
    }

    fun deleteDownload(downloadId: Long) {
        activeJobs.remove(downloadId)?.cancel()
        scope.launch {
            val entity = dao.getById(downloadId)
            if (entity != null) {
                cleanupDownloadFiles(entity)
            }
            dao.deleteById(downloadId)
        }
    }

    private fun startDownloadJob(
        id: Long,
        entity: OfflineDownloadEntity,
        format: StreamFormat,
        headers: Map<String, String>
    ) {
        activeJobs.remove(id)?.cancel()
        val job = scope.launch {
            val folder = File(rootDir, sanitizeFolderName(entity.title, id)).apply {
                if (exists()) deleteRecursively()
                mkdirs()
            }
            try {
                dao.updateStatus(
                    id = id,
                    status = STATUS_DOWNLOADING,
                    progressPercent = 0,
                    bytesDownloaded = 0L,
                    totalBytes = 0L,
                    localFilePath = ""
                )
                val isHls = format == StreamFormat.HLS ||
                    format == StreamFormat.M3U8 ||
                    entity.sourceUrl.contains(".m3u8", ignoreCase = true)

                val finalPath = if (isHls) {
                    downloadHlsStream(id, entity.sourceUrl, folder, headers)
                } else {
                    downloadProgressiveFile(id, entity.sourceUrl, format, folder, headers)
                }

                val totalSize = computeFolderSize(folder)
                dao.updateStatus(
                    id = id,
                    status = STATUS_COMPLETED,
                    progressPercent = 100,
                    bytesDownloaded = totalSize,
                    totalBytes = totalSize,
                    localFilePath = finalPath,
                    errorMessage = null
                )
            } catch (ce: CancellationException) {
                folder.deleteRecursively()
                throw ce
            } catch (t: Throwable) {
                folder.deleteRecursively()
                dao.updateStatus(
                    id = id,
                    status = STATUS_FAILED,
                    progressPercent = 0,
                    bytesDownloaded = 0L,
                    totalBytes = 0L,
                    localFilePath = "",
                    errorMessage = t.message?.take(140) ?: "Download failed"
                )
            } finally {
                activeJobs.remove(id)
            }
        }
        activeJobs[id] = job
    }

    private suspend fun downloadProgressiveFile(
        id: Long,
        url: String,
        format: StreamFormat,
        folder: File,
        headers: Map<String, String>
    ): String {
        val ext = when {
            format == StreamFormat.MKV || url.contains(".mkv", ignoreCase = true) -> "mkv"
            url.contains(".webm", ignoreCase = true) -> "webm"
            url.contains(".ts", ignoreCase = true) -> "ts"
            else -> "mp4"
        }
        val outFile = File(folder, "media.$ext")
        val req = buildRequest(url, headers)
        okHttpClient.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("Empty response body")
            val contentLength = body.contentLength().coerceAtLeast(0L)
            if (contentLength > MAX_SINGLE_FILE_BYTES) {
                throw IllegalStateException("File exceeds offline size limit")
            }
            val buffer = ByteArray(64 * 1024)
            var downloaded = 0L
            var lastReportedPct = -1
            var lastReportMs = 0L

            body.byteStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val pct = if (contentLength > 0L) {
                            ((downloaded * 100L) / contentLength).toInt().coerceIn(0, 99)
                        } else {
                            0
                        }
                        val now = System.currentTimeMillis()
                        if (pct != lastReportedPct && now - lastReportMs >= 400L) {
                            lastReportedPct = pct
                            lastReportMs = now
                            dao.updateStatus(
                                id = id,
                                status = STATUS_DOWNLOADING,
                                progressPercent = pct,
                                bytesDownloaded = downloaded,
                                totalBytes = contentLength,
                                localFilePath = ""
                            )
                        }
                    }
                }
            }
        }
        return outFile.absolutePath
    }

    private suspend fun downloadHlsStream(
        id: Long,
        playlistUrl: String,
        folder: File,
        headers: Map<String, String>
    ): String {
        val initialManifest = fetchText(playlistUrl, headers)
            ?: throw IllegalStateException("Failed to fetch HLS playlist")

        val mediaPlaylistUrl: String
        val mediaManifest: String
        if (initialManifest.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
            mediaPlaylistUrl = pickBestHlsVariantUrl(playlistUrl, initialManifest)
                ?: throw IllegalStateException("No playable HLS variant found")
            mediaManifest = fetchText(mediaPlaylistUrl, headers)
                ?: throw IllegalStateException("Failed to fetch HLS variant playlist")
        } else {
            mediaPlaylistUrl = playlistUrl
            mediaManifest = initialManifest
        }

        val lines = mediaManifest.lines()
        val segmentLines = lines.filter { it.trim().isNotEmpty() && !it.trim().startsWith("#") }
        if (segmentLines.isEmpty()) {
            throw IllegalStateException("HLS playlist has no media segments")
        }
        if (segmentLines.size > MAX_HLS_SEGMENTS) {
            throw IllegalStateException("HLS stream too long for offline save (${segmentLines.size} segments)")
        }

        val rewrittenLines = mutableListOf<String>()
        var segmentIndex = 0
        var totalBytes = 0L
        var lastPct = -1

        for (rawLine in lines) {
            currentCoroutineContext().ensureActive()
            val trimmed = rawLine.trim()
            when {
                trimmed.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    val uriMatch = Regex("""URI="([^"]+)"""").find(trimmed)
                    if (uriMatch != null) {
                        val keyUrl = resolveRelativeUrl(mediaPlaylistUrl, uriMatch.groupValues[1])
                        val keyFile = File(folder, "key_$segmentIndex.bin")
                        downloadBinaryFile(keyUrl, keyFile, headers)
                        rewrittenLines.add(trimmed.replace(uriMatch.value, """URI="${keyFile.name}""""))
                    } else {
                        rewrittenLines.add(trimmed)
                    }
                }
                trimmed.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    val uriMatch = Regex("""URI="([^"]+)"""").find(trimmed)
                    if (uriMatch != null) {
                        val mapUrl = resolveRelativeUrl(mediaPlaylistUrl, uriMatch.groupValues[1])
                        val mapFile = File(folder, "init_$segmentIndex.mp4")
                        totalBytes += downloadBinaryFile(mapUrl, mapFile, headers)
                        rewrittenLines.add(trimmed.replace(uriMatch.value, """URI="${mapFile.name}""""))
                    } else {
                        rewrittenLines.add(trimmed)
                    }
                }
                trimmed.isEmpty() || trimmed.startsWith("#") -> {
                    rewrittenLines.add(trimmed)
                }
                else -> {
                    val segUrl = resolveRelativeUrl(mediaPlaylistUrl, trimmed)
                    val ext = if (trimmed.contains(".m4s", ignoreCase = true)) "m4s" else "ts"
                    val segFile = File(folder, "seg_${segmentIndex}.$ext")
                    totalBytes += downloadBinaryFile(segUrl, segFile, headers)
                    rewrittenLines.add(segFile.name)
                    segmentIndex++
                    val pct = ((segmentIndex * 100) / segmentLines.size).coerceIn(0, 99)
                    if (pct != lastPct) {
                        lastPct = pct
                        dao.updateStatus(
                            id = id,
                            status = STATUS_DOWNLOADING,
                            progressPercent = pct,
                            bytesDownloaded = totalBytes,
                            totalBytes = 0L,
                            localFilePath = ""
                        )
                    }
                }
            }
        }

        val localManifest = File(folder, "index.m3u8")
        localManifest.writeText(rewrittenLines.joinToString("\n"), Charsets.UTF_8)
        return localManifest.absolutePath
    }

    private fun fetchText(url: String, headers: Map<String, String>): String? {
        return okHttpClient.newCall(buildRequest(url, headers)).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            resp.body?.string()
        }
    }

    private fun downloadBinaryFile(url: String, target: File, headers: Map<String, String>): Long {
        okHttpClient.newCall(buildRequest(url, headers)).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("Segment HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("Empty segment body")
            var written = 0L
            val buf = ByteArray(32 * 1024)
            body.byteStream().use { input ->
                FileOutputStream(target).use { output ->
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        written += read
                    }
                }
            }
            return written
        }
    }

    private fun buildRequest(url: String, headers: Map<String, String>): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
        headers.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank()) builder.header(k, v)
        }
        return builder.build()
    }

    private fun cleanupDownloadFiles(entity: OfflineDownloadEntity) {
        if (entity.localFilePath.isNotBlank()) {
            val file = File(entity.localFilePath)
            val parent = file.parentFile
            if (parent != null && parent.parentFile?.canonicalPath == rootDir.canonicalPath) {
                parent.deleteRecursively()
            } else if (file.exists()) {
                file.delete()
            }
        }
        val expectedFolder = File(rootDir, sanitizeFolderName(entity.title, entity.id))
        if (expectedFolder.exists()) {
            expectedFolder.deleteRecursively()
        }
    }

    private fun computeFolderSize(folder: File): Long {
        if (!folder.exists()) return 0L
        return folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
