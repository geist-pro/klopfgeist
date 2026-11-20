package de.kaikunze.marginvoice.session

import de.kaikunze.marginvoice.core.AudioPlan
import de.kaikunze.marginvoice.core.AudioRoute
import de.kaikunze.marginvoice.core.Hypothesis
import de.kaikunze.marginvoice.core.ReviewMachine
import de.kaikunze.marginvoice.core.SpokenWord
import de.kaikunze.marginvoice.core.VoiceCommand
import kotlinx.coroutines.CoroutineScope
import java.io.File
import kotlin.math.min

/** A clock the test moves by hand. */
class TestClock {
    var time = 1000.0
}

class FakePlayer : BookPlayer {
    override var isPlaying = false
        private set
    override var liveTime = 0.0
    override var duration = 3600.0
    override var rate = 1.0
        private set
    override var onEvent: ((PlayerEvent) -> Unit)? = null
    /** Everything the session asked for, in order. */
    val calls = ArrayList<String>()
    var refusesToPlay = false

    var failsToLoad = false

    override fun load(file: File, position: Double, rate: Double) {
        if (failsToLoad) throw java.io.IOException("The file is damaged.")
        liveTime = position
        this.rate = rate
        calls.add("load")
    }

    override fun unload() {
        calls.add("unload")
    }

    override fun play() {
        calls.add("play")
        if (refusesToPlay || isPlaying) {
            if (refusesToPlay) onEvent?.invoke(PlayerEvent.PlayingChanged(false))
            return
        }
        isPlaying = true
        onEvent?.invoke(PlayerEvent.PlayingChanged(true))
    }

    override fun pause() {
        calls.add("pause")
        if (!isPlaying) return
        isPlaying = false
        onEvent?.invoke(PlayerEvent.PlayingChanged(false))
    }

    override fun seek(to: Double) {
        liveTime = to
        calls.add("seek $to")
    }

    /** The pause in the audio the next resume finds, if any; the times asked for are kept. */
    var quiet: Double? = null
    val quietAsked = ArrayList<Double>()
    override fun quietPoint(near: Double): Double? {
        quietAsked.add(near)
        return quiet
    }

    override fun setRate(rate: Double) {
        this.rate = rate
    }

    override fun playEarcon(earcon: ReviewMachine.Earcon) {
        calls.add("beep $earcon")
    }

    override fun announce(text: String, language: String) {
        calls.add("say $text")
    }

    /** The book plays for a while. */
    fun advance(seconds: Double) {
        if (isPlaying) liveTime += seconds * rate
    }
}

class FakeCapture : AudioCapture {
    override var route = AudioRoute.builtIn
    override var microphonePermission = MicrophonePermission.GRANTED
    override var onEvent: ((CaptureEvent) -> Unit)? = null
    var otherAudio = false

    /** Every plan applied, with how much had to be rebuilt. */
    val applied = ArrayList<Pair<AudioPlan, AudioPlan.Change>>()
    var failNextApply: String? = null
    /** Every apply fails, as when the system refuses the microphone to an app in the background. */
    var failsToApply: String? = null
    var applyAttempts = 0
        private set
    /** Runs once in the middle of the next apply (something happens while the microphone is being set up). */
    var duringNextApply: (() -> Unit)? = null
    var micTime = 0.0
    var silence = 0.0
    var isRunning = false
    var recording: File? = null
        private set
    var recordedDuration = 4.0
    /** How many `apply` calls were running at once at most: the session must never overlap them. */
    var mostAppliesAtOnce = 0
    private var appliesRunning = 0
    /** Lets a test hold `apply` open (it suspends until completed). */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    class Failure(message: String) : Exception(message)

    override suspend fun requestMicrophonePermission(): Boolean {
        if (microphonePermission == MicrophonePermission.UNDETERMINED) microphonePermission = MicrophonePermission.GRANTED
        return microphonePermission == MicrophonePermission.GRANTED
    }

    override suspend fun apply(plan: AudioPlan, change: AudioPlan.Change, language: VoiceCommand.Language): CaptureStatus {
        appliesRunning++
        mostAppliesAtOnce = maxOf(mostAppliesAtOnce, appliesRunning)
        try {
            gate?.await()
            applyAttempts++
            duringNextApply?.let { during ->
                duringNextApply = null
                during()
                kotlinx.coroutines.yield()
            }
            failsToApply?.let { throw Failure(it) }
            failNextApply?.let { why ->
                failNextApply = null
                throw Failure(why)
            }
            applied.add(plan to change)
            if (change >= AudioPlan.Change.CAPTURE) micTime = 0.0
            isRunning = plan.capture
            return CaptureStatus(
                microphone = if (route.input == AudioRoute.Port.BUILT_IN_MIC) "Built-in microphone" else "AirPods Pro, high quality",
                output = "test", latency = 0.0,
            )
        } finally {
            appliesRunning--
        }
    }

