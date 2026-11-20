package de.kaikunze.marginvoice.core.conversion

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.File
import java.io.StringWriter

/** Desktop PDFBox twin of the app's `PdfBoxLineReader` (pdfbox-android has the same 2.0 API). */
object PdfBoxLineReader {
    data class Result(val lines: List<PdfLine>, val pageCount: Int)

    fun read(file: File): Result = PDDocument.load(file).use { doc ->
        val builder = PdfLineBuilder()
        val stripper = object : PDFTextStripper() {
            override fun startPage(page: PDPage) {
                val box = page.cropBox
                builder.startPage(currentPageNo - 1, box.width.toDouble(), box.height.toDouble())
            }

            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                for (tp in textPositions) {
                    val font = tp.font
                    val descriptor = font?.fontDescriptor
                    builder.add(PdfLineBuilder.Glyph(
                        text = tp.unicode ?: "", x = tp.xDirAdj.toDouble(), baseline = tp.yDirAdj.toDouble(),
                        width = tp.widthDirAdj.toDouble(), fontSize = tp.yScale.toDouble(), fontName = font?.name ?: "",
                        forceBold = descriptor != null && (descriptor.isForceBold || descriptor.fontWeight >= 600),
                    ))
                }
            }

            override fun writeWordSeparator() = builder.append(" ")
            override fun writeLineSeparator() = builder.endLine()
            override fun endPage(page: PDPage) = builder.endLine()
        }
        stripper.writeText(doc, StringWriter())
        Result(builder.finish(), doc.numberOfPages)
    }
}
