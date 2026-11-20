package com.example

import android.content.Context
import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AppCheckProviderMode
import com.example.ai.AppCheckRuntimeState
import com.example.ai.configureAppCheck
import com.example.ai.resolveRecaptchaSiteKey
import com.example.model.StreamFormat
import com.example.player.CinePlayerManager
import com.example.player.M3uInspectionResult
import com.example.player.M3uPlaylistParser
import com.example.player.MediaFormatDetector
import com.example.player.PcmAudioCaptureProcessor
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
class HlsAndAppCheckTest {

    companion object {
        private const val TEST_HLS_URL =
            "https://multicloud-s3-index.ajithvnr2001.workers.dev/pub/9f712691f8937399bfe52e34c80a450d/rustfs-demo-2/The.hidden.face.2011-hls/master.m3u8"
    }

    @Test
    fun sideloadedAppWithoutDebugTokenUsesProvisionedRecaptchaEnterprise() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Reset meta/debug state to simulate fresh sideloaded install on user phone/TV
        context.getSharedPreferences("cinestream_appcheck_meta", Context.MODE_PRIVATE)
            .edit().clear().commit()
        AppCheckRuntimeState.activeMode = AppCheckProviderMode.UNINITIALIZED

        val siteKey = resolveRecaptchaSiteKey(context)
        assertNotNull("Provisioned recaptcha_site_key must be bundled in resources", siteKey)
        assertTrue("recaptcha_site_key should be non-blank", siteKey!!.isNotBlank())

        // Launch with normal launcher Intent (no FIREBASE_APPCHECK_DEBUG_TOKEN extra)
        configureAppCheck(context, Intent(Intent.ACTION_MAIN))

        assertEquals(
            "Sideloaded APK without debug token extra must use Recaptcha Enterprise provider",
            AppCheckProviderMode.RECAPTCHA_ENTERPRISE,
            AppCheckRuntimeState.activeMode
        )
    }

    @Test
    fun emulatorLaunchWithDebugTokenExtraUsesRegisteredDebugProvider() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("cinestream_appcheck_meta", Context.MODE_PRIVATE)
            .edit().clear().commit()
        AppCheckRuntimeState.activeMode = AppCheckProviderMode.UNINITIALIZED

        val harnessIntent = Intent(Intent.ACTION_MAIN).apply {
            putExtra("FIREBASE_APPCHECK_DEBUG_TOKEN", "00000000-1111-2222-3333-444444444444")
        }
        configureAppCheck(context, harnessIntent)

        assertEquals(
            "Harness launch with explicit debug token must use DEBUG_REGISTERED_TOKEN",
            AppCheckProviderMode.DEBUG_REGISTERED_TOKEN,
            AppCheckRuntimeState.activeMode
        )
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

        // Verify live HLS manifest inspection if reachable from test environment, plus deterministic manifest check
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
    fun capturesFivePointOneSurroundMovieAudioWithoutDisturbingPassthrough() {
        val processor = PcmAudioCaptureProcessor()
        val surroundFormat = AudioFormat(48_000, 6, C.ENCODING_PCM_16BIT)
        val configured = processor.configure(surroundFormat)
        assertEquals(surroundFormat, configured)
        assertTrue(processor.isActive())

        // Feed 2 seconds of 48kHz 5.1 audio with speech-like sine wave on center channel (ch 2)
        val totalFrames = 48_000 * 2
        val input = ByteBuffer.allocateDirect(totalFrames * 6 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until totalFrames) {
            val speechSample = (sin(2.0 * Math.PI * 440.0 * frame / 48_000.0) * 6000.0).toInt().toShort()
            for (ch in 0 until 6) {
                input.putShort(if (ch == 2) speechSample else 0)
            }
        }
        input.flip()

        processor.queueInput(input)
        val passthrough = processor.getOutput()
        assertEquals("Pass-through output must preserve exact byte count", totalFrames * 6 * 2, passthrough.remaining())

        val clip = processor.snapshotWavClip(requestedSeconds = 2, currentPlaybackPositionMs = 12_000L)
        assertNotNull("Should capture a valid 16kHz WAV clip from 5.1 HLS audio", clip)
        assertTrue("Center-channel 5.1 dialogue must be detected as audible", clip!!.hasAudibleSignal)
        assertEquals("RIFF", String(clip.wavBytes, 0, 4, Charsets.US_ASCII))
    }
}
