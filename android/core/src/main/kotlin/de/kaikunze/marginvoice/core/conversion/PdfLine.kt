package de.kaikunze.marginvoice.core.conversion

/** One text line of a PDF page with its typography and position. */
data class PdfLine(
    val page: Int,
    val text: String,
    val fontSize: Double,
    val isBold: Boolean,
    /** Top of the line, 0 = top of page, 1 = bottom. */
    val top: Double,
    /** Left edge and width, as fractions of the page width. */
    val left: Double,
    val width: Double,
    /** Line height as a fraction of the page height. */
    val height: Double,
)

/**
 * Collects the glyphs a PDF text extractor reports (PDFBox on Android and in the
 * JVM tests) into [PdfLine]s, the way PDFKit's line selections do on iOS.
 */
class PdfLineBuilder {
    /** One glyph: position in points from the page's top-left corner; `fontSize` is the vertical size (PDFKit's point size), not PDFBox's `fontSizeInPt`, which follows horizontal stretching. */
    data class Glyph(
        val text: String, val x: Double, val baseline: Double, val width: Double, val fontSize: Double,
        val fontName: String, val forceBold: Boolean,
    )

    private val lines = ArrayList<PdfLine>()
    private val text = StringBuilder()
    private val glyphs = ArrayList<Glyph>()
    private var page = 0
    private var pageWidth = 1.0
    private var pageHeight = 1.0

    fun startPage(index: Int, width: Double, height: Double) {
        endLine()
        page = index
        pageWidth = width
        pageHeight = height
    }

    fun append(s: String) { text.append(s) }

    fun add(glyph: Glyph) {
        glyphs.add(glyph)
        text.append(glyph.text)
    }

    fun endLine() {
        val line = text.toString()
        val gs = glyphs.toList()
        text.clear()
        glyphs.clear()
        if (gs.isEmpty() || line.none { !it.isWhitespace() } || pageHeight <= 0) return
        // character-weighted dominant font size and boldness of the line
        val sizes = HashMap<Double, Int>()
        var boldChars = 0
        for (g in gs) {
            val n = g.text.length
            val key = Math.round(g.fontSize * 2) / 2.0
            sizes[key] = (sizes[key] ?: 0) + n
            if (isBold(g)) boldChars += n
        }
        val size = sizes.maxByOrNull { it.value }?.key ?: 10.0
        val chars = gs.sumOf { it.text.length }
        val minX = gs.minOf { it.x }
        val maxX = gs.maxOf { it.x + it.width }
        // PDFKit's line box: about 0.93 of the font size around the baseline
        val ascent = gs.maxOf { it.fontSize * 0.75 }
        val descent = gs.maxOf { it.fontSize * 0.18 }
        val top = gs.minOf { it.baseline } - ascent
        val bottom = gs.maxOf { it.baseline } + descent
        if (maxX - minX <= 0) return
        lines.add(PdfLine(
            page = page, text = line.trimEnd('\n', '\r'), fontSize = size, isBold = boldChars * 2 > chars,
            top = top / pageHeight, left = minX / pageWidth, width = (maxX - minX) / pageWidth, height = (bottom - top) / pageHeight,
        ))
    }

    fun finish(): List<PdfLine> {
        endLine()
        return lines
    }

    companion object {
        private val subsetPrefix = Regex("^[A-Z]{6}\\+")

        fun isBold(g: Glyph): Boolean {
            // PDFKit substitutes a regular system face for subset-embedded fonts ("ABCDEF+Times-Bold")
            // and reports them as not bold; the layout heuristics were tuned on that.
            if (subsetPrefix.containsMatchIn(g.fontName)) return false
            val name = g.fontName.lowercase()
            return g.forceBold || name.contains("bold") || name.contains("semibold") || name.contains("black") ||
                name.contains("heavy") || name.endsWith(".b") || name.contains("cmbx")
        }
    }
}
