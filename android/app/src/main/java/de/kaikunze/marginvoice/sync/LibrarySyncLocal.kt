package de.kaikunze.marginvoice.sync

import de.kaikunze.marginvoice.core.BookPackage
import de.kaikunze.marginvoice.core.PackagedBook
import de.kaikunze.marginvoice.core.PackagedComment
import de.kaikunze.marginvoice.core.SyncLocal
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.model.BookStore
import de.kaikunze.marginvoice.model.Library
import de.kaikunze.marginvoice.model.packaged
import de.kaikunze.marginvoice.model.toBook
import de.kaikunze.marginvoice.model.toComment
import de.kaikunze.marginvoice.model.update
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * The library as the sync engine sees it. The model is Compose state, so every read
 * and change runs on `main`; file copies run on IO. What arrives from the store keeps
 * its `updatedAt` (`Library.adopt`).
 */
class LibrarySyncLocal(
    private val library: Library,
    private val store: BookStore,
    /** Called before a book that was deleted elsewhere goes (closes it if it is open). */
    private val willDelete: (Book) -> Unit = {},
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
) : SyncLocal {
    /** Narration text per book, for the offset conversion. */
    private val texts = HashMap<UUID, String?>()

    private suspend fun text(book: Book): String? =
        if (texts.containsKey(book.id)) texts[book.id] else withContext(Dispatchers.IO) { store.narration(book) }.also { texts[book.id] = it }

    private suspend fun book(id: String): Book? = withContext(main) { library.book(runCatching { UUID.fromString(id) }.getOrNull()) }

    override suspend fun books(): List<PackagedBook> {
        val books = withContext(main) { library.books.toList() }
        val sources = withContext(Dispatchers.IO) { books.map { store.hasSource(it) } }
        return withContext(main) { books.zip(sources) { b, s -> b.packaged(s) } }
    }

    override suspend fun comments(bookId: String): List<PackagedComment> {
        val book = book(bookId) ?: return emptyList()
        val text = text(book)
        return withContext(main) { book.comments.map { it.packaged(text) } }
    }

    override suspend fun file(bookId: String, path: String): File? {
        val book = book(bookId) ?: return null
        return File(store.folder(book), path).takeIf { it.isFile }
    }

    override suspend fun putBook(book: PackagedBook, fetch: suspend (String, File) -> Unit) {
        val names = listOfNotNull(book.audioFile, book.textFile, book.timingFile, book.sourceFile)
        val existing = book(book.id)
        if (existing != null) {
            for (name in names) {
                val f = File(store.folder(existing), name)
                if (!f.exists()) fetch(name, f)
            }
            withContext(main) {
                existing.update(book)
                library.adopt(existing)
                library.save()
            }
            return
        }
        val (folderName, dir) = store.makeBookFolder()
        try {
            for (name in names) fetch(name, File(dir, name))
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
            throw e
        }
        withContext(main) {
            val b = book.toBook(folderName)
            library.books.add(b)
            library.adopt(b)
            if (!library.save()) {
                library.books.remove(b)
                dir.deleteRecursively()
                error("the library could not be saved")
            }
        }
    }

    override suspend fun deleteBook(id: String) {
        val book = book(id) ?: return
        withContext(main) {
            willDelete(book)
            store.delete(book)
        }
        texts.remove(book.id)
    }

    override suspend fun putComment(bookId: String, comment: PackagedComment, fetch: suspend (String, File) -> Unit) {
        val book = book(bookId) ?: return
        comment.audioFile?.let { name ->
            val f = File(store.commentsDir(book), name)
            if (!f.exists()) fetch("${BookPackage.COMMENTS_DIR}/$name", f)
        }
        val text = text(book)
        withContext(main) {
            val mine = book.comments.firstOrNull { it.id.toString().equals(comment.id, ignoreCase = true) }
            if (mine != null) mine.update(comment, text) else book.comments.add(comment.toComment(text))
            library.adopt(book)
            library.save()
        }
    }

    override suspend fun deleteComment(bookId: String, id: String) {
        val book = book(bookId) ?: return
        withContext(main) {
            book.comments.firstOrNull { it.id.toString().equals(id, ignoreCase = true) }?.let { store.delete(it) }
        }
    }
}
