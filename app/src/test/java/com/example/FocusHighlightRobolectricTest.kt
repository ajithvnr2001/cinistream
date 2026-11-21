package com.example

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import com.example.model.SubtitleAppearance
import com.example.model.TrackOption
import com.example.ui.components.tvFocusHighlight
import com.example.ui.player.SubtitleSheet
import com.example.ui.theme.CineStreamTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FocusHighlightRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tvFocusHighlightTracksExactFocusedButton() {
        var firstFocused by mutableStateOf(false)
        var secondFocused by mutableStateOf(false)
        val firstRequester = FocusRequester()
        val secondRequester = FocusRequester()

        composeRule.setContent {
            CineStreamTheme {
                Column {
                    Button(
                        onClick = {},
                        modifier = Modifier
                            .focusRequester(firstRequester)
                            .tvFocusHighlight(onFocusStateChanged = { firstFocused = it })
                            .testTag("btn_one")
                    ) { Text("One") }

                    Button(
                        onClick = {},
                        modifier = Modifier
                            .focusRequester(secondRequester)
                            .tvFocusHighlight(onFocusStateChanged = { secondFocused = it })
                            .testTag("btn_two")
                    ) { Text("Two") }
                }
            }
        }

        composeRule.runOnIdle { firstRequester.requestFocus() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("btn_one").assertIsFocused()
        composeRule.onNodeWithTag("btn_two").assertIsNotFocused()
        assertTrue("First button should report focused state", firstFocused)
        assertFalse("Second button should not report focused state", secondFocused)

        composeRule.runOnIdle { secondRequester.requestFocus() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("btn_one").assertIsNotFocused()
        composeRule.onNodeWithTag("btn_two").assertIsFocused()
        assertFalse("First button should clear focused state", firstFocused)
        assertTrue("Second button should report focused state", secondFocused)
    }

    @Test
    fun subtitleSheetControlsAndStyleChipsAreFocusable() {
        composeRule.setContent {
            CineStreamTheme {
                SubtitleSheet(
                    subtitleTracks = listOf(
                        TrackOption(
                            id = "sub_en",
                            label = "English",
                            detail = "SRT",
                            language = "en",
                            groupIndex = 0,
                            trackIndex = 0,
                            isSelected = true,
                            isExternal = true
                        )
                    ),
                    subtitlesDisabled = false,
                    hasExternalSubtitles = true,
                    subtitleOffsetMs = 0L,
                    appearance = SubtitleAppearance(),
                    onDismiss = {},
                    onPickSubtitleFile = {},
                    onLoadSubtitleUrl = {},
                    onSelectTrack = {},
                    onDisableSubtitles = {},
                    onAdjustOffset = {},
                    onResetOffset = {},
                    onUpdateTextSize = {},
                    onUpdateColor = {},
                    onUpdateBg = {}
                )
            }
        }

        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.waitForIdle()

        // Subtitle track rows, offset controls, and style chips must all accept D-Pad focus cleanly
        composeRule.onNodeWithTag("subtitle_track_off").requestFocus()
        composeRule.onNodeWithTag("subtitle_track_off").assertIsFocused()

        composeRule.onNodeWithTag("subtitle_offset_plus").requestFocus()
        composeRule.onNodeWithTag("subtitle_offset_plus").assertIsFocused()

        composeRule.onNodeWithTag("subtitle_size_chip_large").requestFocus()
        composeRule.onNodeWithTag("subtitle_size_chip_large").assertIsFocused()
    }
}
