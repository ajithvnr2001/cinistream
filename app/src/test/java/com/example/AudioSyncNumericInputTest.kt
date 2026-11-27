package com.example

import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import com.example.model.SubtitleAppearance
import com.example.player.AudioDelayMath
import com.example.player.SyncOffsetAudioSink
import com.example.player.SyncTimeUnit
import com.example.ui.player.SubtitleSheet
import com.example.ui.player.TracksSheet
import com.example.ui.theme.CineStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioSyncNumericInputTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun parseDelayInputMs_handlesMillisecondsSecondsDecimalsNegativesAndSuffixes() {
        // Default ms mode
        assertEquals(250L, AudioDelayMath.parseDelayInputMs("250", SyncTimeUnit.MILLISECONDS))
        assertEquals(-350L, AudioDelayMath.parseDelayInputMs("-350", SyncTimeUnit.MILLISECONDS))
        assertEquals(0L, AudioDelayMath.parseDelayInputMs("0", SyncTimeUnit.MILLISECONDS))

        // Default seconds mode (supports decimal dot & comma)
        assertEquals(1_500L, AudioDelayMath.parseDelayInputMs("1.5", SyncTimeUnit.SECONDS))
        assertEquals(-2_250L, AudioDelayMath.parseDelayInputMs("-2,25", SyncTimeUnit.SECONDS))
        assertEquals(3_000L, AudioDelayMath.parseDelayInputMs("+3", SyncTimeUnit.SECONDS))

        // Inline explicit unit suffix overrides selected unit
        assertEquals(1_250L, AudioDelayMath.parseDelayInputMs("1.25s", SyncTimeUnit.MILLISECONDS))
        assertEquals(-400L, AudioDelayMath.parseDelayInputMs("-400ms", SyncTimeUnit.SECONDS))

        // Clamped cleanly to -10s..+10s
        assertEquals(10_000L, AudioDelayMath.parseDelayInputMs("25", SyncTimeUnit.SECONDS))
        assertEquals(-10_000L, AudioDelayMath.parseDelayInputMs("-15000", SyncTimeUnit.MILLISECONDS))

        // Rejects blank & invalid text
        assertNull(AudioDelayMath.parseDelayInputMs("", SyncTimeUnit.MILLISECONDS))
        assertNull(AudioDelayMath.parseDelayInputMs("   ", SyncTimeUnit.SECONDS))
        assertNull(AudioDelayMath.parseDelayInputMs("abc", SyncTimeUnit.MILLISECONDS))

        // Label formatting switches cleanly at 1000ms boundary
        assertEquals("0 ms", AudioDelayMath.formatDelayLabel(0L))
        assertEquals("+250 ms", AudioDelayMath.formatDelayLabel(250L))
        assertEquals("-400 ms", AudioDelayMath.formatDelayLabel(-400L))
        assertEquals("+1.50 s", AudioDelayMath.formatDelayLabel(1_500L))
        assertEquals("-2.25 s", AudioDelayMath.formatDelayLabel(-2_250L))
    }

    @Test
    fun syncOffsetAudioSink_shiftsClockBackwardForNegativeDelayOnly() {
        var rawClockUs = 5_000_000L // 5.0 seconds
        var requestedDelayMs = 0L

        val fakeDelegate = createStubAudioSink { rawClockUs }
        val syncSink = SyncOffsetAudioSink(
            delegate = fakeDelegate,
            delayMsProvider = { requestedDelayMs }
        )

        // 0 ms -> unchanged
        assertEquals(5_000_000L, syncSink.getCurrentPositionUs(false))

        // Positive delay (+800 ms) -> clock unchanged (DelayAudioProcessor shifts PCM later)
        requestedDelayMs = 800L
        assertEquals(5_000_000L, syncSink.getCurrentPositionUs(false))

        // Negative delay (-1500 ms) -> clock reported 1.5s earlier so video waits & audio leads by 1.5s
        requestedDelayMs = -1_500L
        assertEquals(3_500_000L, syncSink.getCurrentPositionUs(false))

        // Clamp negative clock shift cleanly at 0 us near start of stream
        rawClockUs = 400_000L
        requestedDelayMs = -2_000L
        assertEquals(0L, syncSink.getCurrentPositionUs(false))

        // Preserve CURRENT_POSITION_NOT_SET sentinel
        rawClockUs = AudioSink.CURRENT_POSITION_NOT_SET
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, syncSink.getCurrentPositionUs(false))
    }

    @Test
    fun tracksSheet_audioSyncAcceptsDirectMsAndSecondsAndSignToggle() {
        var appliedDelayMs = 0L
        composeRule.setContent {
            CineStreamTheme {
                var currentDelay by remember { mutableLongStateOf(0L) }
                TracksSheet(
                    videoTracks = emptyList(),
                    audioTracks = emptyList(),
                    audioDelayMs = currentDelay,
                    onAdjustAudioDelay = { delta ->
                        currentDelay = AudioDelayMath.clampDelayMs(currentDelay + delta)
                        appliedDelayMs = currentDelay
                    },
                    onSetAudioDelay = { target ->
                        currentDelay = AudioDelayMath.clampDelayMs(target)
                        appliedDelayMs = currentDelay
                    },
                    onResetAudioDelay = {
                        currentDelay = 0L
                        appliedDelayMs = 0L
                    },
                    onDismiss = {},
                    onSelectVideoTrack = {},
                    onSelectAudioTrack = {}
                )
            }
        }

        // 1. Type "250" in default ms mode -> Apply -> +250 ms
        composeRule.onNodeWithTag("tracks_audio_delay_input").performScrollTo().performTextInput("250")
        composeRule.onNodeWithTag("tracks_audio_delay_apply").performScrollTo().performClick()
        assertEquals(250L, appliedDelayMs)
        composeRule.onNodeWithTag("tracks_audio_delay_value").assertTextContains("+250 ms")

        // 2. Switch to seconds (s) mode, enter "1.5", tap ± toggle -> "-1.5" -> Apply -> -1500 ms
        composeRule.onNodeWithTag("tracks_audio_delay_unit_s").performScrollTo().performClick()
        composeRule.onNodeWithTag("tracks_audio_delay_input").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("tracks_audio_delay_input").performTextInput("1.5")
        composeRule.onNodeWithTag("tracks_audio_delay_sign_toggle").performScrollTo().performClick()
        composeRule.onNodeWithTag("tracks_audio_delay_apply").performScrollTo().performClick()
        assertEquals(-1_500L, appliedDelayMs)
        composeRule.onNodeWithTag("tracks_audio_delay_value").assertTextContains("-1.50 s")

        // 3. Invalid text shows inline error and leaves applied delay unchanged
        composeRule.onNodeWithTag("tracks_audio_delay_input").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("tracks_audio_delay_input").performTextInput("notanumber")
        composeRule.onNodeWithTag("tracks_audio_delay_apply").performScrollTo().performClick()
        composeRule.onNodeWithTag("tracks_audio_delay_error").performScrollTo().assertIsDisplayed()
        assertEquals(-1_500L, appliedDelayMs)
    }

    @Test
    fun subtitleSheet_subtitleSyncAcceptsDirectSecondsAndMs() {
        var appliedSubOffsetMs = 0L
        composeRule.setContent {
            CineStreamTheme {
                var currentOffset by remember { mutableLongStateOf(0L) }
                SubtitleSheet(
                    subtitleTracks = emptyList(),
                    subtitlesDisabled = false,
                    hasExternalSubtitles = true,
                    subtitleOffsetMs = currentOffset,
                    appearance = SubtitleAppearance(),
                    onDismiss = {},
                    onPickSubtitleFile = {},
                    onLoadSubtitleUrl = {},
                    onSelectTrack = {},
                    onDisableSubtitles = {},
                    onAdjustOffset = { delta ->
                        currentOffset = AudioDelayMath.clampDelayMs(currentOffset + delta)
                        appliedSubOffsetMs = currentOffset
                    },
                    onSetOffset = { target ->
                        currentOffset = AudioDelayMath.clampDelayMs(target)
                        appliedSubOffsetMs = currentOffset
                    },
                    onResetOffset = {
                        currentOffset = 0L
                        appliedSubOffsetMs = 0L
                    },
                    onUpdateTextSize = {},
                    onUpdateColor = {},
                    onUpdateBg = {}
                )
            }
        }

        // Default unit in SubtitleSheet is seconds (s): enter "2.25" -> +2250 ms
        composeRule.onNodeWithTag("subtitle_offset_input").performScrollTo().performTextInput("2.25")
        composeRule.onNodeWithTag("subtitle_offset_apply").performScrollTo().performClick()
        assertEquals(2_250L, appliedSubOffsetMs)
        composeRule.onNodeWithTag("subtitle_offset_value").assertTextContains("+2.25s")

        // Switch to ms, type "-300" -> -300 ms
        composeRule.onNodeWithTag("subtitle_offset_unit_ms").performScrollTo().performClick()
        composeRule.onNodeWithTag("subtitle_offset_input").performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("subtitle_offset_input").performTextInput("-300")
        composeRule.onNodeWithTag("subtitle_offset_apply").performScrollTo().performClick()
        assertEquals(-300L, appliedSubOffsetMs)
        composeRule.onNodeWithTag("subtitle_offset_value").assertTextContains("-0.30s")
    }

    @Test
    fun buildConfig_matchesVersion35() {
        assertEquals(35, BuildConfig.VERSION_CODE)
        assertEquals("1.35", BuildConfig.VERSION_NAME)
    }

    private fun createStubAudioSink(positionUsProvider: () -> Long): AudioSink =
        object : AudioSink {
            private var playbackParams: PlaybackParameters = PlaybackParameters.DEFAULT
            private var skipSilence: Boolean = false
            override fun setListener(listener: AudioSink.Listener) = Unit
            override fun supportsFormat(format: Format): Boolean = true
            override fun getFormatSupport(format: Format): Int = AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            override fun getCurrentPositionUs(sourceEnded: Boolean): Long = positionUsProvider()
            override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) = Unit
            override fun play() = Unit
            override fun handleDiscontinuity() = Unit
            override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean = true
            override fun playToEndOfStream() = Unit
            override fun isEnded(): Boolean = false
            override fun hasPendingData(): Boolean = false
            override fun setPlaybackParameters(playbackParameters: PlaybackParameters) { playbackParams = playbackParameters }
            override fun getPlaybackParameters(): PlaybackParameters = playbackParams
            override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) { skipSilence = skipSilenceEnabled }
            override fun getSkipSilenceEnabled(): Boolean = skipSilence
            override fun setAudioAttributes(audioAttributes: AudioAttributes) = Unit
            override fun getAudioAttributes(): AudioAttributes? = null
            override fun setAudioSessionId(audioSessionId: Int) = Unit
            override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) = Unit
            override fun enableTunnelingV21() = Unit
            override fun disableTunneling() = Unit
            override fun setVolume(volume: Float) = Unit
            override fun pause() = Unit
            override fun flush() = Unit
            override fun reset() = Unit
            override fun getAudioTrackBufferSizeUs(): Long = 0L
        }
}
