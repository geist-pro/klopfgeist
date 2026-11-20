package de.kaikunze.marginvoice.conversion

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.core.NarrationText
import de.kaikunze.marginvoice.core.ReviewTag
import de.kaikunze.marginvoice.core.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Robolectric: pdfbox-android reads its glyph list from Android assets, as the app does in `DocumentExtractor`. */
@RunWith(RobolectricTestRunner::class)
class PdfAnnotatorTests {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun initPdfBox() = PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())

    // the core module's fixtures, relative to this module (Gradle runs tests from the module directory)
    private val fixtures = File("../core/src/test/resources/fixtures")

    @Test fun exportWritesAnnotatedPdf() {
        // unpublished fixture, not in the public repository (core/src/test/resources/fixtures/README.md)
        org.junit.Assume.assumeTrue("private fixture icra.txt is not in this checkout", File(fixtures, "icra.txt").exists())
        val text = NarrationText(File(fixtures, "icra.txt").readText())
        // a weakness on a body sentence of the introduction, a bookmark over the next two, and a comment whose sentence is not in the PDF
        val body = text.sentences.filter { !text.substring(it.range).startsWith("Section:") && it.range.last - it.range.first > 60 }
        val weakness = CommentRecord(mediaTime = 65.0, textOffset = body[2].range.last + 1, confidence = 1.0, transcript = "No baseline.", tag = ReviewTag.WEAKNESS)
        val bookmark = CommentRecord(
            kind = CommentRecord.Kind.BOOKMARK, mediaTime = 10.0, textOffset = body[3].range.first + 2, confidence = 1.0, transcript = "",
            endMediaTime = 20.0, endTextOffset = body[4].range.last,
        )
        val elsewhere = CommentRecord(mediaTime = 90.0, textOffset = 0, confidence = 1.0, transcript = "Lost.")
        val lost = NarrationText("A sentence that the PDF does not contain at all anywhere.\n")

        val output = tmp.newFile("out.pdf")
        val summary = PdfAnnotator.annotate(File(fixtures, "icra.pdf"), output, listOf(weakness, bookmark), text, VoiceCommand.Language.ENGLISH)
        assertEquals(PdfAnnotator.Summary(2, 0), summary)
        assertEquals(PdfAnnotator.Summary(0, 1), PdfAnnotator.annotate(File(fixtures, "icra.pdf"), tmp.newFile("lost.pdf"), listOf(elsewhere), lost, VoiceCommand.Language.ENGLISH))

        PDDocument.load(output).use { doc ->
            val annotations = (0 until doc.numberOfPages).flatMap { doc.getPage(it).annotations }
            val highlights = annotations.filterIsInstance<PDAnnotationTextMarkup>()
            val notes = annotations.filterIsInstance<PDAnnotationText>()
            assertTrue("${highlights.size} highlights", highlights.size >= 2)
            assertEquals(2, notes.size)
            val weak = highlights.first { it.contents.contains("[Weakness] 1:05 · No baseline.") }
            val box = doc.getPage(0).cropBox
            // QuadPoints in page space, inside the page, matching the rectangle
            assertEquals(8, weak.quadPoints.size)
            assertTrue(weak.quadPoints.filterIndexed { i, _ -> i % 2 == 0 }.all { it >= box.lowerLeftX && it <= box.upperRightX })
            assertTrue(weak.quadPoints.filterIndexed { i, _ -> i % 2 == 1 }.all { it >= box.lowerLeftY && it <= box.upperRightY })
            assertEquals(weak.quadPoints[0], weak.quadPoints[4], 0.01f) // upper-left and lower-left share x
            // the appearance handler pads the rectangle by a few points around the quad
            assertTrue(weak.rectangle.lowerLeftX <= weak.quadPoints[4] && weak.rectangle.lowerLeftX > weak.quadPoints[4] - 6)
            assertTrue(weak.rectangle.upperRightY >= weak.quadPoints[1] && weak.rectangle.upperRightY < weak.quadPoints[1] + 6)
            assertTrue(weak.appearance != null) // drawn by viewers that render only what is in the file
        }
    }
}
