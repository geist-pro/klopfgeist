package de.kaikunze.marginvoice.screens

import android.content.Intent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import de.kaikunze.marginvoice.MainActivity
import de.kaikunze.marginvoice.MarginVoiceApplication
import de.kaikunze.marginvoice.core.ChapterMark
import de.kaikunze.marginvoice.model.Book
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * What a screen reader is told about the player's controls, which show an icon and no words:
 * a name, the button role, a click action, and a touch target of at least 48 dp.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class PlayerSemanticsTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun player(body: () -> Unit) {
        val graph = ApplicationProvider.getApplicationContext<MarginVoiceApplication>().graph
        graph.settings.voiceCommandsEnabled = false
        val (folder, dir) = graph.store.makeBookFolder()
        File(dir, "book.m4b").writeText("placeholder")
        graph.library.books.add(
            Book(
                title = "A Paper", folder = folder, audioFile = "book.m4b", textFile = null, timingFile = null, language = "en",
                duration = 600.0, chapters = listOf(ChapterMark("Introduction", 0.0, 300.0), ChapterMark("Method", 300.0, 600.0)),
                position = 120.0, rate = 1.2,
            ),
        )
        graph.library.save()
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).putExtra("open", 0)
        ActivityScenario.launch<MainActivity>(intent).use {
            rule.waitUntilAtLeastOneExists(hasContentDescription("Add comment"), 20_000)
            body()
        }
    }

    /**
     * Exactly one such button is on screen (the control dock also measures layouts it then does not place),
     * and its touch target, which may reach beyond what is drawn, is at least 48 dp each way.
     */
    private fun assertIconButton(description: String) {
        val nodes = rule.onAllNodes(hasContentDescription(description) and button and hasClickAction()).fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }
        assertEquals("buttons named “$description”", 1, nodes.size)
        val node = nodes.single()
        val target = node.touchBoundsInRoot
        val density = node.layoutInfo.density.density
        assertTrue("“$description” is ${target.width / density} dp wide", target.width / density >= 47.5f)
        assertTrue("“$description” is ${target.height / density} dp high", target.height / density >= 47.5f)
    }

    @Test fun playAndCommentAreNamedButtons() = player {
        assertIconButton("Play")
        assertIconButton("Add comment")
    }

    @Test fun theSmallControlsAreNamedButtons() = player {
        for (name in listOf("Chapters", "Export", "Comments and Bookmarks", "Back 15 seconds", "Repeat sentence", "Bookmark", "Playback speed 1.2×")) {
            assertIconButton(name)
        }
    }

    @Test fun thePositionIsAnAdjustableRange() = player {
        rule.onNode(hasContentDescription("Position") and SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertExists()
            .assertHeightIsAtLeast(48.dp)
    }
}
