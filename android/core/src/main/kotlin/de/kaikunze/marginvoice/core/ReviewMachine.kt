package de.kaikunze.marginvoice.core

/**
 * The hands-free review loop as a pure state machine. The app feeds it events
 * (voice commands, headset buttons, silence) and executes the returned effects.
 */
class ReviewMachine(var settings: Settings = Settings()) {
    sealed interface State {
        data object Paused : State
        data object Playing : State
        /** Recording a comment anchored at `anchor` (media seconds). */
        data class Recording(val anchor: Double) : State
    }

    enum class RemoteButton { PLAY_PAUSE, PLAY, PAUSE, NEXT, PREVIOUS }

    sealed interface Event {
        data class Command(val command: VoiceCommand) : Event
        data class Remote(val button: RemoteButton) : Event
        /** Silence after speech while recording (end of comment). */
        data object SilenceTimeout : Event
        /**
         * The comment ended with nobody asking to go on (nothing said, the time limit,
         * a call, another app playing): it is finished, and the book stays paused.
         */
        data class CommentAbandoned(val save: Boolean) : Event
        /** Tap on the big on-screen comment button. */
        data object CommentButton : Event
    }

    enum class Earcon {
        RECORD_START, RECORD_SAVED, RECORD_DISCARDED, BOOKMARK,
        /** The second "bookmark" that closes a passage. */
        BOOKMARK_END,
    }

    sealed interface Effect {
        data object Play : Effect
        data object Pause : Effect
        data class Seek(val to: Double) : Effect
        /** Resume near this time, at a sentence start when one is close before it. */
        data class SeekToSentence(val near: Double) : Effect
        data class Beep(val earcon: Earcon) : Effect
        data class StartRecording(val anchor: Double) : Effect
        data class FinishRecording(val save: Boolean) : Effect
        data class AddBookmark(val at: Double) : Effect
        /** Extends the open bookmark to a passage ending here. */
        data class CloseBookmark(val at: Double) : Effect
        data class SetRate(val rate: Double) : Effect
        data object RestartSentence : Effect
        data class Announce(val text: String) : Effect
    }

    data class Settings(
        /** Subtracted from the command time: people say "stop" a moment after the passage. */
        val reactionOffset: Double = 1.5,
        /** After a comment, resume this far before the moment "stop" was said. */
        val resumeRewind: Double = 2.0,
        val backStep: Double = 15.0,
        val rateStep: Double = 0.1,
        val rateRange: ClosedFloatingPointRange<Double> = 0.7..2.5,
        /** Saying "bookmark" again within this many seconds turns the first bookmark into a passage. */
        val bookmarkWindow: Double = 120.0,
        /**
         * Headsets and cars send some button presses twice (or as "toggle" plus "play"):
         * a second transport button within this time is the same press.
         */
        val remoteDebounce: Double = 0.3,
    )

    private data class Stamp(val time: Double, val wall: Double)

    var state: State = State.Paused
        private set
    /** Where the book was when the comment started; playback resumes just before it. */
    private var stoppedAt = 0.0
    /** Time of the last "back" command, to double the step when repeated quickly. */
    private var lastBack: Stamp? = null
    /** The last bookmark while it can still be extended to a passage. */
    private var openBookmark: Stamp? = null
    /** The last headset button, to drop its echo. */
    private var lastRemote: Pair<RemoteButton, Double>? = null
    /**
     * "Pause" arrived during the comment (an earbud taken out): the "play" that
     * follows is the earbud going back in, not the end of the comment.
     */
    private var pausedByRemoteWhileRecording = false

    /** Whether a second "bookmark" would end a passage now. */
    fun isBookmarkOpen(now: Double): Boolean = openBookmark?.let { now - it.wall <= settings.bookmarkWindow } ?: false

    /** Forgets everything about the previous book (open bookmark, repeated "back"). */
    fun reset() {
        state = State.Paused
        stoppedAt = 0.0
        lastBack = null
        openBookmark = null
        lastRemote = null
        pausedByRemoteWhileRecording = false
    }

    fun setPlaying(playing: Boolean) {
        if (state is State.Recording) return
        state = if (playing) State.Playing else State.Paused
    }

