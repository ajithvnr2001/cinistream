package com.example.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.player.CapturedAudioClip
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.Locale

private const val TAG = "AiSubtitleGenerator"

data class AiSubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val englishText: String
)

sealed class AiSubtitleGenerationResult {
    data class Success(
        val cues: List<AiSubtitleCue>,
        val detectedLanguage: String,
        val modelUsed: AiModelTier
    ) : AiSubtitleGenerationResult()

    data class Cooldown(val waitMs: Long, val message: String) : AiSubtitleGenerationResult()
    data class DailyLimitReached(val message: String) : AiSubtitleGenerationResult()
    data object SilentClip : AiSubtitleGenerationResult()
    data class Error(val message: String) : AiSubtitleGenerationResult()
}

class AiSubtitleGenerator(
    private val context: Context,
    val rateLimiter: GeminiRateLimiter
) {
    private val subtitleSchema = Schema.obj(
        properties = mapOf(
            "detectedLanguage" to Schema.string(
                description = "Primary spoken language detected in the audio clip (e.g., Japanese, Hindi, Spanish, English, None)"
            ),
            "cues" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "startSec" to Schema.double(description = "Cue start time in seconds relative to start of this audio clip"),
                        "endSec" to Schema.double(description = "Cue end time in seconds relative to start of this audio clip"),
                        "englishText" to Schema.string(description = "Concise, natural English subtitle translation/transcription for this segment")
                    )
                ),
                description = "Ordered list of timed English subtitle cues covering spoken dialogue in the audio clip"
            )
        )
    )

    private val subtitleGenerationConfig = generationConfig {
        temperature = 0.2f
        responseMimeType = "application/json"
        responseSchema = subtitleSchema
    }

    private val flashModel by lazy {
        Firebase.ai.generativeModel(
            modelName = AiModelTier.FLASH_3_8.modelId,
            generationConfig = subtitleGenerationConfig
        )
    }

    private val flashLiteModel by lazy {
        Firebase.ai.generativeModel(
            modelName = AiModelTier.FLASH_3_5_LITE.modelId,
            generationConfig = subtitleGenerationConfig
        )
    }

    suspend fun generateEnglishSubtitlesFromClip(
        clip: CapturedAudioClip,
        preferredTier: AiModelTier = AiModelTier.FLASH_3_8
    ): AiSubtitleGenerationResult {
        if (!clip.hasAudibleSignal) {
            return AiSubtitleGenerationResult.SilentClip
        }

        val estimatedTokens = rateLimiter.estimateAudioSubtitleTokens(clip.durationSec)
        val decision = rateLimiter.acquireSlot(
            estimatedTokens = estimatedTokens,
            preferredTier = preferredTier
        )

        val chosenTier = when (decision) {
            is RateLimitDecision.Allowed -> decision.tier
            is RateLimitDecision.CooldownRequired -> {
                return AiSubtitleGenerationResult.Cooldown(decision.waitMs, decision.reason)
            }
            is RateLimitDecision.DailyQuotaExhausted -> {
                return AiSubtitleGenerationResult.DailyLimitReached(decision.reason)
            }
        }

        val prompt = buildString {
            append("Listen carefully to this ")
            append(String.format(Locale.US, "%.1f", clip.durationSec))
            append("-second movie/video audio clip (may contain non-English dialogue such as Spanish, Hindi, Japanese, Korean, or French over background music/sound effects). ")
            append("Detect the spoken language, transcribe all spoken dialogue, and translate it into natural, accurate English subtitles. ")
            append("Return timed cues with startSec and endSec between 0.0 and ")
            append(String.format(Locale.US, "%.1f", clip.durationSec))
            append(". Keep each subtitle concise and readable (max 2 short lines). ")
            append("If there is genuinely no spoken dialogue at all, return an empty cues array.")
        }

        val firstAttempt = invokeModelForClip(
            tier = chosenTier,
            clip = clip,
            prompt = prompt
        )
        if (firstAttempt !is AiSubtitleGenerationResult.Error) {
            return firstAttempt
        }

        // Automatic failover to the alternate tier on transient 429 / 503 / overloaded errors
        val msgLower = firstAttempt.message.lowercase(Locale.US)
        val isTransientFailoverCandidate = msgLower.contains("429") ||
            msgLower.contains("quota") ||
            msgLower.contains("resource_exhausted") ||
            msgLower.contains("503") ||
            msgLower.contains("overloaded") ||
            msgLower.contains("unavailable")

        if (isTransientFailoverCandidate) {
            val alternateTier = if (chosenTier == AiModelTier.FLASH_3_8) {
                AiModelTier.FLASH_3_5_LITE
            } else {
                AiModelTier.FLASH_3_8
            }
            val fallbackDecision = rateLimiter.acquireSlot(
                estimatedTokens = estimatedTokens,
                preferredTier = alternateTier
            )
            if (fallbackDecision is RateLimitDecision.Allowed && fallbackDecision.tier == alternateTier) {
                Log.w(TAG, "Primary tier ${chosenTier.modelId} hit transient limit; failing over to ${alternateTier.modelId}")
                return invokeModelForClip(
                    tier = alternateTier,
                    clip = clip,
                    prompt = prompt
                )
            }
        }

        return firstAttempt
    }

    private suspend fun invokeModelForClip(
        tier: AiModelTier,
        clip: CapturedAudioClip,
        prompt: String
    ): AiSubtitleGenerationResult {
        val model = when (tier) {
            AiModelTier.FLASH_3_8 -> flashModel
            AiModelTier.FLASH_3_5_LITE -> flashLiteModel
        }

        return try {
            val response = withTimeout(60_000L) {
                model.generateContent(
                    content {
                        inlineData(clip.wavBytes, "audio/wav")
                        text(prompt)
                    }
                )
            }

            response.usageMetadata?.totalTokenCount?.let { actualTokens ->
                rateLimiter.recordActualTokens(tier, actualTokens)
            }

            val rawJson = response.text
            if (rawJson.isNullOrBlank()) {
                return AiSubtitleGenerationResult.Error("Gemini returned an empty subtitle response.")
            }

            val parsed = parseStructuredSubtitleJson(
                jsonText = rawJson,
                clipStartPositionMs = clip.startPositionMs,
                clipDurationSec = clip.durationSec
            )
            AiSubtitleGenerationResult.Success(
                cues = parsed.first,
                detectedLanguage = parsed.second,
                modelUsed = tier
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "AI subtitle generation timed out on ${tier.modelId}", e)
            AiSubtitleGenerationResult.Error("Subtitle generation timed out after 60s. Please try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "AI subtitle generation failed on ${tier.modelId}", e)
            AiSubtitleGenerationResult.Error(
                e.localizedMessage ?: "AI subtitle generation failed on ${tier.displayName}."
            )
        }
    }

    suspend fun exportCuesToSrtUri(mediaSourceUri: String, cues: List<AiSubtitleCue>): Uri =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
            val hash = mediaSourceUri.hashCode().toUInt().toString(16)
            val file = File(dir, "ai_english_$hash.srt")
            file.writeText(formatCuesAsSrt(cues), Charsets.UTF_8)
            Uri.fromFile(file)
        }

    companion object {
        fun parseStructuredSubtitleJson(
            jsonText: String,
            clipStartPositionMs: Long,
            clipDurationSec: Float
        ): Pair<List<AiSubtitleCue>, String> {
            val root = JSONObject(jsonText)
            val language = root.optString("detectedLanguage", "Auto").ifBlank { "Auto" }
            val cuesArray = root.optJSONArray("cues")
            val cues = mutableListOf<AiSubtitleCue>()
            val maxOffsetMs = (clipDurationSec * 1000f).toLong().coerceAtLeast(1_000L)

            if (cuesArray != null) {
                for (i in 0 until cuesArray.length()) {
                    val obj = cuesArray.optJSONObject(i) ?: continue
                    val text = obj.optString("englishText", "").trim()
                    if (text.isBlank()) continue

                    val startOffsetMs = (obj.optDouble("startSec", 0.0) * 1000.0)
                        .toLong()
                        .coerceIn(0L, maxOffsetMs)
                    val rawEndOffsetMs = (obj.optDouble("endSec", (startOffsetMs + 2500L) / 1000.0) * 1000.0)
                        .toLong()
                        .coerceIn(startOffsetMs + 500L, maxOffsetMs + 1_500L)

                    cues.add(
                        AiSubtitleCue(
                            startMs = clipStartPositionMs + startOffsetMs,
                            endMs = clipStartPositionMs + rawEndOffsetMs,
                            englishText = text
                        )
                    )
                }
            }
            return cues.sortedBy { it.startMs } to language
        }

        fun formatCuesAsSrt(cues: List<AiSubtitleCue>): String {
            val sorted = cues.sortedBy { it.startMs }
            return buildString {
                sorted.forEachIndexed { idx, cue ->
                    append(idx + 1).append('\n')
                    append(formatSrtTime(cue.startMs))
                        .append(" --> ")
                        .append(formatSrtTime(cue.endMs))
                        .append('\n')
                    append(cue.englishText.trim()).append("\n\n")
                }
            }
        }

        private fun formatSrtTime(totalMs: Long): String {
            val clamped = totalMs.coerceAtLeast(0L)
            val h = clamped / 3_600_000L
            val m = (clamped % 3_600_000L) / 60_000L
            val s = (clamped % 60_000L) / 1000L
            val ms = clamped % 1000L
            return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, ms)
        }
    }
}