    override fun snapshot() = MicrophoneSnapshot(level = 0f, micTime = micTime, silence = silence, isRunning = isRunning)

    override fun startRecording(to: File) {
        recording = to
    }

    override fun stopRecording(): RecordingResult {
        recording = null
        return RecordingResult(recordedDuration, null)
    }

    override fun isOtherAudioPlaying() = otherAudio

    /** How often the microphone itself was rebuilt. */
    val captureRebuilds: Int get() = applied.count { it.second == AudioPlan.Change.CAPTURE }
    val lastPlan: AudioPlan? get() = applied.lastOrNull()?.first
}

class FakeRemote : RemoteControlling {
    var handler: ((ReviewMachine.RemoteButton) -> Unit)? = null
    var nowPlaying: NowPlaying? = null
    override fun activate(handler: (ReviewMachine.RemoteButton) -> Unit) {
        this.handler = handler
    }

    override fun deactivate() {
        handler = null
    }

    override fun update(info: NowPlaying) {
        nowPlaying = info
    }

    fun press(button: ReviewMachine.RemoteButton) {
        handler?.invoke(button)
    }
}

class FakeBook : ReviewBook {
    class Comment(val time: Double, val duration: Double)
    class Bookmark(val time: Double, var end: Double?)

    override var title = "Test Paper"
    override var audioFile = File("/tmp/test.m4b")
    override var position = 0.0
    override var rate = 1.0
    val comments = ArrayList<Comment>()
    val bookmarks = ArrayList<Bookmark>()
    var discarded = 0
    var savedPosition: Double? = null
    var bookText = ""
    var canSave = true

    override fun chapterTitle(at: Double): String? = null
    override fun recentText(before: Double, seconds: Double): String? = bookText
    var sentenceStarts: Double? = null
    override fun sentenceStart(at: Double): Double? = sentenceStarts
    override fun newRecordingFile(): File? = File("/tmp/comment-${comments.size + discarded}.m4a")
    override fun discardRecording(file: File) {
        discarded += 1
    }

    override fun addComment(at: Double, audio: File, duration: Double): Boolean {
        comments.add(Comment(at, duration))
        return canSave
    }

    override fun addBookmark(at: Double): Boolean {
        bookmarks.add(Bookmark(at, null))
        return canSave
    }

    override fun closeBookmark(at: Double): BookmarkClose {
        if (bookmarks.isEmpty()) return BookmarkClose.MISSING
        bookmarks.last().end = at
        return if (canSave) BookmarkClose.CLOSED else BookmarkClose.NOT_SAVED
    }

    override fun savePosition(time: Double): Boolean {
        savedPosition = time
        return canSave
    }

    override fun saveRate(rate: Double) {
        this.rate = rate
    }
}

/** A session wired to fakes, with helpers that read like what happens to the user. */
class Rig(scope: CoroutineScope, route: AudioRoute = AudioRoute.builtIn, settings: SessionSettings = SessionSettings()) {
    val clock = TestClock()
    val player = FakePlayer()
    val capture = FakeCapture().also { it.route = route }
    val remote = FakeRemote()
    val book = FakeBook()
    val session = ReviewSession(player, capture, remote, settings, scope) { clock.time }

    suspend fun open() {
        session.open(book)
    }

    /**
     * Time passes: the clock, the microphone and (while playing) the book move on,
     * and the session ticks ten times a second.
     */
    suspend fun pass(seconds: Double) {
        var left = seconds
        while (left > 1e-9) {
            val step = min(0.1, left)
            left -= step
            clock.time += step
            if (capture.isRunning) capture.micTime += step
            player.advance(step)
            session.tick()
            session.idle()
        }
    }

    /** The recognizer reports words, each (text, seconds ago it started, seconds ago it ended). */
    fun hear(vararg words: Triple<String, Double, Double>, final: Boolean = false) {
        val mic = capture.micTime
        capture.onEvent?.invoke(
            CaptureEvent.Hypothesis(Hypothesis(words.map { SpokenWord(it.first, mic - it.second, mic - it.third) }, final)),
        )
    }

    /** Someone says one word that ended just now, heard only by the recognizer. */
    fun say(word: String, lasting: Double = 0.3) {
        hear(Triple(word, lasting + 0.05, 0.05))
    }

    val state: String get() = ReviewSession.describe(session.machine.state)
    val logText: String get() = session.log.reversed().joinToString("\n") { it.text }

    companion object {
        val airPods = AudioRoute(outputs = listOf(AudioRoute.Port.BLUETOOTH_A2DP), input = AudioRoute.Port.BLUETOOTH_LE, headsetMicrophoneAvailable = true)
    }
}
