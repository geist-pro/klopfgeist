package de.kaikunze.marginvoice.voice

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.kaikunze.marginvoice.audio.ExoBookPlayer
import de.kaikunze.marginvoice.audio.MediaSessionRemote
import de.kaikunze.marginvoice.core.ReviewMachine
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.model.BookContent
import de.kaikunze.marginvoice.model.BookStore
import de.kaikunze.marginvoice.model.Comment
import de.kaikunze.marginvoice.model.Library
import de.kaikunze.marginvoice.model.Settings
import de.kaikunze.marginvoice.session.ReviewSession
import de.kaikunze.marginvoice.session.ReviewSession.ListenState
import de.kaikunze.marginvoice.session.SessionSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant

/**
 * Owns the review session and its Android adapters for the life of the app, and is
 * what the screens talk to. It keeps the session supplied (settings, the 10 Hz tick
 * while a book is open, foreground/background, the next book when one ends) and
 * copies the session's state into Compose state. The review loop itself is
 * [ReviewSession] in `:session`; nothing here decides about commands or audio.
 * Main thread.
 */
class SessionHost(
    val engine: ExoBookPlayer,
    val capture: AndroidAudioCapture,
    remote: MediaSessionRemote,
    val processor: CommentProcessor,
    private val store: BookStore,
    private val library: Library,
    private val settings: Settings,
    /** Has to queue what is launched (`Dispatchers.Main`, as `MainScope()` gives), see [ReviewSession]. */
    private val scope: CoroutineScope,
) {
    /** A short confirmation shown in the status pill ("Comment saved"). */
    data class Flash(val label: String, val isBookmark: Boolean)

    data class LogEntry(val id: Int, val text: String, val accepted: Boolean, val date: Instant)

    val session = ReviewSession(engine, capture, remote, sessionSettings(), scope) { seconds() }

    // What the screens observe (copied from the session whenever it changes)

    var book by mutableStateOf<Book?>(null)
        private set
    private var sessionBook by mutableStateOf<SessionBook?>(null)
    /** The open book's text; arrives a moment after the book. */
    val content: BookContent? get() = sessionBook?.content
    var machineState by mutableStateOf<ReviewMachine.State>(ReviewMachine.State.Paused)
        private set
    var listenState by mutableStateOf<ListenState>(ListenState.Off)
        private set
    /** The microphone listening right now. */
    var microphone by mutableStateOf("Not listening")
        private set
    var micLevel by mutableFloatStateOf(0f)
        private set
    var flash by mutableStateOf<Flash?>(null)
        private set
    /** The command log, newest first. */
    var events by mutableStateOf<List<LogEntry>>(emptyList())
        private set
    /** A bookmark was just set and saying "bookmark" again would end a passage there. */
    var isBookmarkOpen by mutableStateOf(false)
        private set
    /**
     * Voice commands are on, but the microphone was let go because nobody used the
     * paused book for a while, or another app started playing; Play or the status pill listens again.
     */
    var isListeningPaused by mutableStateOf(false)
        private set
    /** The book that plays when this one ends (when continuing is on). */
    var upNext by mutableStateOf<Book?>(null)
        private set
    /** Keep the screen on (the activity follows this). */
    var keepScreenOn by mutableStateOf(false)
        private set
    private var error by mutableStateOf<String?>(null)
    var errorMessage: String?
        get() = error
        set(value) {
            error = value
            if (value == null) session.errorMessage = null
        }

    val isRecording: Boolean get() = machineState is ReviewMachine.State.Recording

    /** A recording of a comment is playing back in the app. */
    var playingOwnAudio = false

    private var ticker: Job? = null
    private var reportedSaveFailure = false
    private var lastLogged = 0

    init {
        session.onChange = ::sessionChanged
        session.onBookFinished = { nextBook() }
        capture.note = session::note
        capture.ownAudioPlaying = { playingOwnAudio || engine.isAnnouncing }
        processor.save = ::save
        processor.note = session::note
    }

    private fun seconds() = SystemClock.elapsedRealtime() / 1000.0

    /** The user's settings as the session reads them. */
    private fun sessionSettings() = SessionSettings(
        voiceCommandsEnabled = settings.voiceCommandsEnabled,
        commandLanguage = settings.commandLanguage,
        useHeadsetMicrophone = settings.useHeadsetMicrophone,
        reactionOffset = settings.reactionOffset,
        resumeRewind = settings.resumeRewind,
        endCommentOnSilence = settings.endCommentOnSilence,
        silenceTimeout = settings.silenceTimeout,
        stopListeningAfter = settings.stopListeningAfter.toInt(),
    )

    /** Hands the current settings to the session (which acts only when something differs). */
    fun settingsChanged() {
        session.settings = sessionSettings()
    }

    private fun sessionChanged() {
        val open = session.book as? SessionBook
        if (open !== sessionBook) {
            sessionBook = open
            book = open?.book
        }
        machineState = session.machine.state
        listenState = session.listenState
        microphone = session.microphone
        micLevel = session.micLevel
        isBookmarkOpen = session.isBookmarkOpen
        isListeningPaused = session.isListeningPaused
        session.errorMessage?.let { error = it }
        flash = currentFlash()
        val log = session.log
        if (log.firstOrNull()?.id != events.firstOrNull()?.id || log.size != events.size) {
            // newest first; what is new since the last look also goes to logcat (tools/device-audio-trace.sh)
            for (entry in log.asReversed()) if (entry.id > lastLogged) Log.i(TAG, entry.text)
            lastLogged = log.firstOrNull()?.id ?: lastLogged
            val now = seconds()
            val wall = Instant.now()
            events = log.map { LogEntry(it.id, it.text, it.accepted, wall.minusMillis(((now - it.at) * 1000).toLong())) }
        }
        // with the plan, this says whether the loudness gate applies (CommandGatePolicy)
        capture.bookPlaying = engine.isPlaying
        keepScreenOn = settings.keepScreenAwake && engine.isPlaying
    }

    /** The session's confirmation, in words, for three seconds. */
    private fun currentFlash(): Flash? {
        val f = session.flash ?: return null
        if (seconds() - f.at > 3) return null
        return when (f.kind) {
            ReviewSession.Flash.Kind.COMMENT -> Flash("Comment saved", isBookmark = false)
            ReviewSession.Flash.Kind.BOOKMARK -> Flash("Bookmarked", isBookmark = true)
            ReviewSession.Flash.Kind.PASSAGE -> Flash("Passage bookmarked", isBookmark = true)
        }
    }

    // Opening books

    /** Opens the book on the app's scope, so leaving the screen doesn't cut the opening short. */
    fun show(book: Book) {
        if (this.book?.id == book.id) return
        scope.launch { open(book) }
    }

    suspend fun open(book: Book) {
        if (this.book?.id == book.id) return
        val prepared = prepare(book)
        if (prepared == null) {
            session.close()
            return
        }
        settingsChanged()
        // the session has the book (or its failure) before this coroutine first suspends,
        // so the ticker started below never looks at a session that is still about to get one
        startTicker()
        session.open(prepared)
        refreshUpNext()
    }

    /** The book as the session takes it, or null (with a message) when its audio is gone. */
    private fun prepare(book: Book): SessionBook? {
        val file = store.audioFile(book)
        if (!file.exists()) {
            error = "Could not open the audiobook: ${file.name} is missing."
            return null
        }
        book.lastOpened = Instant.now()
        // a finished book starts over
        if (book.isFinished) book.position = 0.0
        engine.expect(book.duration, book.title)
        val prepared = SessionBook(book, store, library) { comment -> scope.launch { processor.process(comment) } }
        // the text loads beside the audio: the book can play before its sentences are known
        scope.launch {
            prepared.content = store.content(book)
            if (library.book(book.id) != null) processor.resumeCutOff(book)
        }
        return prepared
    }

    fun close() = session.close()

    /** The session's clock: ten ticks a second while a book is open. */
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(100)
                settingsChanged()
                session.tick()
                // the flash runs out without the session changing
                if (flash != null && currentFlash() == null) flash = null
                // no book (closed, or the next one could not be opened): nothing to tick for.
                // Opening a book starts the ticker again.
                if (session.book == null) break
            }
        }
    }

    // Continuing with the next book

    /** The next unfinished book further down the library (newest first), wrapping around. */
    fun refreshUpNext() {
        val book = book
        if (book == null || !settings.continueWithNextBook) {
            upNext = null
            return
        }
        val all = library.sorted
        val i = all.indexOfFirst { it.id == book.id }
        if (i < 0) return
        upNext = (all.drop(i + 1) + all.take(i)).firstOrNull { !it.isFinished }
    }

    /** The open book ended: what the session opens and plays next, if anything. */
    private suspend fun nextBook(): SessionBook? {
        refreshUpNext()
        val next = upNext ?: return null
        // tell the driver what comes next, then keep going
        val german = settings.commandLanguage == VoiceCommand.Language.GERMAN
        engine.announce(if (german) "Als Nächstes: ${next.title}" else "Up next: ${next.title}", VoiceCommand.Language.fromCode(next.language).regionIdentifier)
        // the next book waits for the announcement (also while the speech engine is still starting), but not for ever
        withTimeoutOrNull(ANNOUNCEMENT_LIMIT) { while (engine.isAnnouncing) delay(100) }
        return prepare(next)
    }

    /**
     * Marks a book finished (or unplayed). The open book moves its playhead, or
     * playback would write its old position back.
     */
    fun setFinished(finished: Boolean, book: Book) {
        if (book === this.book) {
            if (finished) engine.pause()
            session.seek(if (finished) engine.duration else 0.0)
        } else {
            book.position = if (finished) book.duration else 0.0
            save("mark finished")
        }
        refreshUpNext()
    }

    // Controls (UI)

    fun togglePlay() = session.togglePlay()
    fun commentButton() = session.commentButton()
    fun discardRecording() = session.discardRecording()
    fun skipBack() = session.skipBack()
    fun repeatSentence() = session.repeatSentence()
    fun addBookmark() = session.addBookmark()
    fun seek(time: Double) = session.seek(time)
    fun setRate(rate: Double) = session.setRate(rate)

    // Listening

    fun setVoiceCommands(enabled: Boolean) {
        settings.voiceCommandsEnabled = enabled
        settingsChanged()
    }

    /** Listens again after the microphone was let go (the status pill). */
    fun startListening() = session.resumeListening()

    /** The microphone choice or a language changed in Settings. */
    fun restartListening() = settingsChanged()

    /** Settings › Echo Cancellation was switched: the microphone takes its other input now. */
    fun echoCancellationChanged() = capture.echoCancellationSettingChanged()

    /** Lets go of the microphone but keeps voice commands on: Play, or a tap on the status pill, listens again. */
    fun pauseListening(why: String) = session.pauseListening(why)

    /** Set by the activity. */
    fun setInForeground(foreground: Boolean) {
        session.isInBackground = !foreground
        if (foreground) {
            capture.cameToForeground()
            // a microphone Android refused in the background can start now
            if (!session.isListeningPaused) session.retryListening()
        }
    }

    // Comments

    /** Transcribes the comment and pins it to the text. Safe to call again (retry). */
    fun postProcess(comment: Comment) {
        scope.launch { processor.process(comment) }
    }

    /** Transcribes every recorded comment of the book again, replacing the transcripts. */
    fun transcribeAgain(book: Book) {
        scope.launch { processor.transcribeAgain(book) }
    }

    // Diagnostics

    /** The session's flight recorder as JSON lines, written for sharing (Settings › Diagnostics). */
    fun writeTrace(directory: File): File {
        directory.mkdirs()
        return File(directory, TRACE_FILE).also { it.writeText(session.trace.jsonLines() + "\n") }
    }

    val traceSize: Int get() = session.trace.events.size

    /**
     * Saves, and tells the user once when the library can't be written:
     * comments made after that would otherwise be lost silently.
     */
    private fun save(what: String) {
        if (library.save()) return
        session.note("Could not save the $what", false)
        if (!reportedSaveFailure) {
            reportedSaveFailure = true
            error = "Klopfgeist could not save your $what. Free some storage, then reopen the book."
        }
    }

    companion object {
        private const val TAG = "ReviewSession"
        const val TRACE_FILE = "session.trace.jsonl"
        /** Longest wait for "Up next: …" before the next book starts anyway, ms. */
        private const val ANNOUNCEMENT_LIMIT = 10_000L
    }
}
