package de.kaikunze.marginvoice.model

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import de.kaikunze.marginvoice.core.BookFiles
import de.kaikunze.marginvoice.core.BookPackage
import de.kaikunze.marginvoice.core.ChapterMark
import de.kaikunze.marginvoice.core.Mp4Chapters
import de.kaikunze.marginvoice.core.NarrationText
import de.kaikunze.marginvoice.core.TimingSidecar
import de.kaikunze.marginvoice.core.VoiceCommand
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

/** A file to import: a picked or shared document (content URI) or a plain file. */
class ImportSource(val name: String, val open: () -> InputStream) {
    companion object {
        fun of(file: File) = ImportSource(file.name) { file.inputStream() }

        fun of(context: Context, uri: Uri): ImportSource {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            return ImportSource(name) { requireNotNull(context.contentResolver.openInputStream(uri)) { "cannot read $name" } }
        }
    }
}

/** Duration, chapters and title of an audio file (Android's media APIs; a stand-in in tests). */
interface MediaProbe {
    data class Info(val duration: Double, val chapters: List<ChapterMark>, val title: String?)

    fun probe(file: File): Info

    object Android : MediaProbe {
        override fun probe(file: File): Info {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.path)
                val duration = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) / 1000.0
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                Info(duration, Mp4Chapters.read(file, duration), title)
            } catch (e: Exception) {
                Info(0.0, Mp4Chapters.read(file, 0.0), null)
            } finally {
                retriever.release()
            }
        }
    }
}

/** Imports books into the app's files and loads their text lazily. */
class BookStore(val baseDir: File, private val library: Library, private val probe: MediaProbe = MediaProbe.Android) {
    class ImportError(message: String) : Exception(message)

    private val contentCache = HashMap<UUID, BookContent?>()
    private val loading = HashMap<UUID, Deferred<BookContent?>>()

    init {
        baseDir.mkdirs()
    }

    fun folder(book: Book) = File(baseDir, book.folder)
    fun audioFile(book: Book) = File(folder(book), book.audioFile)
    fun commentsDir(book: Book) = File(folder(book), "comments")
    fun audioFile(comment: Comment, book: Book): File? = comment.audioFile?.let { File(commentsDir(book), it) }

    /** A new, empty book folder. */
    fun makeBookFolder(): Pair<String, File> {
        val name = UUID.randomUUID().toString()
        return name to File(baseDir, name).also { it.mkdirs() }
    }

    /**
     * At launch: removes folders of conversions cut off by a crash or kill (no book
     * refers to them, and they hold no recorded comments).
     */
    fun removeLeftovers() {
        val known = library.books.map { it.folder }.toSet()
        for (folder in baseDir.listFiles().orEmpty()) {
            if (runCatching { UUID.fromString(folder.name) }.isFailure || folder.name in known) continue
            if (File(folder, "comments").list().orEmpty().isNotEmpty()) continue
            Log.i(TAG, "removing unused book folder ${folder.name}")
            folder.deleteRecursively()
        }
    }

    // Import

    /**
     * Groups the picked files by name stem (`x.m4b`, `x.txt` / `x.narration.txt`,
     * `x.timing.json`) and imports one book per audio file. All or nothing.
     */
    suspend fun importFiles(sources: List<ImportSource>, defaultRate: Double): List<Book> {
        val (packages, files) = sources.partition { BookFiles.extension(it.name) == BookPackage.EXTENSION }
        val unpacked = packages.map { importPackage(it) }
        if (files.isEmpty()) return unpacked
        return unpacked + importAudiobooks(files, defaultRate)
    }

    private suspend fun importAudiobooks(sources: List<ImportSource>, defaultRate: Double): List<Book> {
        val grouped = BookFiles.group(sources) { it.name }
        val imported = ArrayList<Book>()
        try {
            for (g in grouped.books) imported.add(importBook(g.stem, g.audio, g.text, g.timing, defaultRate))
            if (imported.isEmpty() && grouped.orphans.isNotEmpty()) {
                throw ImportError(
                    "No audiobook found for ${grouped.orphans.joinToString(", ")}. Select the .m4b together with its .txt file.",
                )
            }
            library.books.addAll(imported)
            if (!library.save()) {
                library.books.removeAll(imported)
                throw ImportError("The library could not be saved.")
            }
        } catch (e: Exception) {
            // all or nothing: no half-imported books left behind
            for (book in imported) folder(book).deleteRecursively()
            throw e
        }
        return imported
    }

