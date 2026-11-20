package com.example.player

data class ScrubStepResult(
    val targetPositionMs: Long,
    val stepDeltaMs: Long,
    val stepLabel: String
)

/**
 * Pure, deterministic fast-scrubbing engine for the selectable Cinema Play Bar.
 * Provides minute-level forward/backward jumps with smooth multi-gear acceleration
 * completely separate from the ±10s fine seek buttons.
 */
object SeekBarScrubEngine {

    const val ONE_MINUTE_MS = 60_000L
    const val FINE_SEEK_MS = 10_000L

    fun computeStepMs(durationMs: Long, consecutiveSteps: Int): Long {
        val count = consecutiveSteps.coerceAtLeast(1)
        return when {
            durationMs <= 0L -> ONE_MINUTE_MS
            durationMs < 60_000L -> {
                if (count <= 3) 5_000L else 10_000L
            }
            durationMs < 300_000L -> {
                when {
                    count <= 3 -> 15_000L
                    count <= 7 -> 30_000L
                    else -> 60_000L
                }
            }
            else -> {
                when {
                    count <= 3 -> 60_000L       // ±1 min
                    count <= 7 -> 120_000L      // ±2 min
                    count <= 12 -> 300_000L     // ±5 min
                    else -> 600_000L            // ±10 min
                }
            }
        }
    }

    fun stepPosition(
        currentMs: Long,
        durationMs: Long,
        direction: Int,
        consecutiveSteps: Int
    ): ScrubStepResult {
        val dir = if (direction >= 0) 1 else -1
        val magnitude = computeStepMs(durationMs, consecutiveSteps)
        val signedDelta = dir * magnitude
        val maxPos = durationMs.coerceAtLeast(0L)
        val nextPos = if (maxPos > 0L) {
            (currentMs + signedDelta).coerceIn(0L, maxPos)
        } else {
            (currentMs + signedDelta).coerceAtLeast(0L)
        }
        return ScrubStepResult(
            targetPositionMs = nextPos,
            stepDeltaMs = signedDelta,
            stepLabel = formatStepBadge(signedDelta)
        )
    }

    fun formatStepBadge(deltaMs: Long): String {
        val sign = if (deltaMs >= 0L) "+" else "-"
        val absSec = kotlin.math.abs(deltaMs) / 1000L
        return if (absSec >= 60L && absSec % 60L == 0L) {
            "$sign${absSec / 60L}m"
        } else {
            "$sign${absSec}s"
        }
    }

    fun fractionToPositionMs(fraction: Float, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        return (fraction.coerceIn(0f, 1f) * durationMs).toLong().coerceIn(0L, durationMs)
    }

    fun positionToFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
}
