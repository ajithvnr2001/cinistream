package com.example.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

enum class SyncTimeUnit(val label: String) {
    MILLISECONDS("ms"),
    SECONDS("s")
}

/**
 * Pure clamping, parsing, and formatting math for real-time bidirectional Audio & Subtitle Sync
 * (-10.0 s to +10.0 s in ms or s numeric input, plus quick ±25 ms / ±50 ms steps).
 */
object AudioDelayMath {
    const val MIN_DELAY_MS = -10_000L
    const val MAX_DELAY_MS = 10_000L
    const val FINE_STEP_MS = 25L
    const val COARSE_STEP_MS = 50L

    fun clampDelayMs(delayMs: Long): Long = delayMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)

    /**
     * Parses a user-entered numeric delay string in either milliseconds or seconds.
     * Supports optional leading +/-, decimal dots or commas, and optional inline "ms" / "s" suffix
     * (which overrides [defaultUnit]). Returns null if blank or not a valid finite number.
     */
    fun parseDelayInputMs(rawInput: String, defaultUnit: SyncTimeUnit): Long? {
        val cleaned = rawInput.trim().replace(',', '.')
        if (cleaned.isEmpty()) return null

        val lower = cleaned.lowercase(Locale.US)
        val (numberPart, effectiveUnit) = when {
            lower.endsWith("ms") -> cleaned.dropLast(2).trim() to SyncTimeUnit.MILLISECONDS
            lower.endsWith("s") -> cleaned.dropLast(1).trim() to SyncTimeUnit.SECONDS
            else -> cleaned to defaultUnit
        }

        val parsed = numberPart.toDoubleOrNull() ?: return null
        if (!parsed.isFinite()) return null

        val msValue = when (effectiveUnit) {
            SyncTimeUnit.MILLISECONDS -> parsed.roundToLong()
            SyncTimeUnit.SECONDS -> (parsed * 1000.0).roundToLong()
        }
        return clampDelayMs(msValue)
    }

    /**
     * Formats a clamped millisecond offset into a clean editable number string for [unit].
     */
    fun formatNumericInput(delayMs: Long, unit: SyncTimeUnit): String {
        val clamped = clampDelayMs(delayMs)
        return when (unit) {
            SyncTimeUnit.MILLISECONDS -> clamped.toString()
            SyncTimeUnit.SECONDS -> {
                if (clamped % 1000L == 0L) {
                    (clamped / 1000L).toString()
                } else {
                    String.format(Locale.US, "%.3f", clamped / 1000.0)
                        .trimEnd('0')
                        .trimEnd('.')
                }
            }
        }
    }

    fun computeDelayBytes(
        delayMs: Long,
        sampleRateHz: Int,
        bytesPerFrame: Int
    ): Int {
        if (delayMs <= 0L || sampleRateHz <= 0 || bytesPerFrame <= 0) return 0
        val frames = ((sampleRateHz.toLong() * delayMs) / 1000L).coerceAtLeast(0L)
        val rawBytes = frames * bytesPerFrame.toLong()
        return rawBytes.coerceAtMost(MAX_RING_BUFFER_BYTES.toLong()).toInt()
    }

    /**
     * Computes the exact underlying MediaPeriod seek position for [AudioSyncMediaSource] so
     * video stays anchored on [currentPositionMs] when applying [audioDelayMs]:
     * - Positive delay (+D): seek back by D so audio starting at `pos - D` is shifted to `pos`.
     * - Zero or negative delay (-D): seek to `pos` so MediaCodecAudioRenderer discards shifted
     *   samples `< pos` and starts audio cleanly from original `pos + |D|` at `pos`.
     */
    fun computeRealignSeekPositionMs(
        currentPositionMs: Long,
        audioDelayMs: Long,
        durationMs: Long? = null
    ): Long {
        val clampedPos = currentPositionMs.coerceAtLeast(0L)
        val clampedDelay = clampDelayMs(audioDelayMs)
        val rawTarget = if (clampedDelay > 0L) {
            (clampedPos - clampedDelay).coerceAtLeast(0L)
        } else {
            clampedPos
        }
        return if (durationMs != null && durationMs > 0L) {
            rawTarget.coerceAtMost(durationMs)
        } else {
            rawTarget
        }
    }

    fun formatDelayLabel(delayMs: Long): String {
        val clamped = clampDelayMs(delayMs)
        if (clamped == 0L) return "0 ms"
        val sign = if (clamped > 0L) "+" else "-"
        val absMs = abs(clamped)
        return if (absMs < 1000L) {
            "$sign$absMs ms"
        } else {
            String.format(Locale.US, "%s%.2f s", sign, absMs / 1000.0)
        }
    }

    fun formatSubtitleOffsetLabel(offsetMs: Long): String {
        val sign = if (offsetMs > 0L) "+" else if (offsetMs < 0L) "-" else ""
        val absMs = abs(offsetMs)
        return if (absMs % 1000L == 0L) {
            String.format(Locale.US, "%s%.1fs", sign, absMs / 1000.0)
        } else {
            String.format(Locale.US, "%s%.2fs", sign, absMs / 1000.0)
        }
    }

    private const val MAX_RING_BUFFER_BYTES = 12 * 1024 * 1024
}