    /**
     * Imports the audiobooks in an inbox listing and removes what was imported (a second
     * audio file under the same name stays). A PDF with a book's name becomes its
     * `source.pdf`, so the comments can be written back into it.
     */
    suspend fun importInbox(files: List<File>, defaultRate: Double): List<Book> {
        val packages = files.filter { BookFiles.extension(it.name) == BookPackage.EXTENSION }
        val unpacked = packages.map { f -> importPackage(ImportSource.of(f)).also { f.delete() } }
        val ready = BookFiles.sortInbox(files - packages.toSet()) { it.name }.audiobookFiles
        if (ready.isEmpty()) return unpacked
        val books = importAudiobooks(ready.map { ImportSource.of(it) }, defaultRate)
        val groups = BookFiles.group(ready) { it.name }.books
        for (file in groups.flatMap { listOfNotNull(it.audio, it.text, it.timing) }) {
            if (!file.delete()) Log.e(TAG, "inbox cleanup: ${file.name}")
        }
        for ((group, book) in groups.zip(books)) {
            val pdf = ready.firstOrNull { it.extension.equals("pdf", true) && BookFiles.stem(it.name) == group.stem } ?: continue
            val dest = File(folder(book), "source.pdf")
            runCatching { if (!pdf.renameTo(dest)) { pdf.copyTo(dest, overwrite = true); pdf.delete() } }
                .onFailure { Log.e(TAG, "inbox pdf: ${it.message}") }
        }
        return unpacked + books
    }

    // Packages

    /** The narration as `NarrationText` indexes it (CRLF as LF), for converting offsets. */
    fun narration(book: Book): String? =
        book.textFile?.let { runCatching { File(folder(book), it).readText().replace("\r\n", "\n") }.getOrNull() }

    fun hasSource(book: Book) = File(folder(book), BookPackage.SOURCE_PDF).exists()

    /** The book's files and comment recordings, as package entries. */
    fun packageFiles(book: Book): List<BookPackage.Entry> {
        val folder = folder(book)
        val names = listOfNotNull(book.audioFile, book.textFile, book.timingFile, BookPackage.SOURCE_PDF.takeIf { hasSource(book) })
        val recordings = book.comments.mapNotNull { it.audioFile }.filter { File(commentsDir(book), it).exists() }
        return names.map { BookPackage.Entry.of(it, File(folder, it)) } +
            recordings.map { BookPackage.Entry.of("${BookPackage.COMMENTS_DIR}/$it", File(commentsDir(book), it)) }
    }

    /** Writes the book with its comments and recordings as a `.drafttalk` file. */
    suspend fun exportPackage(book: Book, out: File) {
        val text = withContext(Dispatchers.IO) { narration(book) }
        val packaged = book.packaged(hasSource(book))
        val comments = book.comments.map { it.packaged(text) }
        val files = packageFiles(book)
        withContext(Dispatchers.IO) {
            out.outputStream().use { BookPackage.writeZip(BookPackage.entries(packaged, comments, files), it) }
        }
    }

