package com.example.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class CapturedAudioClip(
    val wavBytes: ByteArray,
    val startPositionMs: Long,
    val endPositionMs: Long,
    val durationSec: Float,
    val rmsEnergy: Float
) {
    val hasAudibleSignal: Boolean
        get() = rmsEnergy >= 0.0018f && durationSec >= 1.5f
}

/**
 * Non-destructive ExoPlayer AudioProcessor tee that passes PCM audio straight through to the
 * output sink while recording a mono 16 kHz 16-bit PCM rolling window for AI English subtitle
 * generation across MKV, MP4, M3U8, HLS, DASH, and WebM streams (no microphone permission needed).
 */
@OptIn(UnstableApi::class)
class PcmAudioCaptureProcessor : AudioProcessor {

    companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        private const val MAX_BUFFER_SECONDS = 25
        private const val MAX_SAMPLES = TARGET_SAMPLE_RATE * MAX_BUFFER_SECONDS
    }

    private var inputFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    private val lock = Any()
    private val ringBuffer = ShortArray(MAX_SAMPLES)
    private var writeIndex = 0
    private var totalBufferedSamples = 0
    private var fractionalSampleCarry = 0.0

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val supported = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_24BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_32BIT
        if (!supported || inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount <= 0) {
            inputFormat = AudioFormat.NOT_SET
            return AudioFormat.NOT_SET
        }
        inputFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean = inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        // Capture PCM directly without allocating a ByteBuffer.duplicate() object on every callback
        val savedPosition = inputBuffer.position()
        val savedOrder = inputBuffer.order()
        try {
            inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
            capturePcmSamples(inputBuffer, inputFormat)
        } finally {
            inputBuffer.position(savedPosition)
            inputBuffer.order(savedOrder)
        }

        val remaining = inputBuffer.remaining()
        if (outputBuffer.capacity() < remaining) {
            outputBuffer = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val out = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return out
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        flush()
        inputFormat = AudioFormat.NOT_SET
        clearCapturedAudio()
    }

    fun clearCapturedAudio() {
        synchronized(lock) {
            writeIndex = 0
            totalBufferedSamples = 0
            fractionalSampleCarry = 0.0
        }
    }

    fun bufferedDurationSec(): Float {
        synchronized(lock) {
            return totalBufferedSamples.toFloat() / TARGET_SAMPLE_RATE.toFloat()
        }
    }

    /**
     * Extracts up to [requestedSeconds] of the most recently played audio as a 16 kHz mono WAV clip.
     */
    fun snapshotWavClip(
        requestedSeconds: Int,
        currentPlaybackPositionMs: Long
    ): CapturedAudioClip? {
        val samples: ShortArray
        synchronized(lock) {
            val wanted = (requestedSeconds.coerceIn(2, MAX_BUFFER_SECONDS) * TARGET_SAMPLE_RATE)
            val available = minOf(totalBufferedSamples, wanted)
            if (available < TARGET_SAMPLE_RATE) return null

            samples = ShortArray(available)
            val startIdx = (writeIndex - available + MAX_SAMPLES) % MAX_SAMPLES
            for (i in 0 until available) {
                samples[i] = ringBuffer[(startIdx + i) % MAX_SAMPLES]
            }
        }

        var sumSquares = 0.0
        for (s in samples) {
            val norm = s / 32768.0
            sumSquares += norm * norm
        }
        val rms = sqrt(sumSquares / samples.size.coerceAtLeast(1)).toFloat()
        val durationSec = samples.size.toFloat() / TARGET_SAMPLE_RATE.toFloat()
        val durationMs = (durationSec * 1000f).toLong()
        val endPos = currentPlaybackPositionMs.coerceAtLeast(durationMs)
        val startPos = (endPos - durationMs).coerceAtLeast(0L)

        return CapturedAudioClip(
            wavBytes = WavEncoder.encodeWav(samples, TARGET_SAMPLE_RATE),
            startPositionMs = startPos,
            endPositionMs = endPos,
            durationSec = durationSec,
            rmsEnergy = rms
        )
    }

    private fun capturePcmSamples(buffer: ByteBuffer, format: AudioFormat) {
        val channels = format.channelCount
        val bytesPerSample = when (format.encoding) {
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> 4
            C.ENCODING_PCM_24BIT -> 3
            else -> 2
        }
        val frameSize = channels * bytesPerSample
        if (frameSize <= 0) return

        val frameCount = buffer.remaining() / frameSize
        if (frameCount <= 0) return

        val step = format.sampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
        // For 5.1/7.1 surround movies, normalize with sqrt(channels) so center-channel dialogue stays clear
        val channelDivisor = if (channels > 2) sqrt(channels.toDouble()).toFloat() else channels.toFloat()

        synchronized(lock) {
            var pos = fractionalSampleCarry
            while (pos < frameCount) {
                val frameIdx = pos.toInt()
                val byteOffset = buffer.position() + frameIdx * frameSize
                var monoAccum = 0f

                for (ch in 0 until channels) {
                    val sampleOffset = byteOffset + ch * bytesPerSample
                    monoAccum += when (format.encoding) {
                        C.ENCODING_PCM_FLOAT -> buffer.getFloat(sampleOffset).coerceIn(-1f, 1f)
                        C.ENCODING_PCM_32BIT -> buffer.getInt(sampleOffset) / 2147483648f
                        C.ENCODING_PCM_24BIT -> {
                            val b0 = buffer.get(sampleOffset).toInt() and 0xFF
                            val b1 = buffer.get(sampleOffset + 1).toInt() and 0xFF
                            val b2 = buffer.get(sampleOffset + 2).toInt() // sign-extended MSB
                            val sample24 = (b2 shl 16) or (b1 shl 8) or b0
                            sample24 / 8388608f
                        }
                        else -> buffer.getShort(sampleOffset) / 32768f
                    }
                }

                val monoFloat = (monoAccum / channelDivisor).coerceIn(-1f, 1f)
                val pcm16 = (monoFloat * 32767f).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()

                ringBuffer[writeIndex] = pcm16
                writeIndex = (writeIndex + 1) % MAX_SAMPLES
                if (totalBufferedSamples < MAX_SAMPLES) {
                    totalBufferedSamples++
                }
                pos += step
            }
            fractionalSampleCarry = pos - frameCount
        }
    }

    object WavEncoder {
        fun encodeWav(samples: ShortArray, sampleRate: Int): ByteArray {
            val dataSize = samples.size * 2
            val totalFileSize = 36 + dataSize
            val wav = ByteArray(44 + dataSize)
            val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)

            // RIFF / WAVE / fmt / data header written straight into the final ByteArray (zero extra copies)
            wav[0] = 'R'.code.toByte()
            wav[1] = 'I'.code.toByte()
            wav[2] = 'F'.code.toByte()
            wav[3] = 'F'.code.toByte()
            bb.putInt(4, totalFileSize)
            wav[8] = 'W'.code.toByte()
            wav[9] = 'A'.code.toByte()
            wav[10] = 'V'.code.toByte()
            wav[11] = 'E'.code.toByte()
            wav[12] = 'f'.code.toByte()
            wav[13] = 'm'.code.toByte()
            wav[14] = 't'.code.toByte()
            wav[15] = ' '.code.toByte()
            bb.putInt(16, 16)            // PCM chunk size
            bb.putShort(20, 1)           // AudioFormat = 1 (PCM)
            bb.putShort(22, 1)           // NumChannels = 1 (Mono)
            bb.putInt(24, sampleRate)    // SampleRate
            bb.putInt(28, sampleRate * 2)// ByteRate
            bb.putShort(32, 2)           // BlockAlign
            bb.putShort(34, 16)          // BitsPerSample
            wav[36] = 'd'.code.toByte()
            wav[37] = 'a'.code.toByte()
            wav[38] = 't'.code.toByte()
            wav[39] = 'a'.code.toByte()
            bb.putInt(40, dataSize)

            bb.position(44)
            for (s in samples) {
                bb.putShort(s)
            }
            return wav
        }
    }
}
