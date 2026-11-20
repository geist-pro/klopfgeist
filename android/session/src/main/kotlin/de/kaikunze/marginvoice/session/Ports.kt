package de.kaikunze.marginvoice.session

import de.kaikunze.marginvoice.core.AudioCoordinator
import de.kaikunze.marginvoice.core.AudioPlan
import de.kaikunze.marginvoice.core.AudioRoute
import de.kaikunze.marginvoice.core.ReviewMachine
import de.kaikunze.marginvoice.core.VoiceCommand
import java.io.File

// The review session talks to the platform only through these interfaces. The app
// supplies ExoPlayer / AudioRecord / Vosk / MediaSession adapters; the tests supply fakes
// and a clock they move by hand, so every rule of the session runs under `gradlew :session:test`.
// Everything here is called on one thread (the app's main thread).

/** Plays the book and the short sounds around it. */
interface BookPlayer {
    val isPlaying: Boolean
    /** Media time right now. */
    val liveTime: Double
    val duration: Double
    val rate: Double

    /** Throws when the audio can't be opened. */
    fun load(file: File, position: Double, rate: Double)
    fun unload()
    /** Starts playing. Every change of `isPlaying` (this one too) is reported as [PlayerEvent.PlayingChanged]. */
    fun play()
    fun pause()
    fun seek(to: Double)
    fun setRate(rate: Double)
    fun playEarcon(earcon: ReviewMachine.Earcon)
    /** Says a short phrase ("Speed 1.4", "Up next: …"). */
    fun announce(text: String, language: String)
    /**
     * Where to resume near `time` so that playback starts after a pause in the narration
     * (`QuietPoint` over the audio around it); null when the audio has none there or can't be read.
     */
    fun quietPoint(near: Double): Double? = null

    var onEvent: ((PlayerEvent) -> Unit)?
}

sealed interface PlayerEvent {
    data class PlayingChanged(val playing: Boolean) : PlayerEvent
    /** Playback reached the end of the book. */
    data object Finished : PlayerEvent
    /** A phone call or the assistant took the audio. */
    data object InterruptionBegan : PlayerEvent
    data object InterruptionEnded : PlayerEvent
    /** The system's audio services restarted: everything has to be set up again. */
    data object MediaServicesReset : PlayerEvent
}

enum class MicrophonePermission { UNDETERMINED, GRANTED, DENIED }

/** What is known about the running microphone. */
data class CaptureStatus(
    /** "AirPods Pro, high quality" */
    val microphone: String = "",
    /** Where the book is heard: "AirPods Pro", "Speaker". */
    val output: String = "",
    /** Output plus input latency of the route, seconds. */
    val latency: Double = 0.0,
    /** Why commands can't be recognized although the microphone runs. */
    val recognizerError: String? = null,
)

data class MicrophoneSnapshot(
    /** 0…1, for the level meter. */
    val level: Float = 0f,
    /** Seconds of microphone audio since capture (re)started. */
    val micTime: Double = 0.0,
    /** Seconds since the energy detector last heard speech. */
    val silence: Double = 0.0,
    val isRunning: Boolean = false,
)

sealed interface CaptureEvent {
    /** A recognizer result (words on the microphone timeline). */
    data class Hypothesis(val hypothesis: de.kaikunze.marginvoice.core.Hypothesis) : CaptureEvent
    /** The energy detector: speech started or ended at this microphone time. */
    data class Speech(val active: Boolean, val micTime: Double) : CaptureEvent
    /** Capture was rebuilt by the system (format or route change): microphone time is zero again. */
    data object Restarted : CaptureEvent
    data class RouteChanged(val route: AudioRoute, val reason: AudioCoordinator.RouteReason) : CaptureEvent
    /** The microphone or recognizer stopped working. */
    data class Failed(val why: String) : CaptureEvent
}

/** What [AudioCapture.stopRecording] returns. `error` when the recording stopped early. */
data class RecordingResult(val duration: Double, val error: String?)

/** The audio session, the microphone, the command recognizer and the comment recorder. */
interface AudioCapture {
    val route: AudioRoute
    val microphonePermission: MicrophonePermission
    suspend fun requestMicrophonePermission(): Boolean

    /**
     * Brings the audio system to `plan`. `change` says how much differs from what was
     * applied last; the adapter rebuilds no more than that. Throws when it can't.
     */
    suspend fun apply(plan: AudioPlan, change: AudioPlan.Change, language: VoiceCommand.Language): CaptureStatus
    fun snapshot(): MicrophoneSnapshot

    /** Throws when the file can't be written. */
    fun startRecording(to: File)
    /** Closes the file. */
    fun stopRecording(): RecordingResult

    /** Another app is playing (as far as the system tells). */
    fun isOtherAudioPlaying(): Boolean

    var onEvent: ((CaptureEvent) -> Unit)?
}

data class NowPlaying(
    val title: String,
    val chapter: String?,
    val elapsed: Double,
    val duration: Double,
    val rate: Double,
    val isPlaying: Boolean,
)

/** Lock screen, headset and steering-wheel buttons. */
interface RemoteControlling {
    fun activate(handler: (ReviewMachine.RemoteButton) -> Unit)
    fun deactivate()
    fun update(info: NowPlaying)
}

/** What became of the end of a passage. */
enum class BookmarkClose {
    /** The bookmark added last is a passage now. */
    CLOSED,
    /** There is no bookmark to extend any more (it was deleted meanwhile). */
    MISSING,
    /** The library could not be written. */
    NOT_SAVED,
}

/** The open book, as far as the session needs it: where comments go. */
interface ReviewBook {
    val title: String
    val audioFile: File
    val position: Double
    val rate: Double
    fun chapterTitle(at: Double): String?

    /** What the book narrated in the `seconds` before `time`. */
    fun recentText(before: Double, seconds: Double): String?
    /** Start time of the sentence playing at `time`. */
    fun sentenceStart(at: Double): Double?

    /** Where the next comment's audio is written. */
    fun newRecordingFile(): File?
    fun discardRecording(file: File)
    /** Each returns false when the library could not be written. */
    fun addComment(at: Double, audio: File, duration: Double): Boolean
    fun addBookmark(at: Double): Boolean
    /** Extends the bookmark added last to a passage ending at `time`. */
    fun closeBookmark(at: Double): BookmarkClose
    fun savePosition(time: Double): Boolean
    fun saveRate(rate: Double)
}

/** The settings the session reads. A value: the app hands over a new one when the user changes something. */
data class SessionSettings(
    val voiceCommandsEnabled: Boolean = true,
    val commandLanguage: VoiceCommand.Language = VoiceCommand.Language.ENGLISH,
    val useHeadsetMicrophone: Boolean = false,
    val reactionOffset: Double = 1.5,
    val resumeRewind: Double = 2.0,
    val endCommentOnSilence: Boolean = false,
    val silenceTimeout: Double = 6.0,
    /** Minutes a paused book keeps listening; 0 = until it closes. */
    val stopListeningAfter: Int = 5,
)
