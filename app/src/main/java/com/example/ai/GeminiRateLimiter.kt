package com.example.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class AiModelTier(
    val modelId: String,
    val displayName: String,
    val maxRpm: Int = 3,
    val maxTpm: Int = 10_000,
    val maxRpd: Int = 10
) {
    FLASH_3_8(
        modelId = "gemini-3.8-flash",
        displayName = "Gemini 3.8 Flash"
    ),
    FLASH_3_5_LITE(
        modelId = "gemini-3.5-flash-lite",
        displayName = "Gemini 3.5 Flash-Lite"
    )
}

data class TierQuotaStatus(
    val tier: AiModelTier,
    val rpmUsed: Int,
    val rpmLimit: Int = tier.maxRpm,
    val tpmUsed: Int,
    val tpmLimit: Int = tier.maxTpm,
    val rpdUsed: Int,
    val rpdLimit: Int = tier.maxRpd
) {
    val remainingRpd: Int get() = (rpdLimit - rpdUsed).coerceAtLeast(0)
    val isDailyExhausted: Boolean get() = rpdUsed >= rpdLimit
}

data class RateLimitSnapshot(
    val flashStatus: TierQuotaStatus = TierQuotaStatus(AiModelTier.FLASH_3_8, 0, 3, 0, 10_000, 0, 10),
    val liteStatus: TierQuotaStatus = TierQuotaStatus(AiModelTier.FLASH_3_5_LITE, 0, 3, 0, 10_000, 0, 10)
) {
    val totalDailyRemaining: Int get() = flashStatus.remainingRpd + liteStatus.remainingRpd
    val totalDailyLimit: Int get() = flashStatus.rpdLimit + liteStatus.rpdLimit
}

sealed class RateLimitDecision {
    data class Allowed(val tier: AiModelTier, val reservedTokens: Int) : RateLimitDecision()
    data class CooldownRequired(val waitMs: Long, val reason: String) : RateLimitDecision()
    data class DailyQuotaExhausted(val reason: String) : RateLimitDecision()
}

/**
 * Enforces strict per-model Gemini rate limits:
 *   - 3 RPM (Requests Per Minute)
 *   - 10,000 TPM (Tokens Per Minute)
 *   - 10 RPD (Requests Per Day)
 * for BOTH Gemini 3.8 Flash and Gemini 3.5 Flash-Lite independently, with automatic failover.
 */
