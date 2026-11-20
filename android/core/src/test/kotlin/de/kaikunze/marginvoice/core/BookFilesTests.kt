package de.kaikunze.marginvoice.core

import de.kaikunze.marginvoice.core.VoiceCommand.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookFilesTests {
    private fun urls(vararg names: String) = names.map { "/inbox/$it" }
    private fun name(p: String?) = p?.substringAfterLast('/')

    @Test fun stemsPairSidecars() {
        assertEquals("My Book", BookFiles.stem("/a/My Book.m4b"))
        assertEquals("My Book", BookFiles.stem("/a/My Book.narration.txt"))
        assertEquals("My Book", BookFiles.stem("/a/My Book.timing.json"))
        assertEquals("v1.2", BookFiles.stem("/a/v1.2.txt"))
    }

    @Test fun groupsOneBookPerAudioFile() {
        val (books, orphans) = BookFiles.group(urls("b.m4b", "b.narration.txt", "b.timing.json", "a.MP3", "c.txt"))
        assertEquals(listOf("a", "b"), books.map { it.stem })
        assertTrue(books[0].text == null && books[0].timing == null)
        assertEquals("b.narration.txt", name(books[1].text))
        assertEquals("b.timing.json", name(books[1].timing))
        assertEquals(listOf("c"), orphans)
        // doc2audio's narration wins over a plain text of the same name
        assertEquals("d.narration.txt", name(BookFiles.group(urls("d.txt", "d.m4b", "d.narration.txt")).books[0].text))
    }

    @Test fun inboxSeparatesAudiobooksFromDocuments() {
        val (audiobook, documents) = BookFiles.sortInbox(urls("x.m4b", "x.txt", "paper.pdf", "notes.md", "song.flac", "a.txt"))
        assertEquals(setOf("x.m4b", "x.txt"), audiobook.map { name(it) }.toSet())
        // a .txt next to an audiobook is its narration, not a document to convert
        assertEquals(listOf("a.txt", "notes.md", "paper.pdf"), documents.map { name(it) })
    }

    @Test fun safeExportNames() {
        assertEquals("Projektskizze--WallBOT---v2", BookFiles.safeName("Projektskizze: WallBOT / v2"))
        assertEquals("Ä-Ö-ü", BookFiles.safeName("  Ä Ö ü  "))
        assertEquals(60, BookFiles.safeName("x".repeat(80)).length)
    }

    @Test fun clockSurvivesInvalidTimes() {
        assertEquals("0:00", TimeFormat.clock(Double.NaN))
        assertEquals("0:00", TimeFormat.clock(Double.POSITIVE_INFINITY))
        assertEquals("1:02:03", TimeFormat.clock(3723.0))
    }

    @Test fun csvQuotesLineBreaksAndDefusesFormulas() {
        assertEquals("\"a\r\nb\"", CommentExport.quote("a\r\nb"))
        assertEquals("'=SUM(A1)", CommentExport.quote("=SUM(A1)"))
        assertEquals("plain", CommentExport.quote("plain"))
    }

    @Test fun languageCodes() {
        assertEquals(Language.GERMAN, Language.fromCode("de"))
        assertEquals(Language.GERMAN, Language.fromCode("de-AT"))
        assertEquals(Language.ENGLISH, Language.fromCode("fr"))
        assertEquals(Language.ENGLISH, Language.fromCode(null))
        assertEquals("de-DE", Language.GERMAN.regionIdentifier)
    }

    @Test fun detectsGermanAndEnglish() {
        assertEquals(Language.GERMAN, Language.detect("Die Ergebnisse zeigen, dass der Roboter die Wand schneller reinigt als bisher."))
        assertEquals(Language.ENGLISH, Language.detect("The results show that the robot cleans the wall faster than before."))
    }

    @Test fun chapterAtTime() {
        val chapters = listOf(ChapterMark("A", 0.0, 10.0), ChapterMark("B", 10.0, 20.0))
        assertEquals(0, chapters.indexAt(5.0))
        assertEquals(1, chapters.indexAt(9.96))
        assertEquals(1, chapters.indexAt(25.0))
        assertNull(emptyList<ChapterMark>().indexAt(3.0))
    }

    @Test fun sentenceNudges() {
        val text = NarrationText("One a. Two b. Three c.\n")
        val afterOne = text.sentences[0].range.upper
        assertEquals(afterOne, CommentRecord.nudgedOffset(afterOne, 0, text))
        assertEquals(text.sentences[1].range.upper, CommentRecord.nudgedOffset(afterOne, 1, text))
        // clamped at both ends
        assertEquals(text.sentences[2].range.upper, CommentRecord.nudgedOffset(afterOne, 9, text))
        assertEquals(text.sentences[0].range.upper, CommentRecord.nudgedOffset(afterOne, -9, text))
        assertEquals(42, CommentRecord.nudgedOffset(42, 1, null))
    }

    @Test fun passageEndFollowsRefinedStart() {
        assertEquals(100, CommentRecord.passageEnd(100, 40, null))
        assertEquals(110, CommentRecord.passageEnd(100, 40, 50))
        // never ends before it starts
        assertEquals(50, CommentRecord.passageEnd(30, 40, 50))
    }
}
