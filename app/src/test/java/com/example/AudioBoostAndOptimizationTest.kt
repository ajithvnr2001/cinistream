package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.example.player.AudioBoostMath
import com.example.player.PcmAudioCaptureProcessor
import com.example.ui.player.TracksSheet
import com.example.ui.player.VerticalVolumeBoostHud
import com.example.ui.player.VolumeBoostDialog
import com.example.ui.theme.CineStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioBoostAndOptimizationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun audioBoostMathMapsZeroToTwoHundredPercentAccurately() {
        assertEquals(0, AudioBoostMath.clampPercent(-25))
        assertEquals(200, AudioBoostMath.clampPercent(320))

        assertEquals(0.0f, AudioBoostMath.computePlayerVolume(0), 0.001f)
        assertEquals(0.5f, AudioBoostMath.computePlayerVolume(50), 0.001f)
        assertEquals(1.0f, AudioBoostMath.computePlayerVolume(100), 0.001f)
        assertEquals(1.0f, AudioBoostMath.computePlayerVolume(150), 0.001f)
        assertEquals(1.0f, AudioBoostMath.computePlayerVolume(200), 0.001f)

        assertEquals(0, AudioBoostMath.computeTargetGainMb(0))
        assertEquals(0, AudioBoostMath.computeTargetGainMb(100))
        assertEquals(500, AudioBoostMath.computeTargetGainMb(125))
        assertEquals(1000, AudioBoostMath.computeTargetGainMb(150))
        assertEquals(2000, AudioBoostMath.computeTargetGainMb(200))

        assertEquals("Muted", AudioBoostMath.formatShortLabel(0))
        assertEquals("100%", AudioBoostMath.formatShortLabel(100))
        assertEquals("Boost 200%", AudioBoostMath.formatShortLabel(200))
        assertEquals("+20.0 dB", AudioBoostMath.formatGainDb(200))
    }

    @Test
    fun pcmCapturePassesThroughWithoutDuplicateAllocationAndEncodesSinglePassWav() {
        val processor = PcmAudioCaptureProcessor()
        val format = AudioFormat(16_000, 1, C.ENCODING_PCM_16BIT)
        processor.configure(format)

        // Generate 2 seconds of audible 440 Hz tone at 16 kHz mono
        val sampleCount = 32_000
        val input = ByteBuffer.allocateDirect(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            val sample = (kotlin.math.sin(2.0 * Math.PI * 440.0 * i / 16_000.0) * 16_000.0).toInt().toShort()
            input.putShort(sample)
        }
        input.flip()

        processor.queueInput(input)
        // Ensure entire inputBuffer was consumed for downstream ExoPlayer AudioSink pass-through
        assertEquals(0, input.remaining())
        val out1 = processor.getOutput()
        assertEquals(sampleCount * 2, out1.remaining())

        // Feed a second frame of equal size and verify the exact same direct ByteBuffer is reused
        input.rewind()
        processor.queueInput(input)
        val out2 = processor.getOutput()
        assertEquals(sampleCount * 2, out2.remaining())
        assertTrue("PcmAudioCaptureProcessor must reuse its direct ByteBuffer across callbacks", out1 === out2)

        val clip = processor.snapshotWavClip(requestedSeconds = 2, currentPlaybackPositionMs = 2_000L)
        assertNotNull(clip)
        assertTrue(clip!!.hasAudibleSignal)
        assertEquals(44 + sampleCount * 2, clip.wavBytes.size)
        assertEquals("RIFF", String(clip.wavBytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(clip.wavBytes, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(clip.wavBytes, 36, 4, Charsets.US_ASCII))
    }

    @Test
    fun memorySafeTargetBufferBytesClampsCorrectlyForTvAndLargeHeap() {
        // 80 MB constrained TV heap -> 16 MB buffer cap (20%)
        assertEquals(
            16 * 1024 * 1024,
            com.example.player.CinePlayerManager.computeSafeTargetBufferBytes(80L * 1024L * 1024L)
        )
        // Tiny 48 MB heap -> clamped to 12 MB minimum
        assertEquals(
            com.example.player.CinePlayerManager.MIN_TARGET_BUFFER_BYTES,
            com.example.player.CinePlayerManager.computeSafeTargetBufferBytes(48L * 1024L * 1024L)
        )
        // 384 MB largeHeap -> clamped to 32 MB maximum
        assertEquals(
            com.example.player.CinePlayerManager.MAX_TARGET_BUFFER_BYTES,
            com.example.player.CinePlayerManager.computeSafeTargetBufferBytes(384L * 1024L * 1024L)
        )

        val appInfo = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
            .applicationInfo
        assertTrue(
            "AndroidManifest must declare android:largeHeap=\"true\"",
            (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP) != 0
        )
    }

    @Test
    fun tracksSheetSoundBoosterPresetsAndStepButtonsAdjustUpTo200Percent() {
        val currentVolumeState = androidx.compose.runtime.mutableIntStateOf(100)

        composeRule.setContent {
            CineStreamTheme {
                TracksSheet(
                    videoTracks = emptyList(),
                    audioTracks = emptyList(),
                    volumePercent = currentVolumeState.intValue,
                    onSetVolumePercent = { currentVolumeState.intValue = it },
                    onDismiss = {},
                    onSelectVideoTrack = {},
                    onSelectAudioTrack = {}
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(180L)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("volume_preset_200").performClick()
        composeRule.waitForIdle()
        assertEquals(200, currentVolumeState.intValue)

        composeRule.onNodeWithTag("volume_boost_minus_button").performClick()
        composeRule.waitForIdle()
        assertEquals(190, currentVolumeState.intValue)

        composeRule.onNodeWithTag("volume_boost_plus_button").performClick()
        composeRule.waitForIdle()
        assertEquals(200, currentVolumeState.intValue)

        composeRule.onNodeWithTag("volume_preset_0").performClick()
        composeRule.waitForIdle()
        assertEquals(0, currentVolumeState.intValue)
    }

    @Test
    fun verticalVolumeHudAndDialogRenderBoostAndDismissCleanly() {
        var dismissed = false
        var volume = 180

        composeRule.setContent {
            CineStreamTheme {
                androidx.compose.foundation.layout.Column {
                    VerticalVolumeBoostHud(volumePercent = volume)
                    VolumeBoostDialog(
                        volumePercent = volume,
                        onSetVolumePercent = { volume = it },
                        onDismiss = { dismissed = true }
                    )
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("BOOST").assertIsDisplayed()
        composeRule.onNodeWithTag("volume_preset_200").performClick()
        assertEquals(200, volume)
        composeRule.onNodeWithTag("volume_dialog_done_button").performClick()
        assertTrue(dismissed)
    }
}
