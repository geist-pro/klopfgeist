package de.kaikunze.marginvoice.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** A store in memory: path → bytes, the tag is a write counter. */
class MemoryFolder : SyncFolder {
    val files = sortedMapOf<String, Pair<ByteArray, Int>>()
    private var writes = 0
    var reads = 0

    override suspend fun list(path: String): List<SyncFolder.Item>? {
        val prefix = "$path/"
        val inside = files.filterKeys { it.startsWith(prefix) }
        if (inside.isEmpty()) return null
        return inside.map { (p, v) ->
            val rest = p.removePrefix(prefix)
            if ('/' in rest) SyncFolder.Item(rest.substringBefore('/'), true, "")
            else SyncFolder.Item(rest, false, v.second.toString())
        }.distinctBy { it.name }
    }

    override suspend fun read(path: String): ByteArray { reads++; return files.getValue(path).first }
    override suspend fun download(path: String, dest: File) { dest.parentFile.mkdirs(); dest.writeBytes(files.getValue(path).first) }
    override suspend fun write(path: String, size: Long, open: () -> InputStream) {
        files[path] = open().use { it.readBytes() } to ++writes
    }
    override suspend fun delete(path: String) { files.keys.removeIf { it == path || it.startsWith("$path/") } }
}

/** A device's library in memory, with its files in a temporary folder. */
class MemoryLocal : SyncLocal {
    val books = linkedMapOf<String, PackagedBook>()
    val comments = linkedMapOf<String, LinkedHashMap<String, PackagedComment>>()
    val dir: File = Files.createTempDirectory("device").toFile()

    fun add(book: PackagedBook, audio: String) {
        books[book.id] = book
        comments[book.id] = linkedMapOf()
        File(dir, "${book.id}/${book.audioFile}").apply { parentFile.mkdirs(); writeText(audio) }
    }

    fun add(bookId: String, c: PackagedComment, audio: String? = null) {
        comments.getValue(bookId)[c.id] = c
        if (audio != null) File(dir, "$bookId/comments/${c.audioFile}").apply { parentFile.mkdirs(); writeText(audio) }
    }

    override suspend fun books() = books.values.toList()
    override suspend fun comments(bookId: String) = comments[bookId]?.values?.toList().orEmpty()
    override suspend fun file(bookId: String, path: String) = File(dir, "$bookId/$path").takeIf { it.exists() }
    override suspend fun putBook(book: PackagedBook, fetch: suspend (String, File) -> Unit) {
        for (name in listOfNotNull(book.audioFile, book.textFile, book.timingFile, book.sourceFile)) {
            val f = File(dir, "${book.id}/$name")
            if (!f.exists()) fetch(name, f)
        }
        books[book.id] = book
        comments.getOrPut(book.id) { linkedMapOf() }
    }
    override suspend fun deleteBook(id: String) { books.remove(id); comments.remove(id); File(dir, id).deleteRecursively() }
    override suspend fun putComment(bookId: String, comment: PackagedComment, fetch: suspend (String, File) -> Unit) {
        comment.audioFile?.let { a ->
            val f = File(dir, "$bookId/comments/$a")
            if (!f.exists()) fetch("comments/$a", f)
        }
        comments.getValue(bookId)[comment.id] = comment
    }
    override suspend fun deleteComment(bookId: String, id: String) { comments[bookId]?.remove(id) }
}

class SyncEngineTests {
    private val bookId = "6F1C2B4A-0D1E-4F00-9A8B-1C2D3E4F5A6B"
    private fun book(updatedAt: String = "2026-10-29T10:00:00.000Z", position: Double = 0.0) = PackagedBook(
        addedAt = "2026-10-29T10:00:00.000Z", audioFile = "book.m4b", duration = 100.0, id = bookId,
        position = position, title = "Cuddle-fish", updatedAt = updatedAt,
    )
    private fun comment(id: String, transcript: String, at: String = "2026-10-29T10:01:00.000Z") = PackagedComment(
        audioFile = "comment-$id.m4a", createdAt = at, id = id, kind = "comment", mediaTime = 12.0,
        transcript = transcript, transcriptStatus = "done", updatedAt = at,
    )

    private class Device(val folder: SyncFolder) {
        val local = MemoryLocal()
        var state = SyncState()
        suspend fun sync(): SyncEngine.Report = SyncEngine(folder, local).sync(state).also { state = it.state }
    }

    @Test fun commentTravelsBothWaysAndDeletesFollow() = runTest {
        val store = MemoryFolder()
        val pixel = Device(store)
        val phone = Device(store)
        pixel.local.add(book(), audio = "AUDIO")
        pixel.local.add(bookId, comment("A", "the baseline is never tuned"), audio = "VOICE")
        pixel.sync()
        assertTrue("Books/$bookId/book.json" in store.files)
        assertTrue("Books/$bookId/comments/comment-A.m4a" in store.files)

        phone.sync()
        assertEquals("AUDIO", File(phone.local.dir, "$bookId/book.m4b").readText())
        assertEquals("VOICE", File(phone.local.dir, "$bookId/comments/comment-A.m4a").readText())
        assertEquals("the baseline is never tuned", phone.local.comments.getValue(bookId).getValue("A").transcript)

        phone.local.deleteComment(bookId, "A")
        phone.local.add(bookId, comment("B", "question: why fish"), audio = "V2")
        phone.sync()
        pixel.sync()
        assertEquals(setOf("B"), pixel.local.comments.getValue(bookId).keys)
        assertTrue("Books/$bookId/comments/comment-A.m4a" !in store.files)

        // nothing changed: nothing is read again
        val reads = store.reads
        val quiet = pixel.sync()
        assertEquals(0, quiet.uploaded + quiet.downloaded + quiet.deleted)
        assertEquals(reads, store.reads)
    }

    @Test fun positionTakesLatest() = runTest {
        val store = MemoryFolder()
        val a = Device(store)
        val b = Device(store)
        a.local.add(book(), audio = "AUDIO")
        a.sync(); b.sync()
        a.local.books[bookId] = book("2026-10-29T11:00:00.000Z", position = 50.0)
        b.local.books[bookId] = book("2026-10-29T12:00:00.000Z", position = 80.0)
        a.sync(); b.sync(); a.sync()
        assertEquals(80.0, a.local.books.getValue(bookId).position, 0.0)
        assertEquals(80.0, b.local.books.getValue(bookId).position, 0.0)
    }

    @Test fun deletedBookLeavesTheStoreAndOtherDevices() = runTest {
        val store = MemoryFolder()
        val a = Device(store)
        val b = Device(store)
        a.local.add(book(), audio = "AUDIO")
        a.sync(); b.sync()
        b.local.deleteBook(bookId)
        b.sync(); a.sync()
        assertTrue(a.local.books.isEmpty())
        assertTrue(store.files.isEmpty())
    }
}
