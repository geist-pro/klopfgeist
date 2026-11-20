package de.kaikunze.marginvoice.core

import de.kaikunze.marginvoice.core.conversion.PdfLine
import kotlin.math.abs

/**
 * Finds the lines of a PDF that a comment's sentence sits on, so the comment can be
 * written back into the PDF as a highlight. The narration differs from the PDF's
 * text layer (ligatures, hyphenation, abbreviations read out, numbers), so each
 * sentence is aligned against every page with the local alignment that refines
 * comment anchors, not searched for as a string. Equations and captions that the
 * narration rewrote may not match and are reported as unplaced.
 */
object PdfAnnotationPlacer {
    /** A box on a page, as fractions of the page size, top-left origin like [PdfLine]. */
    data class Rect(val page: Int, val left: Double, val top: Double, val width: Double, val height: Double)

    data class Placement(
        val page: Int,
        /** One box per line the sentence covers, in reading order. */
        val rects: List<Rect>,
        /** Fraction of the sentence's words found on the page. */
        val confidence: Double,
    )

    /** The pages' text layers as alignment targets, built once per document. */
    class Pages(lines: List<PdfLine>) {
        internal class Page(val index: Int, val text: NarrationText, val lines: List<PdfLine>, /** UTF-16 offset of each line's start in `text`. */ val lineStarts: List<Int>)

        internal val pages: List<Page> = lines.map { it.page }.toSortedSet().map { index ->
            val pageLines = lines.filter { it.page == index }
            val starts = ArrayList<Int>()
            val joined = StringBuilder()
            for (line in pageLines) {
                starts.add(joined.length)
                joined.append(line.text).append('\n')
            }
            Page(index, NarrationText(joined.toString()), pageLines, starts)
        }
        val count: Int get() = pages.size
    }

    /**
     * Where `sentence` sits in the document: the page where most of its words are
     * found, ties going to the page nearest `expectedPage`.
     * @param minimumMatches fewer matched words on the best page means unplaced.
     * @param minimumConfidence a smaller share of matched words means unplaced.
     */
    fun place(sentence: String, pages: Pages, expectedPage: Int? = null, minimumMatches: Int = 4, minimumConfidence: Double = 0.5): Placement? {
        // the aligner accepts min(minimumMatches, query) matches; here a short query is simply unplaceable
        if (TextSegmentation.tokens(sentence).size < minimumMatches) return null
        var bestPage: Pages.Page? = null
        var bestResult: ContextAligner.Result? = null
        for (page in pages.pages) {
            val result = ContextAligner.align(sentence, page.text, near = 0, window = page.text.count + 1, minimumMatches = minimumMatches) ?: continue
            if (result.confidence < minimumConfidence) continue
            val current = bestResult
            if (current != null && bestPage != null) {
                val closer = abs(page.index - (expectedPage ?: page.index)) < abs(bestPage.index - (expectedPage ?: bestPage.index))
                if (!(result.confidence > current.confidence || (result.confidence == current.confidence && closer))) continue
            }
            bestPage = page
            bestResult = result
        }
        val page = bestPage ?: return null
        val result = bestResult ?: return null
        val words = page.text.words.subList(result.wordRange.first, result.wordRange.upper)
        val first = words.firstOrNull() ?: return null
        val last = words.last()
        return Placement(page.index, rects(first.range.first, last.range.upper, page), result.confidence)
    }

    /**
     * Boxes covering characters `from until to` of the page text: a full line for every
     * line in between, a partial one (by character share) for the first and last.
     */
    internal fun rects(from: Int, to: Int, page: Pages.Page): List<Rect> {
        val result = ArrayList<Rect>()
        for ((i, line) in page.lines.withIndex()) {
            val start = page.lineStarts[i]
            val end = start + line.text.length
            if (end <= from || start >= to || line.text.isEmpty()) continue
            val f0 = (maxOf(from, start) - start).toDouble() / line.text.length
            val f1 = (minOf(to, end) - start).toDouble() / line.text.length
            result.add(Rect(line.page, line.left + line.width * f0, line.top, line.width * (f1 - f0), line.height))
        }
        return result
    }
}
