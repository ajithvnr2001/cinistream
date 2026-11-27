package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.model.StreamFormat
import com.example.ui.player.BrightnessHudOverlay
import com.example.ui.player.PlayerGestureMath
import com.example.ui.player.PlayerTopBar
import com.example.ui.player.TvBrightnessControlPanel
import com.example.ui.theme.CineStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrightnessControlTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tvBrightnessMath_clampsAndComputesDimAndBoostOverlays() {
        assertEquals(10, PlayerGestureMath.clampTvBrightnessPercent(-20))
        assertEquals(100, PlayerGestureMath.clampTvBrightnessPercent(100))
        assertEquals(150, PlayerGestureMath.clampTvBrightnessPercent(250))

        // Neutral 100% has zero dim and zero boost overlay
        assertEquals(0f, PlayerGestureMath.computeTvDimOverlayAlpha(100), 0.0001f)
        assertEquals(0f, PlayerGestureMath.computeTvBoostOverlayAlpha(100), 0.0001f)

        // Dimming below 100% increases dark overlay smoothly up to 0.78f at 10%
        assertTrue(PlayerGestureMath.computeTvDimOverlayAlpha(50) > 0.35f)
        assertEquals(0.78f, PlayerGestureMath.computeTvDimOverlayAlpha(10), 0.001f)
        assertEquals(0f, PlayerGestureMath.computeTvBoostOverlayAlpha(50), 0.0001f)

        // Boosting above 100% increases luminance lift up to 0.18f at 150%
        assertEquals(0f, PlayerGestureMath.computeTvDimOverlayAlpha(130), 0.0001f)
        assertTrue(PlayerGestureMath.computeTvBoostOverlayAlpha(125) > 0.08f)
        assertEquals(0.18f, PlayerGestureMath.computeTvBoostOverlayAlpha(150), 0.001f)
    }

    @Test
    fun mobileBrightnessHud_rendersVerticalGaugeMatchingVolumeHud() {
        composeRule.setContent {
            CineStreamTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    BrightnessHudOverlay(brightnessPercent = 72)
                }
            }
        }

        composeRule.onNodeWithTag("player_brightness_hud").assertIsDisplayed()
        composeRule.onNodeWithText("72%").assertIsDisplayed()

        val barNode = composeRule.onNodeWithTag("player_brightness_vertical_bar")
        barNode.assertIsDisplayed()
        barNode.assertHeightIsAtLeast(120.dp)
        val bounds = barNode.getUnclippedBoundsInRoot()
        val barWidth = bounds.right - bounds.left
        val barHeight = bounds.bottom - bounds.top
        assertTrue("Expected vertical brightness bar (height > width), got ${barWidth}x${barHeight}", barHeight > barWidth)
    }

    @Test
    fun tvPlayerTopBar_showsBrightnessButtonRightNextToSub_andHidesOnMobile() {
        composeRule.setContent {
            CineStreamTheme {
                PlayerTopBar(
                    title = "Blade Runner 2049",
                    format = StreamFormat.MKV,
                    videoHeight = 2160,
                    hasActiveSubtitles = true,
                    statsForNerdsEnabled = false,
                    hasSeriesEpisodes = false,
                    episodeCodeBadge = null,
                    hasJsonPlaylist = false,
                    hasM3uPlaylist = false,
                    isTv = true,
                    tvOverscanHorizontal = 28.dp,
                    tvOverscanVertical = 20.dp,
                    subButtonFocusRequester = remember { FocusRequester() },
                    statsButtonFocusRequester = remember { FocusRequester() },
                    episodesButtonFocusRequester = remember { FocusRequester() },
                    jsonButtonFocusRequester = remember { FocusRequester() },
                    m3uButtonFocusRequester = remember { FocusRequester() },
                    tracksButtonFocusRequester = remember { FocusRequester() },
                    onBack = {},
                    onOpenSubtitles = {},
                    onToggleStatsForNerds = {},
                    onOpenSeriesEpisodes = {},
                    onOpenJsonCatalog = {},
                    onOpenM3uQueue = {},
                    onOpenTracks = {},
                    onLockControls = {},
                    brightnessPercent = 120,
                    onToggleBrightnessPanel = {}
                )
            }
        }

        val subBounds = composeRule.onNodeWithTag("player_subtitles_button").assertIsDisplayed().getUnclippedBoundsInRoot()
        val brightBounds = composeRule.onNodeWithTag("player_brightness_button").assertIsDisplayed().getUnclippedBoundsInRoot()
        val statsBounds = composeRule.onNodeWithTag("player_stats_button").assertIsDisplayed().getUnclippedBoundsInRoot()

        assertTrue("Brightness button should sit to the right of Sub", brightBounds.left >= subBounds.right)
        assertTrue("Brightness button should sit immediately next to Sub before Stats", brightBounds.right <= statsBounds.left)
        composeRule.onNodeWithText("120%").assertIsDisplayed()
    }

    @Test
    fun tvBrightnessControlPanel_increasesDecreasesPresetsAndResets() {
        var dismissed = false
        composeRule.setContent {
            CineStreamTheme {
                var pct by remember { mutableIntStateOf(100) }
                TvBrightnessControlPanel(
                    brightnessPercent = pct,
                    onSetBrightnessPercent = { pct = it },
                    onDismiss = { dismissed = true }
                )
            }
        }

        composeRule.onNodeWithTag("tv_brightness_panel").assertIsDisplayed()
        composeRule.onNodeWithTag("tv_brightness_percent_label").assertTextContains("100%")

        // Increase (+10%) -> 110%
        composeRule.onNodeWithTag("tv_brightness_plus_button").performClick()
        composeRule.onNodeWithTag("tv_brightness_percent_label").assertTextContains("110%")

        // Increase again -> 120%
        composeRule.onNodeWithTag("tv_brightness_plus_button").performClick()
        composeRule.onNodeWithTag("tv_brightness_percent_label").assertTextContains("120%")

        // Decrease (-10%) -> 110%
        composeRule.onNodeWithTag("tv_brightness_minus_button").performClick()
        composeRule.onNodeWithTag("tv_brightness_percent_label").assertTextContains("110%")

        // Preset 60%
        composeRule.onNodeWithTag("tv_brightness_preset_60").performClick()
        composeRule.onNodeWithTag("tv_brightness_percent_label").assertTextContains("60%")

        // Reset back to 100%
        composeRule.onNodeWithTag("tv_brightness_reset_button").performClick()
        composeRule.onNodeWithTag("tv_brightness_percent_label").assertTextContains("100%")

        // Done dismisses panel
        composeRule.onNodeWithTag("tv_brightness_done_button").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun buildConfig_matchesVersion35() {
        assertEquals(35, BuildConfig.VERSION_CODE)
        assertEquals("1.35", BuildConfig.VERSION_NAME)
    }
}
