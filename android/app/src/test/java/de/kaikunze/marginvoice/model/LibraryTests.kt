package de.kaikunze.marginvoice.model

import de.kaikunze.marginvoice.TestLibrary
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.core.ReviewTag
import de.kaikunze.marginvoice.core.NarrationText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

/** BookStore, the models and exports against a temporary library and books folder. */
class LibraryTests {
    private val t = TestLibrary()
    private val store get() = t.store
    private val library get() = t.library

    @Test fun importsAudioWithNarrationAndTiming() = runTest {
        val files = listOf(
            t.source("Projekt_Skizze.m4b"),
            t.source("Projekt_Skizze.narration.txt", "Die Ergebnisse zeigen, dass der Roboter die Wand schneller reinigt als bisher. Das ist gut."),
            t.source("Projekt_Skizze.timing.json", "{}"),
        )
        val imported = store.importFiles(files, defaultRate = 1.2)
        assertEquals(1, imported.size)
        val book = library.books.first()
        assertEquals("Projekt Skizze", book.title)
        assertEquals("de", book.language)
        assertEquals(1.2, book.rate, 0.0)
        assertTrue(book.textFile == "narration.txt" && book.timingFile == "timing.json")
        assertTrue(store.audioFile(book).exists())
        // and it is saved: a fresh library reads it back
        val reread = Library(File(t.root, "library.json")).apply { load() }
        assertEquals(listOf("Projekt Skizze"), reread.books.map { it.title })
    }

    @Test fun textWithoutAudioIsRejected() = runTest {
        try {
            store.importFiles(listOf(t.source("notes.txt", "Hello.")), defaultRate = 1.0)
            fail("expected an import error")
        } catch (e: BookStore.ImportError) {
        }
        assertTrue(library.books.isEmpty())
        assertTrue(t.bookFolders().isEmpty())
    }

    @Test fun failedImportLeavesNothingBehind() = runTest {
        val good = t.source("a.m4b")
        val missing = ImportSource("b.m4b") { throw FileNotFoundException("b.m4b") }
        try {
            store.importFiles(listOf(good, missing), defaultRate = 1.0)
            fail("expected an error")
        } catch (e: Exception) {
        }
        assertTrue(library.books.isEmpty())
        assertTrue(t.bookFolders().isEmpty())
    }

    @Test fun deletingABookRemovesItsFolder() = runTest {
        val book = store.importFiles(listOf(t.source("a.m4b")), 1.0).first()
        val folder = store.folder(book)
        store.delete(book)
        assertTrue(library.books.isEmpty())
        assertFalse(folder.exists())
    }

    @Test fun deletingACommentRemovesItsRecording() = runTest {
        val book = store.importFiles(listOf(t.source("a.m4b")), 1.0).first()
        store.commentsDir(book).mkdirs()
        val audio = File(store.commentsDir(book), "c.m4a").also { it.writeText("x") }
        val comment = Comment(kind = CommentRecord.Kind.COMMENT, mediaTime = 3.0, estimatedOffset = 0, confidence = 0.8, chapter = null, audioFile = "c.m4a")
        book.comments.add(comment)
        assertTrue(library.save())

        store.delete(comment)
        assertTrue(book.comments.isEmpty())
        assertFalse(audio.exists())
        assertTrue(store.audioFile(book).exists())
    }

    @Test fun leftoversAreRemovedButCommentsKept() = runTest {
        val book = store.importFiles(listOf(t.source("a.m4b")), 1.0).first()
        val (_, crashed) = store.makeBookFolder()
        val (_, withComments) = store.makeBookFolder()
        File(withComments, "comments").mkdirs()
        File(withComments, "comments/c.m4a").writeText("x")

        store.removeLeftovers()
        assertEquals(setOf(book.folder, withComments.name), t.bookFolders())
        assertFalse(crashed.exists())
    }

    @Test fun commentRecordUsesRefinedAndNudgedOffsets() {
        val text = NarrationText("One a. Two b. Three c.\n")
        val comment = Comment(kind = CommentRecord.Kind.BOOKMARK, mediaTime = 1.0, estimatedOffset = 2, confidence = 0.3, chapter = null, audioFile = null)
        comment.endEstimatedOffset = 12
        comment.refinedOffset = text.sentences[0].range.last + 1
        val refined = comment.record(text)
        assertEquals(text.sentences[0].range.last + 1, refined.textOffset)
        assertEquals(12 + text.sentences[0].range.last + 1 - 2, refined.endTextOffset)

        comment.sentenceNudge = 1
        val nudged = comment.record(text)
        assertEquals(text.sentences[1].range.last + 1, nudged.textOffset)
        assertEquals(1.0, nudged.confidence, 0.0)
        // the passage end moves with the nudged start
        assertEquals(12 + text.sentences[1].range.last + 1 - 2, nudged.endTextOffset)
    }

    @Test fun exportWritesSelectedFiles() = runTest {
        val book = store.importFiles(listOf(t.source("My Draft.m4b")), 1.0).first()
        val comment = Comment(kind = CommentRecord.Kind.COMMENT, mediaTime = 65.0, estimatedOffset = null, confidence = 0.0, chapter = null, audioFile = null)
        comment.transcript = "Shorter, please."
        book.comments.add(comment)

        val files = ExportBuilder.build(book, null, store, ExportBuilder.Options(csv = true), File(t.root, "cache").also { it.mkdirs() })
        // no narration text, so no annotated text
        assertEquals(setOf("My-Draft-comments.md", "My-Draft-comments.csv"), files.map { it.name }.toSet())
        assertTrue(files.first { it.extension == "md" }.readText().contains("Shorter, please."))

        // the review draft: an untagged comment lands under "Other notes" with its time
        comment.tag = ReviewTag.WEAKNESS
        val draft = ExportBuilder.build(book, null, store, ExportBuilder.Options(false, false, reviewDraft = true), File(t.root, "cache"))
        assertEquals(listOf("My-Draft-review.md"), draft.map { it.name })
        assertTrue(draft.single().readText().contains("## Weaknesses\n\n- Shorter, please. (1:05)\n"))
    }

    @Test fun aVersion1LibraryOpensAndKeepsItsComments() {
        // written by 0.1.0: no reviewTag key anywhere
        val v1 = """{"version":1,"books":[{"id":"6f0d2d3e-1111-4a7b-9c2d-000000000001","title":"Old Draft","folder":"b1","audioFile":"a.m4b","duration":10.0,"rate":1.0,"addedAt":1700000000000,"comments":[{"id":"6f0d2d3e-2222-4a7b-9c2d-000000000002","kind":"comment","mediaTime":3.0,"transcript":"The baseline is missing.","transcriptStatus":"done","createdAt":1700000001000}]}]}"""
        val file = File(t.root, "v1.json").also { it.writeText(v1) }
        val library = Library(file).apply { load() }
        assertEquals(null, library.loadError)
        val comment = library.books.single().comments.single()
        assertEquals("The baseline is missing.", comment.transcript)
        assertEquals(null, comment.reviewTag)

        // a tag survives a save and reload; the file keeps its version, so older apps still read it
        comment.reviewTag = "weakness"
        assertTrue(library.save())
        val reread = Library(file).apply { load() }
        assertEquals("weakness", reread.books.single().comments.single().reviewTag)
        assertEquals(1, Library.VERSION)
    }

    @Test fun anUnreadableLibraryIsNeverOverwritten() {
        val file = File(t.root, "broken.json").also { it.writeText("{ not json") }
        val broken = Library(file).apply { load() }
        assertTrue(broken.loadError != null)
        assertFalse(broken.save())
        assertEquals("{ not json", file.readText())
    }
}