class GeminiRateLimiter(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val dayKeyProvider: () -> String = { currentUtcDayKey() },
    private val onDailyUsageChanged: ((dayKey: String, flashRpd: Int, liteRpd: Int) -> Unit)? = null
) {
    private data class UsageEvent(
        val timestampMs: Long,
        var tokens: Int
    )

    private val lock = Any()
    private val windowMs = 60_000L

    private var trackedDayKey: String = dayKeyProvider()
    private val minuteEvents = mutableMapOf(
        AiModelTier.FLASH_3_8 to mutableListOf<UsageEvent>(),
        AiModelTier.FLASH_3_5_LITE to mutableListOf<UsageEvent>()
    )
    private val dailyCounts = mutableMapOf(
        AiModelTier.FLASH_3_8 to 0,
        AiModelTier.FLASH_3_5_LITE to 0
    )

    private val _snapshot = MutableStateFlow(buildSnapshot(clockMs()))
    val snapshot: StateFlow<RateLimitSnapshot> = _snapshot.asStateFlow()

    fun restorePersistedDailyCounts(savedDayKey: String, flashRpd: Int, liteRpd: Int) {
        synchronized(lock) {
            val today = dayKeyProvider()
            trackedDayKey = today
            if (savedDayKey == today) {
                dailyCounts[AiModelTier.FLASH_3_8] = flashRpd.coerceIn(0, AiModelTier.FLASH_3_8.maxRpd)
                dailyCounts[AiModelTier.FLASH_3_5_LITE] = liteRpd.coerceIn(0, AiModelTier.FLASH_3_5_LITE.maxRpd)
            } else {
                dailyCounts[AiModelTier.FLASH_3_8] = 0
                dailyCounts[AiModelTier.FLASH_3_5_LITE] = 0
            }
            _snapshot.value = buildSnapshot(clockMs())
        }
    }

    fun estimateAudioSubtitleTokens(audioDurationSec: Float): Int {
        // Gemini audio tokenization (~32 tokens/sec) + prompt (~180) + structured JSON cues (~320)
        val audioTokens = (audioDurationSec.coerceAtLeast(1f) * 35f).toInt()
        return (audioTokens + 500).coerceIn(600, 9_500)
    }

    fun acquireSlot(
        estimatedTokens: Int,
        preferredTier: AiModelTier = AiModelTier.FLASH_3_8
    ): RateLimitDecision {
        synchronized(lock) {
            val now = clockMs()
            rolloverDayIfNeeded()
            pruneExpiredEvents(now)

            val candidateOrder = if (preferredTier == AiModelTier.FLASH_3_8) {
                listOf(AiModelTier.FLASH_3_8, AiModelTier.FLASH_3_5_LITE)
            } else {
                listOf(AiModelTier.FLASH_3_5_LITE, AiModelTier.FLASH_3_8)
            }

            if (estimatedTokens > AiModelTier.FLASH_3_8.maxTpm) {
                return RateLimitDecision.CooldownRequired(
                    waitMs = 10_000L,
                    reason = "Audio clip exceeds 10K TPM limit. Use a shorter clip."
                )
            }

            // Check if all tiers exhausted daily quota
            if (candidateOrder.all { (dailyCounts[it] ?: 0) >= it.maxRpd }) {
                _snapshot.value = buildSnapshot(now)
                return RateLimitDecision.DailyQuotaExhausted(
                    "Daily limit reached (10/10 RPD on Gemini 3.8 Flash and 10/10 RPD on Gemini 3.5 Flash-Lite)."
                )
            }

            var shortestWaitMs = Long.MAX_VALUE
            var waitReason = "Rate limit active."

            for (tier in candidateOrder) {
                val rpd = dailyCounts[tier] ?: 0
                if (rpd >= tier.maxRpd) continue

                val events = minuteEvents[tier]!!
                val rpmUsed = events.size
                val tpmUsed = events.sumOf { it.tokens }

                if (rpmUsed < tier.maxRpm && (tpmUsed + estimatedTokens) <= tier.maxTpm) {
                    events.add(UsageEvent(timestampMs = now, tokens = estimatedTokens))
                    dailyCounts[tier] = rpd + 1
                    val flashRpd = dailyCounts[AiModelTier.FLASH_3_8] ?: 0
                    val liteRpd = dailyCounts[AiModelTier.FLASH_3_5_LITE] ?: 0
                    _snapshot.value = buildSnapshot(now)
                    onDailyUsageChanged?.invoke(trackedDayKey, flashRpd, liteRpd)
                    return RateLimitDecision.Allowed(tier = tier, reservedTokens = estimatedTokens)
                }

                // Compute exact wait time until oldest event drops out of the 60s window
                val oldest = events.firstOrNull()
                if (oldest != null) {
                    val waitForWindow = ((oldest.timestampMs + windowMs) - now).coerceAtLeast(1_000L)
                    if (waitForWindow < shortestWaitMs) {
                        shortestWaitMs = waitForWindow
                        waitReason = if (rpmUsed >= tier.maxRpm) {
                            "3 RPM ceiling reached — cooling down ${(waitForWindow / 1000L) + 1}s"
                        } else {
                            "10K TPM ceiling reached — cooling down ${(waitForWindow / 1000L) + 1}s"
                        }
                    }
                }
            }

            _snapshot.value = buildSnapshot(now)
            val safeWait = if (shortestWaitMs == Long.MAX_VALUE) 20_000L else shortestWaitMs
            return RateLimitDecision.CooldownRequired(waitMs = safeWait, reason = waitReason)
        }
    }

    fun recordActualTokens(tier: AiModelTier, actualTotalTokens: Int) {
        if (actualTotalTokens <= 0) return
        synchronized(lock) {
            val now = clockMs()
            pruneExpiredEvents(now)
            minuteEvents[tier]?.lastOrNull()?.let { last ->
                last.tokens = actualTotalTokens.coerceAtMost(tier.maxTpm)
            }
            _snapshot.value = buildSnapshot(now)
        }
    }

    fun refreshSnapshot() {
        synchronized(lock) {
            val now = clockMs()
            rolloverDayIfNeeded()
            pruneExpiredEvents(now)
            _snapshot.value = buildSnapshot(now)
        }
    }

    private fun rolloverDayIfNeeded() {
        val today = dayKeyProvider()
        if (today != trackedDayKey) {
            trackedDayKey = today
            dailyCounts[AiModelTier.FLASH_3_8] = 0
            dailyCounts[AiModelTier.FLASH_3_5_LITE] = 0
            onDailyUsageChanged?.invoke(today, 0, 0)
        }
    }

    private fun pruneExpiredEvents(nowMs: Long) {
        val cutoff = nowMs - windowMs
        minuteEvents.values.forEach { list ->
            list.removeAll { it.timestampMs <= cutoff }
        }
    }

    private fun buildSnapshot(nowMs: Long): RateLimitSnapshot {
        fun statusFor(tier: AiModelTier): TierQuotaStatus {
            val events = minuteEvents[tier].orEmpty().filter { it.timestampMs > nowMs - windowMs }
            return TierQuotaStatus(
                tier = tier,
                rpmUsed = events.size,
                tpmUsed = events.sumOf { it.tokens },
                rpdUsed = dailyCounts[tier] ?: 0
            )
        }
        return RateLimitSnapshot(
            flashStatus = statusFor(AiModelTier.FLASH_3_8),
            liteStatus = statusFor(AiModelTier.FLASH_3_5_LITE)
        )
    }

    companion object {
        fun currentUtcDayKey(): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date())
        }
    }
}
