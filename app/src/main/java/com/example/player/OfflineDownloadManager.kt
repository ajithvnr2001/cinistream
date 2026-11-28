package com.example.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.example.data.OfflineDownloadDao
import com.example.data.OfflineDownloadEntity
import com.example.model.StreamFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * External audio track candidate (from active playback session or JSON playlist) that can be
 * displayed in the mobile download quality picker and downloaded alongside the primary video.
 */
data class DownloadExternalAudioCandidate(
    val id: String,
    val url: String,
    val label: String,
    val language: String? = null,
    val formatBadge: String = "AUDIO",
    val isHls: Boolean = false,
    val delayMs: Long = 0L,
    val isCurrentlyActive: Boolean = false
)

/**
 * Saved sidecar manifest (`offline_audio.json`) stored inside a completed offline download folder
 * when an external audio track (HLS `.m3u8` or direct `.mp3`/`.ac3`/`.flac`/`.wav`/`.m4a`/etc.)
 * was bundled with the offline video.
 */
data class OfflineSidecarAudio(
    val id: String,
    val relativePath: String,
    val label: String,
    val language: String? = null,
    val formatBadge: String = "AUDIO",
    val isHls: Boolean = false,
    val delayMs: Long = 0L
)

data class DownloadQualityOption(
    val id: String,
    val label: String,
    val detail: String,
    val height: Int,
    val bandwidthBps: Long,
    val variantPlaylistUrl: String,
    val streamInfLine: String,
    val audioPlaylistUrl: String? = null,
    val audioName: String = "Default",
    val audioLanguage: String? = null,
    val audioChannels: String? = null,
    val audioBitrateBps: Long = 0L,
    val estimatedBytes: Long = 0L,
    val isRecommended: Boolean = false,
    val hasExplicitAudioChoice: Boolean = false,
    val isExternalAudio: Boolean = false,
    val externalAudioUrl: String? = null,
    val externalAudioId: String? = null,
    val externalAudioFormatBadge: String? = null,
    val externalAudioIsHls: Boolean = false,
    val externalAudioDelayMs: Long = 0L,
    val isProgressiveVideo: Boolean = false
)

/**
 * High-Speed Resumable & Network-Resilient Offline Download Manager (Mobile).
 *
 * - **Resumable Checkpoint Persistence**: Never wipes downloaded HLS segments or progressive byte
 *   ranges on network drop, Wi-Fi ↔ Mobile switches, pause, or transient errors. Stores per-download
 *   checkpoint state in `download_state.json` and writes HLS segments atomically via `.part` files.
 * - **Seamless Network-Off & Network-Switch Recovery**: Registers a default [ConnectivityManager]
 *   callback to evict stale sockets across Wi-Fi ↔ Cellular transitions, auto-pause gracefully into
 *   [STATUS_PAUSED] when offline, and auto-resume from the exact saved byte/segment once online.
 * - **Multi-Quality HLS Selection**: Parses all `#EXT-X-STREAM-INF` master variants + matching
 *   `#EXT-X-MEDIA:TYPE=AUDIO` renditions with bitrate & estimated file sizes via [inspectHlsQualities].
 * - **Parallel Engines**: 8-way parallel HLS segment pool + 4-way parallel HTTP `206` range chunks
 *   for large MP4/MKV/WebDAV files, with live bytes/sec telemetry via [downloadSpeeds].
 */
