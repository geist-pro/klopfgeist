package de.kaikunze.marginvoice.core

import de.kaikunze.marginvoice.core.conversion.PdfBoxLineReader
import de.kaikunze.marginvoice.core.conversion.PdfLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PdfAnnotationPlacerTests {
    private fun icraPages(): Pair<PdfAnnotationPlacer.Pages, Int> {
        val read = PdfBoxLineReader.read(Fixture.file("icra.pdf"))
        return PdfAnnotationPlacer.Pages(read.lines) to read.pageCount
    }

    /**
     * Sentences of the doc2audio narration (which rewrote the PDF's text) land on the
     * right page, on boxes inside the page, mostly in reading order.
     */
    @Test fun placesASentenceOnItsLines() {
        val (pages, pageCount) = icraPages()
        val text = Fixture.text("icra")
        // the body sentences of the first pages, long enough to align
        val sentences = text.sentences.take(60).map { text.substring(it.range) }
            .filter { !it.startsWith("Section:") && TextSegmentation.tokens(it).size >= 8 }
        assertTrue(sentences.size >= 30)
        val placed = ArrayList<PdfAnnotationPlacer.Placement>()
        val unplaced = ArrayList<String>()
        for (s in sentences) {
            val p = PdfAnnotationPlacer.place(s, pages, expectedPage = 0)
            if (p != null) placed.add(p) else unplaced.add(s)
        }
        println("placed ${placed.size} of ${sentences.size}; unplaced: ${unplaced.map { it.take(60) }}")
        assertTrue(placed.size >= sentences.size * 0.8)
        for (p in placed) {
            assertTrue(p.page < pageCount && p.page <= 2)
            assertTrue(p.rects.isNotEmpty() && p.rects.size <= 8)
            for (r in p.rects) {
                assertTrue("$r", r.left >= 0 && r.top >= 0 && r.left + r.width <= 1.001 && r.top + r.height <= 1.001 && r.width > 0)
            }
        }
        // reading order: the pages never go backwards by more than one
        for ((a, b) in placed.zipWithNext()) assertTrue(b.page >= a.page - 1)

        // the title sits on the first lines of page 1
        val title = PdfAnnotationPlacer.place(Fixture.file("icra.txt").readLines().first().split(" ").take(3).joinToString(" "), pages, minimumMatches = 2)!!
        assertTrue(title.page == 0 && title.rects.size == 1 && title.rects[0].top < 0.2)
    }

    @Test fun reportsUnplacedSentences() {
        val (pages, _) = icraPages()
        assertNull(PdfAnnotationPlacer.place("The quick brown fox jumps over the lazy dog near the river bank.", pages))
        assertNull(PdfAnnotationPlacer.place("", pages))
        assertNull(PdfAnnotationPlacer.place("prediction", pages)) // one word is not enough
    }

    @Test fun partialLinesCoverOnlyTheSentence() {
        val lines = listOf(
            PdfLine(0, "One two three four five six seven eight.", 10.0, false, top = 0.1, left = 0.1, width = 0.8, height = 0.02),
            PdfLine(0, "Nine ten eleven twelve.", 10.0, false, top = 0.13, left = 0.1, width = 0.5, height = 0.02),
        )
        val p = PdfAnnotationPlacer.place("five six seven eight nine ten", PdfAnnotationPlacer.Pages(lines))!!
        assertEquals(2, p.rects.size)
        // starts at "five" (character 19 of 40 in the first line) and ends after "ten" on the second
        assertTrue(abs(p.rects[0].left - (0.1 + 0.8 * 19 / 40)) < 0.001)
        assertTrue(abs(p.rects[0].left + p.rects[0].width - 0.9) < 0.001)
        assertTrue(abs(p.rects[1].left - 0.1) < 0.001 && p.rects[1].width < 0.5 * 0.45)
    }
}
