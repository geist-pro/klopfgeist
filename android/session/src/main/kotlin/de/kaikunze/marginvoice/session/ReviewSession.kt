package de.kaikunze.marginvoice.session

import de.kaikunze.marginvoice.core.AudioCoordinator
import de.kaikunze.marginvoice.core.AudioNeeds
import de.kaikunze.marginvoice.core.AudioPlan
import de.kaikunze.marginvoice.core.CommandDetector
import de.kaikunze.marginvoice.core.OtherAudioWatch
import de.kaikunze.marginvoice.core.ReviewMachine
import de.kaikunze.marginvoice.core.TraceEvent
import de.kaikunze.marginvoice.core.TraceRecorder
import de.kaikunze.marginvoice.core.TraceReplay
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.core.rawValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * The hands-free review loop for the open book: playback, voice commands, headset
 * buttons and comment recording. The Kotlin twin of `ReviewSession.swift`, rule for rule.
 *
 * Decisions are made by pure types in `:core` (`ReviewMachine`, `CommandDetector`,
 * `AudioCoordinator`); this class feeds them what the ports report and carries out
 * what they ask for. It never sleeps or reads a clock of its own: time is `now()`,
 * and the periodic rules run in `tick()`, which the app calls ten times a second and
 * a test calls when it wants time to pass.
 *
 * Threading: everything runs on the one thread of [scope]'s dispatcher (the app's
 * main thread, a test dispatcher in tests). The dispatcher has to queue what is
 * launched (`Dispatchers.Main`, not `Main.immediate`), as Swift's `Task {}` does.
 *
 * Observation: the state below is plain properties; [onChange] is called after any of
 * them changed. The app copies them into Compose state there.
 */
