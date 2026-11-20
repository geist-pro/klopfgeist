package de.kaikunze.marginvoice.screens

import android.content.Intent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import de.kaikunze.marginvoice.MainActivity
import de.kaikunze.marginvoice.MarginVoiceApplication
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.core.ReviewTag
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.model.Comment
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The v1 wide layouts through the real activity: what each size class shows, and what survives recreation and a fold. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WideLayoutTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val graph get() = ApplicationProvider.getApplicationContext<MarginVoiceApplication>().graph
    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
    private val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun seed(): Book {
        graph.settings.voiceCommandsEnabled = false
        val (folder, dir) = graph.store.makeBookFolder()
        File(dir, "book.m4b").writeText("placeholder")
        val book = Book(
            title = "Sample Paper", folder = folder, audioFile = "book.m4b", textFile = null, timingFile = null,
            language = "en", duration = 3000.0, chapters = emptyList(), position = 0.0, rate = 1.0,
        )
        for (i in 0 until 4) {
            book.comments.add(
                Comment(
                    kind = CommentRecord.Kind.COMMENT, mediaTime = 100.0 + 300 * i, estimatedOffset = null, confidence = 0.3,
                    chapter = "Chapter $i", audioFile = null, transcript = "Remark number $i", transcriptStatus = Comment.TranscriptStatus.DONE,
                ).also { if (i == 2) it.tag = ReviewTag.WEAKNESS },
            )
        }
        book.comments.add(Comment(kind = CommentRecord.Kind.BOOKMARK, mediaTime = 1500.0, estimatedOffset = null, confidence = 0.3, chapter = null, audioFile = null))
        graph.library.books.add(book)
        graph.library.save()
        return book
    }

    private fun launch(show: String? = null): ActivityScenario<MainActivity> {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).putExtra("open", 0)
        if (show != null) intent.putExtra("show", show)
        return ActivityScenario.launch(intent)
    }

    @Test fun mediumShowsThePlayerBesideItsComments() {
        RuntimeEnvironment.setQualifiers("w700dp-h900dp-port-xhdpi")
        seed()
        launch().use {
            rule.waitUntilAtLeastOneExists(hasText("Comments & Bookmarks"), 20_000)
            rule.onNodeWithContentDescription("Player").assertExists()
            rule.onNodeWithText("Remark number 0").assertExists()
            rule.onNodeWithText("Bookmark · 25:00").assertExists()
            // Play and Comment are the wide 80 dp buttons, named after what a tap does
            rule.onNodeWithContentDescription("Play").assert(button).assertHasClickAction().assertHeightIsAtLeast(80.dp)
            rule.onNodeWithContentDescription("Add comment").assert(button).assertHasClickAction().assertHeightIsAtLeast(80.dp)
            rule.onNodeWithContentDescription("Back 15 seconds").assertHasClickAction().assertHeightIsAtLeast(48.dp)
            // the comments are on screen: no button for them in the bar, and no rail in a medium window
            rule.onNodeWithContentDescription("Comments and Bookmarks", substring = true).assertDoesNotExist()
            rule.onNodeWithText("Settings").assertDoesNotExist()
            // a comment opens the existing full-screen detail
            rule.onNodeWithText("Remark number 1").performClick()
            rule.waitUntilAtLeastOneExists(hasText("Transcript"), 10_000)
            rule.onNodeWithContentDescription("Back").assertExists()
        }
    }

    @Test fun expandedHasARailOnEveryTopLevelDestination() {
        RuntimeEnvironment.setQualifiers("w1280dp-h800dp-land-xhdpi")
        seed()
        launch().use {
            rule.waitUntilAtLeastOneExists(hasContentDescription("Add comment"), 20_000)
            for (label in listOf("Library", "Player", "Comments", "Settings")) rule.onNode(hasText(label) and tab).assertHasClickAction()
            rule.onNode(hasText("Player") and tab).assertIsSelected()
            rule.onNode(hasText("Comments") and tab).performClick()
            rule.waitUntilAtLeastOneExists(hasText("Comments & Bookmarks"), 10_000)
            rule.onNodeWithText("No Comment Selected").assertExists()
            rule.onNodeWithText("Sample Paper · 4 comments · 1 bookmark").assertExists()
            rule.onNode(hasText("Settings") and tab).performClick()
            rule.waitUntilAtLeastOneExists(hasText("Command Language"), 10_000)
            rule.onNode(hasText("Settings") and tab).assertIsSelected()
        }
    }

    @Test fun expandedCommentsAreListAndDetailAndTheSelectionSurvivesRecreation() {
        RuntimeEnvironment.setQualifiers("w1280dp-h800dp-land-xhdpi")
        seed()
        launch(show = "notes").use { scenario ->
            rule.waitUntilAtLeastOneExists(hasText("Comments & Bookmarks"), 20_000)
            rule.onNodeWithText("Remark number 2").performClick()
            rule.waitUntilAtLeastOneExists(hasText("Play from here"), 10_000)
            // the detail shows the chosen comment beside the list, without a back arrow
            rule.onNodeWithText("Comment · 11:40 · Chapter 2").assertExists()
            rule.onNodeWithContentDescription("Back").assertDoesNotExist()
            rule.onNode(hasText("Weakness") and radio).assertIsSelected()
            // choosing another replaces the selection
            rule.onNodeWithText("Remark number 3").performClick()
            rule.waitUntilAtLeastOneExists(hasText("Comment · 16:40 · Chapter 3"), 10_000)

            scenario.recreate()
            rule.waitUntilAtLeastOneExists(hasText("Comment · 16:40 · Chapter 3"), 20_000)

            // back clears the selection before it leaves the comments
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            rule.waitUntilAtLeastOneExists(hasText("No Comment Selected"), 10_000)
        }
    }

    @Test fun foldingKeepsTheSelectedCommentAndTheFilter() {
        RuntimeEnvironment.setQualifiers("w1280dp-h800dp-land-xhdpi")
        val book = seed()
        launch(show = "notes").use { scenario ->
            rule.waitUntilAtLeastOneExists(hasText("Comments & Bookmarks"), 20_000)
            // the filter's segment, not the rail's item of the same name (a tab)
            rule.onNode(hasText("Comments") and radio).performClick()
            rule.onNodeWithText("Remark number 1").performClick()
            rule.waitUntilAtLeastOneExists(hasText("Play from here"), 10_000)

            // folded to a phone: the same comment, full screen with its back arrow
            RuntimeEnvironment.setQualifiers("w360dp-h720dp-xxhdpi")
            scenario.recreate()
            rule.waitUntilAtLeastOneExists(hasText("Transcript"), 20_000)
            rule.onNodeWithText("Remark number 1").assertExists()
            rule.onNodeWithContentDescription("Back").performClick()
            // the list is still filtered to comments: the bookmark stays hidden
            rule.waitUntilAtLeastOneExists(hasText("Comments & Bookmarks"), 10_000)
            rule.onNodeWithText("Remark number 0").assertExists()
            assertEquals(0, rule.onAllNodes(hasContentDescription("Bookmark")).fetchSemanticsNodes().size)
            assertEquals(5, book.comments.size)
        }
    }
}
