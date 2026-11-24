package com.example

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import com.example.data.ContinueWatchingHelper
import com.example.data.MediaHistoryEntity
import com.example.player.AudioDelayMath
import com.example.player.DelayAudioProcessor
import com.example.player.FrameDropAnalyzer
import com.example.player.FrameDropDiagnosis
import com.example.player.MediaCodecQueueingPolicy
import com.example.player.PlayerNetworkStats
import com.example.player.SmartVpnFailoverCoordinator
import com.example.ui.home.ContinueWatchingSection
import com.example.ui.player.NetworkPrivacySheet
import com.example.ui.player.PlayerGestureMath
import com.example.ui.player.PlayerStatsCard
import com.example.ui.player.TracksSheet
import com.example.ui.theme.CineStreamTheme
import com.example.viewmodel.NetworkPrivacyUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CineStreamV23FeaturesTest {

    @get:Rule
    val composeRule = createComposeRule()

    // -------------------------------------------------------------------------
    // 1. Frame-Drop Analyzer & Selective Async MediaCodec Queueing Policy
    // -------------------------------------------------------------------------

    @Test
    fun frameDropAnalyzer_classifiesSeekGraceVsSwCpuVsStreamPtsAndDecays() {
        val analyzer = FrameDropAnalyzer(seekJoinGraceMs = 2_500L, rollingWindowMs = 3_000L)
        analyzer.reset(nowElapsedMs = 1_000L)

        // Drops within the 2.5s startup grace window are classified as SEEK_BURST
        analyzer.recordDroppedFrames(count = 14, nowElapsedMs = 2_200L)
        val startupSnap = analyzer.snapshot(nowElapsedMs = 2_400L, isSoftwareDecoder = false)
        assertEquals(FrameDropDiagnosis.SEEK_BURST, startupSnap.diagnosis)
        assertEquals(14L, startupSnap.seekJoinDropped)
        assertEquals(0L, startupSnap.steadyDropped)
        assertFalse(startupSnap.shouldThrottlePrefetch)

        // Sustained steady drops on Hardware decoder -> STREAM_PTS_OR_RESYNC (URL/CDN side)
        analyzer.recordDroppedFrames(count = 6, nowElapsedMs = 5_000L)
        val hwSnap = analyzer.snapshot(nowElapsedMs = 5_100L, isSoftwareDecoder = false)
        assertEquals(FrameDropDiagnosis.STREAM_PTS_OR_RESYNC, hwSnap.diagnosis)
        assertEquals(6L, hwSnap.steadyDropped)
        assertTrue(hwSnap.liveDropsPerSec >= 1.5f)
        assertFalse(hwSnap.shouldThrottlePrefetch)

        // Same steady drops on Software FFmpeg decoder -> SW_CPU_LOAD + prefetch throttle
        val swSnap = analyzer.snapshot(nowElapsedMs = 5_100L, isSoftwareDecoder = true)
        assertEquals(FrameDropDiagnosis.SW_CPU_LOAD, swSnap.diagnosis)
        assertTrue(swSnap.shouldThrottlePrefetch)

        // After 3s rolling window elapses with no new drops -> decays back to HEALTHY
        val recoveredSnap = analyzer.snapshot(nowElapsedMs = 9_000L, isSoftwareDecoder = true)
        assertEquals(FrameDropDiagnosis.HEALTHY, recoveredSnap.diagnosis)
        assertEquals(0f, recoveredSnap.liveDropsPerSec, 0.01f)
        assertFalse(recoveredSnap.shouldThrottlePrefetch)

        // Marking a manual seek opens a fresh 2.5s grace window
        analyzer.markSeekOrTransition(nowElapsedMs = 10_000L)
        analyzer.recordDroppedFrames(count = 10, nowElapsedMs = 11_500L)
        val postSeekSnap = analyzer.snapshot(nowElapsedMs = 11_600L, isSoftwareDecoder = false)
        assertEquals(24L, postSeekSnap.seekJoinDropped)
        assertEquals(6L, postSeekSnap.steadyDropped)
    }

    @Test
    fun mediaCodecQueueingPolicy_selectsAsyncOnlyOnModernHealthyMobile() {
        assertTrue(
            MediaCodecQueueingPolicy.shouldUseAsyncQueueing(
                sdkInt = Build.VERSION_CODES.S,
                isConstrainedOrTv = false,
                hasCodecErrorOccurred = false
            )
        )
        assertFalse(
            MediaCodecQueueingPolicy.shouldUseAsyncQueueing(
                sdkInt = Build.VERSION_CODES.R,
                isConstrainedOrTv = false,
                hasCodecErrorOccurred = false
            )
        )
        assertFalse(
            MediaCodecQueueingPolicy.shouldUseAsyncQueueing(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                isConstrainedOrTv = true,
                hasCodecErrorOccurred = false
            )
        )
        assertFalse(
            MediaCodecQueueingPolicy.shouldUseAsyncQueueing(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                isConstrainedOrTv = false,
                hasCodecErrorOccurred = true
            )
        )
    }

    // -------------------------------------------------------------------------
    // 2. Real-Time Audio Delay Processor & Math
    // -------------------------------------------------------------------------

    @Test
    fun audioDelayMath_clampsAndFormatsCleanly() {
        assertEquals(-500L, AudioDelayMath.clampDelayMs(-1_200L))
        assertEquals(500L, AudioDelayMath.clampDelayMs(900L))
        assertEquals("0 ms", AudioDelayMath.formatDelayLabel(0L))
        assertEquals("+75 ms", AudioDelayMath.formatDelayLabel(75L))
        assertEquals("-50 ms", AudioDelayMath.formatDelayLabel(-50L))
        assertEquals("+0.50s", AudioDelayMath.formatSubtitleOffsetLabel(500L))
        assertEquals("-1.0s", AudioDelayMath.formatSubtitleOffsetLabel(-1_000L))
    }

    @Test
    fun delayAudioProcessor_passthroughAtZeroAndDelaysPcmAtPositiveOffset() {
        val processor = DelayAudioProcessor()
        val format = AudioFormat(1000, 1, C.ENCODING_PCM_16BIT) // 2 bytes/frame, 100ms = 200 bytes
        processor.configure(format)
        processor.flush()

        // 0ms passthrough
        processor.setDelayMs(0L)
        val inputZero = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        inputZero.putShort(1234).putShort(5678).flip()
        processor.queueInput(inputZero)
        val outZero = processor.output
        assertEquals(4, outZero.remaining())
        assertEquals(1234.toShort(), outZero.short)
        assertEquals(5678.toShort(), outZero.short)

        // +100ms delay = 200 bytes ring buffer (100 samples)
        processor.flush()
        processor.setDelayMs(100L)
        val leadIn = ByteBuffer.allocateDirect(200).order(ByteOrder.nativeOrder())
        for (i in 0 until 100) {
            leadIn.putShort((i + 1).toShort())
        }
        leadIn.flip()
        processor.queueInput(leadIn)
        val outSilence = processor.output
        assertEquals(200, outSilence.remaining())
        repeat(100) {
            assertEquals(0.toShort(), outSilence.short)
        }

        // Next chunk should emit the previously buffered samples 1, 2, 3, 4
        val nextChunk = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        nextChunk.putShort(9001).putShort(9002).putShort(9003).putShort(9004).flip()
        processor.queueInput(nextChunk)
        val outDelayed = processor.output
        assertEquals(8, outDelayed.remaining())
        assertEquals(1.toShort(), outDelayed.short)
        assertEquals(2.toShort(), outDelayed.short)
        assertEquals(3.toShort(), outDelayed.short)
        assertEquals(4.toShort(), outDelayed.short)
    }

    // -------------------------------------------------------------------------
    // 3. Continue Watching & Per-Episode Progress Helper
    // -------------------------------------------------------------------------

    @Test
    fun continueWatchingHelper_filtersBoundsDeduplicatesSeriesAndMapsEpisodeProgress() {
        val now = 1_700_000_000_000L
        val entries = listOf(
            // Valid movie at 50%
            MediaHistoryEntity(
                id = 1L,
                title = "Dune Part Two",
                sourceUri = "https://cdn.example/dune.m3u8",
                formatName = "HLS",
                lastPositionMs = 5_000_000L,
                durationMs = 10_000_000L,
                lastPlayedAt = now - 1_000L
            ),
            // Completed movie (>= 95%) -> excluded from Continue Watching
            MediaHistoryEntity(
                id = 2L,
                title = "Completed Film",
                sourceUri = "https://cdn.example/done.mp4",
                formatName = "MP4",
                lastPositionMs = 960_000L,
                durationMs = 1_000_000L,
                lastPlayedAt = now - 500L
            ),
            // Barely started (< 2%) -> excluded
            MediaHistoryEntity(
                id = 3L,
                title = "Just Started",
                sourceUri = "https://cdn.example/start.mp4",
                formatName = "MP4",
                lastPositionMs = 5_000L,
                durationMs = 1_000_000L,
                lastPlayedAt = now - 200L
            ),
            // Too short (< 60s) -> excluded
            MediaHistoryEntity(
                id = 4L,
                title = "Short Trailer",
                sourceUri = "https://cdn.example/trailer.mp4",
                formatName = "MP4",
                lastPositionMs = 15_000L,
                durationMs = 30_000L,
                lastPlayedAt = now - 100L
            ),
            // Series S01E02 (newer) and S01E01 (older) -> deduplicated to S01E02
            MediaHistoryEntity(
                id = 5L,
                title = "Breaking Bad · S01E02",
                sourceUri = "https://cdn.example/bb_s1e2.m3u8",
                formatName = "HLS",
                lastPositionMs = 1_200_000L,
                durationMs = 3_000_000L,
                lastPlayedAt = now,
                seriesId = "series_bb",
                seriesTitle = "Breaking Bad",
                seasonNumber = 1,
                episodeNumber = 2,
                episodeId = "ep_bb_1_2"
            ),
            MediaHistoryEntity(
                id = 6L,
                title = "Breaking Bad · S01E01",
                sourceUri = "https://cdn.example/bb_s1e1.m3u8",
                formatName = "HLS",
                lastPositionMs = 2_900_000L,
                durationMs = 3_000_000L,
                lastPlayedAt = now - 5_000L,
                seriesId = "series_bb",
                seriesTitle = "Breaking Bad",
                seasonNumber = 1,
                episodeNumber = 1,
                episodeId = "ep_bb_1_1"
            )
        )

        val rail = ContinueWatchingHelper.buildContinueWatchingList(entries)
        assertEquals(2, rail.size)
        assertEquals("ep_bb_1_2", rail[0].episodeId)
        assertEquals("Dune Part Two", rail[1].title)

        val progressMap = ContinueWatchingHelper.buildEpisodeProgressMap(entries)
        assertEquals(0.4f, progressMap["ep_bb_1_2"] ?: 0f, 0.01f)
        assertTrue((progressMap["ep_bb_1_1"] ?: 0f) >= 0.95f)
        assertEquals("S1 · E2", ContinueWatchingHelper.formatEpisodeCode(1, 2))
        assertEquals("30m left", ContinueWatchingHelper.formatRemainingTime(1_200_000L, 3_000_000L))
    }

    // -------------------------------------------------------------------------
    // 4. Smart Auto-VPN Failover Coordinator
    // -------------------------------------------------------------------------

    @Test
    fun smartVpnFailoverCoordinator_respectsStallThresholdFatalTriggerCooldownAndManualSuppression() {
        val coordinator = SmartVpnFailoverCoordinator()
        coordinator.onNewMediaSession("https://cdn.example/stream1.m3u8")

        // Requires 2 low-bandwidth stalls when enabled + logged in + VPN off
        assertFalse(
            coordinator.recordRebufferStall(
                isFeatureEnabled = true,
                hasNordCredentials = true,
                isNordAlreadyActive = false,
                isLocalMedia = false,
                estimatedBandwidthBps = 1_500_000L
            )
        )
        assertTrue(
            coordinator.recordRebufferStall(
                isFeatureEnabled = true,
                hasNordCredentials = true,
                isNordAlreadyActive = false,
                isLocalMedia = false,
                estimatedBandwidthBps = 1_200_000L
            )
        )
        // Per-session cooldown: won't trigger a second time for same media
        assertFalse(
            coordinator.recordFatalNetworkError(
                isFeatureEnabled = true,
                hasNordCredentials = true,
                isNordAlreadyActive = false,
                isLocalMedia = false
            )
        )

        // Fresh media: fatal error triggers immediately
        coordinator.onNewMediaSession("https://cdn.example/stream2.m3u8")
        assertTrue(
            coordinator.recordFatalNetworkError(
                isFeatureEnabled = true,
                hasNordCredentials = true,
                isNordAlreadyActive = false,
                isLocalMedia = false
            )
        )

        // Manual disconnect suppresses auto-failover for the current session
        coordinator.onNewMediaSession("https://cdn.example/stream3.m3u8")
        coordinator.suppressForCurrentSession()
        assertFalse(
            coordinator.recordFatalNetworkError(
                isFeatureEnabled = true,
                hasNordCredentials = true,
                isNordAlreadyActive = false,
                isLocalMedia = false
            )
        )

        // Local media or already-active VPN never triggers failover
        coordinator.onNewMediaSession("https://cdn.example/stream4.m3u8")
        assertFalse(
            coordinator.recordFatalNetworkError(
                isFeatureEnabled = true,
                hasNordCredentials = true,
                isNordAlreadyActive = true,
                isLocalMedia = false
            )
        )
        assertFalse(
            coordinator.recordFatalNetworkError(
                isFeatureEnabled = true,
                hasNordCredentials = true,
                isNordAlreadyActive = false,
                isLocalMedia = true
            )
        )
    }

    // -------------------------------------------------------------------------
    // 5. Player Gesture Math (Brightness & Pinch Zoom)
    // -------------------------------------------------------------------------

    @Test
    fun playerGestureMath_clampsBrightnessAndSnapsZoom() {
        assertEquals(2, PlayerGestureMath.clampBrightnessPercent(-15))
        assertEquals(100, PlayerGestureMath.clampBrightnessPercent(145))
        assertEquals(64, PlayerGestureMath.clampBrightnessPercent(64))

        assertEquals(1.0f, PlayerGestureMath.clampZoomScale(0.75f), 0.001f)
        assertEquals(1.0f, PlayerGestureMath.clampZoomScale(1.03f), 0.001f)
        assertEquals(1.75f, PlayerGestureMath.clampZoomScale(1.75f), 0.001f)
        assertEquals(2.5f, PlayerGestureMath.clampZoomScale(4.0f), 0.001f)
        assertEquals("Fit · 1.0×", PlayerGestureMath.formatZoomBadge(1.0f))
        assertEquals("1.75×", PlayerGestureMath.formatZoomBadge(1.75f))
    }

    // -------------------------------------------------------------------------
    // 6. Compose Robolectric UI Verification
    // -------------------------------------------------------------------------

    @Test
    fun continueWatchingSection_rendersCardAndTriggersResumeAndDismiss() {
        var resumedId = -1L
        var dismissedId = -1L
        val item = MediaHistoryEntity(
            id = 42L,
            title = "Andor · S01E03",
            sourceUri = "https://cdn.example/andor_s1e3.m3u8",
            formatName = "HLS",
            lastPositionMs = 1_500_000L,
            durationMs = 3_000_000L,
            seriesId = "andor",
            seriesTitle = "Andor",
            seasonNumber = 1,
            episodeNumber = 3,
            episodeId = "andor_1_3"
        )

        composeRule.setContent {
            CineStreamTheme {
                ContinueWatchingSection(
                    items = listOf(item),
                    onResumeItem = { resumedId = it.id },
                    onDismissItem = { dismissedId = it.id }
                )
            }
        }

        composeRule.onNodeWithTag("continue_watching_section").assertIsDisplayed()
        composeRule.onNodeWithTag("continue_watching_card_42").assertIsDisplayed()
        composeRule.onNodeWithText("Andor").assertIsDisplayed()
        composeRule.onNodeWithTag("continue_watching_card_42").performClick()
        assertEquals(42L, resumedId)

        composeRule.onNodeWithTag("continue_watching_dismiss_42").performClick()
        assertEquals(42L, dismissedId)
    }

    @Test
    fun playerStatsCard_displaysFrameDropDiagnosisAndAsyncQueueingTag() {
        val stats = PlayerNetworkStats(
            activeVideoDecoderName = "c2.qti.hevc.decoder",
            asyncQueueingActive = true,
            droppedFrames = 12L,
            steadyDroppedFrames = 8L,
            seekJoinDroppedFrames = 4L,
            liveDroppedFramesPerSec = 2.7f,
            frameDropDiagnosis = FrameDropDiagnosis.STREAM_PTS_OR_RESYNC
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

        composeRule.onNodeWithTag("stats_decoder_value").assertTextContains("Async", substring = true)
        composeRule.onNodeWithTag("stats_dropped_value").assertTextContains("Stream PTS", substring = true)
    }

    @Test
    fun tracksSheet_audioDelayStepperUpdatesAndBackgroundAudioHiddenOnTv() {
        var delayMs by mutableLongStateOf(0L)
        var bgAudio by mutableStateOf(false)
        var isTvMode by mutableStateOf(false)

        composeRule.setContent {
            CineStreamTheme {
                TracksSheet(
                    videoTracks = emptyList(),
                    audioTracks = emptyList(),
                    audioDelayMs = delayMs,
                    onAdjustAudioDelay = { delta -> delayMs = AudioDelayMath.clampDelayMs(delayMs + delta) },
                    onResetAudioDelay = { delayMs = 0L },
                    isTv = isTvMode,
                    backgroundAudioEnabled = bgAudio,
                    onToggleBackgroundAudio = { bgAudio = it },
                    onDismiss = {},
                    onSelectVideoTrack = {},
                    onSelectAudioTrack = {}
                )
            }
        }

        composeRule.onNodeWithTag("tracks_audio_delay_value").performScrollTo().assertTextContains("0 ms")
        composeRule.onNodeWithTag("tracks_audio_delay_plus_50").performScrollTo().performClick()
        composeRule.onNodeWithTag("tracks_audio_delay_value").assertTextContains("+50 ms")
        composeRule.onNodeWithTag("tracks_audio_delay_minus_25").performScrollTo().performClick()
        composeRule.onNodeWithTag("tracks_audio_delay_value").assertTextContains("+25 ms")
        composeRule.onNodeWithTag("tracks_audio_delay_reset").performScrollTo().performClick()
        composeRule.onNodeWithTag("tracks_audio_delay_value").assertTextContains("0 ms")

        composeRule.onNodeWithTag("tracks_background_audio_toggle").performScrollTo().performClick()
        assertTrue(bgAudio)

        isTvMode = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tracks_background_audio_toggle").assertDoesNotExist()
    }

    @Test
    fun networkPrivacySheet_showsAndTogglesSmartAutoVpnFailoverWhenLoggedIn() {
        var smartFailover by mutableStateOf(false)

        composeRule.setContent {
            CineStreamTheme {
                NetworkPrivacySheet(
                    state = NetworkPrivacyUiState(
                        nordVpnLoggedIn = true,
                        nordVpnEnabled = false,
                        smartAutoVpnFailoverEnabled = smartFailover
                    ),
                    onSelectDnsMode = {},
                    onLoginNordToken = {},
                    onLoginNordConfiguredSecret = {},
                    onToggleNordEnabled = {},
                    onToggleSmartAutoVpnFailover = { smartFailover = it },
                    onLogoutNord = {},
                    onProbeRoute = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithTag("nordvpn_smart_failover_switch").performScrollTo().performClick()
        assertTrue(smartFailover)
    }
}
