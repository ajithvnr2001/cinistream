package com.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.example.ui.player.SelectableCinemaSeekBar
import com.example.ui.theme.CineStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CinemaSeekBarRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `play bar is selectable and rapidly scrubs forward and backward across minutes via D-Pad`() {
        val durationMs = 60L * 60_000L // 60 minutes
        var committedSeekMs = -1L
        val focusRequester = FocusRequester()

        composeRule.setContent {
            CineStreamTheme {
                var posMs by remember { mutableLongStateOf(10L * 60_000L) } // 10:00
                SelectableCinemaSeekBar(
                    currentPositionMs = posMs,
                    bufferedPositionMs = 20L * 60_000L,
                    durationMs = durationMs,
                    isTv = true,
                    focusRequester = focusRequester,
                    onSeekCommit = { target ->
                        committedSeekMs = target
                        posMs = target
                    },
                    onScrubbingStateChanged = {},
                    onInteraction = {}
                )
            }
        }

        composeRule.runOnIdle { focusRequester.requestFocus() }
        composeRule.waitForIdle()

        val seekBar = composeRule.onNodeWithTag("player_seek_bar")
        seekBar.assertIsDisplayed()
        seekBar.assertIsFocused()

        // Press D-Pad Right 4 times: +1m, +1m, +1m, +2m = +5 minutes (10:00 -> 15:00), then OK to commit
        seekBar.performKeyInput {
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionRight)
            pressKey(Key.DirectionCenter)
        }
        composeRule.waitForIdle()
        assertEquals(15L * 60_000L, committedSeekMs)

        // Press D-Pad Left 2 times: -1m, -1m (15:00 -> 13:00), then OK to commit
        seekBar.performKeyInput {
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionCenter)
        }
        composeRule.waitForIdle()
        assertEquals(13L * 60_000L, committedSeekMs)
    }

    @Test
    fun `dedicated 1m forward and rewind buttons jump exact minutes separately from 10s controls`() {
        val durationMs = 30L * 60_000L // 30 minutes
        var committedSeekMs = 5L * 60_000L // 05:00
        val focusRequester = FocusRequester()

        composeRule.setContent {
            CineStreamTheme {
                var posMs by remember { mutableLongStateOf(committedSeekMs) }
                SelectableCinemaSeekBar(
                    currentPositionMs = posMs,
                    bufferedPositionMs = 10L * 60_000L,
                    durationMs = durationMs,
                    isTv = false,
                    focusRequester = focusRequester,
                    onSeekCommit = { target ->
                        committedSeekMs = target
                        posMs = target
                    },
                    onScrubbingStateChanged = {},
                    onInteraction = {}
                )
            }
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithTag("player_forward_1m_button").performClick()
        composeRule.waitForIdle()
        assertEquals(6L * 60_000L, committedSeekMs)

        composeRule.onNodeWithTag("player_rewind_1m_button").performClick()
        composeRule.waitForIdle()
        assertEquals(5L * 60_000L, committedSeekMs)
    }
}
