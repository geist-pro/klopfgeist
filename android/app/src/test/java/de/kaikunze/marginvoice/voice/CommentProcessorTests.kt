package de.kaikunze.marginvoice.voice

import de.kaikunze.marginvoice.TestLibrary
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.core.ReviewTag
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.model.BookContent
import de.kaikunze.marginvoice.model.Comment
import de.kaikunze.marginvoice.model.CommentLanguage
import de.kaikunze.marginvoice.model.MemoryStore
import de.kaikunze.marginvoice.model.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Transcription and anchoring after a comment, with stand-in speech engines. */
class CommentProcessorTests {
    private val t = TestLibrary()
    private val settings = Settings(MemoryStore(), systemLanguage = "en")

    private class Engines(
        val transcribe: suspend (VoiceCommand.Language, List<String>) -> String,
        val refine: suspend () -> AnchorRefiner.Refinement? = { null },
    ) : CommentProcessor.Engines {
        override suspend fun transcribe(file: File, language: VoiceCommand.Language, vocabulary: List<String>) = transcribe(language, vocabulary)
        override suspend fun refine(bookAudio: File, anchor: Double, content: BookContent, language: VoiceCommand.Language) = refine()
    }

    private fun TestScope.processor(engines: Engines) = CommentProcessor(t.store, t.library, settings, this, engines)

    /** A German book with narration and one recorded comment. */
    private suspend fun germanComment(): Comment {
        val book = t.store.importFiles(
            listOf(
                t.source("Skizze.m4b"),
                t.source("Skizze.narration.txt", "Die Ergebnisse zeigen, dass der WallBOT Roboter die Wand schneller reinigt als bisher. Das ist gut.\n"),
            ),
            1.0,
        ).first()
        val comment = Comment(kind = CommentRecord.Kind.COMMENT, mediaTime = 0.0, estimatedOffset = 10, confidence = 0.3, chapter = null, audioFile = "c.m4a")
        book.comments.add(comment)
        t.library.save()
        return comment
    }

    /** Holds a stand-in engine until the test lets it go on. */
    private class Gate {
        var passes = 0
        val reached = CompletableDeferred<Unit>()
        private val opened = CompletableDeferred<Unit>()
        suspend fun pass() {
            passes++
            reached.complete(Unit)
            opened.await()
        }
        fun open() = opened.complete(Unit)
    }

    @Test fun transcribesInTheBookLanguageAndPinsTheComment() = runTest {
        val comment = germanComment()
        var locale = ""
        var vocabulary = emptyList<String>()
        processor(Engines({ lang, words -> locale = lang.regionIdentifier; vocabulary = words; "Das ist zu lang. Weiter." }) {
            AnchorRefiner.Refinement(42, 0.9)
        }).process(comment)
        assertEquals("de-DE", locale)
        assertTrue(vocabulary.contains("WallBOT"))
        assertEquals("Das ist zu lang.", comment.transcript)
        assertEquals(Comment.TranscriptStatus.DONE, comment.transcriptStatus)
        assertTrue(comment.refinedOffset == 42 && comment.confidence == 0.9)
    }

    @Test fun aSpokenTagSortsTheComment() = runTest {
        val comment = germanComment()
        processor(Engines({ _, _ -> "Schwäche: die Baseline wird nie optimiert. Weiter." })).process(comment)
        assertEquals("Die Baseline wird nie optimiert.", comment.transcript)
        assertEquals(ReviewTag.WEAKNESS, comment.tag)
    }

    @Test fun aFixedCommentLanguageWins() = runTest {
        val comment = germanComment()
        settings.commentLanguage = CommentLanguage.ENGLISH
        var locale = ""
        processor(Engines({ lang, _ -> locale = lang.regionIdentifier; "Shorter." })).process(comment)
        assertEquals("en-US", locale)
    }

    @Test fun failureCanBeRetried() = runTest {
        val comment = germanComment()
        processor(Engines({ _, _ -> throw IllegalStateException("no recognizer") })).process(comment)
        assertEquals(Comment.TranscriptStatus.FAILED, comment.transcriptStatus)
        processor(Engines({ _, _ -> "Second try." })).process(comment)
        assertEquals("Second try.", comment.transcript)
    }

    @Test fun aCommentDeletedMeanwhileIsLeftAlone() = runTest {
        val comment = germanComment()
        val gate = Gate()
        val p = processor(Engines({ _, _ -> gate.pass(); "Too late." }) { AnchorRefiner.Refinement(1, 1.0) })
        val running = async { p.process(comment) }
        gate.reached.await()
        t.store.delete(comment)
        gate.open()
        running.await()
        assertEquals("", comment.transcript)
        assertEquals(null, comment.refinedOffset)
    }

    @Test fun runsEachCommentOnceAtATime() = runTest {
        val comment = germanComment()
        val gate = Gate()
        val p = processor(Engines({ _, _ -> gate.pass(); "Once." }))
        val first = async { p.process(comment) }
        gate.reached.await()
        p.process(comment) // returns at once: already running
        gate.open()
        first.await()
        assertEquals(1, gate.passes)
    }

    @Test fun transcribingAgainRedoesARunningComment() = runTest {
        val comment = germanComment()
        val gate = Gate()
        val p = processor(Engines({ _, _ -> gate.pass(); "Again." }))
        val first = async { p.process(comment) }
        gate.reached.await()
        val again = async { p.transcribeAgain(t.library.bookOf(comment)!!) }
        yield()
        gate.open()
        again.await()
        first.await()
        assertEquals(2, gate.passes)
        assertEquals(Comment.TranscriptStatus.DONE, comment.transcriptStatus)
    }
}
