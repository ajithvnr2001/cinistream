package com.example.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.abs

/**
 * Pure clamping and formatting math for real-time Bluetooth / Soundbar Audio Delay Sync
 * (-500 ms to +500 ms in 25 ms / 50 ms steps).
 */
object AudioDelayMath {
    const val MIN_DELAY_MS = -500L
    const val MAX_DELAY_MS = 500L
    const val FINE_STEP_MS = 25L
    const val COARSE_STEP_MS = 50L

    fun clampDelayMs(delayMs: Long): Long = delayMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)

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

    fun formatDelayLabel(delayMs: Long): String {
        val clamped = clampDelayMs(delayMs)
        return when {
            clamped == 0L -> "0 ms"
            clamped > 0L -> "+$clamped ms"
            else -> "$clamped ms"
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

    private const val MAX_RING_BUFFER_BYTES = 4 * 1024 * 1024
}

/**
 * Zero-reload real-time PCM ring-buffer delay processor plugged into ExoPlayer's audio sink.
 *
 * - Positive [delayMs] (+25..+500 ms): delays PCM output smoothly through a lock-free ring buffer,
 *   outputting silence during initial ramp-up and shifting audio later to match delayed video.
 * - Zero or negative [delayMs]: passes PCM through with zero added audio latency while the player
 *   coordinates subtitle/video sync compensation.
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
