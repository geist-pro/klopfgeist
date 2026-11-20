package de.kaikunze.marginvoice.core

import de.kaikunze.marginvoice.core.SyncMerge.Action
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream

/**
 * The sync store: a folder somewhere (iCloud Drive, WebDAV) holding
 * `Books/<BOOK-UUID>/…` in the `BookPackage` layout. Paths use `/` and are
 * relative to the store's root.
 */
interface SyncFolder {
    /** A file or folder directly inside a listed folder; `tag` changes whenever its content does (an ETag, size + date). */
    data class Item(val name: String, val isFolder: Boolean, val tag: String)

    /** The items in `path`, or null when the folder does not exist. */
    suspend fun list(path: String): List<Item>?
    suspend fun read(path: String): ByteArray
    /** Copies a remote file to `dest`. */
    suspend fun download(path: String, dest: File)
    /** Writes `path`, creating its folders; replaces a file that is there. */
    suspend fun write(path: String, size: Long, open: () -> InputStream)
    /** Deletes a file or a whole folder; a missing one is fine. */
    suspend fun delete(path: String)
}

/** This device's library as the sync engine sees it. */
interface SyncLocal {
    suspend fun books(): List<PackagedBook>
    suspend fun comments(bookId: String): List<PackagedComment>
    /** The local file of a book-relative path (`book.m4b`, `comments/x.m4a`), if it is there. */
    suspend fun file(bookId: String, path: String): File?
    /** Adds or updates a book; `fetch` copies one of its files from the store into a local file. */
    suspend fun putBook(book: PackagedBook, fetch: suspend (path: String, dest: File) -> Unit)
    suspend fun deleteBook(id: String)
    suspend fun putComment(bookId: String, comment: PackagedComment, fetch: suspend (path: String, dest: File) -> Unit)
    suspend fun deleteComment(bookId: String, id: String)
}

/** What this device knew after its last sync with one store. Saved between syncs. */
@Serializable
data class SyncState(
    val books: Map<String, String> = emptyMap(),
    /** Book id → comment id → hash. */
    val comments: Map<String, Map<String, String>> = emptyMap(),
    /** Store path → tag and JSON as last read, so unchanged files are not read again. */
    val cache: Map<String, Cached> = emptyMap(),
) {
    @Serializable
    data class Cached(val tag: String, val json: String)
}

class SyncEngine(private val folder: SyncFolder, private val local: SyncLocal, private val log: (String) -> Unit = {}) {
    data class Report(val state: SyncState, val uploaded: Int, val downloaded: Int, val deleted: Int)

    private var cache = HashMap<String, SyncState.Cached>()
    private var uploaded = 0
    private var downloaded = 0
    private var deleted = 0

    suspend fun sync(state: SyncState): Report {
        cache = HashMap()
        uploaded = 0; downloaded = 0; deleted = 0

        val remoteBooks = remoteBooks(state)
        val localBooks = local.books().associateBy { it.id.uppercase() }
        val merged = SyncMerge.merge(state.books, localBooks.mapValues { item(it.value) }, remoteBooks.mapValues { item(it.value) })
        val removed = HashSet<String>()
        for (action in merged.actions) when (action) {
            is Action.PutRemote -> uploadBook(action.value)
            is Action.PutLocal -> { local.putBook(action.value) { p, d -> fetch("${bookPath(action.id)}/$p", d) }; downloaded++ }
            is Action.DeleteRemote -> { folder.delete(bookPath(action.id)); removed.add(action.id); deleted++ }
            is Action.DeleteLocal -> { local.deleteBook(action.id); removed.add(action.id); deleted++ }
        }

        val comments = HashMap<String, Map<String, String>>()
        for (id in merged.base.keys) {
            if (id in removed) continue
            comments[id] = syncComments(id, state.comments[id].orEmpty(), state)
        }
        return Report(SyncState(merged.base, comments, cache), uploaded, downloaded, deleted)
    }

    private fun item(b: PackagedBook) = SyncMerge.Item(b, BookPackage.hash(b), BookPackage.date(b.updatedAt))
    private fun item(c: PackagedComment) = SyncMerge.Item(c, BookPackage.hash(c), BookPackage.date(c.updatedAt))

