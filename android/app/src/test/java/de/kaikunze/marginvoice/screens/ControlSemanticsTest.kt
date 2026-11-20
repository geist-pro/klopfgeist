package de.kaikunze.marginvoice.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import de.kaikunze.marginvoice.model.BookFont
import de.kaikunze.marginvoice.model.ColorTheme
import de.kaikunze.marginvoice.ui.MarginVoiceTheme
import de.kaikunze.marginvoice.ui.Theme
import de.kaikunze.marginvoice.ui.components.BigControlButton
import de.kaikunze.marginvoice.ui.components.Scrubber
import org.junit.Assert.assertEquals
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import de.kaikunze.marginvoice.ui.components.ViewThatFits
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The transport controls on their own: their names follow their state, and the scrubber follows the time without its caller recomposing. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ControlSemanticsTest {
    @get:Rule val rule = createComposeRule()

    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    @Test fun playIsNamedAfterWhatATapDoes() {
        var playing by mutableStateOf(false)
        rule.setContent {
            MarginVoiceTheme(ColorTheme.GRUVBOX, BookFont.NEW_YORK) {
                BigControlButton(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play",
                    Theme.palette.accent, size = 80.dp, prominent = true, onClick = { playing = !playing },
                )
            }
        }
        rule.onNodeWithContentDescription("Play").assert(button).assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithContentDescription("Pause").assert(button).assertHasClickAction()
        rule.onNodeWithContentDescription("Play").assertDoesNotExist()
    }

    @Test fun commentIsNamedAfterWhatATapDoes() {
        var recording by mutableStateOf(false)
        rule.setContent {
            MarginVoiceTheme(ColorTheme.STANDARD, BookFont.NEW_YORK) {
                BigControlButton(
                    if (recording) Icons.Rounded.Stop else Icons.Rounded.Mic, if (recording) "Save comment" else "Add comment",
                    Theme.palette.record, size = 92.dp, prominent = true, onClick = { recording = !recording },
                )
            }
        }
        rule.onNodeWithContentDescription("Add comment").assert(button).assertHasClickAction().assertWidthIsAtLeast(48.dp).performClick()
        rule.onNodeWithContentDescription("Save comment").assert(button).assertHasClickAction()
    }

    @Test fun theScrubberFollowsTheTimeWithoutRecomposingItsCaller() {
        var time by mutableDoubleStateOf(10.0)
        var compositions = 0
        var counted = 0
        rule.setContent {
            MarginVoiceTheme(ColorTheme.GRUVBOX, BookFont.NEW_YORK) {
                SideEffect { counted += 1 }
                Scrubber(time = { time }, duration = 100.0, chapters = emptyList(), markers = emptyList(), onSeek = {})
            }
        }
        rule.onNodeWithText("0:10").assertExists()
        rule.onNodeWithText("-1:30").assertExists()
        rule.onNodeWithContentDescription("Position").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0:10 of 1:40"))
        rule.runOnIdle { compositions = counted }

        time = 65.0
        rule.onNodeWithText("1:05").assertExists()
        rule.onNodeWithText("-0:35").assertExists()
        rule.onNodeWithContentDescription("Position").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1:05 of 1:40"))
        // a tick within the same second changes neither label
        time = 65.2
        rule.onNodeWithText("1:05").assertExists()
        rule.runOnIdle { assertEquals("the scrubber's caller recomposed with the time", compositions, counted) }
    }

    @Test fun viewThatFitsShowsOnlyTheChosenCandidateToAccessibility() {
        rule.setContent {
            MarginVoiceTheme(ColorTheme.GRUVBOX, BookFont.NEW_YORK) {
                ViewThatFits(
                    Modifier.width(200.dp),
                    listOf(
                        { Row { repeat(6) { TextButton(onClick = {}) { Text("Wide") } } } },
                        { TextButton(onClick = {}) { Text("Narrow") } },
                    ),
                )
            }
        }
        // the first candidate is measured and does not fit; neither it nor the probe of the second is announced
        rule.onAllNodesWithText("Wide").assertCountEquals(0)
        rule.onAllNodesWithText("Narrow").assertCountEquals(1)
    }
}
