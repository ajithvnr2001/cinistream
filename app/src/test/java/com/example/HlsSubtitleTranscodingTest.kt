package com.example

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.test.core.app.ApplicationProvider
import com.example.player.SubtitleMediaSourceBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HlsSubtitleTranscodingTest {

    @Test
    fun subtitleMediaSourceBuilder_usesProgressiveSubtitleExtractorForSrtVttAssAndTtml() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dsFactory = DefaultDataSource.Factory(ctx)

        listOf(
            MimeTypes.APPLICATION_SUBRIP to "sub.srt",
            MimeTypes.TEXT_VTT to "sub.vtt",
            MimeTypes.TEXT_SSA to "sub.ass",
            MimeTypes.APPLICATION_TTML to "sub.ttml"
        ).forEach { (mime, filename) ->
            assertTrue(SubtitleMediaSourceBuilder.supportsExtractionTranscoding(mime))
            val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse("https://cdn.example/$filename"))
                .setId("id_$filename")
                .setMimeType(mime)
                .setLanguage("en")
                .setLabel("English [$filename]")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()

            val source = SubtitleMediaSourceBuilder.buildMediaSource(subConfig, dsFactory)
            assertTrue(
                "Expected ProgressiveMediaSource for $mime so Media3 1.11.1 transcodes to x-media3-cues",
                source is ProgressiveMediaSource
            )
        }
    }

    @Test
    fun subtitleMediaSourceBuilder_fallsBackToSingleSampleForUnsupportedMime() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dsFactory = DefaultDataSource.Factory(ctx)
        assertFalse(SubtitleMediaSourceBuilder.supportsExtractionTranscoding(MimeTypes.APPLICATION_CEA608))

        val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse("https://cdn.example/cc.bin"))
            .setMimeType(MimeTypes.APPLICATION_CEA608)
            .build()

        val source = SubtitleMediaSourceBuilder.buildMediaSource(subConfig, dsFactory)
        assertTrue(source is SingleSampleMediaSource)
    }

    @Test
    fun subtitleExtractor_transcodesSrtAndVttToApplicationMedia3Cues() {
        val parserFactory = DefaultSubtitleParserFactory()

        val srtBytes = """
            1
            00:00:01,000 --> 00:00:03,500
            Welcome to CineStream v1.28

        """.trimIndent().toByteArray(Charsets.UTF_8)

        val srtFormat = Format.Builder()
            .setId("sub_srt_1")
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .setLabel("English [SRT]")
            .setLanguage("en")
            .build()

        val srtResult = runSubtitleExtractor(parserFactory, srtFormat, srtBytes)
        val emittedSrtFormat = srtResult.emittedFormat
        assertNotNull(emittedSrtFormat)
        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, emittedSrtFormat!!.sampleMimeType)
        assertEquals(MimeTypes.APPLICATION_SUBRIP, emittedSrtFormat.codecs)
        assertEquals("English [SRT]", emittedSrtFormat.label)
        assertTrue("Expected non-empty cue sample bytes", srtResult.sampleByteCount > 0)

        val vttBytes = """
            WEBVTT

            00:02.000 --> 00:04.500
            Streaming HLS external VTT captions

        """.trimIndent().toByteArray(Charsets.UTF_8)

        val vttFormat = Format.Builder()
            .setId("sub_vtt_1")
            .setSampleMimeType(MimeTypes.TEXT_VTT)
            .setLabel("English [VTT]")
            .setLanguage("en")
            .build()

        val vttResult = runSubtitleExtractor(parserFactory, vttFormat, vttBytes)
        val emittedVttFormat = vttResult.emittedFormat
        assertNotNull(emittedVttFormat)
        assertEquals(MimeTypes.APPLICATION_MEDIA3_CUES, emittedVttFormat!!.sampleMimeType)
        assertEquals(MimeTypes.TEXT_VTT, emittedVttFormat.codecs)
        assertTrue("Expected non-empty VTT cue sample bytes", vttResult.sampleByteCount > 0)
    }

    @Test
    fun subtitleMediaSourceBuilder_parsesLocalFileViaProgressiveMediaSourceForHlsMerging() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val subFile = File(ctx.cacheDir, "hls_test_external.srt").apply {
            writeText(
                """
                1
                00:00:00,500 --> 00:00:02,500
                HLS external caption test
                """.trimIndent()
            )
        }
        try {
            val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subFile))
                .setId("hls_ext_1")
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLabel("hls_test_external.srt [SRT]")
                .setLanguage("und")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            val dsFactory = DefaultDataSource.Factory(ctx)
            val source = SubtitleMediaSourceBuilder.buildMediaSource(subConfig, dsFactory)
            assertTrue(source is ProgressiveMediaSource)
            assertEquals(Uri.fromFile(subFile), source.mediaItem.localConfiguration?.uri)
        } finally {
            subFile.delete()
        }
    }

    @Test
    fun subtitleMediaSourceBuilder_formatsTranscodedAndRawCodecLabels() {
        assertEquals(
            "SRT",
            SubtitleMediaSourceBuilder.formatSubtitleCodecLabel(
                MimeTypes.APPLICATION_MEDIA3_CUES,
                MimeTypes.APPLICATION_SUBRIP
            )
        )
        assertEquals(
            "VTT",
            SubtitleMediaSourceBuilder.formatSubtitleCodecLabel(
                MimeTypes.APPLICATION_MEDIA3_CUES,
                MimeTypes.TEXT_VTT
            )
        )
        assertEquals(
            "ASS",
            SubtitleMediaSourceBuilder.formatSubtitleCodecLabel(MimeTypes.TEXT_SSA, null)
        )
        assertEquals(
            "TTML",
            SubtitleMediaSourceBuilder.formatSubtitleCodecLabel(MimeTypes.APPLICATION_TTML, null)
        )
        assertEquals(
            "CEA-608",
            SubtitleMediaSourceBuilder.formatSubtitleCodecLabel(MimeTypes.APPLICATION_CEA608, null)
        )
        assertEquals(
            "TEXT",
            SubtitleMediaSourceBuilder.formatSubtitleCodecLabel(null, null)
        )
    }

    @Test
    fun appVersionConfig_matchesV35Release() {
        assertEquals(35, BuildConfig.VERSION_CODE)
        assertEquals("1.35", BuildConfig.VERSION_NAME)
    }

    private data class ExtractedSubResult(
        val emittedFormat: Format?,
        val sampleByteCount: Int
    )

    private fun runSubtitleExtractor(
        parserFactory: DefaultSubtitleParserFactory,
        format: Format,
        data: ByteArray
    ): ExtractedSubResult {
        val extractor = SubtitleExtractor(parserFactory.create(format), format)
        var capturedFormat: Format? = null
        var bytesWritten = 0

        val trackOutput = object : TrackOutput {
            override fun format(format: Format) {
                capturedFormat = format
            }

            override fun sampleData(
                input: androidx.media3.common.DataReader,
                length: Int,
                allowEndOfInput: Boolean,
                sampleDataPart: Int
            ): Int {
                val buf = ByteArray(length)
                val read = input.read(buf, 0, length)
                if (read > 0) bytesWritten += read
                return read
            }

            override fun sampleData(
                data: androidx.media3.common.util.ParsableByteArray,
                length: Int,
                sampleDataPart: Int
            ) {
                bytesWritten += length
                data.skipBytes(length)
            }

            override fun sampleMetadata(
                timeUs: Long,
                flags: Int,
                size: Int,
                offset: Int,
                cryptoData: TrackOutput.CryptoData?
            ) = Unit
        }

        val extractorOutput = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = trackOutput
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) = Unit
        }

        val input = ByteArrayExtractorInput(data)
        assertTrue(extractor.sniff(input))
        input.resetPeekPosition()
        extractor.init(extractorOutput)

        val positionHolder = PositionHolder()
        var iterations = 0
        while (iterations < 32) {
            val res = extractor.read(input, positionHolder)
            if (res == Extractor.RESULT_END_OF_INPUT) break
            iterations++
        }
        extractor.release()
        return ExtractedSubResult(capturedFormat, bytesWritten)
    }

    private class ByteArrayExtractorInput(private val bytes: ByteArray) : ExtractorInput {
        private var pos = 0
        private var peekPos = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val available = bytes.size - pos
            if (available <= 0) return androidx.media3.common.C.RESULT_END_OF_INPUT
            val toRead = minOf(length, available)
            System.arraycopy(bytes, pos, buffer, offset, toRead)
            pos += toRead
            peekPos = pos
            return toRead
        }

        override fun readFully(buffer: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (bytes.size - pos < length) {
                if (allowEndOfInput) return false
                throw java.io.EOFException()
            }
            System.arraycopy(bytes, pos, buffer, offset, length)
            pos += length
            peekPos = pos
            return true
        }

        override fun readFully(buffer: ByteArray, offset: Int, length: Int) {
            readFully(buffer, offset, length, false)
        }

        override fun skip(length: Int): Int {
            val available = bytes.size - pos
            if (available <= 0) return androidx.media3.common.C.RESULT_END_OF_INPUT
            val skipped = minOf(length, available)
            pos += skipped
            peekPos = pos
            return skipped
        }

        override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
            if (bytes.size - pos < length) {
                if (allowEndOfInput) return false
                throw java.io.EOFException()
            }
            pos += length
            peekPos = pos
            return true
        }

        override fun skipFully(length: Int) {
            skipFully(length, false)
        }

        override fun peek(buffer: ByteArray, offset: Int, length: Int): Int {
            val available = bytes.size - peekPos
            if (available <= 0) return androidx.media3.common.C.RESULT_END_OF_INPUT
            val toRead = minOf(length, available)
            System.arraycopy(bytes, peekPos, buffer, offset, toRead)
            peekPos += toRead
            return toRead
        }

        override fun peekFully(buffer: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
            if (bytes.size - peekPos < length) {
                if (allowEndOfInput) return false
                throw java.io.EOFException()
            }
            System.arraycopy(bytes, peekPos, buffer, offset, length)
            peekPos += length
            return true
        }

        override fun peekFully(buffer: ByteArray, offset: Int, length: Int) {
            peekFully(buffer, offset, length, false)
        }

        override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
            if (bytes.size - peekPos < length) {
                if (allowEndOfInput) return false
                throw java.io.EOFException()
            }
            peekPos += length
            return true
        }

        override fun advancePeekPosition(length: Int) {
            advancePeekPosition(length, false)
        }

        override fun resetPeekPosition() {
            peekPos = pos
        }

        override fun getPeekPosition(): Long = peekPos.toLong()
        override fun getPosition(): Long = pos.toLong()
        override fun getLength(): Long = bytes.size.toLong()

        override fun <E : Throwable> setRetryPosition(position: Long, e: E) {
            pos = position.toInt()
            peekPos = pos
            throw e
        }
    }
}
