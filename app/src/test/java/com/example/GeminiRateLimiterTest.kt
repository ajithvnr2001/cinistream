package com.example

import com.example.ai.AiModelTier
import com.example.ai.AiSubtitleCue
import com.example.ai.AiSubtitleGenerator
import com.example.ai.GeminiRateLimiter
import com.example.ai.RateLimitDecision
import com.example.player.PcmAudioCaptureProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeminiRateLimiterTest {

    @Test
    fun enforcesThreeRpmPerTierAndFailsOverToLiteThenCoolsDown() {
        var nowMs = 1_000_000L
        val limiter = GeminiRateLimiter(
            clockMs = { nowMs },
            dayKeyProvider = { "2026-11-19" }
        )

        // First 3 requests use Gemini 3.8 Flash (RPM 3/3)
        repeat(3) { idx ->
            val decision = limiter.acquireSlot(estimatedTokens = 1_200, preferredTier = AiModelTier.FLASH_3_8)
            assertTrue("Request $idx should be allowed on Flash", decision is RateLimitDecision.Allowed)
            assertEquals(AiModelTier.FLASH_3_8, (decision as RateLimitDecision.Allowed).tier)
            nowMs += 1_000L
        }

        // Next 3 requests automatically fail over to Gemini 3.5 Flash-Lite (RPM 3/3)
        repeat(3) { idx ->
            val decision = limiter.acquireSlot(estimatedTokens = 1_200, preferredTier = AiModelTier.FLASH_3_8)
            assertTrue("Failover request $idx should be allowed on Lite", decision is RateLimitDecision.Allowed)
            assertEquals(AiModelTier.FLASH_3_5_LITE, (decision as RateLimitDecision.Allowed).tier)
            nowMs += 1_000L
        }

        // 7th request within same 60s window must trigger CooldownRequired
        val blocked = limiter.acquireSlot(estimatedTokens = 1_200, preferredTier = AiModelTier.FLASH_3_8)
        assertTrue("7th request within 60s must require cooldown", blocked is RateLimitDecision.CooldownRequired)
        val cooldown = blocked as RateLimitDecision.CooldownRequired
        assertTrue("Cooldown wait should be positive", cooldown.waitMs in 1_000L..60_000L)

        // Advance clock past the 60s sliding window -> Flash slot re-opens
        nowMs += 60_000L
        val reopened = limiter.acquireSlot(estimatedTokens = 1_200, preferredTier = AiModelTier.FLASH_3_8)
        assertTrue("Slot should reopen after 60s window expires", reopened is RateLimitDecision.Allowed)
        assertEquals(AiModelTier.FLASH_3_8, (reopened as RateLimitDecision.Allowed).tier)
    }

    @Test
    fun enforcesTenThousandTpmPerTierAndFailsOver() {
        var nowMs = 2_000_000L
        val limiter = GeminiRateLimiter(
            clockMs = { nowMs },
            dayKeyProvider = { "2026-11-19" }
        )

        val d1 = limiter.acquireSlot(estimatedTokens = 6_000, preferredTier = AiModelTier.FLASH_3_8)
        assertEquals(AiModelTier.FLASH_3_8, (d1 as RateLimitDecision.Allowed).tier)

        // Second 6,000-token request would push Flash to 12,000 > 10,000 TPM -> must fail over to Lite
        val d2 = limiter.acquireSlot(estimatedTokens = 6_000, preferredTier = AiModelTier.FLASH_3_8)
        assertTrue("Should fail over to Lite when Flash TPM would exceed 10K", d2 is RateLimitDecision.Allowed)
        assertEquals(AiModelTier.FLASH_3_5_LITE, (d2 as RateLimitDecision.Allowed).tier)

        // Third 6,000-token request would exceed 10K TPM on both tiers -> CooldownRequired
        val d3 = limiter.acquireSlot(estimatedTokens = 6_000, preferredTier = AiModelTier.FLASH_3_8)
        assertTrue("Both tiers above 10K TPM should require cooldown", d3 is RateLimitDecision.CooldownRequired)
    }

    @Test
    fun enforcesTenRpdPerTierAndResetsOnDayRollover() {
        var nowMs = 3_000_000L
        var dayKey = "2026-11-19"
        val limiter = GeminiRateLimiter(
            clockMs = { nowMs },
            dayKeyProvider = { dayKey }
        )

        var flashCount = 0
        var liteCount = 0
        // Consume all 20 daily requests (10 Flash + 10 Lite), spacing past 60s windows
        repeat(20) {
            val decision = limiter.acquireSlot(estimatedTokens = 1_000, preferredTier = AiModelTier.FLASH_3_8)
            assertTrue("Request $it within 20 daily budget should succeed", decision is RateLimitDecision.Allowed)
            when ((decision as RateLimitDecision.Allowed).tier) {
                AiModelTier.FLASH_3_8 -> flashCount++
                AiModelTier.FLASH_3_5_LITE -> liteCount++
            }
            nowMs += 65_000L
        }

        assertEquals(10, flashCount)
        assertEquals(10, liteCount)
        assertEquals(0, limiter.snapshot.value.totalDailyRemaining)

        // 21st request same day must hit DailyQuotaExhausted
        val exhausted = limiter.acquireSlot(estimatedTokens = 1_000, preferredTier = AiModelTier.FLASH_3_8)
        assertTrue("21st request must exhaust daily quota", exhausted is RateLimitDecision.DailyQuotaExhausted)

        // Roll over to next UTC day -> quotas reset cleanly to 0/10
        dayKey = "2026-11-20"
        val nextDay = limiter.acquireSlot(estimatedTokens = 1_000, preferredTier = AiModelTier.FLASH_3_8)
        assertTrue("Next UTC day must reset daily quota", nextDay is RateLimitDecision.Allowed)
        assertEquals(AiModelTier.FLASH_3_8, (nextDay as RateLimitDecision.Allowed).tier)
        assertEquals(1, limiter.snapshot.value.flashStatus.rpdUsed)
        assertEquals(0, limiter.snapshot.value.liteStatus.rpdUsed)
    }

    @Test
    fun parsesStructuredSubtitleJsonAndFormatsValidSrt() {
        val sampleJson = """
            {
              "detectedLanguage": "Japanese",
              "cues": [
                {"startSec": 1.25, "endSec": 4.5, "englishText": "Welcome to CineStream."},
                {"startSec": 5.0, "endSec": 8.75, "englishText": "English captions are synced live."}
              ]
            }
        """.trimIndent()

        val (cues, language) = AiSubtitleGenerator.parseStructuredSubtitleJson(
            jsonText = sampleJson,
            clipStartPositionMs = 60_000L,
            clipDurationSec = 18f
        )

        assertEquals("Japanese", language)
        assertEquals(2, cues.size)
        assertEquals(61_250L, cues[0].startMs)
        assertEquals(64_500L, cues[0].endMs)
        assertEquals("Welcome to CineStream.", cues[0].englishText)

        val srt = AiSubtitleGenerator.formatCuesAsSrt(cues)
        assertTrue(srt.contains("1\n00:01:01,250 --> 00:01:04,500\nWelcome to CineStream."))
        assertTrue(srt.contains("2\n00:01:05,000 --> 00:01:08,750\nEnglish captions are synced live."))
    }

    @Test
    fun encodesValidRiffWaveHeaderAndPcmPayload() {
        val pcm = ShortArray(1600) { i -> ((i % 200) * 100).toShort() }
        val wav = PcmAudioCaptureProcessor.WavEncoder.encodeWav(pcm, PcmAudioCaptureProcessor.TARGET_SAMPLE_RATE)

        assertEquals(44 + pcm.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
    }
}
