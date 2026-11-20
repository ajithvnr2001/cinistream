package com.example.player

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import kotlin.math.roundToInt

/**
 * Pure math & DSP gain mapping for VLC / MX Player style 0%–200% audio boost.
 */
object AudioBoostMath {
    const val MIN_PERCENT = 0
    const val NORMAL_MAX_PERCENT = 100
    const val MAX_PERCENT = 200
    const val MAX_GAIN_MB = 2000 // +20.0 dB peak boost at 200%

    fun clampPercent(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /**
     * Normalized ExoPlayer volume (0.0f..1.0f). Above 100%, player runs at 1.0f while
     * hardware/DSP LoudnessEnhancer supplies the extra +0 dB to +20 dB gain.
     */
    fun computePlayerVolume(percent: Int): Float {
        val clamped = clampPercent(percent)
        return (clamped.coerceAtMost(NORMAL_MAX_PERCENT)) / 100f
    }

    /**
     * Gain in millibels (mB) applied to Android's LoudnessEnhancer when volume > 100%.
     * 100% -> 0 mB, 150% -> +1000 mB (+10 dB), 200% -> +2000 mB (+20 dB).
     */
    fun computeTargetGainMb(percent: Int): Int {
        val clamped = clampPercent(percent)
        if (clamped <= NORMAL_MAX_PERCENT) return 0
        val boostFraction = (clamped - NORMAL_MAX_PERCENT).toFloat() /
            (MAX_PERCENT - NORMAL_MAX_PERCENT).toFloat()
        return (boostFraction * MAX_GAIN_MB).roundToInt().coerceIn(0, MAX_GAIN_MB)
    }

    fun formatGainDb(percent: Int): String {
        val mb = computeTargetGainMb(percent)
        if (mb <= 0) return "+0.0 dB"
        val db = mb / 100f
        return String.format(java.util.Locale.US, "+%.1f dB", db)
    }

    fun formatShortLabel(percent: Int): String {
        val clamped = clampPercent(percent)
        return when {
            clamped == 0 -> "Muted"
            clamped > NORMAL_MAX_PERCENT -> "Boost $clamped%"
            else -> "$clamped%"
        }
    }
}

/**
 * Attaches Android's LoudnessEnhancer (+ optional API 28+ DynamicsProcessing soft-limiter)
 * to ExoPlayer's active audio session so volume can be amplified cleanly up to 200%
 * across Mobile, Tablet, and Android TV HDMI outputs without requiring extra permissions.
 */
class AudioVolumeBooster {

    private var currentSessionId: Int = 0
    private var currentPercent: Int = AudioBoostMath.NORMAL_MAX_PERCENT
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null

    @Synchronized
    fun bindAudioSession(audioSessionId: Int) {
        if (audioSessionId <= 0) return
        if (currentSessionId == audioSessionId && loudnessEnhancer != null) {
            applyCurrentSettingsLocked()
            return
        }
        releaseEffectsLocked()
        currentSessionId = audioSessionId
        if (currentPercent > AudioBoostMath.NORMAL_MAX_PERCENT) {
            createEffectsLocked(audioSessionId)
            applyCurrentSettingsLocked()
        }
    }

    @Synchronized
    fun applyBoost(percent: Int, fallbackAudioSessionId: Int = 0) {
        val clamped = AudioBoostMath.clampPercent(percent)
        currentPercent = clamped
        if (fallbackAudioSessionId > 0 && currentSessionId != fallbackAudioSessionId) {
            releaseEffectsLocked()
            currentSessionId = fallbackAudioSessionId
        }
        if (clamped > AudioBoostMath.NORMAL_MAX_PERCENT && currentSessionId > 0 && loudnessEnhancer == null) {
            createEffectsLocked(currentSessionId)
        }
        applyCurrentSettingsLocked()
    }

    private fun createEffectsLocked(sessionId: Int) {
        runCatching {
            loudnessEnhancer = LoudnessEnhancer(sessionId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    1,
                    false,
                    0,
                    false,
                    0,
                    false,
                    0,
                    true
                )
                    .setPreferredFrameDuration(10f)
                    .build()
                val dp = DynamicsProcessing(0, sessionId, config)
                val limiter = DynamicsProcessing.Limiter(
                    true,
                    true,
                    0,
                    2.0f,
                    60.0f,
                    6.0f,
                    -1.0f,
                    0.0f
                )
                dp.setLimiterAllChannelsTo(limiter)
                dynamicsProcessing = dp
            }
        }
    }

    private fun applyCurrentSettingsLocked() {
        val gainMb = AudioBoostMath.computeTargetGainMb(currentPercent)
        val boostActive = gainMb > 0
        runCatching {
            loudnessEnhancer?.apply {
                setTargetGain(gainMb)
                enabled = boostActive
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                dynamicsProcessing?.enabled = boostActive
            }
        }
    }

    private fun releaseEffectsLocked() {
        runCatching {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
        }
        loudnessEnhancer = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                dynamicsProcessing?.enabled = false
                dynamicsProcessing?.release()
            }
        }
        dynamicsProcessing = null
    }

    @Synchronized
    fun release() {
        releaseEffectsLocked()
        currentSessionId = 0
    }
}
