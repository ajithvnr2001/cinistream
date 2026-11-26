package com.example

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.FixedTrackSelection
import com.example.player.AudioDelayMath
import com.example.player.AudioOffsetSampleStream
import com.example.player.AudioSyncMediaPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class HlsAudioSyncSampleStreamTest {

    private class FakeSampleStream(
        private val sampleTimestampsUs: MutableList<Long>,
        private val emitEndOfStream: Boolean = true
    ) : SampleStream {
        var lastSkipPositionUs: Long? = null
        private var index = 0

        override fun isReady(): Boolean = true

        override fun maybeThrowError() = Unit

        override fun readData(
            formatHolder: FormatHolder,
            buffer: DecoderInputBuffer,
            readFlags: Int
        ): Int {
            if (index >= sampleTimestampsUs.size) {
                return if (emitEndOfStream) {
                    buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                    C.RESULT_BUFFER_READ
                } else {
                    C.RESULT_NOTHING_READ
                }
            }
            buffer.clear()
            buffer.timeUs = sampleTimestampsUs[index]
            if ((readFlags and SampleStream.FLAG_PEEK) == 0) {
                index++
            }
            return C.RESULT_BUFFER_READ
        }

        override fun skipData(positionUs: Long): Int {
            lastSkipPositionUs = positionUs
            var skipped = 0
            while (index < sampleTimestampsUs.size && sampleTimestampsUs[index] < positionUs) {
                index++
                skipped++
            }
            return skipped
        }
    }

    private class FakeHlsMediaPeriod(
        private val videoStream: SampleStream,
        private val audioStream: SampleStream,
        private val textStream: SampleStream
    ) : MediaPeriod {
        var lastDiscardedPositionUs: Long? = null
        var rawBufferedPositionUs: Long = 66_000_000L
        var sawUnwrappedAudioStreamOnSecondSelect: Boolean = false
        private var selectCalls = 0

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            callback.onPrepared(this)
        }

        override fun maybeThrowPrepareError() = Unit

        override fun getTrackGroups(): TrackGroupArray = TrackGroupArray.EMPTY

        override fun getStreamKeys(trackSelections: MutableList<ExoTrackSelection>): MutableList<StreamKey> =
            mutableListOf()

        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long
        ): Long {
            selectCalls++
            if (selectCalls >= 2) {
                // Verify AudioSyncMediaPeriod unwrapped AudioOffsetSampleStream before delegating
                sawUnwrappedAudioStreamOnSecondSelect = streams[1] === audioStream
            }
            streams[0] = if (selections[0] != null) videoStream else null
            streams[1] = if (selections[1] != null) audioStream else null
            streams[2] = if (selections[2] != null) textStream else null
            return positionUs
        }

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
            lastDiscardedPositionUs = positionUs
        }

        override fun readDiscontinuity(): Long = C.TIME_UNSET

        override fun seekToUs(positionUs: Long): Long = positionUs

        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
            positionUs

        override fun getBufferedPositionUs(): Long = rawBufferedPositionUs

        override fun getNextLoadPositionUs(): Long = rawBufferedPositionUs

        override fun continueLoading(loadingInfo: LoadingInfo): Boolean = false

        override fun isLoading(): Boolean = false

        override fun reevaluateBuffer(positionUs: Long) = Unit
    }

    @Test
    fun audioOffsetSampleStream_shiftsPositiveAndNegativeHlsAudioSamplesCleanly() {
        val fake = FakeSampleStream(
            mutableListOf(0L, 1_000_000L, 2_000_000L, 3_500_000L, 5_000_000L)
        )
        var delayUs = 2_000_000L // +2.0s delay
        val wrapped = AudioOffsetSampleStream(fake) { delayUs }
        val holder = FormatHolder()
        val buf = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)

        // Positive delay (+2s): original 0s -> 2.0s, original 1s -> 3.0s
        assertEquals(C.RESULT_BUFFER_READ, wrapped.readData(holder, buf, 0))
        assertEquals(2_000_000L, buf.timeUs)

        assertEquals(C.RESULT_BUFFER_READ, wrapped.readData(holder, buf, 0))
        assertEquals(3_000_000L, buf.timeUs)

        // Negative delay (-2.5s): original 2.0s -> -0.5s (dropped cleanly), original 3.5s -> 1.0s
        delayUs = -2_500_000L
        assertEquals(C.RESULT_BUFFER_READ, wrapped.readData(holder, buf, 0))
        assertFalse(buf.isEndOfStream)
        assertEquals(1_000_000L, buf.timeUs)

        // Next sample: original 5.0s -> 2.5s
        assertEquals(C.RESULT_BUFFER_READ, wrapped.readData(holder, buf, 0))
        assertEquals(2_500_000L, buf.timeUs)

        // End of stream preserved untouched
        assertEquals(C.RESULT_BUFFER_READ, wrapped.readData(holder, buf, 0))
        assertTrue(buf.isEndOfStream)
    }

    @Test
    fun audioOffsetSampleStream_skipDataTranslatesBackToOriginalTimeline() {
        val fake = FakeSampleStream(mutableListOf(58_000_000L, 59_000_000L, 60_000_000L, 62_000_000L))
        var delayUs = 2_000_000L // +2.0s
        val wrapped = AudioOffsetSampleStream(fake) { delayUs }

        // Shifted 60.0s corresponds to original 58.0s
        wrapped.skipData(60_000_000L)
        assertEquals(58_000_000L, fake.lastSkipPositionUs)

        // Negative delay (-2.0s): shifted 60.0s corresponds to original 62.0s
        delayUs = -2_000_000L
        wrapped.skipData(60_000_000L)
        assertEquals(62_000_000L, fake.lastSkipPositionUs)
    }

    @Test
    fun audioSyncMediaPeriod_wrapsOnlyAudioAndUnwrapsOnTrackReselection() {
        val videoStream = FakeSampleStream(mutableListOf(10_000_000L))
        val audioStream = FakeSampleStream(mutableListOf(10_000_000L))
        val textStream = FakeSampleStream(mutableListOf(10_000_000L))
        val fakePeriod = FakeHlsMediaPeriod(videoStream, audioStream, textStream)

        var delayUs = 1_500_000L
        val syncPeriod = AudioSyncMediaPeriod(fakePeriod) { delayUs }

        val videoSelection = FixedTrackSelection(
            TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()),
            0
        )
        val audioSelection = FixedTrackSelection(
            TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()),
            0
        )
        val textSelection = FixedTrackSelection(
            TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build()),
            0
        )

        val selections = arrayOf<ExoTrackSelection?>(videoSelection, audioSelection, textSelection)
        val retain = booleanArrayOf(false, false, false)
        val streams = arrayOfNulls<SampleStream>(3)
        val resets = booleanArrayOf(false, false, false)

        syncPeriod.selectTracks(selections, retain, streams, resets, 0L)
        assertSame(videoStream, streams[0])
        assertTrue(streams[1] is AudioOffsetSampleStream)
        assertSame(textStream, streams[2])
        val firstAudioWrapper = streams[1]

        // Re-selecting tracks must unwrap before calling delegate and reuse the wrapper instance
        syncPeriod.selectTracks(selections, booleanArrayOf(true, true, true), streams, resets, 0L)
        assertTrue(fakePeriod.sawUnwrappedAudioStreamOnSecondSelect)
        assertSame(firstAudioWrapper, streams[1])

        // discardBuffer subtracts positive delay so delayed audio segments are never prematurely discarded
        syncPeriod.discardBuffer(60_000_000L, false)
        assertEquals(58_500_000L, fakePeriod.lastDiscardedPositionUs)

        // getBufferedPositionUs adjusts for negative delay so LoadControl keeps prefetching audio segments
        delayUs = -4_000_000L
        fakePeriod.rawBufferedPositionUs = 66_000_000L
        assertEquals(62_000_000L, syncPeriod.bufferedPositionUs)

        // TIME_END_OF_SOURCE must stay intact so streams terminate cleanly
        fakePeriod.rawBufferedPositionUs = C.TIME_END_OF_SOURCE
        assertEquals(C.TIME_END_OF_SOURCE, syncPeriod.bufferedPositionUs)
    }

    @Test
    fun computeRealignSeekPositionMs_handlesPositiveNegativeAndBounds() {
        assertEquals(57_500L, AudioDelayMath.computeRealignSeekPositionMs(60_000L, 2_500L, 120_000L))
        assertEquals(0L, AudioDelayMath.computeRealignSeekPositionMs(1_000L, 2_500L, 120_000L))
        assertEquals(60_000L, AudioDelayMath.computeRealignSeekPositionMs(60_000L, -2_500L, 120_000L))
        assertEquals(60_000L, AudioDelayMath.computeRealignSeekPositionMs(60_000L, 0L, 120_000L))
    }
}