    private fun bookPath(id: String) = "$BOOKS/${id.uppercase()}"

    /** Reads a JSON file of the store, or takes it from the cache when its tag is unchanged. */
    private suspend fun json(path: String, tag: String, state: SyncState): String {
        val known = state.cache[path]
        val s = if (known != null && known.tag == tag) known.json else folder.read(path).decodeToString()
        cache[path] = SyncState.Cached(tag, s)
        return s
    }

    private suspend fun remoteBooks(state: SyncState): Map<String, PackagedBook> {
        val result = HashMap<String, PackagedBook>()
        for (dir in folder.list(BOOKS).orEmpty().filter { it.isFolder }) {
            // a book without book.json is still being uploaded
            val meta = folder.list("$BOOKS/${dir.name}")?.firstOrNull { it.name == BookPackage.BOOK_FILE } ?: continue
            val book = runCatching { BookPackage.decodeBook(json("$BOOKS/${dir.name}/${meta.name}", meta.tag, state)) }
                .onFailure { log("skipping ${dir.name}: ${it.message}") }.getOrNull() ?: continue
            result[book.id.uppercase()] = book
        }
        return result
    }

    private suspend fun fetch(path: String, dest: File) {
        folder.download(path, dest)
    }

    /** Files first and `book.json` last, so other devices never see half a book. */
    private suspend fun uploadBook(book: PackagedBook) {
        val base = bookPath(book.id)
        val there = folder.list(base).orEmpty().map { it.name }.toSet()
        for (name in listOfNotNull(book.audioFile, book.textFile, book.timingFile, book.sourceFile)) {
            if (name in there) continue
            val file = local.file(book.id, name) ?: continue
            folder.write("$base/$name", file.length()) { file.inputStream() }
        }
        val json = BookPackage.encode(book).encodeToByteArray()
        folder.write("$base/${BookPackage.BOOK_FILE}", json.size.toLong()) { json.inputStream() }
        uploaded++
    }

    private suspend fun syncComments(bookId: String, base: Map<String, String>, state: SyncState): Map<String, String> {
        val dir = "${bookPath(bookId)}/${BookPackage.COMMENTS_DIR}"
        val listing = folder.list(dir).orEmpty()
        val names = listing.map { it.name }.toMutableSet()
        val remote = HashMap<String, PackagedComment>()
        for (f in listing.filter { !it.isFolder && it.name.endsWith(".json") }) {
            val c = runCatching { BookPackage.decodeComment(json("$dir/${f.name}", f.tag, state)) }
                .onFailure { log("skipping comment ${f.name}: ${it.message}") }.getOrNull() ?: continue
            remote[c.id.uppercase()] = c
        }
        val localComments = local.comments(bookId).associateBy { it.id.uppercase() }
        val merged = SyncMerge.merge(base, localComments.mapValues { item(it.value) }, remote.mapValues { item(it.value) })
        for (action in merged.actions) when (action) {
            is Action.PutRemote -> {
                val c = action.value
                val audio = c.audioFile
                if (audio != null && audio !in names) {
                    local.file(bookId, "${BookPackage.COMMENTS_DIR}/$audio")?.let { f ->
                        folder.write("$dir/$audio", f.length()) { f.inputStream() }
                        names.add(audio)
                    }
                }
                val json = BookPackage.encode(c).encodeToByteArray()
                folder.write("$dir/${c.id.uppercase()}.json", json.size.toLong()) { json.inputStream() }
                uploaded++
            }
            is Action.PutLocal -> { local.putComment(bookId, action.value) { p, d -> fetch("${bookPath(bookId)}/$p", d) }; downloaded++ }
            is Action.DeleteRemote -> {
                val c = remote[action.id]
                folder.delete("$dir/${action.id}.json")
                c?.audioFile?.let { folder.delete("$dir/$it") }
                deleted++
            }
            is Action.DeleteLocal -> { local.deleteComment(bookId, action.id); deleted++ }
        }
        return merged.base
    }

    companion object {
        const val BOOKS = "Books"
    }
}
