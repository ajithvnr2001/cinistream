package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.PlaybackException
import com.example.model.StreamFormat
import com.example.player.DecoderFallbackHelper
import com.example.player.PlayerNetworkStats
import com.example.player.VideoDecoderMode
import com.example.ui.player.PlayerErrorCard
import com.example.ui.player.PlayerStatsCard
import com.example.ui.player.TracksSheet
import com.example.ui.theme.CineStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DecoderFallbackTest {

    @get:Rule
    val composeRule = createComposeRule()

    private data class FakeCodec(
        val name: String,
        val isSoftware: Boolean
    )

    @Test
    fun detectsQualcommHevcDecoderFailureAndReordersToSoftwareFallback() {
        val simulatedCause = RuntimeException("Decoder failed: c2.qti.hevc.decoder")
        val playbackException = PlaybackException(
            "Video decoding error",
            simulatedCause,
            PlaybackException.ERROR_CODE_DECODING_FAILED
        )

        assertTrue(DecoderFallbackHelper.isDecoderError(playbackException))
        assertEquals(
            "c2.qti.hevc.decoder",
            DecoderFallbackHelper.extractFailedDecoderName(playbackException)
        )
        assertFalse(DecoderFallbackHelper.isSoftwareCodecName("c2.qti.hevc.decoder"))
        assertTrue(DecoderFallbackHelper.isSoftwareCodecName("c2.android.hevc.decoder"))
        assertTrue(DecoderFallbackHelper.isSoftwareCodecName("OMX.google.hevc.decoder"))
        assertEquals("HW · c2.qti.hevc.decoder", DecoderFallbackHelper.formatDecoderTag("c2.qti.hevc.decoder"))
        assertEquals("SW · c2.android.hevc.decoder", DecoderFallbackHelper.formatDecoderTag("c2.android.hevc.decoder"))

        val deviceCodecs = listOf(
            FakeCodec("c2.qti.hevc.decoder", isSoftware = false),
            FakeCodec("c2.android.hevc.decoder", isSoftware = true),
            FakeCodec("OMX.google.hevc.decoder", isSoftware = true)
        )

        // Default AUTO keeps hardware first before any failure
        val autoOrder = DecoderFallbackHelper.reorderCodecs(
            codecs = deviceCodecs,
            mode = VideoDecoderMode.AUTO,
            blockedCodecNames = emptySet(),
            nameOf = { it.name },
            isSoftwareOf = { it.isSoftware }
        )
        assertEquals("c2.qti.hevc.decoder", autoOrder.first().name)

        // Once c2.qti.hevc.decoder fails and is blacklisted, c2.android.hevc.decoder immediately becomes #1
        val fallbackOrder = DecoderFallbackHelper.reorderCodecs(
            codecs = deviceCodecs,
            mode = VideoDecoderMode.SOFTWARE,
            blockedCodecNames = setOf("c2.qti.hevc.decoder"),
            nameOf = { it.name },
            isSoftwareOf = { it.isSoftware }
        )
        assertEquals(listOf("c2.android.hevc.decoder", "OMX.google.hevc.decoder"), fallbackOrder.map { it.name })

        // Explicit SOFTWARE mode puts software codecs before hardware codecs even without a failure
        val swPreferred = DecoderFallbackHelper.reorderCodecs(
            codecs = deviceCodecs,
            mode = VideoDecoderMode.SOFTWARE,
            blockedCodecNames = emptySet(),
            nameOf = { it.name },
            isSoftwareOf = { it.isSoftware }
        )
        assertEquals("c2.android.hevc.decoder", swPreferred.first().name)
        assertEquals("c2.qti.hevc.decoder", swPreferred.last().name)
    }

    @Test
    fun playerErrorCardShowsTrySoftwareDecoderAndSkipSegmentActionsOnDecoderError() {
        var switchedMode: VideoDecoderMode? = null
        var skippedSegment = false

        composeRule.setContent {
            CineStreamTheme {
                PlayerErrorCard(
                    message = "Error code decoding failed: Decoder failed: c2.qti.hevc.decoder",
                    currentFormat = StreamFormat.MKV,
                    isDecoderError = true,
                    effectiveDecoderMode = VideoDecoderMode.HARDWARE,
                    onRetry = {},
                    onSwitchDecoderMode = { switchedMode = it },
                    onSkipBadSegment = { skippedSegment = true },
                    onForceHls = {},
                    onBack = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Video Decoder Error").assertIsDisplayed()
        composeRule.onNodeWithTag("player_error_switch_decoder_button")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithTag("player_error_skip_segment_button")
            .assertIsDisplayed()
            .performClick()

        assertEquals(VideoDecoderMode.SOFTWARE, switchedMode)
        assertTrue(skippedSegment)
    }

    @Test
    fun pickCompatibleFallbackTrackPrefersNonHevcThenLowerResolution() {
        val mixedTracks = listOf(
            DecoderFallbackHelper.VideoTrackCandidate(
                id = "0:0",
                groupIndex = 0,
                trackIndex = 0,
                mimeType = "video/hevc",
                height = 1080,
                bitrate = 6_000_000,
                isSelected = true,
                label = "1080p HEVC"
            ),
            DecoderFallbackHelper.VideoTrackCandidate(
                id = "0:1",
                groupIndex = 0,
                trackIndex = 1,
                mimeType = "video/hevc",
                height = 720,
                bitrate = 3_000_000,
                isSelected = false,
                label = "720p HEVC"
            ),
            DecoderFallbackHelper.VideoTrackCandidate(
                id = "1:0",
                groupIndex = 1,
                trackIndex = 0,
                mimeType = "video/avc",
                height = 720,
                bitrate = 3_500_000,
                isSelected = false,
                label = "720p H.264"
            )
        )

        // Prefers H.264 over HEVC when both are untried
        val firstPick = DecoderFallbackHelper.pickCompatibleFallbackTrack(
            candidates = mixedTracks,
            triedTrackIds = emptySet()
        )
        assertEquals("1:0", firstPick?.id)

        // When only HEVC tracks remain, steps down to the next lower resolution
        val hevcOnlyTracks = listOf(
            DecoderFallbackHelper.VideoTrackCandidate(
                id = "0:0",
                groupIndex = 0,
                trackIndex = 0,
                mimeType = "video/hevc",
                height = 2160,
                bitrate = 14_000_000,
                isSelected = true,
                label = "2160p HEVC"
            ),
            DecoderFallbackHelper.VideoTrackCandidate(
                id = "0:1",
                groupIndex = 0,
                trackIndex = 1,
                mimeType = "video/hevc",
                height = 1080,
                bitrate = 6_000_000,
                isSelected = false,
                label = "1080p HEVC"
            ),
            DecoderFallbackHelper.VideoTrackCandidate(
                id = "0:2",
                groupIndex = 0,
                trackIndex = 2,
                mimeType = "video/hevc",
                height = 720,
                bitrate = 2_500_000,
                isSelected = false,
                label = "720p HEVC"
            )
        )

        val step1 = DecoderFallbackHelper.pickCompatibleFallbackTrack(
            candidates = hevcOnlyTracks,
            triedTrackIds = emptySet()
        )
        assertEquals("0:1", step1?.id)

        val step2 = DecoderFallbackHelper.pickCompatibleFallbackTrack(
            candidates = hevcOnlyTracks,
            triedTrackIds = setOf("0:1")
        )
        assertEquals("0:2", step2?.id)

        val hint = DecoderFallbackHelper.buildReadableDecoderErrorHint(
            failedCodec = "c2.android.hevc.decoder",
            effectiveMode = VideoDecoderMode.SOFTWARE,
            bothHwAndSwFailed = true
        )
        assertTrue(hint.contains("8-bit", ignoreCase = true))
        assertTrue(hint.contains("10-bit", ignoreCase = true))
        assertTrue(hint.contains("Skip Bad Frame", ignoreCase = true))
    }

    @Test
    fun tracksSheetSelectsVideoDecoderModeAndStatsCardShowsActiveSoftwareDecoder() {
        var chosenMode: VideoDecoderMode? = null

        composeRule.setContent {
            CineStreamTheme {
                TracksSheet(
                    videoTracks = emptyList(),
                    audioTracks = emptyList(),
                    preferredDecoderMode = VideoDecoderMode.AUTO,
                    effectiveDecoderMode = VideoDecoderMode.SOFTWARE,
                    activeVideoDecoderName = "c2.android.hevc.decoder",
                    onSelectDecoderMode = { chosenMode = it },
                    onDismiss = {},
                    onSelectVideoTrack = {},
                    onSelectAudioTrack = {}
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(200L)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tracks_decoder_mode_software").performScrollTo()
        composeRule.onNodeWithText("Active: SW · c2.android.hevc.decoder").assertIsDisplayed()
        composeRule.onNodeWithTag("tracks_decoder_mode_software").performClick()

        assertEquals(VideoDecoderMode.SOFTWARE, chosenMode)
    }

    @Test
    fun excludesLowLatencyCodecsAndDelegatesToFfmpegTenBitRenderer() {
        assertTrue(DecoderFallbackHelper.isLowLatencyCodecName("c2.qti.hevc.decoder.low_latency"))
        assertFalse(DecoderFallbackHelper.isLowLatencyCodecName("c2.qti.hevc.decoder"))
        assertTrue(DecoderFallbackHelper.isSoftwareCodecName("libffmpeg"))
        assertEquals("SW · FFmpeg (libffmpeg)", DecoderFallbackHelper.formatDecoderTag("libffmpeg"))

        val qualcommHevcCodecs = listOf(
            FakeCodec("c2.qti.hevc.decoder", isSoftware = false),
            FakeCodec("c2.qti.hevc.decoder.low_latency", isSoftware = false),
            FakeCodec("c2.android.hevc.decoder", isSoftware = true)
        )

        // Low-latency WebRTC codec is stripped out even before any failure
        val normalOrder = DecoderFallbackHelper.reorderCodecs(
            codecs = qualcommHevcCodecs,
            mode = VideoDecoderMode.AUTO,
            blockedCodecNames = emptySet(),
            nameOf = { it.name },
            isSoftwareOf = { it.isSoftware },
            excludeLowLatency = true,
            allowEmptyAfterFiltering = true
        )
        assertEquals(listOf("c2.qti.hevc.decoder", "c2.android.hevc.decoder"), normalOrder.map { it.name })

        // Once c2.qti.hevc.decoder & c2.android.hevc.decoder are blocked and FFmpeg is available,
        // returns emptyList() so Media3 cleanly hands off to NextLib's FfmpegVideoRenderer (10-bit HEVC)
        val handoffToFfmpeg = DecoderFallbackHelper.reorderCodecs(
            codecs = qualcommHevcCodecs,
            mode = VideoDecoderMode.SOFTWARE,
            blockedCodecNames = setOf("c2.qti.hevc.decoder", "c2.android.hevc.decoder"),
            nameOf = { it.name },
            isSoftwareOf = { it.isSoftware },
            excludeLowLatency = true,
            allowEmptyAfterFiltering = true
        )
        assertTrue(handoffToFfmpeg.isEmpty())

        // SmartMediaCodecSelector delegates video/* directly to FfmpegVideoRenderer when preferExternalFfmpegVideoProvider is true
        val selector = com.example.player.SmartMediaCodecSelector(
            modeProvider = { VideoDecoderMode.SOFTWARE },
            blockedCodecNamesProvider = { emptySet() },
            preferExternalFfmpegVideoProvider = { true },
            hasFfmpegFallbackProvider = { true }
        )
        assertTrue(selector.getDecoderInfos("video/hevc", false, false).isEmpty())
    }

    @Test
    fun statsCardDisplaysActiveVideoDecoderRow() {
        val stats = PlayerNetworkStats(
            streamHost = "cdn.example.com",
            streamFormatLabel = "MKV",
            playbackStateLabel = "PLAYING",
            activeVideoDecoderName = "c2.android.hevc.decoder",
            effectiveDecoderMode = VideoDecoderMode.SOFTWARE
        )

        composeRule.setContent {
            CineStreamTheme {
                PlayerStatsCard(
                    stats = stats,
                    isTv = false,
                    showCloseButton = false,
                    onClose = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Decoder").assertIsDisplayed()
        composeRule.onNodeWithText("SW · c2.android.hevc.decoder").assertIsDisplayed()
    }
}
