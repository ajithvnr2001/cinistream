package com.example

import com.example.player.SeekBarScrubEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class SeekBarScrubEngineTest {

    @Test
    fun `movie duration steps by 1m then accelerates to 2m, 5m, and 10m`() {
        val twoHoursMs = 2L * 60L * 60L * 1000L

        assertEquals(60_000L, SeekBarScrubEngine.computeStepMs(twoHoursMs, 1))
        assertEquals(60_000L, SeekBarScrubEngine.computeStepMs(twoHoursMs, 3))
        assertEquals(120_000L, SeekBarScrubEngine.computeStepMs(twoHoursMs, 4))
        assertEquals(120_000L, SeekBarScrubEngine.computeStepMs(twoHoursMs, 7))
        assertEquals(300_000L, SeekBarScrubEngine.computeStepMs(twoHoursMs, 8))
        assertEquals(600_000L, SeekBarScrubEngine.computeStepMs(twoHoursMs, 13))
    }

    @Test
    fun `forward and backward minute stepping clamps within bounds`() {
        val durationMs = 60L * 60L * 1000L // 60 minutes

        val fwd = SeekBarScrubEngine.stepPosition(
            currentMs = 10L * 60_000L,
            durationMs = durationMs,
            direction = 1,
            consecutiveSteps = 1
        )
        assertEquals(11L * 60_000L, fwd.targetPositionMs)
        assertEquals("+1m", fwd.stepLabel)

        val fastFwd = SeekBarScrubEngine.stepPosition(
            currentMs = 58L * 60_000L,
            durationMs = durationMs,
            direction = 1,
            consecutiveSteps = 9 // +5m gear -> clamps to 60m
        )
        assertEquals(durationMs, fastFwd.targetPositionMs)
        assertEquals("+5m", fastFwd.stepLabel)

        val bwd = SeekBarScrubEngine.stepPosition(
            currentMs = 30_000L,
            durationMs = durationMs,
            direction = -1,
            consecutiveSteps = 1
        )
        assertEquals(0L, bwd.targetPositionMs)
        assertEquals("-1m", bwd.stepLabel)
    }

    @Test
    fun `short clips use proportional sub-minute steps`() {
        val fortySeconds = 40_000L
        assertEquals(5_000L, SeekBarScrubEngine.computeStepMs(fortySeconds, 1))
        assertEquals(10_000L, SeekBarScrubEngine.computeStepMs(fortySeconds, 5))

        val threeMinutes = 180_000L
        assertEquals(15_000L, SeekBarScrubEngine.computeStepMs(threeMinutes, 1))
        assertEquals(30_000L, SeekBarScrubEngine.computeStepMs(threeMinutes, 5))
        assertEquals(60_000L, SeekBarScrubEngine.computeStepMs(threeMinutes, 9))
    }

    @Test
    fun `fraction conversion maps accurately to target minutes`() {
        val durationMs = 100L * 60_000L // 100 minutes
        assertEquals(25L * 60_000L, SeekBarScrubEngine.fractionToPositionMs(0.25f, durationMs))
        assertEquals(0.25f, SeekBarScrubEngine.positionToFraction(25L * 60_000L, durationMs), 0.0001f)
    }
}
