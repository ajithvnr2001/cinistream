package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.MimeTypes
import androidx.test.core.app.ApplicationProvider
import com.example.model.StreamFormat
import com.example.player.M3uInspectionResult
import com.example.player.M3uPlaylistParser
import com.example.player.MediaFormatDetector
import com.example.player.PlayerNetworkStats
import com.example.player.PlayerStatsFormatter
import com.example.ui.player.PlayerStatsCard
import com.example.ui.theme.CineStreamTheme
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class HlsAndAppCheckTest {

    @get:Rule
    val composeRule = createComposeRule()

    companion object {
        private const val TEST_HLS_URL =
            "https://multicloud-s3-index.ajithvnr2001.workers.dev/pub/9f712691f8937399bfe52e34c80a450d/rustfs-demo-2/The.hidden.face.2011-hls/master.m3u8"
    }

    @Test
    fun detectsAndParsesUserHiddenFaceHlsMasterPlaylist() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(MediaFormatDetector.isValidMediaLink(TEST_HLS_URL))
        assertEquals(StreamFormat.M3U8, MediaFormatDetector.detectFormat(TEST_HLS_URL))
        assertEquals(MimeTypes.APPLICATION_M3U8, MediaFormatDetector.resolveExoPlayerMimeType(StreamFormat.M3U8))
        assertTrue(MediaFormatDetector.shouldInspectAsM3u(TEST_HLS_URL, StreamFormat.AUTO))

        val title = MediaFormatDetector.inferTitle(TEST_HLS_URL)
        assertTrue("Inferred title should reference The.hidden.face.2011-hls", title.contains("The.hidden.face.2011-hls"))

        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
        val parser = M3uPlaylistParser(context, client)

        val liveResult = parser.inspect(TEST_HLS_URL, isLocalFile = false)
        assertFalse(
            "HLS master.m3u8 must never be misclassified as an IPTV channel playlist",
            liveResult is M3uInspectionResult.ChannelPlaylist
        )

        val multiTrackMaster = """
            #EXTM3U
            #EXT-X-VERSION:4
            #EXT-X-INDEPENDENT-SEGMENTS
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",LANGUAGE="es",NAME="Spanish",DEFAULT=YES,AUTOSELECT=YES,URI="audio_es/index.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=3200000,RESOLUTION=1920x1080,AUDIO="audio"
            video_1080p/index.m3u8
        """.trimIndent()
        assertEquals(M3uInspectionResult.DirectHlsStream, parser.parseContent(multiTrackMaster))
    }

    @Test
    fun playerStatsFormatterFormatsNetworkAndBufferTelemetryCleanly() {
        assertEquals("—", PlayerStatsFormatter.formatBitrate(0L))
        assertEquals("850 Kbps", PlayerStatsFormatter.formatBitrate(850_000L))
        assertEquals("14.50 Mbps", PlayerStatsFormatter.formatBitrate(14_500_000L))

        assertEquals("0 KB/s", PlayerStatsFormatter.formatThroughput(0L))
        assertEquals("512 KB/s", PlayerStatsFormatter.formatThroughput(512L * 1024L))
        assertEquals("2.50 MB/s", PlayerStatsFormatter.formatThroughput((2.5 * 1024 * 1024).toLong()))

        assertEquals("12.4 MB", PlayerStatsFormatter.formatBytes((12.4 * 1024 * 1024).toLong()))
        assertEquals("14.20 s", PlayerStatsFormatter.formatBufferSeconds(14_200L))
        assertEquals("1920×1080 @ 24 fps", PlayerStatsFormatter.formatResolution(1920, 1080, 23.976f))
        assertEquals("avc1.640028 (AVC)", PlayerStatsFormatter.formatCodecLabel("avc1.640028", "video/avc"))
        assertEquals("AAC · 5.1 · 48.0 kHz · 192k", PlayerStatsFormatter.formatAudioDetail("AAC", 6, 48_000, 192_000))
        assertEquals("cdn.example.com", PlayerStatsFormatter.extractHostLabel("https://cdn.example.com/hls/master.m3u8", isLocal = false))
        assertEquals("Local Storage", PlayerStatsFormatter.extractHostLabel("content://media/external/video/12", isLocal = true))
    }

    @Test
    fun playerStatsCardDisplaysNetworkAndBufferDetailsAndCloses() {
        var closed = false
        val sampleStats = PlayerNetworkStats(
            streamHost = "cdn.cinestream.test",
            streamFormatLabel = "HLS",
            isLocalSource = false,
            playbackStateLabel = "PLAYING",
            connectionSpeedBps = 24_800_000L,
            networkActivityBytesPerSec = 1_650_000L,
            sessionDownloadedBytes = 48L * 1024L * 1024L,
            bufferHealthMs = 15_600L,
            bufferedPercent = 22,
            allocatedBufferBytes = 14L * 1024L * 1024L,
            targetBufferBytes = 24 * 1024 * 1024,
            jvmHeapUsedBytes = 62L * 1024L * 1024L,
            jvmHeapMaxBytes = 384L * 1024L * 1024L,
            rebufferCount = 1,
            totalRebufferMs = 420L,
            videoWidth = 1920,
            videoHeight = 1080,
            videoFps = 24f,
            videoCodec = "avc1.640028 (AVC)",
            videoBitrateBps = 6_400_000,
            audioCodec = "mp4a.40.2 (AAC)",
            audioChannels = 6,
            audioSampleRateHz = 48_000,
            audioBitrateBps = 192_000,
            droppedFrames = 0L,
            networkActivityHistoryKBps = listOf(400f, 1_200f, 1_650f),
            bufferHealthHistorySec = listOf(8.0f, 12.5f, 15.6f)
        )

        composeRule.setContent {
            CineStreamTheme {
                PlayerStatsCard(
                    stats = sampleStats,
                    isTv = false,
                    showCloseButton = true,
                    onClose = { closed = true }
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithTag("player_stats_overlay").assertIsDisplayed()
        composeRule.onNodeWithText("STATS FOR NERDS").assertIsDisplayed()
        composeRule.onNodeWithText("24.80 Mbps").assertIsDisplayed()
        composeRule.onNodeWithText("15.60 s (22%) · 1 stall").assertIsDisplayed()
        composeRule.onNodeWithText("HLS · cdn.cinestream.test").assertIsDisplayed()

        composeRule.onNodeWithTag("stats_overlay_close_button").performClick()
        assertTrue("Clicking close button should dismiss Stats for Nerds overlay", closed)
    }
}
