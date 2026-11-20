package de.kaikunze.marginvoice.core.conversion

import kotlin.math.abs

/**
 * Rebuilds document structure from PDF lines (heuristics ported from doc2audio's
 * `extract_pdf.py`): body font, headings by size/weight/numbering, running
 * headers and footers, title page authors, captions, references cut-off.
 */
object PdfLayoutAnalyzer {
    data class Options(val includeAppendix: Boolean = false)

    private class CaptionState(val kind: Char, val number: String, val lines: MutableList<String>)

    fun document(allLines: List<PdfLine>, pageCount: Int, fallbackTitle: String?, options: Options = Options()): SourceDocument {
        var lines = allLines.map { it.copy(text = TextCleanup.normalize(it.text)) }.filter { it.text.isNotEmpty() }
        if (lines.isEmpty()) return SourceDocument(fallbackTitle, emptyList())

        val body = bodySize(lines)
        val repeated = repeatingLines(lines, pageCount)
        lines = lines.filterNot { l ->
            (repeatKey(l.text) in repeated && (l.top < 0.12 || l.top > 0.88)) ||
                l.text.rxContains("""^\d{1,4}$""") || // page and line numbers
                (TextCleanup.isBoilerplate(l.text) && !TextCleanup.startsFrontMatterBlock(l.text)) // blocks are skipped as a whole below
        }
        if (lines.isEmpty()) return SourceDocument(fallbackTitle, emptyList())
        val typicalWidth = median(lines.filter { abs(it.fontSize - body) < 0.6 }.map { it.width }) ?: 0.4
        val margins = columnMargins(lines, body)

        // title: the largest text on the first page, above the body size
        var title: String? = null
        var index = 0
        val firstPage = lines.filter { it.page == lines[0].page }
        val maxSize = firstPage.maxOfOrNull { it.fontSize }
        if (maxSize != null && maxSize >= body * 1.3) {
            val parts = ArrayList<String>()
            while (index < lines.size && lines[index].page == lines[0].page && lines[index].fontSize < maxSize - 0.5 && parts.isEmpty() && index < 6) {
                index++ // skip small lines above the title (venue, preprint notes)
            }
            while (index < lines.size && abs(lines[index].fontSize - maxSize) < 0.6) {
                parts.add(lines[index].text)
                index++
            }
            if (parts.isNotEmpty()) title = TextCleanup.joinLines(parts) else index = 0
        }

        val blocks = ArrayList<SourceDocument.Block>()
        val paragraph = ArrayList<PdfLine>()
        var caption: CaptionState? = null
        var seenHeading = false
        // Inside keywords / CCS concepts / contact information on the title page.
        var inFrontMatter = false
        // Inside acknowledgments or funding, until the next heading.
        var inSkippedSection = false
        // Below a table caption: cells until prose resumes at the column edge.
        var inTableBody = false
        var tableRows = 0
        val titlePage = lines[0].page

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            val text = TextCleanup.stripCitations(TextCleanup.joinLines(paragraph.map { it.text }))
            paragraph.clear()
            if (text.isEmpty()) return
            if (!seenHeading && TextCleanup.looksLikeAuthorLine(text) && text.length < 300) return
            // a short fragment without a sentence is a label inside a figure
            val words = text.split(' ').filter { it.isNotEmpty() }
            if (words.size <= 3 && text.none { it in ".!?:" }) return
            if (words.all { it.length <= 2 }) return // "a b c d" panel letters
            // ACM and Springer abstracts have no heading: a long first paragraph on the title page
            if (!seenHeading && blocks.isEmpty() && text.length > 400) blocks.add(SourceDocument.Block.Heading("Abstract", 2))
            val run = TextCleanup.splitRunInHeading(text)
            if (run != null) {
                blocks.add(SourceDocument.Block.Heading(run.heading, run.level))
                blocks.add(SourceDocument.Block.Paragraph(run.rest))
                return
            }
            if (text.startsWith("•") || text.startsWith("◦") || text.startsWith("– ")) {
                blocks.add(SourceDocument.Block.ListItem(text.dropWhile { it in "•◦– " }))
            } else {
                blocks.add(SourceDocument.Block.Paragraph(text))
            }
        }

