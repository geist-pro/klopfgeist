package de.kaikunze.marginvoice.model

import de.kaikunze.marginvoice.core.AnnotatedTextExport
import de.kaikunze.marginvoice.core.BookFiles
import de.kaikunze.marginvoice.core.CommentExport
import de.kaikunze.marginvoice.core.ReviewDraftExport
import de.kaikunze.marginvoice.core.TimeFormat
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.conversion.PdfAnnotator
import java.io.File
import java.util.Locale
import java.util.UUID

/** Writes the selected export files into a fresh folder in the cache for sharing. */
object ExportBuilder {
    data class Options(
        val annotatedText: Boolean = true,
        val commentList: Boolean = true,
        val csv: Boolean = false,
        val json: Boolean = false,
        val audio: Boolean = false,
        /** The comments sorted by tag into a review draft (`<name>-review.md`). */
        val reviewDraft: Boolean = false,
        val reviewLayout: ReviewDraftExport.Layout = ReviewDraftExport.Layout.GENERIC,
        /** The source PDF with a highlight and note per comment (`<name>-annotated.pdf`). */
        val annotatedPdf: Boolean = false,
        /** The whole book with comments and recordings as a `.drafttalk` file, to open on another device. */
        val bookPackage: Boolean = false,
    ) {
        val nothingChosen: Boolean get() = !annotatedText && !commentList && !csv && !json && !audio && !reviewDraft && !annotatedPdf && !bookPackage
    }

    /** The PDF a book was converted from, kept as `source.pdf` in its folder (or picked once at export). */
    fun sourcePdf(book: Book, store: BookStore): File? = File(store.folder(book), "source.pdf").takeIf { it.exists() }

    /**
     * Returns the files to share; a recordings folder is listed as the files in it.
     * @param language the language of the review draft's headings (the command language).
     * @param sourcePdf the PDF to annotate; without one (or without narration text) no annotated PDF is written.
     */
    fun build(
        book: Book, content: BookContent?, store: BookStore, options: Options, cacheDir: File,
        language: VoiceCommand.Language = VoiceCommand.Language.ENGLISH, sourcePdf: File? = null,
    ): List<File> {
        val base = File(cacheDir, "export-${UUID.randomUUID()}").also { it.mkdirs() }
        val name = BookFiles.safeName(book.title)
        val comments = book.sortedComments
        val files = ArrayList<File>()

        // audio files are numbered in time order and referenced from the exports
        val audioNames = HashMap<UUID, String>()
        if (options.audio) {
            val folder = File(base, "$name-audio").also { it.mkdirs() }
            for ((i, c) in comments.withIndex()) {
                val src = store.audioFile(c, book)?.takeIf { it.exists() } ?: continue
                val file = String.format(Locale.ROOT, "comment-%02d-%s.m4a", i + 1, TimeFormat.clock(c.mediaTime).replace(":", "-"))
                src.copyTo(File(folder, file))
                audioNames[c.id] = "$name-audio/$file"
                files.add(File(folder, file))
            }
        }

        val records = comments.map { it.record(content?.text, audioNames[it.id]) }
        fun write(text: String, file: String) {
            files.add(File(base, file).also { it.writeText(text) })
        }
        if (options.annotatedText && content != null) {
            write(AnnotatedTextExport.markdown(content.text, records, book.title, content.headingOffsets), "$name-annotated.md")
        }
        if (options.commentList) write(CommentExport.markdown(book.title, records, content?.text), "$name-comments.md")
        if (options.csv) write(CommentExport.csv(records, content?.text), "$name-comments.csv")
        if (options.json) write(CommentExport.json(book.title, records), "$name-comments.json")
        if (options.reviewDraft) {
            write(ReviewDraftExport.markdown(book.title, records, content?.text, options.reviewLayout, language), "$name-review.md")
        }
        if (options.annotatedPdf && sourcePdf != null && content != null) {
            val out = File(base, "$name-annotated.pdf")
            PdfAnnotator.annotate(sourcePdf, out, records, content.text, language)
            files.add(out)
        }
        return files
    }

    /** At launch: exports and documents copied for conversion in earlier runs. */
    fun removeTemporaryFiles(cacheDir: File) {
        for (item in cacheDir.listFiles().orEmpty()) {
            if (item.name.startsWith("export-") || item.name.startsWith("convert-")) item.deleteRecursively()
        }
    }
}
