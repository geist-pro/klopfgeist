package de.kaikunze.marginvoice.voice

import de.kaikunze.marginvoice.TestLibrary
import de.kaikunze.marginvoice.core.AudioPlan
import de.kaikunze.marginvoice.core.AudioRoute
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.core.Hypothesis
import de.kaikunze.marginvoice.core.ReviewMachine
import de.kaikunze.marginvoice.core.SpokenWord
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.model.Comment
import de.kaikunze.marginvoice.model.Library
import de.kaikunze.marginvoice.session.AudioCapture
import de.kaikunze.marginvoice.session.BookPlayer
import de.kaikunze.marginvoice.session.BookmarkClose
import de.kaikunze.marginvoice.session.CaptureEvent
import de.kaikunze.marginvoice.session.CaptureStatus
import de.kaikunze.marginvoice.session.MicrophonePermission
import de.kaikunze.marginvoice.session.MicrophoneSnapshot
import de.kaikunze.marginvoice.session.NowPlaying
import de.kaikunze.marginvoice.session.PlayerEvent
import de.kaikunze.marginvoice.session.RecordingResult
import de.kaikunze.marginvoice.session.RemoteControlling
import de.kaikunze.marginvoice.session.ReviewSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The session's book over the JSON library: comments, bookmarks, passages and the position, as the archived app stored them. */
class SessionBookTests {
    private val t = TestLibrary()
    private val added = ArrayList<Comment>()

    /** A book with narration (and so a time map) in the temporary library. */
    private suspend fun book(): Book {
        val book = t.store.importFiles(
            listOf(
                t.source("Paper.m4b"),
                t.source("Paper.narration.txt", "The robot cleans the wall. It is faster than before. The baseline is never tuned.\n"),
            ),
            1.0,
        ).first()
        book.duration = 60.0
        t.library.save()
        return book
    }

    private suspend fun open(book: Book) = SessionBook(book, t.store, t.library) { added.add(it) }.also { it.content = t.store.content(book) }

    private fun reread() = Library(File(t.root, "library.json")).apply { load() }

    @Test fun describesTheBookToTheSession() = runTest {
        val book = book()
        book.position = 12.0
        book.rate = 1.3
        val sb = open(book)
        assertEquals("Paper", sb.title)
        assertEquals(t.store.audioFile(book), sb.audioFile)
        assertEquals(12.0, sb.position, 0.0)
        assertEquals(1.3, sb.rate, 0.0)
        assertNotNull(sb.recentText(before = 30.0, seconds = 5.0))
        assertNotNull(sb.sentenceStart(30.0))
    }

    @Test fun withoutTextThereIsNothingToCompareOrSnapTo() = runTest {
        val book = t.store.importFiles(listOf(t.source("Audio.m4b")), 1.0).first()
        val sb = open(book)
        assertNull(sb.recentText(10.0, 5.0))
        assertNull(sb.sentenceStart(10.0))
        assertTrue(sb.addBookmark(3.0))
        val bookmark = book.comments.single()
        assertNull(bookmark.estimatedOffset)
        assertEquals(0.0, bookmark.confidence, 0.0)
    }

    @Test fun aCommentIsStoredWithItsRecordingAndHandedToTheProcessor() = runTest {
        val book = book()
        val sb = open(book)
        val file = sb.newRecordingFile()!!
        assertEquals(t.store.commentsDir(book), file.parentFile)
        assertTrue(file.name.startsWith("comment-") && file.name.endsWith(".m4a"))
        file.writeText("audio")

        assertTrue(sb.addComment(at = 20.0, audio = file, duration = 4.2))
        val comment = book.comments.single()
        assertEquals(CommentRecord.Kind.COMMENT, comment.kind)
        assertEquals(20.0, comment.mediaTime, 0.0)
        assertEquals(file.name, comment.audioFile)
        assertEquals(4.2, comment.duration, 0.0)
        assertEquals(sb.content!!.offset(20.0), comment.estimatedOffset)
        // no timing sidecar: the position in the text is an estimate
        assertEquals(0.3, comment.confidence, 0.0)
        assertEquals(Comment.TranscriptStatus.PENDING, comment.transcriptStatus)
        assertEquals(listOf(comment), added)

        // on disk, in the file format the archived app wrote
        val text = File(t.root, "library.json").readText()
        assertTrue(text.startsWith("{\"version\":1,"))
        val stored = reread().books.single().comments.single()
        assertEquals(comment.id, stored.id)
        assertEquals(file.name, stored.audioFile)
        assertEquals(t.store.audioFile(stored, book), file)
    }