        fun flushCaption() {
            val c = caption ?: return
            inTableBody = c.kind == 'T'
            tableRows = 0
            val text = TextCleanup.stripCitations(TextCleanup.joinLines(c.lines))
            blocks.add(if (c.kind == 'F') SourceDocument.Block.Figure(c.number, text) else SourceDocument.Block.Table(c.number, text))
            caption = null
        }

        fun lettersOrDigits(s: String) = s.count { it.isLetterOrDigit() }

        var previous: PdfLine? = null

        /** Handles one line; returns false to stop (references or appendix). */
        fun process(line: PdfLine): Boolean {
            // title page: authors set in capitals, keywords, contact information, license
            if (!seenHeading && line.page == titlePage && TextCleanup.looksLikeCapitalizedAuthorLine(line.text)) return true
            if (TextCleanup.startsFrontMatterBlock(line.text)) {
                flushParagraph()
                inFrontMatter = true
                return true
            }
            if (inFrontMatter) {
                // the block runs on in the same type until a gap or a new page
                val prev = previous
                if (prev != null && prev.page == line.page && abs(prev.fontSize - line.fontSize) < 0.5 && line.top - prev.top < prev.height * 2) {
                    return true
                }
                inFrontMatter = false
            }
            // headings
            val level = headingLevel(line, body, if (index < lines.size) lines[index] else null)
            if (level != null) {
                val text = TextCleanup.cleanHeading(line.text)
                // "Anonymous Authors" or an author name set larger than the body
                if (!seenHeading && line.page == titlePage && TextCleanup.numberingDepth(line.text) == null && TextCleanup.looksLikeAuthorLine(text)) {
                    return true
                }
                if (TextCleanup.isReferencesHeading(text) || (!options.includeAppendix && TextCleanup.isAppendixHeading(text))) {
                    return false
                }
                flushParagraph()
                flushCaption()
                inFrontMatter = false
                inTableBody = false
                inSkippedSection = TextCleanup.isSkippedSection(text)
                if (inSkippedSection) return true
                // multi-line headings: continue while the next line has the same style
                val parts = arrayListOf(text)
                while (index < lines.size && abs(lines[index].fontSize - line.fontSize) < 0.3 && lines[index].isBold == line.isBold &&
                    lines[index].page == line.page && lines[index].top - line.top < line.height * 3.2 && parts.size < 3 &&
                    headingLevel(lines[index], body, null) != null && TextCleanup.numberingDepth(lines[index].text) == null
                ) {
                    parts.add(lines[index].text)
                    index++
                }
                blocks.add(SourceDocument.Block.Heading(TextCleanup.joinLines(parts), level))
                seenHeading = true
                return true
            }
            if (inSkippedSection) return true
            // IEEE style: "Abstract—Text..." in one line
            val abstractPrefix = """^(Abstract|Zusammenfassung)\s*[—–\-.:]\s*"""
            if (line.text.rxContains(abstractPrefix)) {
                flushParagraph()
                flushCaption()
                blocks.add(SourceDocument.Block.Heading("Abstract", 2))
                seenHeading = true
                paragraph.add(line.copy(text = line.text.rxReplace(abstractPrefix, "")))
                return true
            }
            // captions run until a paragraph break
            TextCleanup.figureCaption(line.text)?.let { f ->
                flushParagraph()
                flushCaption()
                caption = CaptionState('F', f.number, arrayListOf(f.caption))
                return true
            }
            TextCleanup.tableCaption(line.text)?.let { t ->
                flushParagraph()
                flushCaption()
                caption = CaptionState('T', t.number, arrayListOf(t.caption))
                return true
            }
            caption?.let { c ->
                // table captions sit in small caps below "TABLE I"; figure captions run on in caption size
                val limit = if (c.kind == 'T') 3 else 8
                val prev = previous
                if (prev != null && line.fontSize >= body * 0.6 && c.lines.size < limit &&
                    !(c.kind == 'T' && c.lines.size > 1 && line.fontSize >= body * 0.95) &&
                    (!isBreak(prev, line, body, typicalWidth) || c.lines.all { it.isEmpty() })
                ) {
                    c.lines.add(line.text)
                    return true
                }
                flushCaption()
            }
            // stray accents and punctuation split off by math typesetting
            if (lettersOrDigits(line.text) < 2) return true
            // display equations (indented or numbered): consecutive math lines become one "Equation n."
            if (TextCleanup.isEquationLine(line.text) && (line.width < typicalWidth * 0.85 || TextCleanup.equationNumber(line.text) != null)) {
                var number = TextCleanup.equationNumber(line.text)
                while (index < lines.size && lines[index].page == line.page &&
                    (TextCleanup.isEquationLine(lines[index].text) || lettersOrDigits(lines[index].text) < 2)
                ) {
                    number = number ?: TextCleanup.equationNumber(lines[index].text)
                    index++
                }
                // unnumbered display math is skipped without breaking the sentence around it
                if (number != null) {
                    flushParagraph()
                    blocks.add(SourceDocument.Block.Equation(number))
                }
                return true
            }
            if (inTableBody) {
                // the table ends at a wider gap, after which prose resumes at the column edge
                val prev = previous
                val gap = prev?.let { if (it.page == line.page) line.top - it.top else 1.0 } ?: 1.0
                val jump = gap > (prev?.height ?: 0.0) * 2 || gap < 0 // a wider gap, or the next column
                val prose = tableRows > 0 && jump && abs(line.fontSize - body) < 0.6 && margins.any { abs(it - line.left) < 0.004 }
                if (!prose && prev?.page == line.page) {
                    tableRows++
                    return true
                }
                inTableBody = false
            }
            if (line.fontSize < body * 0.88) return true // figure labels, footnotes and other small print
            // labels inside figures and table cells sit off the text column's left edge
            // (continuation lines of a list item or paragraph are indented too, but follow it closely)
            val continues = paragraph.lastOrNull()?.let { p ->
                p.page == line.page && line.top - p.top > 0 && line.top - p.top < p.height * 1.6 && line.left >= p.left - 0.007
            } ?: false
            val bullet = line.text.firstOrNull()?.let { it in "•◦▪–" } ?: false
            // ("τ depends only on the": a sentence fragment between inline math stays)
            val prosy = line.text.rxContains("""\b\p{Ll}{2,}\s+\p{Ll}{2,}\b""")
            if (!continues && !bullet && !prosy && line.width < typicalWidth * 0.6 && margins.isNotEmpty() && margins.none { abs(it - line.left) < 0.007 }) {
                return true
            }
            paragraph.lastOrNull()?.let { if (isBreak(it, line, body, typicalWidth)) flushParagraph() }
            paragraph.add(line)
            return true
        }

