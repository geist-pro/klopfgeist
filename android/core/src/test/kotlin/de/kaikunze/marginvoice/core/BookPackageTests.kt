package de.kaikunze.marginvoice.core

import de.kaikunze.marginvoice.core.SyncMerge.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookPackageTests {
    private val book = PackagedBook(
        addedAt = "2026-10-29T10:00:00.000Z", audioFile = "book.m4b", chapters = listOf(ChapterMark("Intro", 0.0, 12.5)),
        duration = 1234.5, id = "6F1C2B4A-0D1E-4F00-9A8B-1C2D3E4F5A6B", language = "en", position = 61.0, rate = 1.25,
        sourceFile = "source.pdf", textFile = "narration.txt", timingFile = "timing.json", title = "Cuddle-fish",
        updatedAt = "2026-10-29T10:05:00.123Z",
    )
    private val comment = PackagedComment(
        audioFile = "comment-1761732300000.m4a", chapter = "Intro", confidence = 0.8, createdAt = "2026-10-29T10:05:00.000Z",
        duration = 7.4, endEstimatedOffset = null, estimatedOffset = 120, id = "0B5F1D9E-3C4A-4B2A-8E7F-123456789ABC",
        kind = "comment", mediaTime = 12.0, refinedOffset = 118, reviewTag = "weakness", sentenceNudge = -1,
        transcript = "the baseline is never tuned", transcriptStatus = "done", updatedAt = "2026-10-29T10:06:00.000Z",
    )

    @Test fun packageRoundTripsEveryCommentField() {
        val dir = Files.createTempDirectory("pkg").toFile()
        val audio = ByteArray(5000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        BookPackage.writeZip(
            BookPackage.entries(book, listOf(comment), listOf(
                BookPackage.Entry.of("narration.txt", "Hello. World."),
                BookPackage.Entry("book.m4b") { ByteArrayInputStream(audio) },
                BookPackage.Entry("comments/${comment.audioFile}") { ByteArrayInputStream(audio) },
            )),
            out,
        )
        val unpacked = BookPackage.unzip(ByteArrayInputStream(out.toByteArray()), dir)
        assertEquals(book, unpacked.book)
        assertEquals(listOf(comment), unpacked.comments)
        assertEquals("Hello. World.", dir.resolve("narration.txt").readText())
        assertTrue(dir.resolve("book.m4b").readBytes().contentEquals(audio))
        assertTrue(dir.resolve("comments/${comment.audioFile}").exists())
        // keys sorted, nulls left out: the same bytes the Swift encoder writes
        assertTrue(BookPackage.encode(comment).startsWith("{\"audioFile\":\"comment-"))
        assertTrue("endEstimatedOffset" !in BookPackage.encode(comment))
    }

    @Test fun hashIgnoresUpdatedAt() {
        assertEquals(BookPackage.hash(comment), BookPackage.hash(comment.copy(updatedAt = "2027-01-01T00:00:00.000Z")))
        assertNotEquals(BookPackage.hash(comment), BookPackage.hash(comment.copy(transcript = "tuned")))
        assertEquals("2026-10-29T10:05:00.123Z", BookPackage.date(BookPackage.date("2026-10-29T10:05:00.123Z")))
    }

    @Test fun unzipRefusesPathsOutsideTheFolder() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("../evil.txt")); z.write(1); z.closeEntry()
        }
        val dir = Files.createTempDirectory("pkg").toFile()
        assertThrows(IllegalArgumentException::class.java) { BookPackage.unzip(ByteArrayInputStream(out.toByteArray()), dir) }
    }

    @Test fun newerFormatIsRefused() {
        val s = BookPackage.encode(book.copy(format = 2))
        assertThrows(IllegalArgumentException::class.java) { BookPackage.decodeBook(s) }
    }

    @Test fun offsetsSurviveEmojiAndUmlauts() {
        val s = "Schöne 👍🏽 Grüße. Next"
        val utf16 = s.indexOf("Next")
        val cp = OffsetUnits.toCodePoints(s, utf16)
        assertEquals(s.codePointCount(0, utf16), cp)
        assertEquals(utf16 - 2, cp) // the two emoji code points are surrogate pairs
        assertEquals(utf16, OffsetUnits.fromCodePoints(s, cp))
        assertEquals(s.length, OffsetUnits.fromCodePoints(s, 10_000))
        assertEquals(0, OffsetUnits.fromCodePoints(s, -3))
    }
}