class ReviewSession(
    val player: BookPlayer,
    private val capture: AudioCapture,
    private val remote: RemoteControlling,
    settings: SessionSettings = SessionSettings(),
    private val scope: CoroutineScope,
    private val now: () -> Double,
) {
    sealed interface ListenState {
        data object Off : ListenState
        data object Starting : ListenState
        data object Listening : ListenState
        data class Unavailable(val why: String) : ListenState
    }

    /** A short confirmation for the status pill ("Comment saved"). */
    data class Flash(val kind: Kind, val at: Double) {
        enum class Kind { COMMENT, BOOKMARK, PASSAGE }
    }

    data class LogEntry(
        val id: Int,
        /** Session clock. */
        val at: Double,
        val text: String,
        val accepted: Boolean,
    )

    // MARK: Observable state

    /** Called (on the session's thread) after observable state changed. */
    var onChange: (() -> Unit)? = null

    private fun <T> observed(initial: T) = object : ReadWriteProperty<Any?, T> {
        private var value = initial
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            if (this.value == value) return
            this.value = value
            onChange?.invoke()
        }
    }

    val machine = ReviewMachine()
    var book: ReviewBook? by observed(null)
        private set
    var listenState: ListenState by observed(ListenState.Off)
        private set
    /** The microphone listening right now ("Pixel Buds, headset microphone"). */
    var microphone: String by observed("Not listening")
        private set
    var micLevel: Float by observed(0f)
        private set
    /** Session clock time the current recording began. */
    var recordingStarted: Double? by observed(null)
        private set
    var flash: Flash? by observed(null)
        private set
    /** Newest first. */
    var log: List<LogEntry> by observed(emptyList())
        private set
    /** A bookmark was just set and saying "bookmark" again would end a passage there. */
    var isBookmarkOpen: Boolean by observed(false)
        private set
    /**
     * Voice commands are on, but the microphone was let go because nobody used the
     * paused book for a while; Play or the status pill turns it back on.
     */
    var isListeningPaused: Boolean by observed(false)
        private set
    var errorMessage: String? by observed(null)

    var settings: SessionSettings = settings
        set(value) {
            val oldValue = field
            field = value
            if (value == oldValue || book == null) return
            if (value.commandLanguage != oldValue.commandLanguage) languageChanged = true
            if (value.voiceCommandsEnabled && !oldValue.voiceCommandsEnabled) {
                isListeningPaused = false
                // turned on with the microphone never asked for: ask now, or listening silently stays off
                permission = capture.microphonePermission
                if (permission == MicrophonePermission.UNDETERMINED) {
                    askForMicrophone()
                    return
                }
            }
            replan()
        }

    /** The app is in the background: a paused book lets go of the microphone sooner. */
    var isInBackground = false
        set(value) {
            val was = field
            field = value
            backgroundSince = if (value) now() else null
            // a microphone that could not start while the app was away is tried again at once
            if (!value && was) lastRepair = Double.NEGATIVE_INFINITY
        }

    /** The book finished. Return the next book to open and play, or null. */
    var onBookFinished: (suspend () -> ReviewBook?)? = null

    val isRecording: Boolean get() = machine.state is ReviewMachine.State.Recording
    val isPlaying: Boolean get() = player.isPlaying

    // MARK: Pure parts

    private val detector = CommandDetector()
    private val coordinator = AudioCoordinator()
    private val otherAudio = OtherAudioWatch()
    val trace = TraceRecorder()

    // MARK: Bookkeeping

    private class ClockSample(val mic: Double, val book: Double, val playing: Boolean)

    private var plan = AudioPlan.inactive
    private var status = CaptureStatus()
    private var hasPlayed = false
    private var permission = MicrophonePermission.UNDETERMINED
    private var languageChanged = false
    /** The audio setup is being brought to the latest plan. */
    private var pump: Job? = null
    private var dirty = false
    private var rebuildForced = false
    private var lastRepair = Double.NEGATIVE_INFINITY
    /** Recent (microphone time, book time, playing) samples to map an utterance to a book position. */
    private val clock = ArrayList<ClockSample>()
    private var lastMicProgressMic = 0.0
    private var lastMicProgressAt = 0.0
    private var detectorMode = ""

    private var recordingFile: File? = null
    private var recordingAnchor = 0.0
    private var recordingTask: Job? = null
    /** Microphone time the recording began: the end of "stop" heard after it is not the comment. */
    private var recordingMicStart = Double.POSITIVE_INFINITY
    private var heardSpeechWhileRecording = false
    /** Microphone time anything was last heard, by the energy detector or the recognizer. */
    private var lastSpeechEvidence = 0.0
    private var otherAudioAtRecordingStart = false

    private var lastPositionSave = Double.NEGATIVE_INFINITY
    private var lastOtherAudioLook = Double.NEGATIVE_INFINITY
    /** When the book last stopped playing or was last used while paused; null while it plays. */
    private var pausedSince: Double? = null
    private var backgroundSince: Double? = null
    private var reportedSaveFailure = false
    private var nextLogID = 0

    init {
        player.onEvent = { playerEvent(it) }
        capture.onEvent = { captureEvent(it) }
    }

    // MARK: Opening and closing

    suspend fun open(book: ReviewBook) {
        if (this.book === book) return
        close(releasingAudio = false)
        try {
            player.load(book.audioFile, book.position, book.rate)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = "Could not open the audiobook: ${describe(e)}"
            // the book before it is closed and nothing took its place: let go of the audio
            replan()
            return
        }
        this.book = book
        hasPlayed = false
        pausedSince = now()
        otherAudio.reset()
        permission = capture.microphonePermission
        remote.activate { remoteButton(it) }
        updateNowPlaying()
        if (settings.voiceCommandsEnabled && permission == MicrophonePermission.UNDETERMINED) {
            permission = if (capture.requestMicrophonePermission()) MicrophonePermission.GRANTED else MicrophonePermission.DENIED
            if (this.book !== book) return
        }
        coordinator.routeChanged(capture.route, AudioCoordinator.RouteReason.OTHER, now() - coordinator.policy.settle)
        replan()
        idle()
    }

    /** Closes the book. Switching to another book keeps the audio session. */
    fun close(releasingAudio: Boolean = true) {
        if (book == null) return
        if (isRecording) dispatch(ReviewMachine.Event.CommentAbandoned(save = true))
        player.pause()
        persistPosition(force = true)
        machine.reset()
        player.unload()
        remote.deactivate()
        book = null
        isBookmarkOpen = false
        isListeningPaused = false
        hasPlayed = false
        detector.reset()
        clock.clear()
        onChange?.invoke()
        if (releasingAudio) replan()
    }

    /**
     * Waits until the audio setup has caught up with the latest plan (and a
     * recording that was starting has started).
     */
    suspend fun idle() {
        while (pump != null || recordingTask != null || permissionTask != null) {
            permissionTask?.join()
            pump?.join()
            recordingTask?.join()
        }
    }

    private var permissionTask: Job? = null

    private fun askForMicrophone() {
        if (permissionTask != null) return
        permissionTask = scope.launch {
            permission = if (capture.requestMicrophonePermission()) MicrophonePermission.GRANTED else MicrophonePermission.DENIED
            permissionTask = null
            replan()
        }
    }

    // MARK: Controls (UI)

    fun togglePlay() = dispatch(ReviewMachine.Event.Remote(ReviewMachine.RemoteButton.PLAY_PAUSE), debounced = false)
    fun commentButton() = dispatch(ReviewMachine.Event.CommentButton)
    fun discardRecording() = dispatch(ReviewMachine.Event.Command(VoiceCommand.CANCEL))
    fun skipBack() = dispatch(ReviewMachine.Event.Command(VoiceCommand.BACK))
    fun repeatSentence() = dispatch(ReviewMachine.Event.Command(VoiceCommand.AGAIN))
    fun addBookmark() = dispatch(ReviewMachine.Event.Command(VoiceCommand.MARK))

    fun seek(to: Double) {
        touch()
        player.seek(to)
        persistPosition(force = true)
        updateNowPlaying()
    }

    fun setRate(rate: Double) {
        player.setRate(rate)
        book?.saveRate(rate)
        updateNowPlaying()
    }

    /** Listens again after `isListeningPaused` (the status pill, or Play). */
    fun resumeListening() {
        if (!isListeningPaused) return
        isListeningPaused = false
        touch()
        replan()
    }

    /**
     * Tries the microphone again now ("Try Again" on the status pill) instead of
     * waiting for the next automatic attempt.
     */
    fun retryListening() {
        if (isListeningPaused) return resumeListening()
        if (book == null || listenState !is ListenState.Unavailable || pump != null) return
        // the answer may have changed in the system's settings
        permission = capture.microphonePermission
        if (permission != MicrophonePermission.GRANTED) return
        lastRepair = now()
        listenState = ListenState.Starting
        forceRebuild()
    }

    /** Lets go of the microphone but keeps voice commands on. */
    fun pauseListening(why: String) {
        if (book == null || !settings.voiceCommandsEnabled || isListeningPaused) return
        isListeningPaused = true
        note("Stopped listening: $why", accepted = true)
        replan()
    }

    // MARK: Audio setup

    private val needs: AudioNeeds
        get() = AudioNeeds(
            bookOpen = book != null,
            hasPlayed = hasPlayed,
            voiceCommands = settings.voiceCommandsEnabled,
            listeningPaused = isListeningPaused,
            recording = isRecording,
            microphonePermission = permission == MicrophonePermission.GRANTED,
            useHeadsetMicrophone = settings.useHeadsetMicrophone,
        )

    /** Something the plan depends on changed. */
    private fun replan() {
        schedule(coordinator.needsChanged(needs, now()))
    }

    private fun schedule(action: AudioCoordinator.Action?) {
        if (action == null && !languageChanged) return
        runPump()
    }

    private fun runPump() {
        dirty = true
        if (pump != null) return
        startPump {
            while (dirty) {
                dirty = false
                applyLatest()
            }
        }
    }

    /**
     * Runs `block` as the one pump. Created lazily and started after `pump` is set,
     * so the job can clear the field even when the dispatcher runs it at once.
     */
    private fun startPump(block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                pump = null
            }
        }
        pump = job
        job.start()
    }

    /**
     * One pass: whatever the coordinator wants now, applied. Requests that arrive
     * during the pass are folded into the next one, never dropped and never run in parallel.
     */
    private suspend fun applyLatest() {
        var action = coordinator.needsChanged(needs, now())
        if (action == null && languageChanged) {
            // same plan, another language: only the recognizer restarts
            action = AudioCoordinator.Action(coordinator.applied, AudioPlan.Change.RECOGNIZER)
        }
        if (rebuildForced) {
            // what is running is broken or dead: rebuild it even though the plan is the same
            rebuildForced = false
            val plan = action?.plan ?: AudioPlan.make(needs, capture.route, coordinator.echoCancellationAllowed)
            action = AudioCoordinator.Action(plan, AudioPlan.Change.CAPTURE)
        }
        if (action == null) return
        languageChanged = false
        val wanted = action.plan
        if (wanted.recognize && listenState != ListenState.Listening) listenState = ListenState.Starting
        trace.record(TraceEvent.Kind.Plan(wanted, describe(action.change)), now())
        try {
            val status = capture.apply(wanted, action.change, settings.commandLanguage)
            coordinator.didApply(wanted, now())
            plan = wanted
            this.status = status
            if (action.change >= AudioPlan.Change.CAPTURE) microphoneRestarted()
            if (wanted.capture) {
                microphone = status.microphone
                if (action.change >= AudioPlan.Change.CAPTURE) {
                    note("Microphone: ${status.microphone}, book on ${status.output}${if (wanted.echoCancellation) ", echo cancellation on" else ""}", accepted = true)
                }
            } else {
                microphone = "Not listening"
            }
            val problem = status.recognizerError
            listenState = if (problem != null && wanted.recognize) {
                note("Speech recognition failed: $problem", accepted = false)
                ListenState.Unavailable(problem)
            } else if (!wanted.capture && permission == MicrophonePermission.DENIED && settings.voiceCommandsEnabled && !isListeningPaused && book != null) {
                ListenState.Unavailable("Microphone access is off. Turn it on in Settings › Klopfgeist.")
            } else {
                if (wanted.recognize) ListenState.Listening else ListenState.Off
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // nothing was applied: the coordinator still holds the old plan and asks again
            listenState = if (wanted.capture) ListenState.Unavailable(describe(e)) else ListenState.Off
            note("Microphone failed: ${describe(e)}", accepted = false)
            lastRepair = now()
        }
    }

    private fun microphoneRestarted() {
        detector.reset()
        clock.clear()
        lastSpeechEvidence = 0.0
        lastMicProgressMic = 0.0
        lastMicProgressAt = now()
        if (recordingFile != null) recordingMicStart = 0.0
        trace.record(TraceEvent.Kind.MicrophoneRestarted, now())
    }

    private fun wantsMicrophone(): Boolean = plan.capture || needs.voiceCommands && !needs.listeningPaused

    /** Brings the microphone back when it failed to start or stopped delivering audio. */
    private fun watchMicrophone(snap: MicrophoneSnapshot) {
        if (book == null || !wantsMicrophone() || pump != null) return
        val t = now()
        if (listenState is ListenState.Unavailable) {
            // a failed start (another app held the microphone) is tried again now and then
            // the system does not start a microphone for an app in the background: wait for it to return
            if (permission != MicrophonePermission.GRANTED || isInBackground || t - lastRepair <= 15) return
            lastRepair = t
            note("Microphone unavailable: trying again", accepted = false)
            forceRebuild()
            return
        }
        if (!plan.capture) return
        if (snap.micTime != lastMicProgressMic) {
            lastMicProgressMic = snap.micTime
            lastMicProgressAt = t
        }
        val stalled = snap.isRunning && t - lastMicProgressAt > 3
        if (!(!snap.isRunning || stalled) || t - lastRepair <= 5) return
        lastRepair = t
        note(if (stalled) "Microphone stalled: restarting" else "Microphone missing: restarting", accepted = false)
        forceRebuild()
    }

    /**
     * Rebuilds the microphone although the plan has not changed. Goes through the
     * same pump as every other change, so a request made while one is being applied
     * runs right after it.
     */
    private fun forceRebuild() {
        rebuildForced = true
        runPump()
    }

    // MARK: Events from the ports

    private fun playerEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.PlayingChanged -> {
                val playing = event.playing
                pausedSince = if (playing) null else now()
                if (playing) {
                    if (!hasPlayed) {
                        hasPlayed = true
                        replan()
                    }
                    if (isListeningPaused) resumeListening()
                } else {
                    otherAudio.ownSound(now())
                }
                machine.setPlaying(playing)
                persistPosition(force = !playing)
                updateNowPlaying()
                onChange?.invoke()
            }
            PlayerEvent.Finished -> scope.launch { bookFinished() }
            PlayerEvent.InterruptionBegan -> {
                // a phone call while recording: keep what was said
                if (!isRecording) return
                note("Audio interrupted: comment saved", accepted = true)
                dispatch(ReviewMachine.Event.CommentAbandoned(save = true))
            }
            PlayerEvent.InterruptionEnded -> {
                if (book == null || !wantsMicrophone()) return
                forceRebuild()
            }
            PlayerEvent.MediaServicesReset -> {
                if (book == null) return
                forceRebuild()
            }
        }
    }

    private fun captureEvent(event: CaptureEvent) {
        when (event) {
            is CaptureEvent.Hypothesis -> {
                val h = event.hypothesis
                val mic = capture.snapshot().micTime
                trace.record(TraceEvent.Kind.Hypothesis(h, mic), now())
                h.words.maxOfOrNull { it.end }?.let { end ->
                    lastSpeechEvidence = max(lastSpeechEvidence, end)
                    // the recognizer hears speech the energy detector can miss (a quiet headset microphone)
                    if (isRecording && end > recordingMicStart) heardSpeechWhileRecording = true
                }
                handle(detector.hypothesis(h, mic, detectorContext()))
            }
            is CaptureEvent.Speech -> {
                trace.record(TraceEvent.Kind.Speech(event.active, event.micTime), now())
                detector.speech(event.active, event.micTime)
                lastSpeechEvidence = max(lastSpeechEvidence, event.micTime)
                if (event.active) {
                    // a sample right at speech start, so a "stop" just after a restart has one too
                    sampleClock(event.micTime)
                    if (isRecording && event.micTime >= recordingMicStart) heardSpeechWhileRecording = true
                }
            }
            CaptureEvent.Restarted -> microphoneRestarted()
            is CaptureEvent.RouteChanged -> {
                if (book == null) return
                trace.record(TraceEvent.Kind.Route(event.route, describe(event.reason)), now())
                if (event.reason != AudioCoordinator.RouteReason.OTHER) {
                    note("Audio device ${if (event.reason == AudioCoordinator.RouteReason.DEVICE_ARRIVED) "connected" else "disconnected"}", accepted = true)
                }
                schedule(coordinator.routeChanged(event.route, event.reason, now()))
            }
            is CaptureEvent.Failed -> {
                note("Microphone failed: ${event.why}", accepted = false)
                lastRepair = now() - 10
                listenState = ListenState.Unavailable(event.why)
            }
        }
    }

    private fun remoteButton(button: ReviewMachine.RemoteButton) {
        trace.record(TraceEvent.Kind.Remote(button), now())
        dispatch(ReviewMachine.Event.Remote(button))
    }

    // MARK: Commands

    private fun detectorContext(): CommandDetector.Context {
        val mode: String
        val context: CommandDetector.Context
        if (isRecording) {
            mode = "recording"
            context = CommandDetector.Context(CommandDetector.Policy.recording, CommandDetector.Context.recordingCommands)
        } else if (plan.bookAudible && player.isPlaying) {
            mode = "listeningOverSpeaker"
            context = CommandDetector.Context(
                CommandDetector.Policy.listeningOverSpeaker, recentBookText = book?.recentText(player.liveTime + 0.5, 5.0),
            )
        } else {
            mode = "listening"
            context = CommandDetector.Context(CommandDetector.Policy.listening)
        }
        if (mode != detectorMode) {
            detectorMode = mode
            trace.record(TraceEvent.Kind.Mode(mode), now())
        }
        return context
    }

    private fun handle(outcomes: List<CommandDetector.Outcome>) {
        for (outcome in outcomes) {
            trace.record(TraceEvent.Kind.Outcome(TraceReplay.describe(outcome)), now())
            when (outcome) {
                is CommandDetector.Outcome.Rejected -> note("Ignored “${outcome.text}” (${describe(outcome.rejection)})", accepted = false)
                is CommandDetector.Outcome.Fire -> {
                    val c = outcome.candidate
                    // the other app's speech may be what was heard, and starting the book would cut it off
                    if (otherAudio.isPlaying && !player.isPlaying) {
                        if (isRecording && (c.command == VoiceCommand.RESUME || c.command == VoiceCommand.CANCEL)) {
                            note("“${c.text}” ends the comment; another app is playing, so the book stays paused", accepted = true)
                            dispatch(ReviewMachine.Event.CommentAbandoned(save = c.command == VoiceCommand.RESUME))
                        } else {
                            note("Ignored “${c.text}” (another app is playing)", accepted = false)
                        }
                        continue
                    }
                    val bookTime = bookTime(atMic = c.start)
                    note("“${c.text}” → ${c.command.rawValue} at ${clockText(bookTime)}", accepted = true)
                    dispatch(
                        ReviewMachine.Event.Command(c.command),
                        time = if (c.command == VoiceCommand.STOP || c.command == VoiceCommand.MARK) bookTime else null,
                    )
                }
            }
        }
    }

    private fun sampleClock(mic: Double) {
        // what the listener heard when this microphone audio was spoken
        val heard = if (player.isPlaying) max(0.0, player.liveTime - status.latency * player.rate) else player.liveTime
        clock.add(ClockSample(mic, heard, player.isPlaying))
        if (clock.size > 200) clock.subList(0, clock.size - 200).clear()
    }

    /** Book position at a moment on the microphone timeline. */
    private fun bookTime(atMic: Double): Double {
        val sample = clock.lastOrNull { it.mic <= atMic + 0.01 } ?: clock.firstOrNull() ?: return player.liveTime
        return if (sample.playing) sample.book + (atMic - sample.mic) * player.rate else sample.book
    }

    // MARK: State machine

    private fun dispatch(event: ReviewMachine.Event, time: Double? = null, debounced: Boolean = true) {
        if (book == null) return
        touch()
        machine.settings = machine.settings.copy(
            reactionOffset = settings.reactionOffset,
            resumeRewind = settings.resumeRewind,
            // the on-screen button is not a headset: two quick taps are two taps
            remoteDebounce = if (debounced) ReviewMachine.Settings().remoteDebounce else 0.0,
        )
        val effects = machine.handle(event, time ?: player.liveTime, player.rate, now())
        trace.record(TraceEvent.Kind.Machine(event.toString(), describe(machine.state), effects.map { it.toString() }), now())
        for (effect in effects) perform(effect)
        onChange?.invoke()
    }

    private fun perform(effect: ReviewMachine.Effect) {
        when (effect) {
            ReviewMachine.Effect.Play -> player.play()
            ReviewMachine.Effect.Pause -> player.pause()
            is ReviewMachine.Effect.Seek -> seek(effect.to)
            is ReviewMachine.Effect.SeekToSentence -> {
                // Start a sentence cleanly when one begins just before the resume point. Sentence times are
                // estimates, so the audio has the last word: resume after the pause nearest to that point.
                val t = effect.near
                val start = book?.sentenceStart(t) ?: t
                val atSentence = t - start <= 1.5 && t >= start
                val quiet = player.quietPoint(if (atSentence) start else t)
                seek(max(0.0, quiet ?: if (atSentence) start - 0.1 else t))
            }
            is ReviewMachine.Effect.Beep -> {
                otherAudio.ownSound(now())
                player.playEarcon(effect.earcon)
            }
            is ReviewMachine.Effect.StartRecording -> {
                // the machine leaves `Recording` before `FinishRecording` runs, so keep the anchor here
                recordingAnchor = effect.anchor
                beginRecording()
            }
            is ReviewMachine.Effect.FinishRecording -> endRecording(effect.save)
            is ReviewMachine.Effect.AddBookmark -> {
                val book = book ?: return
                saved(book.addBookmark(effect.at), "bookmark")
                flash = Flash(Flash.Kind.BOOKMARK, now())
                note("Bookmark at ${clockText(effect.at)}", accepted = true)
            }
            is ReviewMachine.Effect.CloseBookmark -> {
                val book = book ?: return
                when (book.closeBookmark(effect.at)) {
                    BookmarkClose.CLOSED -> {
                        flash = Flash(Flash.Kind.PASSAGE, now())
                        note("Bookmarked passage up to ${clockText(effect.at)}", accepted = true)
                    }
                    // the bookmark was deleted since it was set: nothing became a passage, so nothing is confirmed
                    BookmarkClose.MISSING -> note("No bookmark to extend", accepted = false)
                    BookmarkClose.NOT_SAVED -> saved(false, "passage end")
                }
            }
            is ReviewMachine.Effect.SetRate -> setRate(effect.rate)
            ReviewMachine.Effect.RestartSentence -> {
                val t = player.liveTime
                seek(max(0.0, (book?.sentenceStart(t) ?: (t - 5)) - 0.2))
            }
            is ReviewMachine.Effect.Announce -> {
                val german = settings.commandLanguage == VoiceCommand.Language.GERMAN
                player.announce(if (german) "Tempo ${effect.text}" else "Speed ${effect.text}", if (german) "de-DE" else "en-US")
            }
        }
    }

    // MARK: Recording

    private fun beginRecording() {
        val book = book ?: return
        recordingStarted = now()
        heardSpeechWhileRecording = false
        otherAudioAtRecordingStart = otherAudio.isPlaying
        // commands spoken before the comment are not its end
        detector.forget(capture.snapshot().micTime)
        detectorContext()
        recordingTask?.cancel()
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (permission == MicrophonePermission.UNDETERMINED) {
                    permission = if (capture.requestMicrophonePermission()) MicrophonePermission.GRANTED else MicrophonePermission.DENIED
                }
                replan()
                pump?.join()
                // "continue" or "cancel" while the microphone was starting: nothing to record
                if (!isRecording || recordingFile != null || this@ReviewSession.book !== book || !job.isActive) return@launch
                val file = if (plan.capture) book.newRecordingFile() else null
                if (file == null) {
                    errorMessage = "Recording needs microphone access."
                    dispatch(ReviewMachine.Event.CommentAbandoned(save = false))
                    return@launch
                }
                try {
                    capture.startRecording(file)
                    recordingFile = file
                    recordingMicStart = capture.snapshot().micTime
                    lastSpeechEvidence = max(lastSpeechEvidence, recordingMicStart)
                } catch (e: Exception) {
                    errorMessage = "Could not record: ${describe(e)}"
                    dispatch(ReviewMachine.Event.CommentAbandoned(save = false))
                }
            } finally {
                // Swift clears the field unconditionally; a task that was replaced must not
                // clear its successor, or `idle()` would return while that one still starts.
                if (recordingTask === job) recordingTask = null
            }
        }
        recordingTask = job
        job.start()
    }

    private fun endRecording(save: Boolean) {
        recordingStarted = null
        recordingMicStart = Double.POSITIVE_INFINITY
        detectorContext()
        try {
            val file = recordingFile ?: return
            val book = book ?: return
            recordingFile = null
            val (duration, error) = capture.stopRecording()
            if (error != null) {
                note("Recording stopped early: $error", accepted = false)
                errorMessage = "Only the first ${duration.toInt()} s of the comment could be recorded: $error"
            }
            if (!save || duration <= 0.3) {
                book.discardRecording(file)
                return
            }
            saved(book.addComment(recordingAnchor, file, duration), "comment")
            flash = Flash(Flash.Kind.COMMENT, now())
            note("Saved comment at ${clockText(recordingAnchor)}", accepted = true)
        } finally {
            replan()
        }
    }

    // MARK: Time

    /** The periodic rules. The app calls this ten times a second. */
    fun tick() {
        if (book == null) return
        val t = now()
        val snap = capture.snapshot()
        micLevel = snap.level
        if (snap.isRunning) sampleClock(snap.micTime)
        watchMicrophone(snap)
        schedule(coordinator.tick(capture.route, t))
        if (plan.recognize || isRecording) {
            handle(detector.tick(snap.micTime, detectorContext()))
        }
        val started = recordingStarted
        if (started != null && isRecording && recordingFile != null) checkRecording(t - started, snap)
        if (player.isPlaying) persistPosition()
        lookForOtherAudio(t)
        checkIdleListening(t)
        val open = machine.isBookmarkOpen(t)
        if (open != isBookmarkOpen) isBookmarkOpen = open
    }

    private fun checkRecording(elapsed: Double, snap: MicrophoneSnapshot) {
        // quiet since anything was last heard, by the energy detector or the recognizer:
        // on a headset the energy detector alone can sit through a whole sentence
        val silence = min(snap.silence, max(0.0, snap.micTime - lastSpeechEvidence))
        if (otherAudio.isPlaying && !otherAudioAtRecordingStart) {
            // what it records from here on is the other app
            note("Another app started playing: comment ${if (heardSpeechWhileRecording) "saved" else "discarded"}, the book stays paused", accepted = true)
            dispatch(ReviewMachine.Event.CommentAbandoned(save = heardSpeechWhileRecording))
        } else if (settings.endCommentOnSilence && heardSpeechWhileRecording && silence >= settings.silenceTimeout && elapsed > 1) {
            note("Comment ended after ${settings.silenceTimeout.toInt()} s of silence", accepted = true)
            // after a long comment the listener may have moved on: only a short one plays on by itself
            val playOn = elapsed <= LONGEST_COMMENT_THAT_PLAYS_ON && !otherAudio.isPlaying
            dispatch(if (playOn) ReviewMachine.Event.SilenceTimeout else ReviewMachine.Event.CommentAbandoned(save = true))
        } else if (!heardSpeechWhileRecording && elapsed > NOTHING_SAID_LIMIT) {
            note("Nothing said, comment discarded, the book stays paused", accepted = false)
            dispatch(ReviewMachine.Event.CommentAbandoned(save = false))
        } else if (elapsed > LONGEST_COMMENT) {
            note("Comment ended after 5 minutes, the book stays paused", accepted = true)
            dispatch(ReviewMachine.Event.CommentAbandoned(save = true))
        }
    }

    private fun lookForOtherAudio(t: Double) {
        if (player.isPlaying) {
            otherAudio.reset()
        } else if (t - lastOtherAudioLook >= 0.5) {
            lastOtherAudioLook = t
            otherAudio.update(capture.isOtherAudioPlaying(), t)
        }
    }

    /**
     * A paused book stops listening after the time set in Settings, and after a
     * minute at most once the app is in the background: the microphone (and its
     * indicator) should not stay on for a book nobody is using.
     */
    private fun checkIdleListening(t: Double) {
        var since = pausedSince
        if (settings.stopListeningAfter <= 0 || isListeningPaused || isRecording || player.isPlaying || since == null ||
            !(listenState == ListenState.Listening || listenState == ListenState.Starting)
        ) return
        if (otherAudio.isPlaying) {
            pauseListening("another app is playing")
            return
        }
        var limit = settings.stopListeningAfter * 60.0
        val background = backgroundSince
        if (background != null) {
            limit = min(limit, 60.0)
            since = max(since, background)
        }
        if (t - since < limit) return
        pauseListening(if (background != null) "paused in the background" else "paused for ${settings.stopListeningAfter} min")
    }

    /** The paused book was used: the idle countdown starts over. */
    private fun touch() {
        if (pausedSince != null) pausedSince = now()
    }

    private suspend fun bookFinished() {
        val book = book ?: return
        if (isRecording) return
        saved(book.savePosition(player.duration), "position")
        note("Finished “${book.title}”", accepted = true)
        val next = onBookFinished?.invoke()
        if (next == null) {
            // nothing more to hear: nothing to say either
            pauseListening("the book ended")
            return
        }
        open(next)
        if (this.book !== next || player.isPlaying) return
        dispatch(ReviewMachine.Event.Remote(ReviewMachine.RemoteButton.PLAY), debounced = false)
    }

    // MARK: Persistence and feedback

    private fun persistPosition(force: Boolean = false) {
        val book = book ?: return
        if (!force && now() - lastPositionSave <= 5) return
        lastPositionSave = now()
        saved(book.savePosition(player.liveTime), "position")
        updateNowPlaying()
    }

    private fun updateNowPlaying() {
        val book = book ?: return
        val t = player.liveTime
        remote.update(NowPlaying(book.title, book.chapterTitle(t), t, player.duration, player.rate, player.isPlaying))
    }

    /**
     * Tells the user once when the library can't be written: comments made after
     * that would otherwise be lost silently.
     */
    private fun saved(ok: Boolean, what: String) {
        if (ok) return
        note("Could not save the $what", accepted = false)
        if (!reportedSaveFailure) {
            reportedSaveFailure = true
            errorMessage = "Klopfgeist could not save your $what. Free some storage, then reopen the book."
        }
    }

    /**
     * Adds a line to the command log (the app reports its own steps here too, like
     * "Transcribed 3 comments again"; on Android also the capture adapter's evidence
     * for each command word).
     */
    fun note(text: String, accepted: Boolean) {
        nextLogID += 1
        log = (listOf(LogEntry(nextLogID, now(), text, accepted)) + log).take(100)
        trace.record(TraceEvent.Kind.Note(text), now())
    }

    companion object {
        /** A comment ended by silence after longer than this leaves the book paused. */
        const val LONGEST_COMMENT_THAT_PLAYS_ON = 120.0
        const val NOTHING_SAID_LIMIT = 20.0
        const val LONGEST_COMMENT = 300.0

        fun describe(why: CommandDetector.Rejection): String = when (why) {
            CommandDetector.Rejection.NOT_ISOLATED -> "not said on its own"
            CommandDetector.Rejection.SPEECH_CONTINUED -> "more was said"
            CommandDetector.Rejection.BOOK_ECHO -> "the book just said it"
            CommandDetector.Rejection.DUPLICATE -> "already done"
            CommandDetector.Rejection.TOO_LONG -> "drawn out"
        }

        fun describe(state: ReviewMachine.State): String = when (state) {
            ReviewMachine.State.Paused -> "paused"
            ReviewMachine.State.Playing -> "playing"
            is ReviewMachine.State.Recording -> "recording"
        }

        /** As Swift prints the case: "capture", "deviceArrived". */
        fun describe(change: AudioPlan.Change): String = change.name.lowercase()

        fun describe(reason: AudioCoordinator.RouteReason): String = when (reason) {
            AudioCoordinator.RouteReason.DEVICE_ARRIVED -> "deviceArrived"
            AudioCoordinator.RouteReason.DEVICE_LEFT -> "deviceLeft"
            AudioCoordinator.RouteReason.OTHER -> "other"
        }

        private fun describe(e: Exception): String = e.localizedMessage ?: e.toString()

        fun clockText(seconds: Double): String {
            val s = max(0.0, seconds).toInt()
            return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
        }
    }
}
