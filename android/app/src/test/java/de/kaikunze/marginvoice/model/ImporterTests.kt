package de.kaikunze.marginvoice.model

import de.kaikunze.marginvoice.TestLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The import queue and the inbox. */
class ImporterTests {
    private val t = TestLibrary()

    /** The imports copy files on Dispatchers.IO, so virtual time alone doesn't finish them. */
    private suspend fun finish(importer: Importer) {
        val until = System.currentTimeMillis() + 5_000
        while (importer.isImporting && System.currentTimeMillis() < until) withContext(Dispatchers.IO) { Thread.sleep(5) }
    }

    @Test fun importQueueRunsEachBatchOnce() = runTest {
        val importer = Importer(t.store, { 1.0 }, backgroundScope)
        importer.import(listOf(t.source("A.m4b"), t.source("A.narration.txt", "One.")))
        importer.import(listOf(t.source("B.m4b")))
        assertTrue(importer.isImporting)
        finish(importer)
        assertFalse(importer.isImporting)
        assertEquals(listOf("A", "B"), t.library.books.map { it.title }.sorted())
        assertEquals(listOf("B"), importer.imported?.books?.map { it.title })
        assertNull(importer.error)
    }

    @Test fun anInboxQueuedTwiceImportsOnce() = runTest {
        val inbox = File(t.root, "Inbox").also { it.mkdirs() }
        File(inbox, "Paper.m4b").writeText("audio")
        File(inbox, "Paper.narration.txt").writeText("One.")
        val importer = Importer(t.store, { 1.0 }, backgroundScope)
        importer.importInbox(inbox)
        importer.importInbox(inbox)
        finish(importer)
        assertEquals(1, t.library.books.size)
        assertTrue(importer.imported!!.fromInbox)
        assertNull(importer.error)
    }

    @Test fun inboxMovesSameStemPdfIntoBook() = runTest {
        val inbox = File(t.root, "Inbox").also { it.mkdirs() }
        File(inbox, "Paper.m4b").writeText("audio")
        File(inbox, "Paper.narration.txt").writeText("One.")
        File(inbox, "Paper.pdf").writeText("%PDF")
        File(inbox, "Other.pdf").writeText("%PDF")
        val books = t.store.importInbox(inbox.listFiles()!!.toList(), 1.0)
        assertEquals(1, books.size)
        val pdf = File(t.store.folder(books[0]), "source.pdf")
        assertEquals("%PDF", pdf.readText())
        // the PDF of a document to convert stays
        assertEquals(listOf("Other.pdf"), inbox.list()!!.toList())
    }
}
