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
    private var nightModeEnabled: Boolean = false
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
        if (needsEffectsLocked()) {
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
        if (needsEffectsLocked() && currentSessionId > 0 && loudnessEnhancer == null) {
            createEffectsLocked(currentSessionId)
        }
        applyCurrentSettingsLocked()
    }

    @Synchronized
    fun setNightModeEnabled(enabled: Boolean, fallbackAudioSessionId: Int = 0) {
        nightModeEnabled = enabled
        if (fallbackAudioSessionId > 0 && currentSessionId != fallbackAudioSessionId) {
            releaseEffectsLocked()
            currentSessionId = fallbackAudioSessionId
        }
        if (needsEffectsLocked() && currentSessionId > 0 && loudnessEnhancer == null) {
            createEffectsLocked(currentSessionId)
        }
        applyCurrentSettingsLocked()
    }

    private fun needsEffectsLocked(): Boolean =
        currentPercent > AudioBoostMath.NORMAL_MAX_PERCENT || nightModeEnabled

    private fun createEffectsLocked(sessionId: Int) {
        runCatching {
            loudnessEnhancer = LoudnessEnhancer(sessionId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                // 4-band Pre-EQ + 1-band MBC + Limiter for clean Night Mode dialogue clarity + boost protection
                val config = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    1,
                    true,
                    4,
                    true,
                    1,
                    false,
                    0,
                    true
                )
                    .setPreferredFrameDuration(10f)
                    .build()
                val dp = DynamicsProcessing(0, sessionId, config)
                dynamicsProcessing = dp
            }
        }
    }

    private fun applyCurrentSettingsLocked() {
        val boostGainMb = AudioBoostMath.computeTargetGainMb(currentPercent)
        // Pre-API 28 fallback: gentle +3.5 dB loudness boost in Night Mode so dialogue stays audible at low volume
        val nightFallbackMb = if (nightModeEnabled && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) 350 else 0
        val effectiveGainMb = (boostGainMb + nightFallbackMb).coerceAtMost(AudioBoostMath.MAX_GAIN_MB)
        val boostActive = effectiveGainMb > 0
        runCatching {
            loudnessEnhancer?.apply {
                setTargetGain(effectiveGainMb)
                enabled = boostActive
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val dp = dynamicsProcessing ?: return@runCatching
                val preEq = DynamicsProcessing.Eq(true, nightModeEnabled, 4).apply {
                    // Roll off rumbling sub-bass explosions, lift 1–4 kHz human dialogue presence
                    setBand(0, DynamicsProcessing.EqBand(true, 120f, -4.5f))
                    setBand(1, DynamicsProcessing.EqBand(true, 500f, 0.0f))
                    setBand(2, DynamicsProcessing.EqBand(true, 2200f, 4.5f))
                    setBand(3, DynamicsProcessing.EqBand(true, 8000f, -1.5f))
                }
                dp.setPreEqAllChannelsTo(preEq)

                val mbc = DynamicsProcessing.Mbc(true, nightModeEnabled, 1).apply {
                    val band = DynamicsProcessing.MbcBand(
                        true,
                        20_000f,
                        15f,   // attack ms
                        180f,  // release ms
                        4.5f,  // compression ratio (tames loud explosions)
                        -22f,  // threshold dB
                        6f,    // knee width dB
                        -60f,  // noise gate threshold
                        1.0f,  // expander ratio
                        2.5f,  // pre-gain dB
                        1.5f   // post-gain dB
                    )
                    setBand(0, band)
                }
                dp.setMbcAllChannelsTo(mbc)

                val limiter = DynamicsProcessing.Limiter(
                    true,
                    boostActive || nightModeEnabled,
                    0,
                    2.0f,
                    60.0f,
                    if (nightModeEnabled) 8.0f else 6.0f,
                    if (nightModeEnabled) -3.0f else -1.0f,
                    0.0f
                )
                dp.setLimiterAllChannelsTo(limiter)
                dp.enabled = boostActive || nightModeEnabled
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