    /**
     * Imports a `.drafttalk` file. A book that is already in the library gets the
     * comments it lacks, and the newer version of those both have.
     */
    suspend fun importPackage(source: ImportSource): Book {
        val (folderName, dir) = makeBookFolder()
        val unpacked = try {
            withContext(Dispatchers.IO) { source.open().use { BookPackage.unzip(it, dir) } }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw ImportError("${source.name} could not be read: ${e.message}")
        }
        val existing = library.book(runCatching { UUID.fromString(unpacked.book.id) }.getOrNull())
        if (existing == null) {
            val text = withContext(Dispatchers.IO) {
                File(dir, BookPackage.BOOK_FILE).delete()
                File(dir, BookPackage.COMMENTS_DIR).listFiles { f -> f.extension == "json" }?.forEach { it.delete() }
                unpacked.book.textFile?.let { runCatching { File(dir, it).readText().replace("\r\n", "\n") }.getOrNull() }
            }
            val book = unpacked.book.toBook(folderName)
            book.comments.addAll(unpacked.comments.map { it.toComment(text) })
            library.books.add(book)
            library.adopt(book)
            if (!library.save()) {
                library.books.remove(book)
                dir.deleteRecursively()
                throw ImportError("The library could not be saved.")
            }
            return book
        }
        val text = withContext(Dispatchers.IO) { narration(existing) }
        for (c in unpacked.comments) {
            val mine = existing.comments.firstOrNull { it.id.toString().equals(c.id, ignoreCase = true) }
            if (mine == null) {
                c.audioFile?.let { name ->
                    val from = File(dir, "${BookPackage.COMMENTS_DIR}/$name")
                    val to = File(commentsDir(existing), name)
                    withContext(Dispatchers.IO) { if (from.exists() && !to.exists()) { to.parentFile?.mkdirs(); from.copyTo(to) } }
                }
                existing.comments.add(c.toComment(text))
            } else if (BookPackage.date(c.updatedAt) > mine.updatedAt) {
                mine.update(c, text)
            }
        }
        library.adopt(existing)
        library.save()
        withContext(Dispatchers.IO) { dir.deleteRecursively() }
        return existing
    }

    private suspend fun importBook(stem: String, audio: ImportSource, text: ImportSource?, timing: ImportSource?, rate: Double): Book =
        withContext(Dispatchers.IO) {
            val (folderName, folder) = makeBookFolder()
            var finished = false
            try {
                fun copy(source: ImportSource, name: String) {
                    source.open().use { input -> File(folder, name).outputStream().use { input.copyTo(it) } }
                }
                val audioName = "book." + BookFiles.extension(audio.name)
                copy(audio, audioName)
                if (text != null) copy(text, "narration.txt")
                if (timing != null) copy(timing, "timing.json")

                val info = probe.probe(File(folder, audioName))
                val title = info.title?.takeIf { it.isNotBlank() } ?: stem.replace('_', ' ')
                val language = if (text != null) {
                    runCatching { VoiceCommand.Language.detect(File(folder, "narration.txt").readText()).code }.getOrNull()
                } else null
                finished = true
                Book(
                    title = title, folder = folderName, audioFile = audioName, textFile = text?.let { "narration.txt" },
                    timingFile = timing?.let { "timing.json" }, language = language, duration = info.duration,
                    chapters = info.chapters, rate = rate,
                )
            } finally {
                if (!finished) folder.deleteRecursively()
            }
        }

    // Content

    fun cachedContent(book: Book): BookContent? = contentCache[book.id]

    /** Parses the narration text off the main thread; cached per book. */
    suspend fun content(book: Book): BookContent? = coroutineScope {
        if (contentCache.containsKey(book.id)) return@coroutineScope contentCache[book.id]
        val task = loading[book.id] ?: run {
            val textFile = book.textFile ?: return@coroutineScope null
            val folder = folder(book)
            val chapters = book.chapters
            val duration = book.duration
            val timingFile = book.timingFile
            async(Dispatchers.Default) {
                val s = runCatching { File(folder, textFile).readText() }.getOrNull() ?: return@async null
                val sidecar = timingFile?.let { runCatching { TimingSidecar.decode(File(folder, it).readText()) }.getOrNull() }
                BookContent(NarrationText(s), chapters, duration, sidecar)
            }.also { loading[book.id] = it }
        }
        val content = task.await()
        loading.remove(book.id)
        contentCache[book.id] = content
        content
    }

    /**
     * Files go only after the deletion is saved, so a failed save never leaves
     * a book or comment whose audio is gone.
     */
    fun delete(book: Book): Boolean {
        val index = library.books.indexOf(book)
        if (index < 0) return false
        library.books.removeAt(index)
        if (!library.save()) {
            library.books.add(index, book)
            return false
        }
        contentCache.remove(book.id)
        folder(book).deleteRecursively()
        return true
    }

    fun delete(comment: Comment): Boolean {
        val book = library.bookOf(comment) ?: return false
        val index = book.comments.indexOf(comment)
        val audio = audioFile(comment, book)
        book.comments.removeAt(index)
        if (!library.save()) {
            book.comments.add(index, comment)
            return false
        }
        audio?.delete()
        return true
    }

    companion object {
        private const val TAG = "BookStore"
    }
}
