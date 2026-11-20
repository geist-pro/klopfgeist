package de.kaikunze.marginvoice.screens

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import de.kaikunze.marginvoice.MarginVoiceApplication
import de.kaikunze.marginvoice.MainActivity
import de.kaikunze.marginvoice.core.ChapterMark
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.model.Comment
import de.kaikunze.screentest.FormFactor
import de.kaikunze.screentest.FormFactors
import de.kaikunze.screentest.LayoutChecks
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Library, player, comments and settings on every form factor, rendered on the JVM
 * (Robolectric native graphics), with LayoutChecks on each screen.
 * Output: build/screens/<form factor>/.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotTest(private val form: FormFactor) {
    @get:Rule val rule = createEmptyComposeRule()

    private val checks = LayoutChecks(clipAllowedTags = listOf("truncates"), boardTag = null)

    @Serializable private data class Chapters(val duration: Double, val chapters: List<ChapterMark>)

    /** Two books: a conference paper with doc2audio's narration and chapters, half listened, with notes; a finished one. */
    private fun seed() {
        val graph = ApplicationProvider.getApplicationContext<MarginVoiceApplication>().graph
        graph.settings.voiceCommandsEnabled = false
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
        book.comments.add(Comment(kind = CommentRecord.Kind.COMMENT, mediaTime = 180.0, estimatedOffset = 4200, confidence = 0.3, chapter = "Introduction", audioFile = "c1.m4a", duration = 6.0, transcript = "This claim needs a citation.", transcriptStatus = Comment.TranscriptStatus.DONE))
        book.comments.add(Comment(kind = CommentRecord.Kind.COMMENT, mediaTime = 420.0, estimatedOffset = 9100, confidence = 0.3, chapter = "Related Work", audioFile = "c2.m4a", duration = 4.0))
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

    @Test fun screens() {
        form.apply()
        seed()
        val out = File("build/screens/${form.name}").apply { mkdirs() }
        val issues = ArrayList<String>()
        val scenes = listOf(
            Scene("01-library", null, hasText("Library")),
            Scene("02-player", "", hasContentDescription("Add comment")),
            Scene("03-comments", "notes", hasText("This claim needs a citation.")),
            Scene("04-settings", "settings", hasText("Command Language")),
        )
        for (scene in scenes) {
            val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            if (scene.show != null) intent.putExtra("open", 0)
            if (!scene.show.isNullOrEmpty()) intent.putExtra("show", scene.show)
            ActivityScenario.launch<MainActivity>(intent).use {
                rule.waitUntilAtLeastOneExists(scene.ready, 20_000)
                rule.waitForIdle()
                rule.onRoot().captureRoboImage(File(out, "${scene.name}.png").path, roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record))
                issues += checks.run(rule, form, scene.name)
            }
        }
        assertTrue("Layout problems on ${form.name}:\n" + issues.joinToString("\n"), issues.isEmpty())
    }

    private class Scene(val name: String, val show: String?, val ready: androidx.compose.ui.test.SemanticsMatcher)

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun devices() = FormFactors.parameters()
    }
}