class SyncMergeTests {
    private val t0 = Instant.parse("2026-10-29T10:00:00Z")
    private fun item(v: String, at: Long = 0) = SyncMerge.Item(v, "h-$v", t0.plusSeconds(at))

    @Test fun newerCommentWins() {
        val r = SyncMerge.merge(mapOf("c" to "h-a"), mapOf("c" to item("b", 10)), mapOf("c" to item("c", 20)))
        assertEquals(listOf<Action<String>>(Action.PutLocal("c", "c")), r.actions)
        assertEquals("h-c", r.base["c"])
        val l = SyncMerge.merge(mapOf("c" to "h-a"), mapOf("c" to item("b", 30)), mapOf("c" to item("c", 20)))
        assertEquals(listOf<Action<String>>(Action.PutRemote("c", "b")), l.actions)
    }

    @Test fun oneSidedChangesWin() {
        val up = SyncMerge.merge(mapOf("c" to "h-a"), mapOf("c" to item("b")), mapOf("c" to item("a", 99)))
        assertEquals(listOf<Action<String>>(Action.PutRemote("c", "b")), up.actions)
        val down = SyncMerge.merge(mapOf("c" to "h-a"), mapOf("c" to item("a", 99)), mapOf("c" to item("b")))
        assertEquals(listOf<Action<String>>(Action.PutLocal("c", "b")), down.actions)
        val same = SyncMerge.merge(emptyMap(), mapOf("c" to item("a")), mapOf("c" to item("a")))
        assertTrue(same.actions.isEmpty())
        assertEquals("h-a", same.base["c"])
    }

    @Test fun deleteRemovesUnchangedCopy() {
        val remoteDeleted = SyncMerge.merge(mapOf("c" to "h-a"), mapOf("c" to item("a")), emptyMap())
        assertEquals(listOf<Action<String>>(Action.DeleteLocal("c")), remoteDeleted.actions)
        assertTrue("c" !in remoteDeleted.base)
        val localDeleted = SyncMerge.merge(mapOf("c" to "h-a"), emptyMap(), mapOf("c" to item("a")))
        assertEquals(listOf<Action<String>>(Action.DeleteRemote("c")), localDeleted.actions)
    }

    @Test fun editSurvivesRemoteDelete() {
        val r = SyncMerge.merge(mapOf("c" to "h-a"), mapOf("c" to item("b")), emptyMap())
        assertEquals(listOf<Action<String>>(Action.PutRemote("c", "b")), r.actions)
        val l = SyncMerge.merge(mapOf("c" to "h-a"), emptyMap(), mapOf("c" to item("b")))
        assertEquals(listOf<Action<String>>(Action.PutLocal("c", "b")), l.actions)
    }

    @Test fun concurrentNewCommentsBothKept() {
        val r = SyncMerge.merge(emptyMap(), mapOf("x" to item("x")), mapOf("y" to item("y")))
        assertEquals(listOf(Action.PutRemote("x", "x"), Action.PutLocal("y", "y")), r.actions)
        assertEquals(setOf("x", "y"), r.base.keys)
    }

    @Test fun tiesPickTheSameSideOnEveryDevice() {
        val a = SyncMerge.merge(mapOf("c" to "h-0"), mapOf("c" to item("a")), mapOf("c" to item("b")))
        val b = SyncMerge.merge(mapOf("c" to "h-0"), mapOf("c" to item("b")), mapOf("c" to item("a")))
        assertEquals("h-b", a.base["c"])
        assertEquals("h-b", b.base["c"])
    }
}