class OfflineDownloadManager(
    context: Context,
    private val dao: OfflineDownloadDao,
    okHttpClient: OkHttpClient
) {
    companion object {
        const val STATUS_QUEUED = "QUEUED"
        const val STATUS_DOWNLOADING = "DOWNLOADING"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_CANCELLED = "CANCELLED"
        const val MAX_HLS_SEGMENTS = 4000
        const val HLS_PARALLEL_CONCURRENCY = 8
        const val PROGRESSIVE_PARALLEL_CHUNKS = 4
        const val MIN_MULTICHUNK_BYTES = 4L * 1024L * 1024L // 4 MB threshold for 4x parallel chunks
        private const val MAX_NETWORK_RETRIES = 5
        private const val MAX_SERVER_RETRIES = 3
        private const val MAX_REDIRECT_HOPS = 5
        private const val MAX_SINGLE_FILE_BYTES = 8L * 1024L * 1024L * 1024L // 8 GB safety cap
        private const val STATE_FILE_NAME = "download_state.json"
        const val SIDECAR_AUDIO_FILE_NAME = "offline_audio.json"

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

        fun formatSpeed(bytesPerSec: Long): String {
            if (bytesPerSec <= 0L) return ""
            val kb = bytesPerSec / 1024.0
            val mb = kb / 1024.0
            return when {
                mb >= 1.0 -> String.format(Locale.US, "%.1f MB/s", mb)
                kb >= 1.0 -> String.format(Locale.US, "%.0f KB/s", kb)
                else -> "$bytesPerSec B/s"
            }
        }

        fun formatEstimatedSize(bytes: Long): String {
            if (bytes <= 0L) return ""
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1024.0) {
                String.format(Locale.US, "~%.2f GB", mb / 1024.0)
            } else {
                String.format(Locale.US, "~%.0f MB", mb.coerceAtLeast(1.0))
            }
        }

        fun formatBitrateLabel(bps: Long): String {
            if (bps <= 0L) return ""
            val mbps = bps / 1_000_000.0
            return if (mbps >= 1.0) {
                String.format(Locale.US, "%.1f Mbps", mbps)
            } else {
                "${(bps / 1000L).coerceAtLeast(1L)} kbps"
            }
        }

        data class HlsMasterSelection(
            val videoPlaylistUrl: String,
            val videoStreamInfLine: String,
            val audioPlaylistUrl: String? = null,
            val audioName: String = "Default",
            val audioLanguage: String? = null
        )

        /**
         * Sums `#EXTINF:<seconds>,` durations from an HLS media playlist to determine total
         * runtime in milliseconds when the player hasn't reported duration yet.
         */
        fun parseHlsPlaylistDurationMs(mediaPlaylistContent: String): Long {
            var totalSeconds = 0.0
            for (raw in mediaPlaylistContent.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                    val sec = line.substringAfter(":").substringBefore(",").trim().toDoubleOrNull()
                    if (sec != null && sec > 0.0) {
                        totalSeconds += sec
                    }
                }
            }
            return (totalSeconds * 1000.0).toLong().coerceAtLeast(0L)
        }

        private fun formatChannelBadge(channelsRaw: String?): String? {
            if (channelsRaw.isNullOrBlank()) return null
            val upper = channelsRaw.uppercase(Locale.US)
            val count = upper.substringBefore('/').toIntOrNull()
            return when {
                upper.contains("JOC") || upper.contains("ATMOS") -> "5.1 Atmos"
                count == 8 -> "7.1"
                count == 6 -> "5.1"
                count == 2 -> "2.0"
                count == 1 -> "Mono"
                else -> null
            }
        }

        private fun estimateAudioBitrateBps(
            channelsRaw: String?,
            name: String,
            groupId: String,
            uri: String?
        ): Long {
            val combined = "$groupId $name ${uri.orEmpty()}".lowercase(Locale.US)
            // Check for explicit kbps hints like "384k", "256kbps", "128k"
            val explicitKbps = Regex("""(?:^|[^0-9])(64|96|128|160|192|224|256|320|384|448|512|640|768)(?:k|kbps)?(?:[^0-9]|$)""")
                .find(combined)
                ?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (explicitKbps != null) return explicitKbps * 1000L

            val upperCh = channelsRaw.orEmpty().uppercase(Locale.US)
            val chCount = upperCh.substringBefore('/').toIntOrNull()
            return when {
                upperCh.contains("JOC") || combined.contains("atmos") -> 448_000L
                chCount == 8 || combined.contains("7.1") -> 512_000L
                chCount == 6 || combined.contains("5.1") ||
                    combined.contains("eac3") || combined.contains("ec-3") ||
                    combined.contains("ddp") || combined.contains("dd+") ||
                    combined.contains("ac-3") || combined.contains("ac3") -> 384_000L
                chCount == 1 || combined.contains("mono") -> 96_000L
                else -> 192_000L
            }
        }

        /**
         * Parses ALL selectable `(Video Quality × Audio Track)` combinations from an HLS Master
         * Playlist, sorted highest resolution/bitrate first and default audio first per resolution.
         * Supports both external demuxed `#EXT-X-MEDIA:TYPE=AUDIO` renditions (`URI="..."`) and
         * muxed default audio renditions (no `URI`), estimating combined file size per combination.
         */
        fun parseHlsQualityOptions(
            masterPlaylistUrl: String,
            masterContent: String,
            durationMs: Long = 0L
        ): List<DownloadQualityOption> {
            val lines = masterContent.lines().map { it.trim() }
            data class AudioRendition(
                val groupId: String,
                val name: String,
                val displayName: String,
                val language: String?,
                val channels: String?,
                val uri: String?,
                val isDefault: Boolean,
                val isAutoSelect: Boolean,
                val estimatedBitrateBps: Long
            )
            data class RawVariant(
                val bandwidth: Long,
                val averageBandwidth: Long,
                val height: Int,
                val width: Int,
                val frameRate: String?,
                val audioGroup: String?,
                val streamInfLine: String,
                val url: String
            )

            val audioRenditions = mutableListOf<AudioRendition>()
            val rawVariants = mutableListOf<RawVariant>()
            var pendingBandwidth = 0L
            var pendingAvgBandwidth = 0L
            var pendingWidth = 0
            var pendingHeight = 0
            var pendingFrameRate: String? = null
            var pendingAudioGroup: String? = null
            var pendingStreamInf = ""
            var awaitingUri = false

            val avgBwRegex = Regex("""AVERAGE-BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
            val bwRegex = Regex("""(?:^|,)BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
            val resRegex = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)
            val fpsRegex = Regex("""FRAME-RATE=([0-9.]+)""", RegexOption.IGNORE_CASE)
            val audioGroupRegex = Regex("""AUDIO="([^"]+)"""", RegexOption.IGNORE_CASE)

            for (line in lines) {
                if (line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) &&
                    line.contains("TYPE=AUDIO", ignoreCase = true)
                ) {
                    val attrs = parseHlsAttributes(line.substringAfter(":"))
                    val groupId = attrs["GROUP-ID"]?.takeIf { it.isNotBlank() } ?: "default_audio"
                    val rawUri = attrs["URI"]?.takeIf { it.isNotBlank() }
                    val resolvedUri = rawUri?.let { resolveRelativeUrl(masterPlaylistUrl, it) }
                    val lang = attrs["LANGUAGE"]?.takeIf { it.isNotBlank() }
                    val rawName = attrs["NAME"]?.trim()?.takeIf { it.isNotBlank() }
                        ?: lang?.uppercase(Locale.US)
                        ?: "Audio ${audioRenditions.size + 1}"
                    val channelsRaw = attrs["CHANNELS"]?.takeIf { it.isNotBlank() }
                    val channelBadge = formatChannelBadge(channelsRaw)
                    val displayName = buildString {
                        append(rawName)
                        if (channelBadge != null && !rawName.contains(channelBadge, ignoreCase = true) &&
                            !(channelBadge == "2.0" && rawName.contains("stereo", ignoreCase = true))
                        ) {
                            // Append channel info when surround or helpful (e.g. "Hindi 5.1")
                            if (channelBadge != "2.0") {
                                append(" $channelBadge")
                            }
                        }
                    }
                    audioRenditions.add(
                        AudioRendition(
                            groupId = groupId,
                            name = rawName,
                            displayName = displayName,
                            language = lang,
                            channels = channelBadge,
                            uri = resolvedUri,
                            isDefault = attrs["DEFAULT"].equals("YES", ignoreCase = true),
                            isAutoSelect = attrs["AUTOSELECT"].equals("YES", ignoreCase = true),
                            estimatedBitrateBps = estimateAudioBitrateBps(channelsRaw, rawName, groupId, rawUri)
                        )
                    )
                } else if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                    val attrPart = line.substringAfter(":")
                    pendingAvgBandwidth = avgBwRegex.find(attrPart)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                    pendingBandwidth = bwRegex.find(attrPart)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                    val resMatch = resRegex.find(attrPart)
                    pendingWidth = resMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    pendingHeight = resMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
                    pendingFrameRate = fpsRegex.find(attrPart)?.groupValues?.getOrNull(1)
                    pendingAudioGroup = audioGroupRegex.find(attrPart)?.groupValues?.getOrNull(1)
                    pendingStreamInf = line
                    awaitingUri = true
                } else if (awaitingUri && line.isNotEmpty() && !line.startsWith("#")) {
                    rawVariants.add(
                        RawVariant(
                            bandwidth = pendingBandwidth,
                            averageBandwidth = pendingAvgBandwidth,
                            height = pendingHeight,
                            width = pendingWidth,
                            frameRate = pendingFrameRate,
                            audioGroup = pendingAudioGroup,
                            streamInfLine = pendingStreamInf,
                            url = resolveRelativeUrl(masterPlaylistUrl, line)
                        )
                    )
                    awaitingUri = false
                }
            }

            if (rawVariants.isEmpty()) return emptyList()

            // Disambiguate duplicate audio display names (e.g. two "English" tracks: Stereo 2.0 vs 5.1)
            val nameCounts = audioRenditions.groupingBy { "${it.groupId}|${it.displayName.lowercase(Locale.US)}" }.eachCount()
            val normalizedAudios = audioRenditions.map { a ->
                val dup = (nameCounts["${a.groupId}|${a.displayName.lowercase(Locale.US)}"] ?: 0) > 1
                if (dup && a.channels != null && !a.displayName.contains(a.channels, ignoreCase = true)) {
                    a.copy(displayName = "${a.displayName} ${a.channels}")
                } else {
                    a
                }
            }

            // Deduplicate identical video URLs, keeping highest bandwidth entry, then sort descending
            val sorted = rawVariants
                .distinctBy { it.url }
                .sortedWith(compareByDescending<RawVariant> { it.height }.thenByDescending { it.bandwidth })

            val hdCandidates = sorted.filter { it.height in 1..1080 }
            val recommendedUrl = (if (hdCandidates.isNotEmpty()) {
                hdCandidates.maxByOrNull { it.bandwidth }
            } else {
                sorted.maxByOrNull { it.bandwidth }
            })?.url

            // Determine whether any variant exposes multiple audio tracks
            fun matchingAudiosFor(v: RawVariant): List<AudioRendition> {
                val rawMatches = when {
                    v.audioGroup != null -> {
                        val inGroup = normalizedAudios.filter { it.groupId == v.audioGroup }
                        inGroup.ifEmpty { normalizedAudios }
                    }
                    normalizedAudios.isNotEmpty() -> normalizedAudios
                    else -> emptyList()
                }
                val ordered = rawMatches.sortedWith(
                    compareByDescending<AudioRendition> { it.isDefault }
                        .thenByDescending { it.isAutoSelect }
                )
                return ordered.distinctBy { "${it.uri ?: "muxed"}|${it.displayName.lowercase(Locale.US)}" }
            }

            val allDistinctAudioKeys = sorted.flatMap { matchingAudiosFor(it) }
                .map { "${it.uri ?: "muxed"}|${it.displayName.lowercase(Locale.US)}" }
                .toSet()
            val hasMultiAudio = allDistinctAudioKeys.size >= 2

            val results = mutableListOf<DownloadQualityOption>()
            sorted.forEachIndexed { vIdx, v ->
                val matchedAudios = matchingAudiosFor(v)
                val baseResolutionLabel = when {
                    v.height >= 2160 -> "${v.height}p 4K UHD"
                    v.height >= 1440 -> "${v.height}p QHD"
                    v.height >= 1080 -> "${v.height}p Full HD"
                    v.height >= 720 -> "${v.height}p HD"
                    v.height > 0 -> "${v.height}p SD"
                    v.bandwidth > 0L -> formatBitrateLabel(v.bandwidth)
                    else -> "Variant ${vIdx + 1}"
                }
                val baseBps = v.averageBandwidth.takeIf { it > 0L } ?: v.bandwidth

                if (matchedAudios.isEmpty()) {
                    val estimatedBytes = if (durationMs > 0L && baseBps > 0L) {
                        (baseBps / 8L) * (durationMs / 1000L)
                    } else {
                        0L
                    }
                    val detailParts = buildList {
                        val br = formatBitrateLabel(baseBps)
                        if (br.isNotBlank()) add(br)
                        val fps = v.frameRate?.toDoubleOrNull()
                        if (fps != null && fps > 35.0) add("${fps.toInt()} fps")
                        val est = formatEstimatedSize(estimatedBytes)
                        if (est.isNotBlank()) add(est)
                    }
                    results.add(
                        DownloadQualityOption(
                            id = "hls_q_${vIdx}_${v.height}_${v.bandwidth}",
                            label = baseResolutionLabel,
                            detail = detailParts.joinToString(" · ").ifBlank { "HLS Stream" },
                            height = v.height,
                            bandwidthBps = baseBps,
                            variantPlaylistUrl = v.url,
                            streamInfLine = v.streamInfLine,
                            audioPlaylistUrl = null,
                            audioName = "Default",
                            audioLanguage = null,
                            audioChannels = null,
                            audioBitrateBps = 0L,
                            estimatedBytes = estimatedBytes,
                            isRecommended = v.url == recommendedUrl,
                            hasExplicitAudioChoice = false
                        )
                    )
                } else {
                    val defaultAudio = matchedAudios.first()
                    matchedAudios.forEachIndexed { aIdx, audio ->
                        val effectiveBps = if (baseBps > 0L) {
                            if (audio.uri != null && matchedAudios.size >= 2) {
                                // Adjust combined estimate for the selected audio track bitrate relative to default
                                val videoCore = (baseBps - defaultAudio.estimatedBitrateBps)
                                    .coerceAtLeast((baseBps * 75L) / 100L)
                                videoCore + audio.estimatedBitrateBps
                            } else {
                                baseBps
                            }
                        } else {
                            audio.estimatedBitrateBps
                        }
                        val estimatedBytes = if (durationMs > 0L && effectiveBps > 0L) {
                            (effectiveBps / 8L) * (durationMs / 1000L)
                        } else {
                            0L
                        }
                        val optionLabel = if (hasMultiAudio) {
                            "$baseResolutionLabel · ${audio.displayName}"
                        } else {
                            baseResolutionLabel
                        }
                        val detailParts = buildList {
                            val br = formatBitrateLabel(effectiveBps)
                            if (br.isNotBlank()) add(br)
                            val fps = v.frameRate?.toDoubleOrNull()
                            if (fps != null && fps > 35.0) add("${fps.toInt()} fps")
                            val est = formatEstimatedSize(estimatedBytes)
                            if (est.isNotBlank()) add(est)
                            val audioTag = buildString {
                                append("Audio: ${audio.displayName}")
                                if (audio.estimatedBitrateBps > 0L && hasMultiAudio) {
                                    append(" (${formatBitrateLabel(audio.estimatedBitrateBps)})")
                                }
                            }
                            add(audioTag)
                        }
                        results.add(
                            DownloadQualityOption(
                                id = "hls_q_${vIdx}_a_${aIdx}_${v.height}_${effectiveBps}",
                                label = optionLabel,
                                detail = detailParts.joinToString(" · ").ifBlank { "HLS Stream" },
                                height = v.height,
                                bandwidthBps = effectiveBps,
                                variantPlaylistUrl = v.url,
                                streamInfLine = v.streamInfLine,
                                audioPlaylistUrl = audio.uri,
                                audioName = audio.displayName,
                                audioLanguage = audio.language,
                                audioChannels = audio.channels,
                                audioBitrateBps = audio.estimatedBitrateBps,
                                estimatedBytes = estimatedBytes,
                                isRecommended = v.url == recommendedUrl && aIdx == 0,
                                hasExplicitAudioChoice = hasMultiAudio
                            )
                        )
                    }
                }
            }
            return results
        }

        fun pickBestHlsVariantUrl(masterPlaylistUrl: String, masterContent: String): String? {
            return selectHlsStreams(masterPlaylistUrl, masterContent)?.videoPlaylistUrl
        }

        fun selectHlsStreams(masterPlaylistUrl: String, masterContent: String): HlsMasterSelection? {
            val all = parseHlsQualityOptions(masterPlaylistUrl, masterContent)
            val chosen = all.firstOrNull { it.isRecommended } ?: all.firstOrNull() ?: return null
            return HlsMasterSelection(
                videoPlaylistUrl = chosen.variantPlaylistUrl,
                videoStreamInfLine = chosen.streamInfLine,
                audioPlaylistUrl = chosen.audioPlaylistUrl,
                audioName = chosen.audioName,
                audioLanguage = chosen.audioLanguage
            )
        }

        private fun parseHlsAttributes(raw: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            val regex = Regex("""([A-Z0-9-]+)=("([^"]*)"|([^,]*))""")
            for (match in regex.findAll(raw)) {
                val key = match.groupValues[1].uppercase(Locale.US)
                val value = match.groupValues[3].ifEmpty { match.groupValues[4] }
                result[key] = value
            }
            return result
        }

        private val SUPPORTED_DIRECT_AUDIO_EXTS = setOf(
            "mp3", "ac3", "eac3", "ec3", "flac", "wav", "m4a",
            "aac", "opus", "ogg", "oga", "dts", "mka", "weba", "ts", "mp4", "mkv"
        )

        /**
         * Resolves the exact local file extension for a downloaded external audio file so Media3's
         * `DefaultExtractorsFactory` picks the right extractor (MP3, AC-3, FLAC, WAV, M4A, AAC,
         * Opus, etc.) immediately on offline playback.
         */
        fun resolveExternalAudioFileExtension(
            url: String,
            contentType: String? = null,
            formatBadge: String? = null
        ): String {
            val cleanPath = url.substringBefore('?').substringBefore('#').lowercase(Locale.US)
            val pathExt = cleanPath.substringAfterLast('/', "").substringAfterLast('.', "")
            if (pathExt in SUPPORTED_DIRECT_AUDIO_EXTS) {
                return if (pathExt == "ec3") "eac3" else pathExt
            }
            val ct = contentType.orEmpty().lowercase(Locale.US)
            when {
                ct.contains("audio/mpeg") || ct.contains("audio/mp3") -> return "mp3"
                ct.contains("audio/eac3") || ct.contains("audio/ec-3") -> return "eac3"
                ct.contains("audio/ac3") || ct.contains("audio/vnd.dolby") -> return "ac3"
                ct.contains("audio/flac") || ct.contains("audio/x-flac") -> return "flac"
                ct.contains("audio/wav") || ct.contains("audio/x-wav") ||
                    ct.contains("audio/wave") || ct.contains("audio/vnd.wave") -> return "wav"
                ct.contains("audio/x-m4a") || ct.contains("audio/mp4") || ct.contains("audio/mp4a") -> return "m4a"
                ct.contains("audio/aac") -> return "aac"
                ct.contains("audio/opus") -> return "opus"
                ct.contains("audio/ogg") -> return "ogg"
                ct.contains("audio/vnd.dts") -> return "dts"
                ct.contains("audio/x-matroska") -> return "mka"
                ct.contains("audio/webm") -> return "weba"
            }
            val badgeExt = formatBadge.orEmpty().trim().lowercase(Locale.US)
            if (badgeExt in SUPPORTED_DIRECT_AUDIO_EXTS) {
                return if (badgeExt == "ec3") "eac3" else badgeExt
            }
            return "m4a"
        }

        fun formatBadgeForExtension(ext: String, fallback: String = "AUDIO"): String = when (ext.lowercase(Locale.US)) {
            "mp3" -> "MP3"
            "ac3" -> "AC3"
            "eac3", "ec3" -> "EAC3"
            "flac" -> "FLAC"
            "wav" -> "WAV"
            "m4a" -> "M4A"
            "aac" -> "AAC"
            "opus" -> "OPUS"
            "ogg", "oga" -> "OGG"
            "dts" -> "DTS"
            "mka" -> "MKA"
            "weba" -> "WEBA"
            "m3u8" -> "HLS"
            else -> fallback.ifBlank { "AUDIO" }
        }

        fun estimateExternalAudioBitrateBps(formatBadge: String?, url: String = ""): Long {
            val ext = resolveExternalAudioFileExtension(url, null, formatBadge)
            return when (ext) {
                "wav" -> 1_411_000L
                "flac" -> 900_000L
                "dts" -> 768_000L
                "eac3" -> 448_000L
                "ac3" -> 384_000L
                "mp3", "m4a", "aac" -> 192_000L
                "opus", "ogg", "oga" -> 128_000L
                else -> 192_000L
            }
        }

        /**
         * Given an external audio URL that points to an HLS Master Playlist (`#EXT-X-STREAM-INF`),
         * resolves the best media playlist URL to download:
         * 1. Prefer an explicit `#EXT-X-MEDIA:TYPE=AUDIO` rendition with a `URI` (default/autoselect first).
         * 2. Otherwise (muxed external master `.m3u8`), pick the lowest-bandwidth variant so offline
         *    audio storage stays compact while `AudioOnlyFilteringMediaSource` strips video on playback.
         */
        fun selectExternalHlsAudioPlaylistUrl(masterUrl: String, masterContent: String): String? {
            val lines = masterContent.lines().map { it.trim() }
            data class MasterAudio(val uri: String, val isDefault: Boolean, val isAutoSelect: Boolean)
            val audioUris = mutableListOf<MasterAudio>()
            var pendingBandwidth = Long.MAX_VALUE
            var awaitingUri = false
            val variants = mutableListOf<Pair<Long, String>>()
            val bwRegex = Regex("""(?:^|,)BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)

            for (line in lines) {
                if (line.startsWith("#EXT-X-MEDIA:", ignoreCase = true) &&
                    (line.contains("TYPE=AUDIO", ignoreCase = true) || line.contains("TYPE=\"AUDIO\"", ignoreCase = true))
                ) {
                    val attrs = parseHlsAttributes(line.substringAfter(":"))
                    val rawUri = attrs["URI"]?.takeIf { it.isNotBlank() } ?: continue
                    audioUris.add(
                        MasterAudio(
                            uri = resolveRelativeUrl(masterUrl, rawUri),
                            isDefault = attrs["DEFAULT"].equals("YES", ignoreCase = true),
                            isAutoSelect = attrs["AUTOSELECT"].equals("YES", ignoreCase = true)
                        )
                    )
                } else if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                    pendingBandwidth = bwRegex.find(line.substringAfter(":"))
                        ?.groupValues?.getOrNull(1)?.toLongOrNull() ?: Long.MAX_VALUE
                    awaitingUri = true
                } else if (awaitingUri && line.isNotEmpty() && !line.startsWith("#")) {
                    variants.add(pendingBandwidth to resolveRelativeUrl(masterUrl, line))
                    awaitingUri = false
                }
            }
            if (audioUris.isNotEmpty()) {
                return audioUris.sortedWith(
                    compareByDescending<MasterAudio> { it.isDefault }
                        .thenByDescending { it.isAutoSelect }
                ).first().uri
            }
            return variants.minByOrNull { it.first }?.second
        }

        /**
         * Merges loaded external audio tracks (from the active player session or JSON playlist)
         * into the download quality options so they appear as selectable audio choices for
         * multi-variant HLS, single-variant HLS, and progressive MP4/MKV/WebM streams alike.
         */
        fun mergeExternalAudioOptions(
            baseHlsOptions: List<DownloadQualityOption>,
            externalAudios: List<DownloadExternalAudioCandidate>,
            sourceUrl: String,
            format: StreamFormat,
            durationMs: Long = 0L,
            progressiveVideoBytes: Long = 0L,
            externalAudioSizesByUrl: Map<String, Long> = emptyMap()
        ): List<DownloadQualityOption> {
            val validExternal = externalAudios
                .filter {
                    val u = it.url.trim().lowercase(Locale.US)
                    u.startsWith("http://") || u.startsWith("https://")
                }
                .distinctBy { it.url.trim().lowercase(Locale.US) }
            if (validExternal.isEmpty()) return baseHlsOptions

            fun formatChipLabel(candidate: DownloadExternalAudioCandidate): String {
                val cleanLabel = candidate.label.trim().ifBlank { "External Audio" }
                val badge = candidate.formatBadge.trim().uppercase(Locale.US)
                return if (badge.isNotBlank() && badge != "AUDIO" && !cleanLabel.contains(badge, ignoreCase = true)) {
                    "$cleanLabel ($badge)"
                } else {
                    cleanLabel
                }
            }

            fun buildSyncSuffix(delayMs: Long): String? = if (delayMs != 0L) {
                "Sync ${AudioDelayMath.formatDelayLabel(delayMs)}"
            } else {
                null
            }

            val hasActiveExternal = validExternal.any { it.isCurrentlyActive }

            // Case 1: Progressive video (MP4/MKV/WebM) or single-variant HLS without master variants
            if (baseHlsOptions.isEmpty()) {
                val pathLower = sourceUrl.substringBefore('?').substringBefore('#').lowercase(Locale.US)
                val isSingleHls = format == StreamFormat.HLS ||
                    format == StreamFormat.M3U8 ||
                    pathLower.contains(".m3u8")
                val videoLabel = when {
                    isSingleHls -> "Original Stream"
                    format == StreamFormat.MKV || pathLower.endsWith(".mkv") -> "Original MKV"
                    format == StreamFormat.WEBM || pathLower.endsWith(".webm") -> "Original WebM"
                    else -> "Original Video"
                }
                val results = mutableListOf<DownloadQualityOption>()

                // Put loaded external audio tracks first when active (so user's loaded track is highlighted)
                validExternal.forEachIndexed { idx, ext ->
                    val chip = formatChipLabel(ext)
                    val audioBps = estimateExternalAudioBitrateBps(ext.formatBadge, ext.url)
                    val probedAudioBytes = externalAudioSizesByUrl[ext.url.trim()] ?: 0L
                    val estAudioBytes = when {
                        probedAudioBytes > 0L -> probedAudioBytes
                        durationMs > 0L -> (audioBps / 8L) * (durationMs / 1000L)
                        else -> 0L
                    }
                    val totalEst = when {
                        progressiveVideoBytes > 0L && estAudioBytes > 0L -> progressiveVideoBytes + estAudioBytes
                        progressiveVideoBytes > 0L -> progressiveVideoBytes
                        else -> estAudioBytes
                    }
                    val detailParts = buildList {
                        add("Loaded Audio: ${ext.label} (${ext.formatBadge.uppercase(Locale.US)})")
                        buildSyncSuffix(ext.delayMs)?.let { add(it) }
                        formatEstimatedSize(totalEst).takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                    results.add(
                        DownloadQualityOption(
                            id = "ext_direct_${idx}_${ext.id}",
                            label = "$videoLabel · $chip",
                            detail = detailParts.joinToString(" · "),
                            height = 0,
                            bandwidthBps = audioBps,
                            variantPlaylistUrl = "",
                            streamInfLine = "",
                            audioPlaylistUrl = null,
                            audioName = chip,
                            audioLanguage = ext.language,
                            audioBitrateBps = audioBps,
                            estimatedBytes = totalEst,
                            isRecommended = ext.isCurrentlyActive || (!hasActiveExternal && idx == 0),
                            hasExplicitAudioChoice = true,
                            isExternalAudio = true,
                            externalAudioUrl = ext.url.trim(),
                            externalAudioId = ext.id,
                            externalAudioFormatBadge = ext.formatBadge.uppercase(Locale.US),
                            externalAudioIsHls = ext.isHls,
                            externalAudioDelayMs = ext.delayMs,
                            isProgressiveVideo = !isSingleHls
                        )
                    )
                }

                // Always keep Original Audio option available
                val origDetailParts = buildList {
                    add(if (isSingleHls) "HLS Stream · Original Audio" else "Direct Video · Original Audio")
                    formatEstimatedSize(progressiveVideoBytes).takeIf { it.isNotBlank() }?.let { add(it) }
                }
                results.add(
                    DownloadQualityOption(
                        id = "orig_video_only",
                        label = "$videoLabel · Original Audio",
                        detail = origDetailParts.joinToString(" · "),
                        height = 0,
                        bandwidthBps = 0L,
                        variantPlaylistUrl = "",
                        streamInfLine = "",
                        audioPlaylistUrl = null,
                        audioName = "Original Audio",
                        estimatedBytes = progressiveVideoBytes,
                        isRecommended = false,
                        hasExplicitAudioChoice = true,
                        isExternalAudio = false,
                        isProgressiveVideo = !isSingleHls
                    )
                )
                return results
            }

            // Case 2: Multi-variant HLS Master Playlist -> combine each resolution tier with loaded external audios
            val recommendedVariantUrl = baseHlsOptions.firstOrNull { it.isRecommended }?.variantPlaylistUrl
                ?: baseHlsOptions.first().variantPlaylistUrl

            // Upgrade base options so every existing track has an explicit audio filter label
            val upgradedBase = baseHlsOptions.map { opt ->
                val audioLabel = if (opt.hasExplicitAudioChoice && opt.audioName.isNotBlank()) {
                    opt.audioName
                } else if (opt.audioName.isNotBlank() && !opt.audioName.equals("Default", ignoreCase = true)) {
                    opt.audioName
                } else {
                    "Original Audio"
                }
                val baseRes = opt.label.substringBefore(" · ")
                opt.copy(
                    label = "$baseRes · $audioLabel",
                    audioName = audioLabel,
                    hasExplicitAudioChoice = true,
                    isRecommended = if (hasActiveExternal) false else opt.isRecommended
                )
            }

            // Unique video resolution tiers in original sorted order
            val distinctVideoTiers = baseHlsOptions.distinctBy { it.variantPlaylistUrl }
            val combined = mutableListOf<DownloadQualityOption>()

            distinctVideoTiers.forEachIndexed { vIdx, tier ->
                val baseRes = tier.label.substringBefore(" · ")
                val tierBaseBps = tier.bandwidthBps

                // Add each loaded external audio for this video tier
                validExternal.forEachIndexed { eIdx, ext ->
                    val chip = formatChipLabel(ext)
                    val audioBps = estimateExternalAudioBitrateBps(ext.formatBadge, ext.url)
                    val videoCoreBps = if (tierBaseBps > 0L) {
                        (tierBaseBps - tier.audioBitrateBps).coerceAtLeast((tierBaseBps * 75L) / 100L)
                    } else {
                        0L
                    }
                    val effectiveBps = videoCoreBps + audioBps
                    val probedAudioBytes = externalAudioSizesByUrl[ext.url.trim()] ?: 0L
                    val estVideoBytes = if (durationMs > 0L && videoCoreBps > 0L) {
                        (videoCoreBps / 8L) * (durationMs / 1000L)
                    } else {
                        tier.estimatedBytes
                    }
                    val estAudioBytes = when {
                        probedAudioBytes > 0L -> probedAudioBytes
                        durationMs > 0L -> (audioBps / 8L) * (durationMs / 1000L)
                        else -> 0L
                    }
                    val totalEst = estVideoBytes + estAudioBytes
                    val detailParts = buildList {
                        formatBitrateLabel(effectiveBps).takeIf { it.isNotBlank() }?.let { add(it) }
                        formatEstimatedSize(totalEst).takeIf { it.isNotBlank() }?.let { add(it) }
                        add("Loaded Audio: ${ext.label} (${ext.formatBadge.uppercase(Locale.US)})")
                        buildSyncSuffix(ext.delayMs)?.let { add(it) }
                    }
                    combined.add(
                        DownloadQualityOption(
                            id = "hls_q_${vIdx}_ext_${eIdx}_${ext.id}_${tier.height}",
                            label = "$baseRes · $chip",
                            detail = detailParts.joinToString(" · "),
                            height = tier.height,
                            bandwidthBps = effectiveBps,
                            variantPlaylistUrl = tier.variantPlaylistUrl,
                            streamInfLine = tier.streamInfLine,
                            audioPlaylistUrl = null,
                            audioName = chip,
                            audioLanguage = ext.language,
                            audioBitrateBps = audioBps,
                            estimatedBytes = totalEst,
                            isRecommended = hasActiveExternal && ext.isCurrentlyActive &&
                                tier.variantPlaylistUrl == recommendedVariantUrl,
                            hasExplicitAudioChoice = true,
                            isExternalAudio = true,
                            externalAudioUrl = ext.url.trim(),
                            externalAudioId = ext.id,
                            externalAudioFormatBadge = ext.formatBadge.uppercase(Locale.US),
                            externalAudioIsHls = ext.isHls,
                            externalAudioDelayMs = ext.delayMs,
                            isProgressiveVideo = false
                        )
                    )
                }

                // Append the original/manifest audio options for this same video tier
                combined.addAll(upgradedBase.filter { it.variantPlaylistUrl == tier.variantPlaylistUrl })
            }

            return combined
        }

        fun writeOfflineSidecarAudio(folder: File, sidecar: OfflineSidecarAudio) {
            runCatching {
                val json = JSONObject().apply {
                    put("id", sidecar.id)
                    put("relativePath", sidecar.relativePath)
                    put("label", sidecar.label)
                    if (!sidecar.language.isNullOrBlank()) put("language", sidecar.language)
                    put("formatBadge", sidecar.formatBadge)
                    put("isHls", sidecar.isHls)
                    put("delayMs", sidecar.delayMs)
                }
                val tmp = File(folder, "$SIDECAR_AUDIO_FILE_NAME.tmp")
                val target = File(folder, SIDECAR_AUDIO_FILE_NAME)
                tmp.writeText(json.toString(), Charsets.UTF_8)
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
        }

        /**
         * Reads the optional saved `offline_audio.json` sidecar from the download folder containing
         * [localMediaFilePath]. Validates canonical path boundaries so tampered paths cannot escape.
         */
        fun readOfflineSidecarAudio(localMediaFilePath: String): OfflineSidecarAudio? {
            if (localMediaFilePath.isBlank()) return null
            val mediaFile = File(localMediaFilePath)
            val folder = mediaFile.parentFile ?: return null
            val sidecarFile = File(folder, SIDECAR_AUDIO_FILE_NAME)
            if (!sidecarFile.exists()) return null
            return runCatching {
                val obj = JSONObject(sidecarFile.readText(Charsets.UTF_8))
                val relPath = obj.optString("relativePath", "").trim()
                if (relPath.isBlank()) return@runCatching null
                val resolvedAudio = File(folder, relPath)
                val folderCanonical = folder.canonicalPath
                val audioCanonical = resolvedAudio.canonicalPath
                if (!audioCanonical.startsWith(folderCanonical + File.separator)) {
                    return@runCatching null
                }
                if (!resolvedAudio.exists() || resolvedAudio.length() <= 0L) {
                    return@runCatching null
                }
                OfflineSidecarAudio(
                    id = obj.optString("id", "offline_ext_audio").ifBlank { "offline_ext_audio" },
                    relativePath = relPath,
                    label = obj.optString("label", "Offline Audio").ifBlank { "Offline Audio" },
                    language = obj.optString("language", "").takeIf { it.isNotBlank() },
                    formatBadge = obj.optString("formatBadge", "AUDIO").ifBlank { "AUDIO" },
                    isHls = obj.optBoolean("isHls", relPath.endsWith(".m3u8", ignoreCase = true)),
                    delayMs = AudioDelayMath.clampDelayMs(obj.optLong("delayMs", 0L))
                )
            }.getOrNull()
        }
    }

    private class NetworkPausedException(message: String) : IOException(message)

    private data class ChunkCheckpoint(
        val start: Long,
        var current: Long,
        val end: Long
    )

    private data class DownloadCheckpoint(
        val selectedVariantUrl: String? = null,
        val selectedStreamInf: String? = null,
        val selectedAudioUrl: String? = null,
        val selectedAudioName: String = "Default",
        val selectedAudioLanguage: String? = null,
        val selectedQualityLabel: String? = null,
        val contentLength: Long = 0L,
        val chunks: List<ChunkCheckpoint> = emptyList(),
        val isProgressiveVideo: Boolean = false,
        val videoCompleted: Boolean = false,
        val completedVideoPath: String? = null,
        val externalAudioUrl: String? = null,
        val externalAudioId: String? = null,
        val externalAudioLabel: String? = null,
        val externalAudioLanguage: String? = null,
        val externalAudioFormatBadge: String? = null,
        val externalAudioIsHls: Boolean = false,
        val externalAudioDelayMs: Long = 0L
    )

    private val appContext = context.applicationContext
    private val downloadClient: OkHttpClient = okHttpClient.newBuilder()
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 16
            }
        )
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val rootDir: File = File(context.filesDir, "offline_downloads").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    // Session-scoped headers kept in RAM only (never written as plaintext secrets to disk)
    private val sessionHeadersById = ConcurrentHashMap<Long, Map<String, String>>()
    private val autoResumeIds = ConcurrentHashMap.newKeySet<Long>()
    private val manualPausedIds = ConcurrentHashMap.newKeySet<Long>()

    private val isNetworkAvailable = AtomicBoolean(true)
    private val lastNetworkHandle = AtomicLong(-1L)
    private var networkCallbackRegistered = false

    private val _downloadSpeeds = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val downloadSpeeds: StateFlow<Map<Long, Long>> = _downloadSpeeds.asStateFlow()

    init {
        registerConnectivityMonitor()
        recoverInterruptedDownloadsOnStart()
    }

    private fun registerConnectivityMonitor() {
        runCatching {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val active = cm.activeNetwork
            if (active != null) {
                lastNetworkHandle.set(active.networkHandle)
            }
            // Stay optimistic on startup (VPNs, hotspots, and custom ROMs can omit NET_CAPABILITY_INTERNET
            // transiently). Real offline sockets fail immediately into STATUS_PAUSED + autoResumeIds.
            isNetworkAvailable.set(true)

            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val prevHandle = lastNetworkHandle.getAndSet(network.networkHandle)
                    isNetworkAvailable.set(true)
                    if (prevHandle != -1L && prevHandle != network.networkHandle) {
                        // Wi-Fi <-> Cellular switch: flush stale pooled & stalled in-flight sockets immediately
                        runCatching { downloadClient.connectionPool.evictAll() }
                        runCatching { downloadClient.dispatcher.cancelAll() }
                    }
                    triggerAutoResumeForNetworkRecovery()
                }

                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    val hasTransportOrNet =
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                            networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    val prevHandle = lastNetworkHandle.getAndSet(network.networkHandle)
                    if (hasTransportOrNet) {
                        isNetworkAvailable.set(true)
                        if (prevHandle != -1L && prevHandle != network.networkHandle) {
                            runCatching { downloadClient.connectionPool.evictAll() }
                            runCatching { downloadClient.dispatcher.cancelAll() }
                        }
                        triggerAutoResumeForNetworkRecovery()
                    }
                }

                override fun onLost(network: Network) {
                    val currentActive = cm.activeNetwork
                    val stillOnline = currentActive != null && currentActive.networkHandle != network.networkHandle
                    isNetworkAvailable.set(stillOnline)
                    runCatching { downloadClient.connectionPool.evictAll() }
                    runCatching { downloadClient.dispatcher.cancelAll() }
                    if (stillOnline) {
                        lastNetworkHandle.set(currentActive.networkHandle)
                        triggerAutoResumeForNetworkRecovery()
                    }
                }
            })
            networkCallbackRegistered = true
        }
    }

    private fun recoverInterruptedDownloadsOnStart() {
        scope.launch {
            runCatching {
                val all = dao.getAllOnce()
                all.filter { it.status == STATUS_DOWNLOADING || it.status == STATUS_QUEUED }.forEach { entity ->
                    dao.updateStatus(
                        id = entity.id,
                        status = STATUS_PAUSED,
                        progressPercent = entity.progressPercent,
                        bytesDownloaded = entity.bytesDownloaded,
                        totalBytes = entity.totalBytes,
                        localFilePath = "",
                        errorMessage = "Paused · Tap Resume to continue"
                    )
                }
            }
        }
    }

    /**
     * Manually notifies the manager that connectivity has changed or recovered (also used by tests).
     */
    fun onNetworkStateChanged(connected: Boolean, switchedInterface: Boolean = false) {
        isNetworkAvailable.set(connected)
        if (switchedInterface || !connected) {
            runCatching { downloadClient.connectionPool.evictAll() }
            runCatching { downloadClient.dispatcher.cancelAll() }
        }
        if (connected) {
            triggerAutoResumeForNetworkRecovery()
        }
    }

    private fun triggerAutoResumeForNetworkRecovery() {
        if (autoResumeIds.isEmpty()) return
        val pending = autoResumeIds.toList()
        scope.launch {
            // Brief settle delay so Android routes DNS cleanly on the new Wi-Fi/Cellular interface
            delay(400L)
            if (!isNetworkAvailable.get()) return@launch
            for (downloadId in pending) {
                if (downloadId in manualPausedIds) {
                    autoResumeIds.remove(downloadId)
                    continue
                }
                if (activeJobs.containsKey(downloadId)) continue
                val entity = dao.getById(downloadId) ?: continue
                if (entity.status == STATUS_PAUSED || entity.status == STATUS_FAILED) {
                    autoResumeIds.remove(downloadId)
                    val format = StreamFormat.entries.firstOrNull { it.name == entity.formatName } ?: StreamFormat.AUTO
                    val headers = sessionHeadersById[downloadId].orEmpty()
                    startDownloadJob(
                        id = downloadId,
                        entity = entity,
                        format = format,
                        headers = headers,
                        selectedQuality = null,
                        preserveExistingProgress = true
                    )
                }
            }
        }
    }

    fun observeDownloads(): Flow<List<OfflineDownloadEntity>> = dao.observeDownloads()

    /**
     * Probes an HLS playlist to discover all available video qualities (resolutions, bitrates,
     * audio tracks, and estimated offline sizes). Returns an empty list for non-HLS or single-variant
     * streams so the UI can begin downloading immediately without showing a picker.
     */
    suspend fun inspectHlsQualities(
        sourceUrl: String,
        headers: Map<String, String> = emptyMap(),
        durationMs: Long = 0L
    ): List<DownloadQualityOption> = withContext(Dispatchers.IO) {
        val trimmed = sourceUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext emptyList()
        }
        runCatching {
            val result = fetchPlaylistTextBounded(trimmed, headers) ?: return@runCatching emptyList()
            val (resolvedUrl, resolvedHeaders, text) = result
            if (!text.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
                return@runCatching emptyList()
            }
            val initialOptions = parseHlsQualityOptions(resolvedUrl, text, durationMs)
            if (durationMs > 0L || initialOptions.isEmpty()) {
                return@runCatching initialOptions
            }
            // Probe first variant playlist to sum #EXTINF durations so estimated sizes are always accurate
            val firstVariantUrl = initialOptions.first().variantPlaylistUrl
            val probedDurationMs = runCatching {
                fetchPlaylistTextBounded(firstVariantUrl, resolvedHeaders)?.third
                    ?.let { parseHlsPlaylistDurationMs(it) }
                    ?: 0L
            }.getOrDefault(0L)
            if (probedDurationMs > 0L) {
                parseHlsQualityOptions(resolvedUrl, text, probedDurationMs)
            } else {
                initialOptions
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Probes HLS variants (when applicable) AND merges any loaded external audio tracks
     * (`.m3u8`, `.mp3`, `.ac3`, `.flac`, `.wav`, `.m4a`, etc.) into selectable download options.
     */
    suspend fun inspectDownloadOptions(
        sourceUrl: String,
        format: StreamFormat = StreamFormat.AUTO,
        headers: Map<String, String> = emptyMap(),
        durationMs: Long = 0L,
        externalAudios: List<DownloadExternalAudioCandidate> = emptyList()
    ): List<DownloadQualityOption> = withContext(Dispatchers.IO) {
        val trimmed = sourceUrl.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return@withContext emptyList()
        }
        val pathLower = trimmed.substringBefore('?').substringBefore('#').lowercase(Locale.US)
        val isKnownProgressiveExt = pathLower.endsWith(".mp4") ||
            pathLower.endsWith(".mkv") ||
            pathLower.endsWith(".webm") ||
            pathLower.endsWith(".avi") ||
            pathLower.endsWith(".mov") ||
            pathLower.endsWith(".m4v") ||
            pathLower.endsWith(".ts")

        val baseHlsOptions = if (!isKnownProgressiveExt || format == StreamFormat.HLS || format == StreamFormat.M3U8) {
            inspectHlsQualities(trimmed, headers, durationMs)
        } else {
            emptyList()
        }

        if (externalAudios.isEmpty()) {
            return@withContext baseHlsOptions
        }

        // For progressive videos paired with external audio, quickly probe Content-Length for size display
        val progressiveBytes = if (baseHlsOptions.isEmpty() && !pathLower.contains(".m3u8") &&
            format != StreamFormat.HLS && format != StreamFormat.M3U8
        ) {
            runCatching { probeProgressiveUrl(trimmed, headers).contentLength }.getOrDefault(0L)
        } else {
            0L
        }

        // Quickly probe Content-Length for direct external audio files (mp3/ac3/flac/wav/m4a)
        val extSizes = mutableMapOf<String, Long>()
        for (ext in externalAudios) {
            val extUrl = ext.url.trim()
            if (!ext.isHls && !extUrl.contains(".m3u8", ignoreCase = true)) {
                val len = runCatching { probeProgressiveUrl(extUrl, headers).contentLength }.getOrDefault(0L)
                if (len > 0L) extSizes[extUrl] = len
            }
        }

        mergeExternalAudioOptions(
            baseHlsOptions = baseHlsOptions,
            externalAudios = externalAudios,
            sourceUrl = trimmed,
            format = format,
            durationMs = durationMs,
            progressiveVideoBytes = progressiveBytes,
            externalAudioSizesByUrl = extSizes
        )
    }

    fun enqueueDownload(
        title: String,
        sourceUrl: String,
        format: StreamFormat,
        posterUrl: String? = null,
        headers: Map<String, String> = emptyMap(),
        selectedQuality: DownloadQualityOption? = null
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
            if (existing != null && existing.status == STATUS_COMPLETED &&
                existing.localFilePath.isNotBlank() && File(existing.localFilePath).exists()
            ) {
                return@launch
            }

            val baseTitle = title.ifBlank { MediaFormatDetector.inferTitle(trimmedUrl) }
            val displayTitle = if (selectedQuality != null) {
                val tagParts = buildList {
                    if (selectedQuality.height > 0 && !baseTitle.contains("${selectedQuality.height}p", ignoreCase = true)) {
                        add("${selectedQuality.height}p")
                    }
                    if ((selectedQuality.hasExplicitAudioChoice || selectedQuality.isExternalAudio) &&
                        selectedQuality.audioName.isNotBlank() &&
                        !selectedQuality.audioName.equals("Original Audio", ignoreCase = true) &&
                        !selectedQuality.audioName.equals("Default", ignoreCase = true)
                    ) {
                        add(selectedQuality.audioName)
                    }
                }
                if (tagParts.isNotEmpty()) "$baseTitle (${tagParts.joinToString(" · ")})" else baseTitle
            } else {
                baseTitle
            }

            // Preserve previous progress if resuming an existing paused/failed download with the same quality
            val isResumingSame = existing != null &&
                (existing.status == STATUS_PAUSED || existing.status == STATUS_FAILED)

            val now = System.currentTimeMillis()
            val entity = OfflineDownloadEntity(
                id = existing?.id ?: 0L,
                sourceUrl = trimmedUrl,
                title = if (isResumingSame && selectedQuality == null) existing.title else displayTitle,
                formatName = format.name,
                posterUrl = posterUrl ?: existing?.posterUrl,
                localFilePath = "",
                status = STATUS_QUEUED,
                progressPercent = if (isResumingSame && selectedQuality == null) existing.progressPercent else 0,
                bytesDownloaded = if (isResumingSame && selectedQuality == null) existing.bytesDownloaded else 0L,
                totalBytes = if (isResumingSame && selectedQuality == null) existing.totalBytes else 0L,
                errorMessage = null,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
            val id = dao.upsert(entity)
            val resolvedId = if (id > 0L) id else (dao.getBySourceUrl(trimmedUrl)?.id ?: return@launch)
            if (headers.isNotEmpty()) {
                sessionHeadersById[resolvedId] = headers
            }
            manualPausedIds.remove(resolvedId)
            autoResumeIds.remove(resolvedId)
            startDownloadJob(
                id = resolvedId,
                entity = entity.copy(id = resolvedId),
                format = format,
                headers = headers.ifEmpty { sessionHeadersById[resolvedId].orEmpty() },
                selectedQuality = selectedQuality,
                preserveExistingProgress = isResumingSame
            )
        }
    }

    fun pauseDownload(downloadId: Long) {
        manualPausedIds.add(downloadId)
        autoResumeIds.remove(downloadId)
        activeJobs.remove(downloadId)?.cancel()
        clearSpeed(downloadId)
        scope.launch {
            val entity = dao.getById(downloadId) ?: return@launch
            if (entity.status == STATUS_DOWNLOADING || entity.status == STATUS_QUEUED) {
                dao.updateStatus(
                    id = downloadId,
                    status = STATUS_PAUSED,
                    progressPercent = entity.progressPercent,
                    bytesDownloaded = entity.bytesDownloaded,
                    totalBytes = entity.totalBytes,
                    localFilePath = "",
                    errorMessage = "Paused"
                )
            }
        }
    }

    fun resumeDownload(downloadId: Long, headersOverride: Map<String, String> = emptyMap()) {
        manualPausedIds.remove(downloadId)
        autoResumeIds.remove(downloadId)
        scope.launch {
            val entity = dao.getById(downloadId) ?: return@launch
            if (entity.status == STATUS_COMPLETED &&
                entity.localFilePath.isNotBlank() && File(entity.localFilePath).exists()
            ) {
                return@launch
            }
            if (activeJobs.containsKey(downloadId)) return@launch
            if (headersOverride.isNotEmpty()) {
                sessionHeadersById[downloadId] = headersOverride
            }
            val effectiveHeaders = sessionHeadersById[downloadId].orEmpty()
            val format = StreamFormat.entries.firstOrNull { it.name == entity.formatName } ?: StreamFormat.AUTO
            startDownloadJob(
                id = downloadId,
                entity = entity,
                format = format,
                headers = effectiveHeaders,
                selectedQuality = null,
                preserveExistingProgress = true
            )
        }
    }

    fun cancelDownload(downloadId: Long) {
        manualPausedIds.remove(downloadId)
        autoResumeIds.remove(downloadId)
        activeJobs.remove(downloadId)?.cancel()
        sessionHeadersById.remove(downloadId)
        clearSpeed(downloadId)
        scope.launch {
            val entity = dao.getById(downloadId) ?: return@launch
            if (entity.status == STATUS_DOWNLOADING || entity.status == STATUS_QUEUED || entity.status == STATUS_PAUSED) {
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
        manualPausedIds.remove(downloadId)
        autoResumeIds.remove(downloadId)
        activeJobs.remove(downloadId)?.cancel()
        sessionHeadersById.remove(downloadId)
        clearSpeed(downloadId)
        scope.launch {
            val entity = dao.getById(downloadId)
            if (entity != null) {
                cleanupDownloadFiles(entity)
            }
            dao.deleteById(downloadId)
        }
    }

    private fun updateSpeed(downloadId: Long, bytesPerSec: Long) {
        _downloadSpeeds.update { current ->
            if (bytesPerSec <= 0L) current - downloadId else current + (downloadId to bytesPerSec)
        }
    }

    private fun clearSpeed(downloadId: Long) {
        _downloadSpeeds.update { current ->
            if (downloadId in current) current - downloadId else current
        }
    }

    private fun resolveDownloadFolder(entity: OfflineDownloadEntity): File {
        // Use stable ID prefix so title edits (e.g., appending "(720p)") never orphan existing resume files
        val existingByPrefix = rootDir.listFiles()?.firstOrNull {
            it.isDirectory && it.name.startsWith("${entity.id}_")
        }
        return existingByPrefix ?: File(rootDir, sanitizeFolderName(entity.title, entity.id))
    }

    private fun startDownloadJob(
        id: Long,
        entity: OfflineDownloadEntity,
        format: StreamFormat,
        headers: Map<String, String>,
        selectedQuality: DownloadQualityOption?,
        preserveExistingProgress: Boolean
    ) {
        activeJobs.remove(id)?.cancel()
        val job = scope.launch {
            val folder = resolveDownloadFolder(entity)
            val savedCheckpoint = if (preserveExistingProgress) readCheckpoint(folder) else null
            val qualityChanged = selectedQuality != null &&
                savedCheckpoint != null &&
                (savedCheckpoint.selectedVariantUrl != selectedQuality.variantPlaylistUrl.takeIf { it.isNotBlank() } ||
                    savedCheckpoint.selectedAudioUrl != selectedQuality.audioPlaylistUrl ||
                    savedCheckpoint.externalAudioUrl != selectedQuality.externalAudioUrl)

            if (!preserveExistingProgress || qualityChanged) {
                if (folder.exists()) folder.deleteRecursively()
                folder.mkdirs()
            } else if (!folder.exists()) {
                folder.mkdirs()
            }

            if (selectedQuality != null) {
                writeCheckpoint(
                    folder,
                    DownloadCheckpoint(
                        selectedVariantUrl = selectedQuality.variantPlaylistUrl.takeIf { it.isNotBlank() },
                        selectedStreamInf = selectedQuality.streamInfLine.takeIf { it.isNotBlank() },
                        selectedAudioUrl = selectedQuality.audioPlaylistUrl,
                        selectedAudioName = selectedQuality.audioName,
                        selectedAudioLanguage = selectedQuality.audioLanguage,
                        selectedQualityLabel = selectedQuality.label,
                        isProgressiveVideo = selectedQuality.isProgressiveVideo,
                        externalAudioUrl = selectedQuality.externalAudioUrl,
                        externalAudioId = selectedQuality.externalAudioId,
                        externalAudioLabel = selectedQuality.audioName.takeIf { selectedQuality.isExternalAudio },
                        externalAudioLanguage = selectedQuality.audioLanguage,
                        externalAudioFormatBadge = selectedQuality.externalAudioFormatBadge,
                        externalAudioIsHls = selectedQuality.externalAudioIsHls,
                        externalAudioDelayMs = selectedQuality.externalAudioDelayMs
                    )
                )
            }

            try {
                dao.updateStatus(
                    id = id,
                    status = STATUS_DOWNLOADING,
                    progressPercent = if (preserveExistingProgress && !qualityChanged) entity.progressPercent else 0,
                    bytesDownloaded = if (preserveExistingProgress && !qualityChanged) entity.bytesDownloaded else 0L,
                    totalBytes = if (preserveExistingProgress && !qualityChanged) entity.totalBytes else 0L,
                    localFilePath = "",
                    errorMessage = null
                )
                val effectiveCheckpoint = readCheckpoint(folder)
                val hasExternalAudio = !effectiveCheckpoint?.externalAudioUrl.isNullOrBlank()
                val videoProgressRange = if (hasExternalAudio) 0..85 else 0..99

                val hasSelectedHlsVariant = !selectedQuality?.variantPlaylistUrl.isNullOrBlank() ||
                    !effectiveCheckpoint?.selectedVariantUrl.isNullOrBlank()
                val isHls = when {
                    selectedQuality?.isProgressiveVideo == true || effectiveCheckpoint?.isProgressiveVideo == true -> false
                    hasSelectedHlsVariant -> true
                    format == StreamFormat.HLS || format == StreamFormat.M3U8 -> true
                    entity.sourceUrl.contains(".m3u8", ignoreCase = true) -> true
                    else -> false
                }

                val savedVideoPath = effectiveCheckpoint?.completedVideoPath
                val finalPath = if (effectiveCheckpoint?.videoCompleted == true &&
                    !savedVideoPath.isNullOrBlank() &&
                    File(savedVideoPath).exists() &&
                    File(savedVideoPath).length() > 0L
                ) {
                    savedVideoPath
                } else {
                    val downloadedVideoPath = if (isHls) {
                        downloadHlsStreamParallel(
                            id = id,
                            playlistUrl = entity.sourceUrl,
                            folder = folder,
                            headers = headers,
                            checkpoint = effectiveCheckpoint,
                            progressRange = videoProgressRange
                        )
                    } else {
                        downloadProgressiveFileFast(
                            id = id,
                            url = entity.sourceUrl,
                            format = format,
                            folder = folder,
                            headers = headers,
                            checkpoint = effectiveCheckpoint,
                            progressRange = videoProgressRange
                        )
                    }
                    if (hasExternalAudio && effectiveCheckpoint != null) {
                        writeCheckpoint(
                            folder,
                            effectiveCheckpoint.copy(
                                videoCompleted = true,
                                completedVideoPath = downloadedVideoPath
                            )
                        )
                    }
                    downloadedVideoPath
                }

                if (hasExternalAudio && effectiveCheckpoint != null) {
                    val sidecar = downloadExternalAudioSidecar(
                        id = id,
                        folder = folder,
                        headers = headers,
                        checkpoint = effectiveCheckpoint,
                        progressRange = 85..99
                    )
                    writeOfflineSidecarAudio(folder, sidecar)
                }

                // Clean up checkpoint metadata file after successful completion
                File(folder, STATE_FILE_NAME).delete()
                val totalSize = computeFolderSize(folder)
                autoResumeIds.remove(id)
                manualPausedIds.remove(id)
                clearSpeed(id)
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
                clearSpeed(id)
                // NEVER delete folder on cancellation here; cancelDownload/deleteDownload handle cleanup explicitly
                throw ce
            } catch (npe: NetworkPausedException) {
                clearSpeed(id)
                // Preserve 100% of downloaded segments/chunks and auto-resume when network returns
                if (id !in manualPausedIds) {
                    autoResumeIds.add(id)
                }
                val latest = dao.getById(id)
                val savedBytes = maxOf(latest?.bytesDownloaded ?: 0L, computeCompletedBytesOnDisk(folder))
                dao.updateStatus(
                    id = id,
                    status = STATUS_PAUSED,
                    progressPercent = latest?.progressPercent ?: entity.progressPercent,
                    bytesDownloaded = savedBytes,
                    totalBytes = latest?.totalBytes ?: entity.totalBytes,
                    localFilePath = "",
                    errorMessage = "Paused · Waiting for network"
                )
            } catch (t: Throwable) {
                clearSpeed(id)
                // Preserve partial progress on disk so Retry resumes instead of restarting from 0%!
                val isConnectivityError = isTransientNetworkError(t)
                val latest = dao.getById(id)
                val savedBytes = maxOf(latest?.bytesDownloaded ?: 0L, computeCompletedBytesOnDisk(folder))
                if (isConnectivityError) {
                    if (id !in manualPausedIds) {
                        autoResumeIds.add(id)
                    }
                    dao.updateStatus(
                        id = id,
                        status = STATUS_PAUSED,
                        progressPercent = latest?.progressPercent ?: entity.progressPercent,
                        bytesDownloaded = savedBytes,
                        totalBytes = latest?.totalBytes ?: entity.totalBytes,
                        localFilePath = "",
                        errorMessage = "Paused · Waiting for network"
                    )
                } else {
                    dao.updateStatus(
                        id = id,
                        status = STATUS_FAILED,
                        progressPercent = latest?.progressPercent ?: entity.progressPercent,
                        bytesDownloaded = savedBytes,
                        totalBytes = latest?.totalBytes ?: entity.totalBytes,
                        localFilePath = "",
                        errorMessage = t.message?.take(140) ?: "Download failed"
                    )
                }
            } finally {
                clearSpeed(id)
                activeJobs.remove(id)
                if (id in autoResumeIds && id !in manualPausedIds && isNetworkAvailable.get()) {
                    triggerAutoResumeForNetworkRecovery()
                }
            }
        }
        activeJobs[id] = job
    }

    // -------------------------------------------------------------------------
    // Resumable Parallel Multi-Chunk Progressive Downloader (4x Range workers)
    // -------------------------------------------------------------------------

    private data class ProgressiveProbe(
        val finalUrl: String,
        val effectiveHeaders: Map<String, String>,
        val contentLength: Long,
        val supportsRanges: Boolean,
        val contentType: String? = null
    )

    private suspend fun probeProgressiveUrl(
        url: String,
        headers: Map<String, String>
    ): ProgressiveProbe {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!isNetworkAvailable.get()) {
                throw NetworkPausedException("No network connection")
            }
            try {
                val (finalUrl, effectiveHeaders, response) = executeWithSafeRedirects(
                    initialUrl = url,
                    baseHeaders = headers,
                    extraHeaders = mapOf("Range" to "bytes=0-0")
                )
                return response.use { resp ->
                    val ct = resp.header("Content-Type")
                    if (resp.code == 206) {
                        val contentRange = resp.header("Content-Range").orEmpty()
                        val totalLen = contentRange.substringAfter('/', "").trim().toLongOrNull() ?: 0L
                        ProgressiveProbe(
                            finalUrl = finalUrl,
                            effectiveHeaders = effectiveHeaders,
                            contentLength = totalLen,
                            supportsRanges = totalLen > 0L,
                            contentType = ct
                        )
                    } else if (resp.isSuccessful) {
                        val len = resp.header("Content-Length")?.toLongOrNull() ?: 0L
                        val acceptsRanges = resp.header("Accept-Ranges").equals("bytes", ignoreCase = true)
                        ProgressiveProbe(
                            finalUrl = finalUrl,
                            effectiveHeaders = effectiveHeaders,
                            contentLength = len,
                            supportsRanges = acceptsRanges && len > 0L,
                            contentType = ct
                        )
                    } else {
                        ProgressiveProbe(url, headers, 0L, false, ct)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ioe: IOException) {
                attempt++
                runCatching { downloadClient.connectionPool.evictAll() }
                if (!isNetworkAvailable.get() || attempt >= MAX_NETWORK_RETRIES) {
                    throw NetworkPausedException(ioe.message ?: "Network interrupted")
                }
                delay(350L * attempt)
            }
        }
    }

    private suspend fun downloadProgressiveFileFast(
        id: Long,
        url: String,
        format: StreamFormat,
        folder: File,
        headers: Map<String, String>,
        checkpoint: DownloadCheckpoint?,
        progressRange: IntRange = 0..99
    ): String {
        val cleanPath = url.substringBefore('?').lowercase(Locale.US)
        val ext = when {
            format == StreamFormat.MKV || cleanPath.endsWith(".mkv") -> "mkv"
            cleanPath.endsWith(".webm") -> "webm"
            cleanPath.endsWith(".ts") || cleanPath.endsWith(".m2ts") -> "ts"
            cleanPath.endsWith(".mov") -> "mov"
            cleanPath.endsWith(".m4v") -> "m4v"
            cleanPath.endsWith(".avi") -> "avi"
            cleanPath.endsWith(".mp3") -> "mp3"
            cleanPath.endsWith(".ac3") -> "ac3"
            cleanPath.endsWith(".eac3") || cleanPath.endsWith(".ec3") -> "eac3"
            cleanPath.endsWith(".flac") -> "flac"
            cleanPath.endsWith(".wav") -> "wav"
            cleanPath.endsWith(".m4a") -> "m4a"
            else -> "mp4"
        }
        val outFile = File(folder, "media.$ext")
        val probe = probeProgressiveUrl(url, headers)

        if (probe.contentLength > MAX_SINGLE_FILE_BYTES) {
            throw IllegalStateException("File exceeds offline size limit")
        }

        if (probe.supportsRanges && probe.contentLength >= MIN_MULTICHUNK_BYTES) {
            downloadProgressiveMultiChunkResumable(
                id = id,
                finalUrl = probe.finalUrl,
                headers = probe.effectiveHeaders,
                contentLength = probe.contentLength,
                outFile = outFile,
                folder = folder,
                existingCheckpoint = checkpoint,
                progressRange = progressRange
            )
        } else {
            downloadProgressiveSingleStreamResumable(
                id = id,
                url = probe.finalUrl,
                headers = probe.effectiveHeaders,
                outFile = outFile,
                progressRange = progressRange
            )
        }
        return outFile.absolutePath
    }

    private suspend fun downloadProgressiveMultiChunkResumable(
        id: Long,
        finalUrl: String,
        headers: Map<String, String>,
        contentLength: Long,
        outFile: File,
        folder: File,
        existingCheckpoint: DownloadCheckpoint?,
        progressRange: IntRange = 0..99
    ) {
        val canResumeChunks = existingCheckpoint != null &&
            existingCheckpoint.contentLength == contentLength &&
            existingCheckpoint.chunks.isNotEmpty() &&
            outFile.exists() &&
            outFile.length() == contentLength

        val chunks: List<ChunkCheckpoint> = if (canResumeChunks) {
            existingCheckpoint.chunks.map { it.copy() }
        } else {
            RandomAccessFile(outFile, "rw").use { raf ->
                raf.setLength(contentLength)
            }
            val chunkCount = PROGRESSIVE_PARALLEL_CHUNKS
            val chunkSize = contentLength / chunkCount
            (0 until chunkCount).map { idx ->
                val start = idx * chunkSize
                val end = if (idx == chunkCount - 1) contentLength - 1L else ((idx + 1) * chunkSize) - 1L
                ChunkCheckpoint(start = start, current = start, end = end)
            }
        }

        val checkpointMutex = Mutex()
        var lastCheckpointFlushMs = 0L

        suspend fun flushChunkCheckpoint(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastCheckpointFlushMs < 500L) return
            checkpointMutex.withLock {
                val nowLocked = System.currentTimeMillis()
                if (!force && nowLocked - lastCheckpointFlushMs < 500L) return
                lastCheckpointFlushMs = nowLocked
                val snapshot = chunks.map { it.copy() }
                writeCheckpoint(
                    folder,
                    (existingCheckpoint ?: DownloadCheckpoint()).copy(
                        contentLength = contentLength,
                        chunks = snapshot
                    )
                )
            }
        }

        val initialBytes = chunks.sumOf { (it.current - it.start).coerceAtLeast(0L) }
        val totalDownloaded = AtomicLong(initialBytes)
        val reporter = ProgressAndSpeedReporter(
            downloadId = id,
            totalBytesExpected = contentLength,
            totalItemsExpected = 0,
            initialBytes = initialBytes,
            minPercent = progressRange.first,
            maxPercent = progressRange.last
        )
        flushChunkCheckpoint(force = true)
        if (initialBytes > 0L) {
            reporter.forceReport(bytesDownloaded = initialBytes, completedItems = 0)
        }

        try {
            coroutineScope {
                chunks.map { chunk ->
                    async(Dispatchers.IO) {
                        var attempt = 0
                        while (chunk.current <= chunk.end) {
                            currentCoroutineContext().ensureActive()
                            if (!isNetworkAvailable.get()) {
                                flushChunkCheckpoint(force = true)
                                throw NetworkPausedException("No network connection")
                            }
                            try {
                                val (_, _, response) = executeWithSafeRedirects(
                                    initialUrl = finalUrl,
                                    baseHeaders = headers,
                                    extraHeaders = mapOf("Range" to "bytes=${chunk.current}-${chunk.end}")
                                )
                                response.use { resp ->
                                    if (resp.code != 206 && !resp.isSuccessful) {
                                        throw IllegalStateException("HTTP ${resp.code}")
                                    }
                                    val body = resp.body ?: throw IllegalStateException("Empty chunk body")
                                    val buffer = ByteArray(64 * 1024)
                                    RandomAccessFile(outFile, "rw").use { raf ->
                                        raf.seek(chunk.current)
                                        body.byteStream().use { input ->
                                            while (chunk.current <= chunk.end) {
                                                currentCoroutineContext().ensureActive()
                                                val maxToRead = minOf(
                                                    buffer.size.toLong(),
                                                    chunk.end - chunk.current + 1L
                                                ).toInt()
                                                val read = input.read(buffer, 0, maxToRead)
                                                if (read == -1) break
                                                raf.write(buffer, 0, read)
                                                chunk.current += read
                                                val agg = totalDownloaded.addAndGet(read.toLong())
                                                reporter.maybeReport(bytesDownloaded = agg, completedItems = 0)
                                                flushChunkCheckpoint(force = false)
                                            }
                                        }
                                    }
                                }
                                if (chunk.current <= chunk.end) {
                                    throw IOException("Connection closed at ${chunk.current}/${chunk.end}")
                                }
                                attempt = 0
                                flushChunkCheckpoint(force = true)
                            } catch (ce: CancellationException) {
                                flushChunkCheckpoint(force = true)
                                throw ce
                            } catch (npe: NetworkPausedException) {
                                flushChunkCheckpoint(force = true)
                                throw npe
                            } catch (t: Throwable) {
                                flushChunkCheckpoint(force = true)
                                attempt++
                                if (isTransientNetworkError(t)) {
                                    runCatching { downloadClient.connectionPool.evictAll() }
                                    if (!isNetworkAvailable.get() || attempt >= MAX_NETWORK_RETRIES) {
                                        throw NetworkPausedException(t.message ?: "Network interrupted")
                                    }
                                    delay(350L * attempt)
                                } else {
                                    if (attempt >= MAX_SERVER_RETRIES) throw t
                                    delay(350L * attempt)
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            flushChunkCheckpoint(force = true)
        }
    }

    private suspend fun downloadProgressiveSingleStreamResumable(
        id: Long,
        url: String,
        headers: Map<String, String>,
        outFile: File,
        progressRange: IntRange = 0..99
    ) {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!isNetworkAvailable.get()) {
                throw NetworkPausedException("No network connection")
            }
            val existingLength = if (outFile.exists()) outFile.length().coerceAtLeast(0L) else 0L
            val extra = if (existingLength > 0L) mapOf("Range" to "bytes=$existingLength-") else emptyMap()
            try {
                val (_, _, response) = executeWithSafeRedirects(url, headers, extra)
                response.use { resp ->
                    if (resp.code == 416 && existingLength > 0L) {
                        // Already completely downloaded
                        return
                    }
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("HTTP ${resp.code}")
                    }
                    val body = resp.body ?: throw IllegalStateException("Empty response body")
                    val appendMode = resp.code == 206 && existingLength > 0L
                    val startOffset = if (appendMode) existingLength else 0L
                    val remainingLength = body.contentLength().coerceAtLeast(0L)
                    val totalExpected = if (remainingLength > 0L) startOffset + remainingLength else 0L
                    if (totalExpected > MAX_SINGLE_FILE_BYTES) {
                        throw IllegalStateException("File exceeds offline size limit")
                    }

                    val buffer = ByteArray(64 * 1024)
                    var downloaded = startOffset
                    val reporter = ProgressAndSpeedReporter(
                        downloadId = id,
                        totalBytesExpected = totalExpected,
                        totalItemsExpected = 0,
                        initialBytes = startOffset,
                        minPercent = progressRange.first,
                        maxPercent = progressRange.last
                    )
                    if (startOffset > 0L) {
                        reporter.forceReport(bytesDownloaded = startOffset, completedItems = 0)
                    }

                    body.byteStream().use { input ->
                        FileOutputStream(outFile, appendMode).use { output ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                reporter.maybeReport(bytesDownloaded = downloaded, completedItems = 0)
                            }
                        }
                    }
                    if (totalExpected > 0L && downloaded < totalExpected) {
                        throw IOException("Truncated stream ($downloaded/$totalExpected bytes)")
                    }
                    return
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (npe: NetworkPausedException) {
                throw npe
            } catch (t: Throwable) {
                attempt++
                if (isTransientNetworkError(t)) {
                    runCatching { downloadClient.connectionPool.evictAll() }
                    if (!isNetworkAvailable.get() || attempt >= MAX_NETWORK_RETRIES) {
                        throw NetworkPausedException(t.message ?: "Network interrupted")
                    }
                    delay(350L * attempt)
                } else {
                    if (attempt >= MAX_SERVER_RETRIES) throw t
                    delay(350L * attempt)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Resumable Parallel Multi-Segment HLS Engine (8x concurrent + Demuxed Audio)
    // -------------------------------------------------------------------------

    private data class HlsAssetTask(
        val url: String,
        val targetFile: File,
        val byteRangeHeader: String? = null,
        val countsAsSegment: Boolean = true
    )

    private data class PreparedHlsPlaylist(
        val rewrittenManifestText: String,
        val tasks: List<HlsAssetTask>,
        val segmentCount: Int
    )

    private suspend fun downloadHlsStreamParallel(
        id: Long,
        playlistUrl: String,
        folder: File,
        headers: Map<String, String>,
        checkpoint: DownloadCheckpoint?,
        progressRange: IntRange = 0..99
    ): String {
        val (resolvedPlaylistUrl, resolvedHeaders, initialManifest) = fetchTextWithRetry(playlistUrl, headers)
            ?: throw IllegalStateException("Failed to fetch HLS playlist")

        val isMaster = initialManifest.contains("#EXT-X-STREAM-INF", ignoreCase = true)
        if (!isMaster) {
            val prepared = prepareHlsMediaPlaylist(
                mediaPlaylistUrl = resolvedPlaylistUrl,
                manifestContent = initialManifest,
                targetDir = folder
            )
            executeParallelHlsTasksWithHeaders(
                downloadId = id,
                tasksWithHeaders = prepared.tasks.map { it to resolvedHeaders },
                totalSegments = prepared.segmentCount,
                progressRange = progressRange
            )
            val localManifest = File(folder, "index.m3u8")
            localManifest.writeText(prepared.rewrittenManifestText, Charsets.UTF_8)
            return localManifest.absolutePath
        }

        // Use user-selected / checkpointed quality if available, else pick default best <= 1080p
        val savedVariantUrl = checkpoint?.selectedVariantUrl?.takeIf { it.isNotBlank() }
        val selection = if (savedVariantUrl != null) {
            HlsMasterSelection(
                videoPlaylistUrl = savedVariantUrl,
                videoStreamInfLine = checkpoint.selectedStreamInf.orEmpty().ifBlank {
                    "#EXT-X-STREAM-INF:BANDWIDTH=4000000"
                },
                audioPlaylistUrl = if (!checkpoint.externalAudioUrl.isNullOrBlank()) null else checkpoint.selectedAudioUrl,
                audioName = checkpoint.selectedAudioName,
                audioLanguage = checkpoint.selectedAudioLanguage
            )
        } else {
            val autoSel = selectHlsStreams(resolvedPlaylistUrl, initialManifest)
                ?: throw IllegalStateException("No playable HLS variant found")
            val effectiveSel = if (!checkpoint?.externalAudioUrl.isNullOrBlank()) {
                autoSel.copy(audioPlaylistUrl = null)
            } else {
                autoSel
            }
            writeCheckpoint(
                folder,
                (checkpoint ?: DownloadCheckpoint()).copy(
                    selectedVariantUrl = effectiveSel.videoPlaylistUrl,
                    selectedStreamInf = effectiveSel.videoStreamInfLine,
                    selectedAudioUrl = effectiveSel.audioPlaylistUrl,
                    selectedAudioName = effectiveSel.audioName,
                    selectedAudioLanguage = effectiveSel.audioLanguage
                )
            )
            effectiveSel
        }

        val (videoUrl, videoHeaders, videoManifest) = fetchTextWithRetry(selection.videoPlaylistUrl, resolvedHeaders)
            ?: throw IllegalStateException("Failed to fetch HLS video variant playlist")

        val audioUrl = selection.audioPlaylistUrl
        if (audioUrl.isNullOrBlank()) {
            val prepared = prepareHlsMediaPlaylist(
                mediaPlaylistUrl = videoUrl,
                manifestContent = videoManifest,
                targetDir = folder
            )
            executeParallelHlsTasksWithHeaders(
                downloadId = id,
                tasksWithHeaders = prepared.tasks.map { it to videoHeaders },
                totalSegments = prepared.segmentCount,
                progressRange = progressRange
            )
            val localManifest = File(folder, "index.m3u8")
            localManifest.writeText(prepared.rewrittenManifestText, Charsets.UTF_8)
            return localManifest.absolutePath
        }

        val audioResult = fetchTextWithRetry(audioUrl, resolvedHeaders)
        val videoDir = File(folder, "video").apply { mkdirs() }
        val videoPrepared = prepareHlsMediaPlaylist(
            mediaPlaylistUrl = videoUrl,
            manifestContent = videoManifest,
            targetDir = videoDir
        )

        if (audioResult == null) {
            executeParallelHlsTasksWithHeaders(
                downloadId = id,
                tasksWithHeaders = videoPrepared.tasks.map { it to videoHeaders },
                totalSegments = videoPrepared.segmentCount,
                progressRange = progressRange
            )
            val localManifest = File(videoDir, "video.m3u8")
            localManifest.writeText(videoPrepared.rewrittenManifestText, Charsets.UTF_8)
            return localManifest.absolutePath
        }

        val (resolvedAudioUrl, audioHeaders, audioManifest) = audioResult
        val audioDir = File(folder, "audio").apply { mkdirs() }
        val audioPrepared = prepareHlsMediaPlaylist(
            mediaPlaylistUrl = resolvedAudioUrl,
            manifestContent = audioManifest,
            targetDir = audioDir
        )

        val combinedSegments = videoPrepared.segmentCount + audioPrepared.segmentCount
        if (combinedSegments > MAX_HLS_SEGMENTS * 2) {
            throw IllegalStateException("HLS stream too long for offline save ($combinedSegments segments)")
        }

        val allTasks = buildList(videoPrepared.tasks.size + audioPrepared.tasks.size) {
            addAll(videoPrepared.tasks.map { it to videoHeaders })
            addAll(audioPrepared.tasks.map { it to audioHeaders })
        }

        executeParallelHlsTasksWithHeaders(
            downloadId = id,
            tasksWithHeaders = allTasks,
            totalSegments = combinedSegments,
            progressRange = progressRange
        )

        File(videoDir, "video.m3u8").writeText(videoPrepared.rewrittenManifestText, Charsets.UTF_8)
        File(audioDir, "audio.m3u8").writeText(audioPrepared.rewrittenManifestText, Charsets.UTF_8)

        val safeAudioName = selection.audioName.replace("\"", "'")
        val langAttr = selection.audioLanguage?.let { """,LANGUAGE="${it.replace("\"", "")}"""" }.orEmpty()
        val sanitizedStreamInf = selection.videoStreamInfLine
            .replace(Regex("""AUDIO="[^"]*""""), """AUDIO="offline_aud"""")
            .let { line ->
                if (line.contains("AUDIO=", ignoreCase = true)) line else """$line,AUDIO="offline_aud""""
            }

        val localMasterText = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:6")
            appendLine("""#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="offline_aud",NAME="$safeAudioName"$langAttr,DEFAULT=YES,AUTOSELECT=YES,URI="audio/audio.m3u8"""")
            appendLine(sanitizedStreamInf)
            appendLine("video/video.m3u8")
        }
        val localMaster = File(folder, "index.m3u8")
        localMaster.writeText(localMasterText, Charsets.UTF_8)
        return localMaster.absolutePath
    }

    /**
     * Downloads an external loaded audio track (HLS `.m3u8` master/media OR direct `.mp3`, `.ac3`,
     * `.eac3`, `.flac`, `.wav`, `.m4a`, `.aac`, `.opus`, `.ogg`, `.dts`, etc.) into `ext_audio/`
     * inside the download folder with full pause/resume safety and returns its sidecar descriptor.
     */
    private suspend fun downloadExternalAudioSidecar(
        id: Long,
        folder: File,
        headers: Map<String, String>,
        checkpoint: DownloadCheckpoint,
        progressRange: IntRange = 85..99
    ): OfflineSidecarAudio {
        val extUrl = checkpoint.externalAudioUrl?.trim()
            ?: throw IllegalStateException("Missing external audio URL")
        val extAudioDir = File(folder, "ext_audio").apply { mkdirs() }
        val audioId = checkpoint.externalAudioId?.takeIf { it.isNotBlank() } ?: "offline_ext_audio"
        val audioLabel = checkpoint.externalAudioLabel?.takeIf { it.isNotBlank() }
            ?: checkpoint.selectedAudioName.takeIf { it.isNotBlank() }
            ?: "External Audio"

        val urlLooksHls = checkpoint.externalAudioIsHls ||
            extUrl.contains(".m3u8", ignoreCase = true) ||
            extUrl.contains("format=m3u8", ignoreCase = true)
        val peekedPlaylist = if (urlLooksHls) {
            fetchTextWithRetry(extUrl, headers)
        } else {
            runCatching { fetchPlaylistTextBounded(extUrl, headers) }.getOrNull()
        }

        if (peekedPlaylist != null && peekedPlaylist.third.contains("#EXTM3U", ignoreCase = true)) {
            val (resolvedUrl, resolvedHeaders, manifestText) = peekedPlaylist
            val (mediaUrl, mediaHeaders, mediaManifest) = if (manifestText.contains("#EXT-X-STREAM-INF", ignoreCase = true)) {
                val chosenMediaUrl = selectExternalHlsAudioPlaylistUrl(resolvedUrl, manifestText)
                    ?: throw IllegalStateException("External audio HLS master has no playable rendition")
                fetchTextWithRetry(chosenMediaUrl, resolvedHeaders)
                    ?: throw IllegalStateException("Failed to fetch external audio HLS media playlist")
            } else {
                Triple(resolvedUrl, resolvedHeaders, manifestText)
            }

            val prepared = prepareHlsMediaPlaylist(
                mediaPlaylistUrl = mediaUrl,
                manifestContent = mediaManifest,
                targetDir = extAudioDir
            )
            executeParallelHlsTasksWithHeaders(
                downloadId = id,
                tasksWithHeaders = prepared.tasks.map { it to mediaHeaders },
                totalSegments = prepared.segmentCount,
                progressRange = progressRange
            )
            val localAudioManifest = File(extAudioDir, "audio.m3u8")
            localAudioManifest.writeText(prepared.rewrittenManifestText, Charsets.UTF_8)
            return OfflineSidecarAudio(
                id = audioId,
                relativePath = "ext_audio/audio.m3u8",
                label = audioLabel,
                language = checkpoint.externalAudioLanguage,
                formatBadge = "HLS",
                isHls = true,
                delayMs = checkpoint.externalAudioDelayMs
            )
        }

        // Direct audio file (.mp3, .ac3, .eac3, .flac, .wav, .m4a, .aac, .opus, .ogg, .dts, etc.)
        val probe = probeProgressiveUrl(extUrl, headers)
        if (probe.contentLength > MAX_SINGLE_FILE_BYTES) {
            throw IllegalStateException("External audio exceeds offline size limit")
        }
        val ext = resolveExternalAudioFileExtension(
            url = probe.finalUrl,
            contentType = probe.contentType,
            formatBadge = checkpoint.externalAudioFormatBadge
        )
        val outFile = File(extAudioDir, "track.$ext")
        downloadProgressiveSingleStreamResumable(
            id = id,
            url = probe.finalUrl,
            headers = probe.effectiveHeaders,
            outFile = outFile,
            progressRange = progressRange
        )
        val badge = formatBadgeForExtension(
            ext = ext,
            fallback = checkpoint.externalAudioFormatBadge ?: "AUDIO"
        )
        return OfflineSidecarAudio(
            id = audioId,
            relativePath = "ext_audio/track.$ext",
            label = audioLabel,
            language = checkpoint.externalAudioLanguage,
            formatBadge = badge,
            isHls = false,
            delayMs = checkpoint.externalAudioDelayMs
        )
    }

    private fun prepareHlsMediaPlaylist(
        mediaPlaylistUrl: String,
        manifestContent: String,
        targetDir: File
    ): PreparedHlsPlaylist {
        val lines = manifestContent.lines()
        val rawSegments = lines.count { it.trim().isNotEmpty() && !it.trim().startsWith("#") }
        if (rawSegments == 0) {
            throw IllegalStateException("HLS playlist has no media segments")
        }
        if (rawSegments > MAX_HLS_SEGMENTS) {
            throw IllegalStateException("HLS stream too long for offline save ($rawSegments segments)")
        }

        val rewrittenLines = mutableListOf<String>()
        val tasks = mutableListOf<HlsAssetTask>()
        val keyFilesByUrl = mutableMapOf<String, String>()
        val mapFilesByUrl = mutableMapOf<String, String>()

        var segmentIndex = 0
        var pendingByteRangeHeader: String? = null
        var lastByteRangeEnd = 0L

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            when {
                trimmed.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    val uriMatch = Regex("""URI="([^"]+)"""").find(trimmed)
                    if (uriMatch != null) {
                        val keyUrl = resolveRelativeUrl(mediaPlaylistUrl, uriMatch.groupValues[1])
                        val fileName = keyFilesByUrl.getOrPut(keyUrl) {
                            val name = "key_${keyFilesByUrl.size}.bin"
                            tasks.add(
                                HlsAssetTask(
                                    url = keyUrl,
                                    targetFile = File(targetDir, name),
                                    countsAsSegment = false
                                )
                            )
                            name
                        }
                        rewrittenLines.add(trimmed.replace(uriMatch.value, """URI="$fileName""""))
                    } else {
                        rewrittenLines.add(trimmed)
                    }
                }
                trimmed.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    val uriMatch = Regex("""URI="([^"]+)"""").find(trimmed)
                    if (uriMatch != null) {
                        val mapUrl = resolveRelativeUrl(mediaPlaylistUrl, uriMatch.groupValues[1])
                        val byteRangeMatch = Regex("""BYTERANGE="(\d+)(?:@(\d+))?"""").find(trimmed)
                        val mapRangeHeader = byteRangeMatch?.let { m ->
                            val len = m.groupValues[1].toLongOrNull() ?: 0L
                            val off = m.groupValues[2].toLongOrNull() ?: 0L
                            if (len > 0L) "bytes=$off-${off + len - 1L}" else null
                        }
                        val cacheKey = "$mapUrl|${mapRangeHeader.orEmpty()}"
                        val fileName = mapFilesByUrl.getOrPut(cacheKey) {
                            val name = "init_${mapFilesByUrl.size}.mp4"
                            tasks.add(
                                HlsAssetTask(
                                    url = mapUrl,
                                    targetFile = File(targetDir, name),
                                    byteRangeHeader = mapRangeHeader,
                                    countsAsSegment = false
                                )
                            )
                            name
                        }
                        val cleanedTag = trimmed
                            .replace(uriMatch.value, """URI="$fileName"""")
                            .replace(Regex(""",?BYTERANGE="[^"]*""""), "")
                        rewrittenLines.add(cleanedTag)
                    } else {
                        rewrittenLines.add(trimmed)
                    }
                }
                trimmed.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true) -> {
                    val spec = trimmed.substringAfter(":").trim()
                    val parts = spec.split("@")
                    val length = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                    val offset = parts.getOrNull(1)?.toLongOrNull() ?: lastByteRangeEnd
                    if (length > 0L) {
                        pendingByteRangeHeader = "bytes=$offset-${offset + length - 1L}"
                        lastByteRangeEnd = offset + length
                    }
                }
                trimmed.isEmpty() || trimmed.startsWith("#") -> {
                    rewrittenLines.add(trimmed)
                }
                else -> {
                    val segUrl = resolveRelativeUrl(mediaPlaylistUrl, trimmed)
                    val cleanSeg = trimmed.substringBefore('?').lowercase(Locale.US)
                    val ext = when {
                        cleanSeg.endsWith(".m4s") -> "m4s"
                        cleanSeg.endsWith(".mp4") -> "mp4"
                        cleanSeg.endsWith(".aac") -> "aac"
                        cleanSeg.endsWith(".fmp4") -> "m4s"
                        else -> "ts"
                    }
                    val segName = "seg_$segmentIndex.$ext"
                    tasks.add(
                        HlsAssetTask(
                            url = segUrl,
                            targetFile = File(targetDir, segName),
                            byteRangeHeader = pendingByteRangeHeader,
                            countsAsSegment = true
                        )
                    )
                    pendingByteRangeHeader = null
                    rewrittenLines.add(segName)
                    segmentIndex++
                }
            }
        }

        return PreparedHlsPlaylist(
            rewrittenManifestText = rewrittenLines.joinToString("\n"),
            tasks = tasks,
            segmentCount = segmentIndex
        )
    }

    private suspend fun executeParallelHlsTasksWithHeaders(
        downloadId: Long,
        tasksWithHeaders: List<Pair<HlsAssetTask, Map<String, String>>>,
        totalSegments: Int,
        progressRange: IntRange = 0..99
    ) {
        // RESUME: skip any segment/key/init already completely saved on disk!
        var alreadyBytes = 0L
        var alreadySegments = 0
        val remainingTasks = mutableListOf<Pair<HlsAssetTask, Map<String, String>>>()

        for (pair in tasksWithHeaders) {
            val file = pair.first.targetFile
            if (file.exists() && file.length() > 0L) {
                alreadyBytes += file.length()
                if (pair.first.countsAsSegment) alreadySegments++
            } else {
                // Remove any incomplete partial file from prior drop
                File(file.parentFile, "${file.name}.part").delete()
                remainingTasks.add(pair)
            }
        }

        val totalBytes = AtomicLong(alreadyBytes)
        val completedSegments = AtomicInteger(alreadySegments)
        val reporter = ProgressAndSpeedReporter(
            downloadId = downloadId,
            totalBytesExpected = 0L,
            totalItemsExpected = totalSegments.coerceAtLeast(1),
            initialBytes = alreadyBytes,
            minPercent = progressRange.first,
            maxPercent = progressRange.last
        )
        if (alreadySegments > 0 || alreadyBytes > 0L) {
            reporter.forceReport(bytesDownloaded = alreadyBytes, completedItems = alreadySegments)
        }
        if (remainingTasks.isEmpty()) return

        val semaphore = Semaphore(HLS_PARALLEL_CONCURRENCY)
        coroutineScope {
            remainingTasks.map { (task, taskHeaders) ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        downloadBinaryFileAtomicWithRetry(
                            url = task.url,
                            target = task.targetFile,
                            headers = taskHeaders,
                            extraHeaders = task.byteRangeHeader?.let { mapOf("Range" to it) }.orEmpty()
                        ) { chunkBytes ->
                            val aggBytes = totalBytes.addAndGet(chunkBytes)
                            reporter.maybeReport(
                                bytesDownloaded = aggBytes,
                                completedItems = completedSegments.get()
                            )
                        }
                        if (task.countsAsSegment) {
                            val done = completedSegments.incrementAndGet()
                            reporter.maybeReport(
                                bytesDownloaded = totalBytes.get(),
                                completedItems = done
                            )
                        }
                    }
                }
            }.awaitAll()
        }
    }

    // -------------------------------------------------------------------------
    // Safe Network Helpers (Atomic .part writes + Redirect Auth Safety + Retry)
    // -------------------------------------------------------------------------

    private suspend fun fetchTextWithRetry(
        url: String,
        headers: Map<String, String>
    ): Triple<String, Map<String, String>, String>? {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!isNetworkAvailable.get()) {
                throw NetworkPausedException("No network connection")
            }
            try {
                return fetchTextWithRedirects(url, headers)
            } catch (ce: CancellationException) {
                throw ce
            } catch (ioe: IOException) {
                attempt++
                runCatching { downloadClient.connectionPool.evictAll() }
                if (!isNetworkAvailable.get() || attempt >= MAX_NETWORK_RETRIES) {
                    throw NetworkPausedException(ioe.message ?: "Network interrupted")
                }
                delay(350L * attempt)
            }
        }
    }

    private suspend fun fetchTextWithRedirects(
        url: String,
        headers: Map<String, String>
    ): Triple<String, Map<String, String>, String>? {
        val (finalUrl, effectiveHeaders, response) = executeWithSafeRedirects(url, headers)
        return response.use { resp ->
            if (!resp.isSuccessful) return@use null
            val text = resp.body?.string() ?: return@use null
            Triple(finalUrl, effectiveHeaders, text)
        }
    }

    /**
     * Safely peeks an HTTP response for HLS playlist inspection (max 512 KB).
     * Immediately aborts binary media streams (MP4/MKV/audio/octet-stream) without buffering them
     * so extensionless HLS URLs can be probed with zero risk of OOM or wasted bandwidth.
     */
    private suspend fun fetchPlaylistTextBounded(
        url: String,
        headers: Map<String, String>,
        maxBytes: Long = 512L * 1024L
    ): Triple<String, Map<String, String>, String>? {
        val (finalUrl, effectiveHeaders, response) = executeWithSafeRedirects(url, headers)
        return response.use { resp ->
            if (!resp.isSuccessful) return@use null
            val contentType = resp.header("Content-Type").orEmpty().lowercase()
            val urlLooksHls = finalUrl.contains(".m3u8", ignoreCase = true) || url.contains(".m3u8", ignoreCase = true)
            val contentLooksHls = contentType.contains("mpegurl") || contentType.contains("m3u")
            val isBinaryMedia = contentType.startsWith("video/") ||
                contentType.startsWith("audio/") ||
                contentType.startsWith("image/") ||
                contentType.startsWith("application/octet-stream")
            if (isBinaryMedia && !urlLooksHls && !contentLooksHls) return@use null
            val contentLen = resp.body?.contentLength() ?: -1L
            if (contentLen > 8L * 1024L * 1024L && !urlLooksHls && !contentLooksHls) return@use null
            val text = resp.peekBody(maxBytes).string()
            if (!text.contains("#EXTM3U", ignoreCase = true)) return@use null
            Triple(finalUrl, effectiveHeaders, text)
        }
    }

    private suspend fun downloadBinaryFileAtomicWithRetry(
        url: String,
        target: File,
        headers: Map<String, String>,
        extraHeaders: Map<String, String> = emptyMap(),
        onBytesChunk: suspend (Long) -> Unit
    ): Long {
        val partFile = File(target.parentFile, "${target.name}.part")
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!isNetworkAvailable.get()) {
                partFile.delete()
                throw NetworkPausedException("No network connection")
            }
            try {
                val (_, _, response) = executeWithSafeRedirects(url, headers, extraHeaders)
                val written = response.use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("Segment HTTP ${resp.code}")
                    val body = resp.body ?: throw IllegalStateException("Empty segment body")
                    val expectedLen = body.contentLength()
                    var count = 0L
                    val buf = ByteArray(32 * 1024)
                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { output ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buf)
                                if (read == -1) break
                                output.write(buf, 0, read)
                                count += read
                                onBytesChunk(read.toLong())
                            }
                        }
                    }
                    if (expectedLen > 0L && count < expectedLen) {
                        throw IOException("Truncated segment ($count/$expectedLen bytes)")
                    }
                    count
                }
                if (target.exists()) target.delete()
                if (!partFile.renameTo(target)) {
                    partFile.copyTo(target, overwrite = true)
                    partFile.delete()
                }
                return written
            } catch (ce: CancellationException) {
                partFile.delete()
                throw ce
            } catch (npe: NetworkPausedException) {
                partFile.delete()
                throw npe
            } catch (t: Throwable) {
                partFile.delete()
                attempt++
                if (isTransientNetworkError(t)) {
                    runCatching { downloadClient.connectionPool.evictAll() }
                    if (!isNetworkAvailable.get() || attempt >= MAX_NETWORK_RETRIES) {
                        throw NetworkPausedException(t.message ?: "Network interrupted")
                    }
                    delay(350L * attempt)
                } else {
                    if (attempt >= MAX_SERVER_RETRIES) throw t
                    delay(350L * attempt)
                }
            }
        }
    }

    private fun isTransientNetworkError(t: Throwable): Boolean {
        if (t is NetworkPausedException) return true
        var cur: Throwable? = t
        while (cur != null) {
            if (cur is IOException) return true
            cur = cur.cause
        }
        return false
    }

    private suspend fun executeWithSafeRedirects(
        initialUrl: String,
        baseHeaders: Map<String, String>,
        extraHeaders: Map<String, String> = emptyMap()
    ): Triple<String, Map<String, String>, Response> {
        var currentUrl = initialUrl
        var currentHeaders = baseHeaders
        for (hop in 0..MAX_REDIRECT_HOPS) {
            currentCoroutineContext().ensureActive()
            val req = buildRequest(currentUrl, currentHeaders, extraHeaders)
            val call = downloadClient.newCall(req)
            val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause != null) call.cancel()
            }
            val resp = try {
                call.execute()
            } finally {
                cancelHandle?.dispose()
            }
            if (resp.code in setOf(301, 302, 303, 307, 308)) {
                val location = resp.header("Location")
                resp.close()
                if (location.isNullOrBlank() || hop == MAX_REDIRECT_HOPS) {
                    throw IllegalStateException("Too many redirects (HTTP ${resp.code})")
                }
                val nextUrl = resolveRelativeUrl(currentUrl, location)
                if (!LocalNetworkClient.isSameOrigin(currentUrl, nextUrl)) {
                    currentHeaders = currentHeaders.filterKeys { !it.equals("Authorization", ignoreCase = true) }
                }
                currentUrl = nextUrl
                continue
            }
            return Triple(currentUrl, currentHeaders, resp)
        }
        throw IllegalStateException("Redirect loop exceeded")
    }

    private fun buildRequest(
        url: String,
        headers: Map<String, String>,
        extraHeaders: Map<String, String> = emptyMap()
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", CinePlayerManager.DEFAULT_USER_AGENT)
        headers.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank()) builder.header(k, v)
        }
        extraHeaders.forEach { (k, v) ->
            if (k.isNotBlank() && v.isNotBlank()) builder.header(k, v)
        }
        return builder.build()
    }

    // -------------------------------------------------------------------------
    // Checkpoint JSON Persistence (Zero Room Schema Changes)
    // -------------------------------------------------------------------------

    private fun writeCheckpoint(folder: File, checkpoint: DownloadCheckpoint) {
        runCatching {
            val json = JSONObject().apply {
                if (checkpoint.selectedVariantUrl != null) put("variantUrl", checkpoint.selectedVariantUrl)
                if (checkpoint.selectedStreamInf != null) put("streamInf", checkpoint.selectedStreamInf)
                if (checkpoint.selectedAudioUrl != null) put("audioUrl", checkpoint.selectedAudioUrl)
                put("audioName", checkpoint.selectedAudioName)
                if (checkpoint.selectedAudioLanguage != null) put("audioLang", checkpoint.selectedAudioLanguage)
                if (checkpoint.selectedQualityLabel != null) put("qualityLabel", checkpoint.selectedQualityLabel)
                put("contentLength", checkpoint.contentLength)
                put("isProgressiveVideo", checkpoint.isProgressiveVideo)
                put("videoCompleted", checkpoint.videoCompleted)
                if (checkpoint.completedVideoPath != null) put("completedVideoPath", checkpoint.completedVideoPath)
                if (checkpoint.externalAudioUrl != null) put("externalAudioUrl", checkpoint.externalAudioUrl)
                if (checkpoint.externalAudioId != null) put("externalAudioId", checkpoint.externalAudioId)
                if (checkpoint.externalAudioLabel != null) put("externalAudioLabel", checkpoint.externalAudioLabel)
                if (checkpoint.externalAudioLanguage != null) put("externalAudioLanguage", checkpoint.externalAudioLanguage)
                if (checkpoint.externalAudioFormatBadge != null) put("externalAudioFormatBadge", checkpoint.externalAudioFormatBadge)
                put("externalAudioIsHls", checkpoint.externalAudioIsHls)
                put("externalAudioDelayMs", checkpoint.externalAudioDelayMs)
                val arr = JSONArray()
                checkpoint.chunks.forEach { ch ->
                    arr.put(
                        JSONObject()
                            .put("start", ch.start)
                            .put("current", ch.current)
                            .put("end", ch.end)
                    )
                }
                put("chunks", arr)
            }
            val tmp = File(folder, "$STATE_FILE_NAME.tmp")
            val target = File(folder, STATE_FILE_NAME)
            tmp.writeText(json.toString(), Charsets.UTF_8)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun readCheckpoint(folder: File): DownloadCheckpoint? {
        val file = File(folder, STATE_FILE_NAME)
        if (!file.exists()) return null
        return runCatching {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val chunksArr = obj.optJSONArray("chunks")
            val chunks = buildList {
                if (chunksArr != null) {
                    for (i in 0 until chunksArr.length()) {
                        val c = chunksArr.optJSONObject(i) ?: continue
                        add(
                            ChunkCheckpoint(
                                start = c.optLong("start", 0L),
                                current = c.optLong("current", 0L),
                                end = c.optLong("end", 0L)
                            )
                        )
                    }
                }
            }
            DownloadCheckpoint(
                selectedVariantUrl = obj.optString("variantUrl", "").takeIf { it.isNotBlank() },
                selectedStreamInf = obj.optString("streamInf", "").takeIf { it.isNotBlank() },
                selectedAudioUrl = obj.optString("audioUrl", "").takeIf { it.isNotBlank() },
                selectedAudioName = obj.optString("audioName", "Default").ifBlank { "Default" },
                selectedAudioLanguage = obj.optString("audioLang", "").takeIf { it.isNotBlank() },
                selectedQualityLabel = obj.optString("qualityLabel", "").takeIf { it.isNotBlank() },
                contentLength = obj.optLong("contentLength", 0L),
                chunks = chunks,
                isProgressiveVideo = obj.optBoolean("isProgressiveVideo", false),
                videoCompleted = obj.optBoolean("videoCompleted", false),
                completedVideoPath = obj.optString("completedVideoPath", "").takeIf { it.isNotBlank() },
                externalAudioUrl = obj.optString("externalAudioUrl", "").takeIf { it.isNotBlank() },
                externalAudioId = obj.optString("externalAudioId", "").takeIf { it.isNotBlank() },
                externalAudioLabel = obj.optString("externalAudioLabel", "").takeIf { it.isNotBlank() },
                externalAudioLanguage = obj.optString("externalAudioLanguage", "").takeIf { it.isNotBlank() },
                externalAudioFormatBadge = obj.optString("externalAudioFormatBadge", "").takeIf { it.isNotBlank() },
                externalAudioIsHls = obj.optBoolean("externalAudioIsHls", false),
                externalAudioDelayMs = obj.optLong("externalAudioDelayMs", 0L)
            )
        }.getOrNull()
    }

    private inner class ProgressAndSpeedReporter(
        private val downloadId: Long,
        private val totalBytesExpected: Long,
        private val totalItemsExpected: Int,
        initialBytes: Long = 0L,
        private val minPercent: Int = 0,
        private val maxPercent: Int = 99
    ) {
        private val lock = Mutex()
        private var lastReportMs = System.currentTimeMillis()
        private var lastBytesSnapshot = initialBytes
        private var lastReportedPct = -1
        private var smoothedBps = 0L

        suspend fun forceReport(bytesDownloaded: Long, completedItems: Int) {
            val pct = computePercent(bytesDownloaded, completedItems)
            lock.withLock {
                lastReportMs = System.currentTimeMillis()
                lastBytesSnapshot = bytesDownloaded
                lastReportedPct = pct
                dao.updateStatus(
                    id = downloadId,
                    status = STATUS_DOWNLOADING,
                    progressPercent = pct,
                    bytesDownloaded = bytesDownloaded,
                    totalBytes = totalBytesExpected,
                    localFilePath = ""
                )
            }
        }

        suspend fun maybeReport(bytesDownloaded: Long, completedItems: Int) {
            val now = System.currentTimeMillis()
            val pct = computePercent(bytesDownloaded, completedItems)
            if (!lock.tryLock()) return
            try {
                val elapsedMs = now - lastReportMs
                val shouldUpdate = elapsedMs >= 350L || (pct != lastReportedPct && elapsedMs >= 150L)
                if (!shouldUpdate) return

                if (elapsedMs > 0L) {
                    val deltaBytes = (bytesDownloaded - lastBytesSnapshot).coerceAtLeast(0L)
                    val instantBps = (deltaBytes * 1000L) / elapsedMs
                    smoothedBps = if (smoothedBps <= 0L) {
                        instantBps
                    } else {
                        ((smoothedBps * 6L) + (instantBps * 4L)) / 10L
                    }
                    updateSpeed(downloadId, smoothedBps)
                }

                lastReportMs = now
                lastBytesSnapshot = bytesDownloaded
                lastReportedPct = pct

                dao.updateStatus(
                    id = downloadId,
                    status = STATUS_DOWNLOADING,
                    progressPercent = pct,
                    bytesDownloaded = bytesDownloaded,
                    totalBytes = totalBytesExpected,
                    localFilePath = ""
                )
            } finally {
                lock.unlock()
            }
        }

        private fun computePercent(bytesDownloaded: Long, completedItems: Int): Int {
            val span = (maxPercent - minPercent).coerceAtLeast(1)
            val rawRatio = when {
                totalItemsExpected > 0 -> (completedItems.toDouble() / totalItemsExpected.toDouble()).coerceIn(0.0, 1.0)
                totalBytesExpected > 0L -> (bytesDownloaded.toDouble() / totalBytesExpected.toDouble()).coerceIn(0.0, 1.0)
                else -> 0.0
            }
            return (minPercent + (rawRatio * span).toInt()).coerceIn(minPercent, maxPercent)
        }
    }

    private fun computeCompletedBytesOnDisk(folder: File): Long {
        if (!folder.exists()) return 0L
        val cp = readCheckpoint(folder)
        if (cp != null && cp.chunks.isNotEmpty()) {
            return cp.chunks.sumOf { (it.current - it.start).coerceAtLeast(0L) }
        }
        return folder.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".part") && it.name != STATE_FILE_NAME && !it.name.endsWith(".tmp") }
            .sumOf { it.length() }
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
        rootDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("${entity.id}_") }
            ?.forEach { it.deleteRecursively() }
        val expectedFolder = File(rootDir, sanitizeFolderName(entity.title, entity.id))
        if (expectedFolder.exists()) {
            expectedFolder.deleteRecursively()
        }
    }

    private fun computeFolderSize(folder: File): Long {
        if (!folder.exists()) return 0L
        return folder.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".part") && it.name != STATE_FILE_NAME && !it.name.endsWith(".tmp") }
            .sumOf { it.length() }
    }
}
