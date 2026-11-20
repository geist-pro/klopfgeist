package de.kaikunze.marginvoice.conversion

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.core.NarrationText
import de.kaikunze.marginvoice.core.PdfAnnotationPlacer
import de.kaikunze.marginvoice.core.ReviewTag
import de.kaikunze.marginvoice.core.TimeFormat
import de.kaikunze.marginvoice.core.VoiceCommand
import java.io.File

/**
 * Writes a book's comments into its source PDF: a highlight on the sentence each
 * comment was made on (coloured by tag, bookmarks yellow) with a note in the margin
 * carrying the tag, time and transcript. Comments whose sentence is not found in
 * the PDF are listed in one note on the first page (iOS: `PDFAnnotator`).
 */
object PdfAnnotator {
    data class Summary(val placed: Int, val unplaced: Int)

    class NoTextLayer : Exception("This PDF has no text layer, so the comments cannot be placed.")

    fun annotate(source: File, output: File, comments: List<CommentRecord>, text: NarrationText, language: VoiceCommand.Language): Summary {
        PDDocument.load(source).use { doc ->
            val lines = DocumentExtractor.lines(doc)
            if (lines.isEmpty()) throw NoTextLayer()
            val pages = PdfAnnotationPlacer.Pages(lines)
            var placed = 0
            val missing = ArrayList<String>()

            for (c in comments) {
                val sentence = c.quotedText(text)
                val expected = if (text.count > 0) (c.textOffset.toDouble() / text.count * doc.numberOfPages).toInt() else null
                val placement = if (sentence.isEmpty()) null else PdfAnnotationPlacer.place(sentence, pages, expected)
                if (placement == null) {
                    missing.add(label(c, language))
                    continue
                }
                val page = doc.getPage(placement.page)
                val box = page.cropBox
                val color = tint(c)
                var first: PDRectangle? = null
                for (r in placement.rects) {
                    val rect = PDRectangle(
                        (box.lowerLeftX + r.left * box.width).toFloat(), (box.lowerLeftY + (1 - r.top - r.height) * box.height).toFloat(),
                        (r.width * box.width).toFloat(), (r.height * box.height).toFloat(),
                    )
                    val highlight = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT)
                    highlight.rectangle = rect
                    // QuadPoints in page space: upper-left, upper-right, lower-left, lower-right
                    highlight.quadPoints = floatArrayOf(
                        rect.lowerLeftX, rect.upperRightY, rect.upperRightX, rect.upperRightY,
                        rect.lowerLeftX, rect.lowerLeftY, rect.upperRightX, rect.lowerLeftY,
                    )
                    highlight.color = color
                    highlight.contents = label(c, language)
                    highlight.titlePopup = "Klopfgeist"
                    add(page, highlight)
                    if (first == null) first = rect
                }
                first?.let { f ->
                    // the note sits in the margin left of the first line
                    val side = minOf(18f, f.height * 1.4f)
                    val x = maxOf(box.lowerLeftX + 2, f.lowerLeftX - side - 4)
                    val note = PDAnnotationText()
                    note.name = PDAnnotationText.NAME_NOTE
                    note.rectangle = PDRectangle(x, f.upperRightY - side, side, side)
                    note.color = color
                    note.contents = label(c, language)
                    note.titlePopup = "Klopfgeist"
                    add(page, note)
                }
                placed++
            }

            if (missing.isNotEmpty() && doc.numberOfPages > 0) {
                val page = doc.getPage(0)
                val box = page.cropBox
                val note = PDAnnotationText()
                note.name = PDAnnotationText.NAME_COMMENT
                note.rectangle = PDRectangle(box.lowerLeftX + 8, box.upperRightY - 28, 20f, 20f)
                note.color = PDColor(floatArrayOf(0.66f, 0.60f, 0.52f), PDDeviceRGB.INSTANCE)
                note.contents = (if (language == VoiceCommand.Language.GERMAN) "Nicht im PDF gefunden:\n" else "Not found in the PDF:\n") +
                    missing.joinToString("\n") { "• $it" }
                note.titlePopup = "Klopfgeist"
                add(page, note)
            }

            doc.save(output)
            return Summary(placed, missing.size)
        }
    }

    /** Adds the annotation with an appearance stream, so viewers that draw only what is in the file show it too. */
    private fun add(page: PDPage, annotation: PDAnnotation) {
        runCatching { annotation.constructAppearances() }
        page.annotations.add(annotation)
    }

    /** "[Weakness] 12:34 · the transcript", "Bookmark 1:00–1:20". */
    fun label(c: CommentRecord, language: VoiceCommand.Language): String {
        val time = c.endMediaTime?.let { "${TimeFormat.clock(c.mediaTime)}–${TimeFormat.clock(it)}" } ?: TimeFormat.clock(c.mediaTime)
        if (c.kind == CommentRecord.Kind.BOOKMARK) return "${if (language == VoiceCommand.Language.GERMAN) "Lesezeichen" else "Bookmark"} $time"
        val t = c.transcript.trim()
        val body = t.ifEmpty { if (language == VoiceCommand.Language.GERMAN) "(nicht transkribiert)" else "(not transcribed)" }
        val tag = c.tag?.let { "[${it.displayName(language)}] " } ?: ""
        return "$tag$time · $body"
    }

    /** Gruvbox-ish highlight colours by tag; bookmarks yellow, plain notes orange. */
    private fun tint(c: CommentRecord): PDColor {
        val rgb = when {
            c.kind == CommentRecord.Kind.BOOKMARK -> floatArrayOf(0.98f, 0.74f, 0.18f)
            c.tag == ReviewTag.STRENGTH -> floatArrayOf(0.72f, 0.73f, 0.15f)
            c.tag == ReviewTag.WEAKNESS -> floatArrayOf(0.98f, 0.29f, 0.20f)
            c.tag == ReviewTag.QUESTION -> floatArrayOf(0.51f, 0.65f, 0.60f)
            c.tag == ReviewTag.MINOR -> floatArrayOf(0.66f, 0.60f, 0.52f)
            else -> floatArrayOf(1.0f, 0.50f, 0.10f)
        }
        return PDColor(rgb, PDDeviceRGB.INSTANCE)
    }
}