    @Test fun aDiscardedRecordingIsDeleted() = runTest {
        val sb = open(book())
        val file = sb.newRecordingFile()!!
        file.writeText("audio")
        sb.discardRecording(file)
        assertFalse(file.exists())
    }

    @Test fun aBookmarkBecomesAPassage() = runTest {
        val book = book()
        val sb = open(book)
        assertTrue(sb.addBookmark(10.0))
        val bookmark = book.comments.single()
        assertEquals(CommentRecord.Kind.BOOKMARK, bookmark.kind)
        assertNull(bookmark.audioFile)
        assertEquals(Comment.TranscriptStatus.NONE, bookmark.transcriptStatus)
        assertFalse(bookmark.isPassage)

        assertEquals(BookmarkClose.CLOSED, sb.closeBookmark(25.0))
        assertEquals(25.0, bookmark.endMediaTime!!, 0.0)
        assertEquals(sb.content!!.offset(25.0), bookmark.endEstimatedOffset)
        assertEquals(1, book.comments.size)
        assertEquals(25.0, reread().books.single().comments.single().endMediaTime!!, 0.0)

        // the passage is closed: another end has nothing to extend
        assertEquals(BookmarkClose.MISSING, sb.closeBookmark(40.0))
        assertEquals(25.0, bookmark.endMediaTime!!, 0.0)
    }

    @Test fun aBookmarkDeletedMeanwhileIsNotExtended() = runTest {
        val book = book()
        val sb = open(book)
        sb.addBookmark(10.0)
        val bookmark = book.comments.single()
        t.store.delete(bookmark)
        assertEquals("nothing to extend, and nothing failed to save", BookmarkClose.MISSING, sb.closeBookmark(25.0))
        assertNull(bookmark.endMediaTime)
    }

    @Test fun aPassageEndThatCannotBeWrittenIsNotSaved() = runTest {
        val book = book()
        val sb = open(book)
        assertTrue(sb.addBookmark(10.0))
        // the library file can no longer be replaced (a folder with something in it stands in its place)
        val file = File(t.root, "library.json")
        file.delete()
        File(file, "in-the-way").apply { parentFile.mkdirs() }.writeText("x")
        assertEquals(BookmarkClose.NOT_SAVED, sb.closeBookmark(25.0))
    }

    @Test fun aPassageEndWithoutAnyBookmarkIsMissing() = runTest {
        val sb = open(book())
        assertEquals(BookmarkClose.MISSING, sb.closeBookmark(5.0))
    }

    @Test fun positionAndRateAreSaved() = runTest {
        val book = book()
        val sb = open(book)
        assertTrue(sb.savePosition(33.0))
        assertEquals(33.0, reread().books.single().position, 0.0)
        // the rate rides along with the next save
        sb.saveRate(1.6)
        assertEquals(1.6, book.rate, 0.0)
        assertTrue(sb.savePosition(34.0))
        assertEquals(1.6, reread().books.single().rate, 0.0)
    }

    @Test fun savingStampsWhatChangedAndOnlyThat() = runTest {
        val book = book()
        val sb = open(book)
        sb.addBookmark(5.0)
        val first = book.comments.single()
        val firstStamp = first.updatedAt
        val bookStamp = book.updatedAt
        Thread.sleep(5)
        sb.addBookmark(8.0)
        assertEquals("an untouched comment keeps its stamp", firstStamp, first.updatedAt)
        assertEquals("comments are stamped on their own", bookStamp, book.updatedAt)
        Thread.sleep(5)
        sb.savePosition(9.0)
        assertTrue(book.updatedAt > bookStamp)
    }

    @Test fun aLibraryThatCannotBeWrittenIsReported() = runTest {
        val book = book()
        val sb = open(book)
        // an unreadable library file is never overwritten
        File(t.root, "library.json").writeText("{ not json")
        t.library.load()
        assertNotNull(t.library.loadError)
        val orphan = SessionBook(book, t.store, t.library)
        assertFalse(orphan.addBookmark(1.0))
        assertFalse(orphan.savePosition(2.0))
        assertFalse(sb.addComment(1.0, File(t.root, "x.m4a"), 1.0))
    }

