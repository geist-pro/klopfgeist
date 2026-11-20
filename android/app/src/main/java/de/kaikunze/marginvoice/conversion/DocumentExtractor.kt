package de.kaikunze.marginvoice.conversion

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import de.kaikunze.marginvoice.core.BookFiles
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.core.conversion.PdfLayoutAnalyzer
import de.kaikunze.marginvoice.core.conversion.PdfLine
import de.kaikunze.marginvoice.core.conversion.PdfLineBuilder
import de.kaikunze.marginvoice.core.conversion.RtfText
import de.kaikunze.marginvoice.core.conversion.SourceDocument
import java.io.File
import java.io.StringWriter
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Reads a document's structure on device: the PDF text layer via PDFBox (with
 * doc2audio's layout heuristics), and plain text, Markdown and RTF.
 */
object DocumentExtractor {
    class ExtractError(message: String) : Exception(message)

    data class Result(val document: SourceDocument, val language: String, val pageCount: Int, val usedOCR: Boolean)

    /** MIME types the document picker offers. */
    val mimeTypes = arrayOf("application/pdf", "text/plain", "text/markdown", "text/x-markdown", "text/rtf", "application/rtf")

    fun canConvert(name: String) = BookFiles.isDocument(name)

    fun extract(context: Context, file: File, progress: (Double) -> Unit = {}): Result {
        val stem = BookFiles.stem(file.name).replace('_', ' ')
        var pages = 0
        val doc = when (BookFiles.extension(file.name)) {
            "pdf" -> {
                PDFBoxResourceLoader.init(context.applicationContext)
                val pdf = try {
                    PDDocument.load(file)
                } catch (e: Exception) {
                    throw ExtractError("The document could not be opened.")
                }
                pdf.use {
                    pages = it.numberOfPages
                    val metaTitle = it.documentInformation?.title?.takeIf { t -> t.length > 8 }
                    val lines = lines(it) { page -> progress(page.toDouble() / maxOf(1, pages) * 0.9) }
                    val textChars = lines.sumOf { l -> l.text.length }
                    if (textChars <= 200 * maxOf(1, pages) / 4) {
                        throw ExtractError("This PDF has no text layer (a scan). Scanned PDFs can't be read on Android yet.")
                    }
                    PdfLayoutAnalyzer.document(lines, pages, metaTitle ?: stem)
                }
            }
            "txt", "md", "markdown" -> SourceDocument.fromPlainText(readText(file), stem, markdown = BookFiles.extension(file.name) != "txt")
            "rtf" -> SourceDocument.fromPlainText(RtfText.plain(readText(file)).replace("\n", "\n\n"), stem)
            else -> throw ExtractError(
                "${BookFiles.extension(file.name).uppercase()} files can't be converted on the device yet. Export the document as PDF and try again.",
            )
        }
        if (doc.blocks.isEmpty()) throw ExtractError("No readable text was found in the document.")
        progress(1.0)
        return Result(doc, VoiceCommand.Language.detect(doc.plainText).code, pages, usedOCR = false)
    }

    /** UTF-8, or Latin-1 when the file isn't valid UTF-8. */
    private fun readText(file: File): String {
        val bytes = file.readBytes()
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    /** The text layer as lines with font size, weight and position (iOS: `PDFLineReader`). */
    fun lines(doc: PDDocument, progress: (Int) -> Unit = {}): List<PdfLine> {
        val builder = PdfLineBuilder()
        val stripper = object : PDFTextStripper() {
            override fun startPage(page: PDPage) {
                progress(currentPageNo - 1)
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
        return builder.finish()
    }
}
