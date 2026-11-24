package com.example.player

import android.net.Uri
import java.util.Locale

/**
 * Real-time YouTube-style "Stats for Nerds" network, buffer, codec, and memory telemetry snapshot.
 */
data class PlayerNetworkStats(
    val streamHost: String = "—",
    val streamFormatLabel: String = "AUTO",
    val isLocalSource: Boolean = false,
    val playbackStateLabel: String = "IDLE",
    val connectionSpeedBps: Long = 0L,
    val networkActivityBytesPerSec: Long = 0L,
    val sessionDownloadedBytes: Long = 0L,
    val bufferHealthMs: Long = 0L,
    val bufferedPercent: Int = 0,
    val allocatedBufferBytes: Long = 0L,
    val targetBufferBytes: Int = 0,
    val jvmHeapUsedBytes: Long = 0L,
    val jvmHeapMaxBytes: Long = 0L,
    val rebufferCount: Int = 0,
    val totalRebufferMs: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoFps: Float = 0f,
    val videoCodec: String = "—",
    val videoBitrateBps: Int = 0,
    val audioCodec: String = "—",
    val audioChannels: Int = 0,
    val audioSampleRateHz: Int = 0,
    val audioBitrateBps: Int = 0,
    val droppedFrames: Long = 0L,
    val liveDroppedFramesPerSec: Float = 0f,
    val steadyDroppedFrames: Long = 0L,
    val seekJoinDroppedFrames: Long = 0L,
    val frameDropDiagnosis: FrameDropDiagnosis = FrameDropDiagnosis.HEALTHY,
    val asyncQueueingActive: Boolean = false,
    val activeCdnIndex: Int = 1,
    val totalCdnNodes: Int = 1,
    val cdnFailoverCount: Int = 0,
    val diskCacheUsedBytes: Long = 0L,
    val diskCacheMaxBytes: Long = 0L,
    val networkTransportLabel: String = "HTTP/2 (OkHttp)",
    val isRamDirectCacheMode: Boolean = false,
    val prefetchDepth: Int = 0,
    val prefetchHitRatePercent: Int = 0,
    val prefetchHits: Long = 0L,
    val activeVideoDecoderName: String = "",
    val effectiveDecoderMode: VideoDecoderMode = VideoDecoderMode.AUTO,
    val privateDnsMode: PrivateDnsMode = PrivateDnsMode.SYSTEM,
    val nordVpnActive: Boolean = false,
    val nordVpnServerHost: String = "",
    val systemVpnActive: Boolean = false,
    val networkActivityHistoryKBps: List<Float> = emptyList(),
    val bufferHealthHistorySec: List<Float> = emptyList()
)

object PlayerStatsFormatter {

    fun formatBitrate(bps: Long): String {
        if (bps <= 0L) return "—"
        return when {
            bps >= 1_000_000L -> String.format(Locale.US, "%.2f Mbps", bps / 1_000_000.0)
            bps >= 1_000L -> String.format(Locale.US, "%d Kbps", bps / 1_000L)
            else -> "$bps bps"
        }
    }

    fun formatThroughput(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0 KB/s"
        val kb = bytesPerSec / 1024.0
        return when {
            kb >= 1024.0 -> String.format(Locale.US, "%.2f MB/s", kb / 1024.0)
            kb >= 1.0 -> String.format(Locale.US, "%.0f KB/s", kb)
            else -> "$bytesPerSec B/s"
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }

    fun formatBufferSeconds(bufferMs: Long): String {
        val clamped = bufferMs.coerceAtLeast(0L)
        return String.format(Locale.US, "%.2f s", clamped / 1000.0)
    }

    fun formatResolution(width: Int, height: Int, fps: Float): String {
        if (width <= 0 || height <= 0) return "—"
        val fpsPart = if (fps > 0.5f) {
            val rounded = if (fps % 1f < 0.05f || fps % 1f > 0.95f) {
                "${kotlin.math.round(fps).toInt()} fps"
            } else {
                String.format(Locale.US, "%.2f fps", fps)
            }
            " @ $rounded"
        } else ""
        return "${width}×${height}$fpsPart"
    }

    fun formatAudioDetail(codec: String, channels: Int, sampleRateHz: Int, bitrateBps: Int): String {
        if (codec == "—" && channels <= 0 && sampleRateHz <= 0) return "—"
        return buildString {
            append(codec)
            if (channels > 0) {
                append(" · ")
                append(
                    when (channels) {
                        1 -> "Mono"
                        2 -> "Stereo"
                        6 -> "5.1"
                        8 -> "7.1"
                        else -> "${channels}ch"
                    }
                )
            }
            if (sampleRateHz > 0) {
                append(" · ")
                append(String.format(Locale.US, "%.1f kHz", sampleRateHz / 1000.0))
            }
            if (bitrateBps > 0) {
                append(" · ")
                append("${bitrateBps / 1000}k")
            }
        }
    }

    fun formatCodecLabel(codecs: String?, mimeType: String?): String {
        val shortMime = mimeType?.substringAfter('/')?.uppercase(Locale.US)
        val cleanCodecs = codecs?.trim()?.takeIf { it.isNotBlank() }
        return when {
            cleanCodecs != null && shortMime != null -> "$cleanCodecs ($shortMime)"
            cleanCodecs != null -> cleanCodecs
            shortMime != null -> shortMime
            else -> "—"
        }
    }

    fun extractHostLabel(sourceUri: String?, isLocal: Boolean): String {
        if (isLocal) return "Local Storage"
        if (sourceUri.isNullOrBlank()) return "—"
        val parsed = runCatching { Uri.parse(sourceUri) }.getOrNull()
        val host = parsed?.host?.takeIf { it.isNotBlank() }
        return host ?: sourceUri.take(28)
    }
}
