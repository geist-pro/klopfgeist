package de.kaikunze.marginvoice.model

import de.kaikunze.marginvoice.TestLibrary
import de.kaikunze.marginvoice.core.CommentRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** `.drafttalk` files: a book with its comments to another device and back. */
class BookPackageImportTests {
    private val text = "Schöne 👍🏽 Grüße. The baseline is never tuned."

    private suspend fun bookWithComment(t: TestLibrary): Book {
        val book = t.store.importFiles(listOf(t.source("Fish.m4b"), t.source("Fish.narration.txt", text)), 1.0).single()
        val offset = text.indexOf("baseline")
        val comment = Comment(
            kind = CommentRecord.Kind.COMMENT, mediaTime = 3.0, estimatedOffset = offset, confidence = 0.7, chapter = null,
            audioFile = "comment-1.m4a", duration = 2.0, transcript = "weakness: never tuned", reviewTag = "weakness",
        )
        File(t.store.commentsDir(book), "comment-1.m4a").apply { parentFile!!.mkdirs(); writeText("voice") }
        book.comments.add(comment)
        assertTrue(t.library.save())
        return book
    }

    private suspend fun export(t: TestLibrary, book: Book): File =
        File(t.root, "out-${System.nanoTime()}.drafttalk").also { t.store.exportPackage(book, it) }

    @Test fun packageCarriesBookCommentsAndRecordings() = runTest {
        val a = TestLibrary()
        val book = bookWithComment(a)
        val file = export(a, book)

        val b = TestLibrary()
        val imported = b.store.importFiles(listOf(ImportSource.of(file)), 1.0).single()
        assertEquals(book.id, imported.id)
        assertEquals("Fish", imported.title)
        val c = imported.comments.single()
        assertEquals(book.comments.single().id, c.id)
        assertEquals(text.indexOf("baseline"), c.estimatedOffset) // via code points and back
        assertEquals("weakness", c.reviewTag)
        assertEquals("voice", File(b.store.commentsDir(imported), "comment-1.m4a").readText())
        assertEquals(text, File(b.store.folder(imported), "narration.txt").readText())
        assertFalse(File(b.store.folder(imported), "book.json").exists())
        // the library file reloads it
        val again = Library(File(b.root, "library.json")).apply { load() }
        assertEquals(c.updatedAt, again.books.single().comments.single().updatedAt)
    }

    @Test fun packageMergesIntoTheSameBook() = runTest {
        val a = TestLibrary()
        val book = bookWithComment(a)
        val b = TestLibrary()
        val copy = b.store.importFiles(listOf(ImportSource.of(export(a, book))), 1.0).single()

        Thread.sleep(5)
        copy.comments.single().transcript = "weakness: the baseline is never tuned"
        val added = Comment(kind = CommentRecord.Kind.BOOKMARK, mediaTime = 9.0, estimatedOffset = 5, confidence = 1.0, chapter = null, audioFile = null)
        copy.comments.add(added)
        assertTrue(b.library.save())
        val older = export(a, book)
        a.store.importFiles(listOf(ImportSource.of(export(b, copy))), 1.0)

        assertEquals(1, a.library.books.size)
        assertEquals(2, book.comments.size)
        assertEquals("weakness: the baseline is never tuned", book.comments.first { it.id == copy.comments[0].id }.transcript)
        // an older package changes nothing back
        b.store.importFiles(listOf(ImportSource.of(older)), 1.0)
        assertEquals("weakness: the baseline is never tuned", copy.comments[0].transcript)
        assertEquals(1, a.bookFolders().size)
    }
}