    // The session and the library together

    private class Player : BookPlayer {
        override var isPlaying = false
        override var liveTime = 0.0
        override var duration = 60.0
        override var rate = 1.0
            private set
        override var onEvent: ((PlayerEvent) -> Unit)? = null
        override fun load(file: File, position: Double, rate: Double) {
            liveTime = position
            this.rate = rate
        }
        override fun unload() {}
        override fun play() {
            if (isPlaying) return
            isPlaying = true
            onEvent?.invoke(PlayerEvent.PlayingChanged(true))
        }
        override fun pause() {
            if (!isPlaying) return
            isPlaying = false
            onEvent?.invoke(PlayerEvent.PlayingChanged(false))
        }
        override fun seek(to: Double) {
            liveTime = to
        }
        override fun setRate(rate: Double) {
            this.rate = rate
        }
        override fun playEarcon(earcon: ReviewMachine.Earcon) {}
        override fun announce(text: String, language: String) {}
    }

    private class Capture : AudioCapture {
        override val route = AudioRoute(listOf(AudioRoute.Port.BLUETOOTH_A2DP), AudioRoute.Port.BUILT_IN_MIC)
        override val microphonePermission = MicrophonePermission.GRANTED
        override var onEvent: ((CaptureEvent) -> Unit)? = null
        var micTime = 0.0
        var running = false
        var recording: File? = null
        override suspend fun requestMicrophonePermission() = true
        override suspend fun apply(plan: AudioPlan, change: AudioPlan.Change, language: VoiceCommand.Language): CaptureStatus {
            running = plan.capture
            return CaptureStatus("Built-in microphone", "Pixel Buds")
        }
        override fun snapshot() = MicrophoneSnapshot(micTime = micTime, isRunning = running)
        override fun startRecording(to: File) {
            to.writeText("audio")
            recording = to
        }
        override fun stopRecording() = RecordingResult(3.0, null).also { recording = null }
        override fun isOtherAudioPlaying() = false
    }

    private object Remote : RemoteControlling {
        override fun activate(handler: (ReviewMachine.RemoteButton) -> Unit) {}
        override fun deactivate() {}
        override fun update(info: NowPlaying) {}
    }

    @Test fun stopCommentContinueWritesTheCommentToTheLibrary() = runTest {
        val book = book()
        val sb = open(book)
        var clock = 1000.0
        val player = Player()
        val capture = Capture()
        val session = ReviewSession(player, capture, Remote, scope = backgroundScope, now = { clock })
        suspend fun pass(seconds: Double) {
            repeat((seconds * 10).toInt()) {
                clock += 0.1
                capture.micTime += 0.1
                if (player.isPlaying) player.liveTime += 0.1
                session.tick()
                session.idle()
            }
        }
        fun say(word: String) {
            val mic = capture.micTime
            capture.onEvent?.invoke(CaptureEvent.Hypothesis(Hypothesis(listOf(SpokenWord(word, mic - 0.35, mic - 0.05)), false)))
        }

        session.open(sb)
        session.togglePlay()
        pass(20.0)
        say("stop")
        pass(0.5)
        assertNotNull(capture.recording)
        pass(3.0)
        say("continue")
        pass(1.0)

        val comment = book.comments.single()
        assertEquals(CommentRecord.Kind.COMMENT, comment.kind)
        // anchored a reaction time before "stop"
        assertEquals(20.0 - 0.35 - 1.5, comment.mediaTime, 0.3)
        assertEquals(3.0, comment.duration, 0.0)
        assertTrue(t.store.audioFile(comment, book)!!.exists())
        assertEquals(listOf(comment), added)
        assertTrue(player.isPlaying)
        // and the position was written on the way
        assertTrue(reread().books.single().position > 10)

        // "stop", then "cancel": the recording is gone, the library unchanged
        pass(5.0)
        say("stop")
        pass(0.5)
        val discarded = capture.recording!!
        pass(2.0)
        say("cancel")
        pass(1.0)
        assertFalse(discarded.exists())
        assertEquals(1, book.comments.size)
    }
}