        while (index < lines.size) {
            val line = lines[index]
            index++
            val go = process(line)
            previous = line
            if (!go) break
        }
        flushParagraph()
        flushCaption()
        return SourceDocument(title ?: fallbackTitle, blocks)
    }

    /** Heading level, or null for body text. */
    internal fun headingLevel(l: PdfLine, body: Double, next: PdfLine?): Int? {
        val tokens = l.text.split(' ').filter { it.isNotEmpty() }
        val words = tokens.size
        if (words > 16 || l.text.length >= 140 || !TextCleanup.looksLikeHeadingText(l.text)) return null
        // "a b c" panel letters inside a figure are not headings
        if (tokens.none { t -> t.count { it.isLetter() } >= 3 }) return null
        if (TextCleanup.figureCaption(l.text) != null || TextCleanup.tableCaption(l.text) != null) return null
        val endsLikeSentence = (l.text.endsWith(".") && words > 6) || l.text.endsWith(",") || l.text.endsWith(";")
        if (endsLikeSentence) return null
        val depth = TextCleanup.numberingDepth(l.text)
        // IEEE/ACM small caps: "I. INTRODUCTION" set smaller than the body
        val letters = l.text.filter { it.isLetter() }
        if (depth != null && letters.length >= 3 && letters == letters.uppercase() && l.fontSize >= body * 0.7) return minOf(depth, 3)
        // ACM sets 11 pt section headings over a 10 pt body
        if (l.fontSize >= body * 1.08) {
            if (depth != null) return minOf(depth, 3)
            return if (l.fontSize >= body * 1.35) 1 else 2
        }
        // same size: needs bold, or numbering plus title-like capitalization
        if (l.isBold && words <= 12) {
            if (depth != null) return minOf(depth, 3)
            val standalone = next?.let { it.page != l.page || it.top - l.top > l.height * 1.2 || it.isBold != l.isBold } ?: true
            return if (standalone || l.text.uppercase() == l.text) 2 else null
        }
        // numbered line on its own: "A. Problem and notation", "2.1 Data"
        val standalone = next?.let { it.page != l.page || it.top - l.top > l.height * 1.15 || it.left > l.left + 0.01 } ?: true
        if (depth != null && words <= 10 && abs(l.fontSize - body) < 0.6 && (standalone || depth >= 1) &&
            l.text.dropWhile { !it.isLetter() }.firstOrNull()?.isUpperCase() == true && !l.text.endsWith(".")
        ) {
            return minOf(depth, 3)
        }
        return null
    }

    /** Paragraph break between two consecutive lines. */
    internal fun isBreak(a: PdfLine, b: PdfLine, body: Double, typicalWidth: Double): Boolean {
        if (abs(a.fontSize - b.fontSize) > 1.0) return true
        val endsSentence = a.text.lastOrNull()?.let { it in ".!?:" } ?: false
        if (a.page != b.page) return endsSentence && a.width < typicalWidth * 0.8
        val gap = b.top - a.top
        if (gap > a.height * 2.0 || (gap < -0.05 && endsSentence && a.width < typicalWidth * 0.8)) return true // spacing or column jump
        if (endsSentence && a.width < typicalWidth * 0.8) return true // short last line
        if (abs(b.left - a.left) > 0.015 && b.left > a.left && gap > 0 && gap < a.height * 2 && endsSentence) return true // indented first line
        return false
    }

    /**
     * Left edges of the text column(s), including first-line indents: x positions
     * shared by many body-size lines.
     */
    internal fun columnMargins(lines: List<PdfLine>, body: Double): List<Double> {
        val bodyLines = lines.filter { abs(it.fontSize - body) < 0.6 }
        val buckets = HashMap<Long, MutableList<Double>>()
        for (l in bodyLines) buckets.getOrPut(Math.round(l.left * 200)) { ArrayList() }.add(l.left)
        val minimum = maxOf(5, bodyLines.size / 40)
        return buckets.values.filter { it.size >= minimum }.mapNotNull { median(it) }.sorted()
    }

    internal fun bodySize(lines: List<PdfLine>): Double {
        val weights = HashMap<Double, Int>()
        for (l in lines) {
            val key = Math.round(l.fontSize * 2) / 2.0
            weights[key] = (weights[key] ?: 0) + l.text.length
        }
        return weights.maxByOrNull { it.value }?.key ?: 10.0
    }

    internal fun repeatKey(s: String): String = s.lowercase().rxReplace("""\d+""", "#")

    internal fun repeatingLines(lines: List<PdfLine>, pageCount: Int): Set<String> {
        val pages = HashMap<String, MutableSet<Int>>()
        for (l in lines) if (l.top < 0.12 || l.top > 0.88) pages.getOrPut(repeatKey(l.text)) { HashSet() }.add(l.page)
        val threshold = maxOf(2, pageCount / 3)
        return pages.filter { it.value.size >= threshold }.keys
    }

    internal fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return s[s.size / 2]
    }
}