    /**
     * @param time current media time.
     * @param rate current playback rate.
     * @param now wall clock seconds (for repeated "back").
     */
    fun handle(event: Event, time: Double, rate: Double, now: Double = 0.0): List<Effect> {
        val s = state
        val command = (event as? Event.Command)?.command
        val button = (event as? Event.Remote)?.button
        if (button != null) {
            val last = lastRemote
            if (last != null && now > last.second && now - last.second < settings.remoteDebounce && isEcho(button, last.first)) {
                return emptyList()
            }
            lastRemote = button to now
            if (s is State.Recording) {
                // AirPods send "pause" when one is taken out of the ear and "play" when it
                // goes back in. Neither is a decision about the comment.
                if (button == RemoteButton.PAUSE) {
                    pausedByRemoteWhileRecording = true
                    return emptyList()
                }
                if (button == RemoteButton.PLAY && pausedByRemoteWhileRecording) {
                    pausedByRemoteWhileRecording = false
                    return emptyList()
                }
            }
        }
        if (s !is State.Recording) pausedByRemoteWhileRecording = false
        // start a comment
        if ((s == State.Playing && (command == VoiceCommand.STOP || button == RemoteButton.NEXT || event == Event.CommentButton)) ||
            (s == State.Paused && (button == RemoteButton.NEXT || event == Event.CommentButton))
        ) {
            val anchor = maxOf(0.0, time - if (s == State.Playing) settings.reactionOffset else 0.0)
            state = State.Recording(anchor)
            stoppedAt = time
            openBookmark = null
            return listOf(Effect.Pause, Effect.Beep(Earcon.RECORD_START), Effect.StartRecording(anchor))
        }
        if (s is State.Recording) {
            if (event is Event.CommentAbandoned) {
                state = State.Paused
                return listOf(
                    Effect.FinishRecording(event.save),
                    Effect.Beep(if (event.save) Earcon.RECORD_SAVED else Earcon.RECORD_DISCARDED),
                    Effect.SeekToSentence(resumePoint),
                )
            }
            // finish a comment
            if (command == VoiceCommand.RESUME || event == Event.SilenceTimeout || button == RemoteButton.PLAY_PAUSE ||
                button == RemoteButton.PLAY || event == Event.CommentButton
            ) {
                state = State.Playing
                return listOf(Effect.FinishRecording(true), Effect.Beep(Earcon.RECORD_SAVED), Effect.SeekToSentence(resumePoint), Effect.Play)
            }
            if (command == VoiceCommand.CANCEL || button == RemoteButton.PREVIOUS) {
                state = State.Playing
                return listOf(Effect.FinishRecording(false), Effect.Beep(Earcon.RECORD_DISCARDED), Effect.SeekToSentence(resumePoint), Effect.Play)
            }
            // "stop", then "mark this": a bookmark, not a comment
            if (command == VoiceCommand.MARK) {
                state = State.Playing
                openBookmark = Stamp(s.anchor, now)
                return listOf(Effect.FinishRecording(false), Effect.AddBookmark(s.anchor), Effect.Beep(Earcon.BOOKMARK), Effect.SeekToSentence(resumePoint), Effect.Play)
            }
            return emptyList() // everything else is part of the comment
        }
        // transport
        if (s == State.Paused && (command == VoiceCommand.RESUME || button == RemoteButton.PLAY || button == RemoteButton.PLAY_PAUSE)) {
            state = State.Playing
            return listOf(Effect.Play)
        }
        if (s == State.Playing && (button == RemoteButton.PAUSE || button == RemoteButton.PLAY_PAUSE)) {
            state = State.Paused
            return listOf(Effect.Pause)
        }
        if (command == VoiceCommand.BACK || button == RemoteButton.PREVIOUS) {
            var step = settings.backStep
            lastBack?.let { if (now - it.wall < 8) step *= 2 }
            lastBack = Stamp(time, now)
            return listOf(Effect.Seek(maxOf(0.0, time - step)))
        }
        if (command == VoiceCommand.AGAIN) return listOf(Effect.RestartSentence)
        if (command == VoiceCommand.MARK) {
            val at = maxOf(0.0, time - settings.reactionOffset)
            val open = openBookmark
            if (open != null && isBookmarkOpen(now) && at > open.time) {
                openBookmark = null
                return listOf(Effect.CloseBookmark(at), Effect.Beep(Earcon.BOOKMARK_END))
            }
            openBookmark = Stamp(at, now)
            return listOf(Effect.AddBookmark(at), Effect.Beep(Earcon.BOOKMARK))
        }
        if (command == VoiceCommand.FASTER || command == VoiceCommand.SLOWER) {
            val sign = if (command == VoiceCommand.FASTER) 1 else -1
            val raw = ((rate + sign * settings.rateStep) * 10).swiftRounded() / 10
            val new = raw.coerceIn(settings.rateRange)
            return if (new == rate) emptyList() else listOf(Effect.SetRate(new), Effect.Announce(fmt("%.1f", new)))
        }
        return emptyList()
    }

    private val resumePoint: Double get() = maxOf(0.0, stoppedAt - settings.resumeRewind)

    /** Whether `button` right after `previous` is the same press reported again. */
    private fun isEcho(button: RemoteButton, previous: RemoteButton): Boolean {
        val transport = setOf(RemoteButton.PLAY, RemoteButton.PAUSE, RemoteButton.PLAY_PAUSE)
        return button == previous || (button in transport && previous in transport)
    }
}