/**
 * Bidirectional MediaClock audio-sync wrapper around [DefaultAudioSink]:
 *
 * - Positive delay (+1 ms..+10 s): handled by [DelayAudioProcessor] delaying PCM samples later
 *   while keeping the reported clock untouched.
 * - Negative delay (-1 ms..-10 s): shifts [getCurrentPositionUs] earlier by `abs(delayMs)` so
 *   ExoPlayer's video renderer holds frames slightly later, pulling audio earlier relative to
 *   lip movement in real time with zero reload on both HW MediaCodec and FFmpeg audio renderers.
 */
@OptIn(UnstableApi::class)
class SyncOffsetAudioSink(
    private val delegate: AudioSink,
    private val delayMsProvider: () -> Long
) : AudioSink by delegate {

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val rawPositionUs = delegate.getCurrentPositionUs(sourceEnded)
        if (rawPositionUs == AudioSink.CURRENT_POSITION_NOT_SET) {
            return rawPositionUs
        }
        val delayMs = AudioDelayMath.clampDelayMs(delayMsProvider())
        return if (delayMs < 0L) {
            (rawPositionUs + delayMs * 1_000L).coerceAtLeast(0L)
        } else {
            rawPositionUs
        }
    }
}

/**
 * Zero-reload real-time PCM ring-buffer delay processor plugged into ExoPlayer's audio sink.
 *
 * - Positive [delayMs] (+1 ms..+10 s): delays PCM output smoothly through a lock-free ring buffer,
 *   outputting silence during initial ramp-up and shifting audio later to match delayed video.
 * - Zero or negative [delayMs]: passes PCM through with zero added audio latency while
 *   [SyncOffsetAudioSink] pulls the A/V presentation clock earlier so audio plays ahead of video.
 */
@OptIn(UnstableApi::class)
class DelayAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var requestedDelayMs: Long = 0L

    private var appliedDelayBytes: Int = 0
    private var ringBuffer: ByteArray = ByteArray(0)
    private var writePos: Int = 0
    private var readPos: Int = 0
    private var bufferedBytes: Int = 0

    fun getDelayMs(): Long = requestedDelayMs

    fun setDelayMs(delayMs: Long) {
        requestedDelayMs = AudioDelayMath.clampDelayMs(delayMs)
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val positiveDelayMs = requestedDelayMs.coerceAtLeast(0L)
        val targetDelayBytes = AudioDelayMath.computeDelayBytes(
            delayMs = positiveDelayMs,
            sampleRateHz = inputAudioFormat.sampleRate,
            bytesPerFrame = inputAudioFormat.bytesPerFrame
        )

        if (targetDelayBytes <= 0) {
            if (appliedDelayBytes > 0) {
                resetRing()
            }
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }

        if (targetDelayBytes != appliedDelayBytes || ringBuffer.size < targetDelayBytes) {
            reallocateRing(targetDelayBytes)
        }

        val out = replaceOutputBuffer(remaining)
        var toProcess = remaining
        while (toProcess > 0) {
            val byteVal = inputBuffer.get()
            if (bufferedBytes < appliedDelayBytes) {
                ringBuffer[writePos] = byteVal
                writePos = (writePos + 1) % appliedDelayBytes
                bufferedBytes++
                out.put(0.toByte())
            } else {
                val delayed = ringBuffer[readPos]
                ringBuffer[writePos] = byteVal
                readPos = (readPos + 1) % appliedDelayBytes
                writePos = (writePos + 1) % appliedDelayBytes
                out.put(delayed)
            }
            toProcess--
        }
        out.flip()
    }

    override fun onFlush() {
        resetRing()
    }

    override fun onReset() {
        resetRing()
        ringBuffer = ByteArray(0)
    }

    private fun reallocateRing(targetBytes: Int) {
        ringBuffer = ByteArray(targetBytes)
        appliedDelayBytes = targetBytes
        writePos = 0
        readPos = 0
        bufferedBytes = 0
    }

    private fun resetRing() {
        appliedDelayBytes = 0
        writePos = 0
        readPos = 0
        bufferedBytes = 0
    }
}
