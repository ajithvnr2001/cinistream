package com.example.player

import android.os.Build
import java.util.Locale

/**
 * Classifies live frame-drop telemetry into actionable Player-side vs. Stream/URL-side causes
 * for the upgraded Stats for Nerds diagnostic row.
 */
enum class FrameDropDiagnosis(
    val shortLabel: String,
    val detailHint: String,
    val isWarning: Boolean
) {
    HEALTHY(
        shortLabel = "Healthy",
        detailHint = "Smooth steady frame pacing",
        isWarning = false
    ),
    SEEK_BURST(
        shortLabel = "Seek/Start Burst",
        detailHint = "Harmless join/seek drops only",
        isWarning = false
    ),
    SW_CPU_LOAD(
        shortLabel = "SW CPU Load",
        detailHint = "CPU software decode overloaded — try HW or lower resolution",
        isWarning = true
    ),
    STREAM_PTS_OR_RESYNC(
        shortLabel = "Stream PTS / Re-Sync",
        detailHint = "URL timestamps or CDN re-sync causing drops (not player)",
        isWarning = true
    )
}

data class FrameDropSnapshot(
    val totalDropped: Long = 0L,
    val steadyDropped: Long = 0L,
    val seekJoinDropped: Long = 0L,
    val liveDropsPerSec: Float = 0f,
    val diagnosis: FrameDropDiagnosis = FrameDropDiagnosis.HEALTHY,
    val shouldThrottlePrefetch: Boolean = false
)

/**
 * Separates harmless startup/seek/quality-switch frame drops from real sustained steady-state
 * frame drops over a rolling 3-second sliding window, and classifies whether sustained drops are
 * caused by Software CPU decoding or Stream/URL timestamp & re-sync issues.
 */
class FrameDropAnalyzer(
    private val seekJoinGraceMs: Long = DEFAULT_SEEK_JOIN_GRACE_MS,
    private val rollingWindowMs: Long = DEFAULT_ROLLING_WINDOW_MS
) {
    companion object {
        const val DEFAULT_SEEK_JOIN_GRACE_MS = 2_500L
        const val DEFAULT_ROLLING_WINDOW_MS = 3_000L
        const val WARNING_DROPS_PER_SEC = 1.5f
        const val PREFETCH_THROTTLE_DROPS_PER_SEC = 2.0f
    }

    private val lock = Any()
    private var totalDropped: Long = 0L
    private var steadyDropped: Long = 0L
    private var seekJoinDropped: Long = 0L
    private var graceUntilElapsedMs: Long = 0L
    private val rollingSteadyEvents = ArrayDeque<Pair<Long, Int>>()

    fun reset(nowElapsedMs: Long) {
        synchronized(lock) {
            totalDropped = 0L
            steadyDropped = 0L
            seekJoinDropped = 0L
            rollingSteadyEvents.clear()
            graceUntilElapsedMs = nowElapsedMs + seekJoinGraceMs
        }
    }

    fun markSeekOrTransition(nowElapsedMs: Long) {
        synchronized(lock) {
            graceUntilElapsedMs = maxOf(graceUntilElapsedMs, nowElapsedMs + seekJoinGraceMs)
        }
    }

    fun recordDroppedFrames(count: Int, nowElapsedMs: Long) {
        if (count <= 0) return
        synchronized(lock) {
            totalDropped += count.toLong()
            if (nowElapsedMs <= graceUntilElapsedMs) {
                seekJoinDropped += count.toLong()
            } else {
                steadyDropped += count.toLong()
                rollingSteadyEvents.addLast(nowElapsedMs to count)
                pruneLocked(nowElapsedMs)
            }
        }
    }

    fun snapshot(
        nowElapsedMs: Long,
        isSoftwareDecoder: Boolean
    ): FrameDropSnapshot {
        synchronized(lock) {
            pruneLocked(nowElapsedMs)
            val recentDrops = rollingSteadyEvents.sumOf { it.second }
            val dropsPerSec = recentDrops.toFloat() / (rollingWindowMs.toFloat() / 1000f)

            val diagnosis = when {
                dropsPerSec >= WARNING_DROPS_PER_SEC && isSoftwareDecoder -> FrameDropDiagnosis.SW_CPU_LOAD
                dropsPerSec >= WARNING_DROPS_PER_SEC -> FrameDropDiagnosis.STREAM_PTS_OR_RESYNC
                steadyDropped == 0L && seekJoinDropped > 0L -> FrameDropDiagnosis.SEEK_BURST
                else -> FrameDropDiagnosis.HEALTHY
            }

            return FrameDropSnapshot(
                totalDropped = totalDropped,
                steadyDropped = steadyDropped,
                seekJoinDropped = seekJoinDropped,
                liveDropsPerSec = dropsPerSec,
                diagnosis = diagnosis,
                shouldThrottlePrefetch = isSoftwareDecoder && dropsPerSec >= PREFETCH_THROTTLE_DROPS_PER_SEC
            )
        }
    }

    private fun pruneLocked(nowElapsedMs: Long) {
        val cutoff = nowElapsedMs - rollingWindowMs
        while (rollingSteadyEvents.isNotEmpty() && rollingSteadyEvents.first().first < cutoff) {
            rollingSteadyEvents.removeFirst()
        }
    }
}

/**
 * Selective MediaCodec asynchronous buffer queueing policy:
 * - Android 12+ (API 31+): enables MediaCodec async queueing by default to reduce codec-thread
 *   blocking and frame drops on high-bitrate 1080p60/4K streams.
 * - Android 7–11 (API < 31) or after any MediaCodec error: falls back to synchronous queueing to
 *   preserve stability on legacy Snapdragon Codec 2.0 HEVC implementations.
 */
object MediaCodecQueueingPolicy {
    fun shouldUseAsyncQueueing(
        sdkInt: Int = Build.VERSION.SDK_INT,
        isConstrainedOrTv: Boolean = false,
        hasCodecErrorOccurred: Boolean = false
    ): Boolean {
        if (hasCodecErrorOccurred) return false
        if (isConstrainedOrTv) return false
        return sdkInt >= Build.VERSION_CODES.S
    }

    fun formatDroppedSummary(snapshot: FrameDropSnapshot): String {
        val rateText = if (snapshot.liveDropsPerSec >= 0.1f) {
            String.format(Locale.US, "%.1f/s", snapshot.liveDropsPerSec)
        } else {
            "0/s"
        }
        return when (snapshot.diagnosis) {
            FrameDropDiagnosis.HEALTHY -> {
                if (snapshot.totalDropped == 0L) {
                    "0/s · Healthy"
                } else {
                    "$rateText · Healthy (${snapshot.steadyDropped} steady)"
                }
            }
            FrameDropDiagnosis.SEEK_BURST -> {
                "0/s · Seek/Start (${snapshot.seekJoinDropped})"
            }
            FrameDropDiagnosis.SW_CPU_LOAD -> {
                "$rateText · SW CPU Load (${snapshot.steadyDropped})"
            }
            FrameDropDiagnosis.STREAM_PTS_OR_RESYNC -> {
                "$rateText · Stream PTS (${snapshot.steadyDropped})"
            }
        }
    }
}
