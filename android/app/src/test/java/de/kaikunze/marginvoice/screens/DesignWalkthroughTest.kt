package de.kaikunze.marginvoice.screens

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureScreenRoboImage
import de.kaikunze.marginvoice.MarginVoiceApplication
import de.kaikunze.marginvoice.MainActivity
import de.kaikunze.marginvoice.core.ChapterMark
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.core.ReviewTag
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.model.ColorTheme
import de.kaikunze.marginvoice.model.Comment
import de.kaikunze.screentest.FormFactor
import de.kaikunze.screentest.FormFactors
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Every screen, sheet, menu and dialog on a phone, in each colour theme, including the
 * windows `ScreenshotTest` can't see (sheets and menus are popups).
 * Output: build/walkthrough/<theme>/. For looking at, no assertions on the pixels.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DesignWalkthroughTest(private val theme: ColorTheme) {
    @get:Rule val rule = createEmptyComposeRule()

    @Serializable private data class Chapters(val duration: Double, val chapters: List<ChapterMark>)

    private val graph get() = ApplicationProvider.getApplicationContext<MarginVoiceApplication>().graph
    private val out by lazy { File("build/walkthrough/${theme.name.lowercase()}").apply { mkdirs() } }

    private fun shot(name: String) {
        rule.waitForIdle()
        captureScreenRoboImage(File(out, "$name.png").path, RoborazziOptions(taskType = RoborazziTaskType.Record))
    }

    private fun click(m: SemanticsMatcher) {
        rule.waitUntilAtLeastOneExists(m, 10_000)
        rule.onNode(m).performClick()
        rule.waitForIdle()
    }

    private fun seed() {
        val fixtures = File("../core/src/test/resources/fixtures")
        // unpublished fixture, not in the public repository (core/src/test/resources/fixtures/README.md)
        org.junit.Assume.assumeTrue("private fixture icra.txt is not in this checkout", File(fixtures, "icra.txt").exists())
        val ch = Json { ignoreUnknownKeys = true }.decodeFromString(Chapters.serializer(), File(fixtures, "icra_chapters.json").readText())
        val (folder, dir) = graph.store.makeBookFolder()
        File(fixtures, "icra.txt").copyTo(File(dir, "narration.txt"))
        File(dir, "book.m4b").writeText("placeholder")
        val book = Book(
            title = "A Paper Under Review", folder = folder, audioFile = "book.m4b",
            textFile = "narration.txt", timingFile = null, language = "en", duration = ch.duration, chapters = ch.chapters,
            position = 512.0, rate = 1.2,
        )
        val tags = listOf(ReviewTag.STRENGTH, ReviewTag.WEAKNESS, ReviewTag.QUESTION, ReviewTag.MINOR, ReviewTag.SUMMARY)
        for ((i, tag) in tags.withIndex()) {
            book.comments.add(
                Comment(
                    kind = CommentRecord.Kind.COMMENT, mediaTime = 100.0 + 40 * i, estimatedOffset = 2000 + 900 * i, confidence = 0.3,
                    chapter = "Introduction", audioFile = "c$i.m4a", duration = 6.0, transcript = "This claim needs a citation.",
                    transcriptStatus = Comment.TranscriptStatus.DONE,
                ).also { it.reviewTag = tag.name.lowercase() },
            )
        }
        book.comments.add(Comment(kind = CommentRecord.Kind.COMMENT, mediaTime = 420.0, estimatedOffset = 9100, confidence = 0.3, chapter = "Related Work", audioFile = "c9.m4a", duration = 4.0))
        book.comments.add(Comment(kind = CommentRecord.Kind.BOOKMARK, mediaTime = 470.0, estimatedOffset = 10100, confidence = 0.3, chapter = "Problem and notation", audioFile = null, endMediaTime = 490.0, endEstimatedOffset = 10600))
        val (folder2, dir2) = graph.store.makeBookFolder()
        File(dir2, "book.m4b").writeText("placeholder")
        val finished = Book(
            title = "Projektskizze WallBOT", folder = folder2, audioFile = "book.m4b", textFile = null, timingFile = null, language = "de",
            duration = 1800.0, chapters = emptyList(), position = 1800.0, rate = 1.0, addedAt = java.time.Instant.now().minusSeconds(3600),
        )
        graph.library.books.addAll(listOf(finished, book))
        graph.library.save()
    }

    private fun launch(open: Int? = null, show: String? = null, body: () -> Unit) {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
        if (open != null) intent.putExtra("open", open)
        if (show != null) intent.putExtra("show", show)
        ActivityScenario.launch<MainActivity>(intent).use { body() }
    }

    @Test fun walkthrough() {
        FormFactors.phone.apply()
        graph.settings.colorTheme = theme
        graph.settings.voiceCommandsEnabled = false

        launch {
            rule.waitUntilAtLeastOneExists(hasText("Library"), 20_000)
            shot("00-library-empty")
        }
        seed()
        launch {
            rule.waitUntilAtLeastOneExists(hasText("Library"), 20_000)
            shot("01-library")
            click(hasContentDescription("Add"))
            shot("02-library-add-menu")
        }
        launch {
            rule.waitUntilAtLeastOneExists(hasText("Library"), 20_000)
            click(hasContentDescription("Add"))
            click(hasText("Sample Papers", substring = true))
            shot("03-samples-sheet")
        }
        launch(open = 0) {
            rule.waitUntilAtLeastOneExists(hasContentDescription("Add comment"), 20_000)
            // the book's text loads off the main thread; the main looper has to run for it to arrive
            repeat(400) {
                if (graph.session.content == null) {
                    org.robolectric.shadows.ShadowLooper.idleMainLooper()
                    Thread.sleep(25)
                }
            }
            shot("10-player-voice-off")
            graph.settings.voiceCommandsEnabled = true
            graph.session.pauseListening("walkthrough")
            shot("11-player-listening-paused")
            graph.session.setVoiceCommands(false)
            click(hasContentDescription("Chapters"))
            shot("12-chapters-sheet")
        }
        launch(open = 0, show = "export") {
            rule.waitUntilAtLeastOneExists(hasContentDescription("Add comment"), 20_000)
            rule.mainClock.advanceTimeBy(1000)
            shot("13-export-sheet")
        }
        launch(open = 0, show = "notes") {
            rule.waitUntilAtLeastOneExists(hasText("Comments & Bookmarks"), 20_000)
            shot("20-comments")
            click(hasContentDescription("More"))
            shot("21-comments-menu")
        }
        launch(open = 0, show = "notes") {
            rule.waitUntilAtLeastOneExists(hasText("Comments & Bookmarks"), 20_000)
            rule.onAllNodes(hasText("This claim needs a citation.", substring = true))[0].performClick()
            rule.waitForIdle()
            shot("22-comment-detail")
        }
        launch(show = "settings") {
            rule.waitUntilAtLeastOneExists(hasText("Command Language"), 20_000)
            shot("30-settings-voice")
            for ((i, section) in listOf("Audio", "Sync", "Appearance", "Licences").withIndex()) {
                val node = hasText(section, substring = true)
                if (rule.onAllNodes(node).fetchSemanticsNodes().isEmpty()) continue
                rule.onAllNodes(node)[0].performScrollTo()
                shot("3${i + 1}-settings-${section.lowercase()}")
            }
            click(hasText("Command Language"))
            shot("35-settings-choice-menu")
        }
        launch(show = "settings") {
            rule.waitUntilAtLeastOneExists(hasText("What You Can Say"), 20_000)
            click(hasText("What You Can Say"))
            shot("36-commands")
        }
        launch(show = "log") {
            rule.waitForIdle()
            shot("37-log")
        }
        launch(show = "settings") {
            rule.waitUntilAtLeastOneExists(hasText("Command Language"), 20_000)
            val licences = hasText("Licences", substring = true)
            rule.onAllNodes(licences)[0].performScrollTo().performClick()
            rule.waitForIdle()
            shot("38-licences")
        }
    }

    /** Waits for the book's text, which loads off the main thread. */
    private fun awaitText() {
        repeat(400) {
            if (graph.session.content == null) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                Thread.sleep(25)
            }
        }
    }

    /** The v1 wide layouts: medium (player beside comments) and expanded (rail, list and detail). Output: build/walkthrough/<theme>/4x-, 5x-. */
    @Test fun wideWindows() {
        graph.settings.colorTheme = theme
        graph.settings.voiceCommandsEnabled = false
        seed()
        for ((name, form) in listOf("40-medium" to FormFactor("medium", "w720dp-h900dp-port-xhdpi"), "41-medium-600" to FormFactor("small-tablet", "w600dp-h960dp-port-xhdpi"))) {
            form.apply()
            launch(open = 0) {
                rule.waitUntilAtLeastOneExists(hasContentDescription("Add comment"), 20_000)
                awaitText()
                // the playhead past the fourth comment: its row is outlined
                graph.session.seek(245.0)
                rule.mainClock.advanceTimeBy(1000)
                shot("$name-player-comments")
            }
        }
        FormFactors.devices.first { it.name == "tablet-landscape" }.apply()
        launch(open = 0) {
            rule.waitUntilAtLeastOneExists(hasContentDescription("Add comment"), 20_000)
            awaitText()
            shot("50-expanded-library-player")
        }
        launch(open = 0, show = "notes") {
            rule.waitUntilAtLeastOneExists(hasText("Comments & Bookmarks"), 20_000)
            awaitText()
            shot("51-expanded-comments")
            rule.onAllNodes(hasText("This claim needs a citation.", substring = true))[1].performClick()
            rule.waitForIdle()
            shot("52-expanded-comment-detail")
            click(hasText("Edit transcript"))
            shot("53-expanded-comment-editing")
        }
        FormFactors.devices.first { it.name == "fold-open" }.apply()
        launch(open = 0, show = "notes") {
            rule.waitUntilAtLeastOneExists(hasText("Comments & Bookmarks"), 20_000)
            awaitText()
            rule.onAllNodes(hasText("Passage", substring = true))[0].performClick()
            rule.waitForIdle()
            shot("54-fold-open-passage-detail")
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun themes(): List<Array<Any>> = ColorTheme.entries.map { arrayOf<Any>(it) }
    }
}
