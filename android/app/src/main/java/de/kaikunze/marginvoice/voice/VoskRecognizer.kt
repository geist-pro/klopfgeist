package de.kaikunze.marginvoice.voice

import de.kaikunze.marginvoice.core.AudioPlan
import de.kaikunze.marginvoice.core.CommandGate
import de.kaikunze.marginvoice.core.CommandGatePolicy
import de.kaikunze.marginvoice.core.CommandSpotter
import de.kaikunze.marginvoice.core.CommandWordGate
import de.kaikunze.marginvoice.core.CommandWords
import de.kaikunze.marginvoice.core.Hypothesis
import de.kaikunze.marginvoice.core.RecognizerFrontEnd
import de.kaikunze.marginvoice.core.RecognizerTimeline
import de.kaikunze.marginvoice.core.VoiceCommand
import org.vosk.Model
import org.vosk.Recognizer
import java.util.concurrent.Executors

/**
 * Streams microphone audio into an offline Vosk recognizer that only knows the
 * command phrases (plus "[unk]" for everything else) and reports word-timed results
 * as [Hypothesis] on the microphone timeline.
 *
 * On the way in, [RecognizerFrontEnd] brings the voice to one level, quiet or loud.
 * What else stands between a command word and the session is the [CommandGate] it is
 * handed (decided by [CommandGatePolicy] in `:core`): none while the book is paused,
 * a comment records, or the book is on headphones; while the book plays on the
 * loudspeaker its echo is turned down and a command word is passed on only after
 * [CommandSpotter] found it standing out ([CommandWordGate]).
 *
 * For every command word it reports the evidence (level, gain, contrast, gate) as a
 * line for the command log.
 *
 * `feed` is called on the microphone thread; everything else of a run (building the
 * recognizer, decoding) happens on its own thread, so neither the microphone nor the
 * main thread waits for it. Callbacks arrive on that thread. A run that was stopped
 * calls back no more, whatever was still queued for it.
 */
class VoskRecognizer {
    /** One run of the recognizer, from `start` to `stop`. */
    private class Run(
        val rate: Int,
        val frontEnd: RecognizerFrontEnd,
        val onHypothesis: (Hypothesis) -> Unit,
        val onEvidence: (String, Boolean) -> Unit,
    ) {
        @Volatile var closed = false
        // worker thread only
        var recognizer: Recognizer? = null
        val timeline = RecognizerTimeline(rate)
        val spotter = CommandSpotter(VoiceCommand.Language.entries.toSet())
        var wordGate: CommandWordGate? = null
        var gate: CommandGate? = null
        /** The contrast gate that applied last (verdicts are described by it). */
        var contrast: CommandGate? = null
        var lastPartial = ""
        /** Command words already reported, by quarter second of their start: the gain they were heard with. */
        val reported = LinkedHashMap<Long, Double>()
    }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "vosk").apply { isDaemon = true } }
    @Volatile private var run: Run? = null
    @Volatile private var gate = CommandGatePolicy().gate(AudioPlan.inactive, bookPlaying = false, recording = false)

    /** Asked to run (the recognizer itself is built on the worker a moment later). */
    val isRunning: Boolean get() = run != null

    /** What applies to command words from now on. */
    fun setGate(new: CommandGate) {
        if (new == gate) return
        gate = new
        val r = run ?: return
        worker.execute { if (!r.closed) applyGate(r, new) }
    }

    private fun applyGate(r: Run, g: CommandGate) {
        if (g == r.gate) return
        r.gate = g
        val now = r.timeline.now
        val words = r.wordGate
        if (g.kind == CommandGate.Kind.CONTRAST) {
            r.spotter.minimumContrast = g.minimumContrast
            // the contrast gate is only ever on while the book plays
            r.spotter.bookPlaying = true
            r.contrast = g
            // what was said before the gate came on was said without a book playing
            if (words == null) r.wordGate = CommandWordGate(r.spotter, since = now)
        } else if (words != null) {
            // the gate is gone and with it the reason to withhold: what waited goes on, by name
            r.wordGate = null
            pass(r, words.release(now), g)
        }
        r.frontEnd.gating = g.echoTurnDown
    }

    /** Hands on what the gate produced: verdicts and releases to the command log, results to the session. */
    private fun pass(r: Run, out: CommandWordGate.Output, g: CommandGate) {
        for (spot in out.spots) {
            val gain = r.reported[Math.round(spot.start * 4)] ?: r.frontEnd.gainDb
            // a verdict was reached under the contrast gate, whatever applies by now
            r.onEvidence(CommandWords.evidence(spot.text, spot.level, gain, r.contrast ?: g, spot), spot.accepted)
        }
        for (text in out.released) r.onEvidence(CommandWords.released(text, g), true)
        out.hypotheses.forEach(r.onHypothesis)
    }

    /**
     * Starts a run on `frontEnd`, which the caller keeps across runs on the same
     * microphone (it knows the room and the voice by then). `onFailed` when the
     * recognizer could not be built.
     */
    fun start(
        model: Model, sampleRate: Int, frontEnd: RecognizerFrontEnd,
        onHypothesis: (Hypothesis) -> Unit, onEvidence: (String, Boolean) -> Unit, onFailed: (String) -> Unit,
    ) {
        stop()
        val r = Run(sampleRate, frontEnd, onHypothesis, onEvidence)
        run = r
        worker.execute {
            if (r.closed) return@execute
            try {
                // tens to hundreds of milliseconds: here, not on the main or the microphone thread.
                // Buffers fed meanwhile queue up behind this and are all recognized.
                val recognizer = Recognizer(model, sampleRate.toFloat(), VoskResults.grammar())
                recognizer.setWords(true)
                recognizer.setPartialWords(true)
                r.recognizer = recognizer
                frontEnd.restart()
                applyGate(r, gate)
            } catch (e: Throwable) {
                r.closed = true
                if (run === r) run = null
                onFailed(e.message ?: "Speech recognition failed.")
            }
        }
    }

    fun stop() {
        val r = run ?: return
        run = null
        // from here on nothing of this run is reported, including what is still queued
        r.closed = true
        worker.execute {
            r.recognizer?.close()
            r.recognizer = null
        }
    }

    /** Mono samples, -1...1, that begin at microphone time `micTime`. */
    fun feed(samples: FloatArray, count: Int, micTime: Double) {
        val r = run ?: return
        val copy = samples.copyOf(count)
        worker.execute {
            if (r.closed) return@execute
            val recognizer = r.recognizer ?: return@execute
            // the recognizer's zero is the first buffer it gets, see RecognizerTimeline
            r.timeline.accept(micTime, count)
            // levels are measured on the signal as the microphone delivers it: with echo
            // cancellation on that is the echo-suppressed residual of the book
            r.spotter.audio(copy, count, micTime)
            val now = r.timeline.now
            val pcm = r.frontEnd.process(copy, count)
            if (pcm.isNotEmpty()) {
                if (recognizer.acceptWaveForm(pcm, pcm.size)) {
                    r.lastPartial = ""
                    heard(r, recognizer.result, now)
                } else {
                    val partial = recognizer.partialResult
                    if (partial != r.lastPartial) {
                        r.lastPartial = partial
                        heard(r, partial, now)
                    }
                }
            }
            if (r.closed) return@execute
            r.wordGate?.let { pass(r, it.advance(now), r.gate ?: gate) }
        }
    }

    private fun heard(r: Run, json: String, now: Double) {
        val h = VoskResults.hypothesis(json, r.timeline.origin ?: 0.0) ?: return
        // the gain each command word was heard with, noted when it first appears
        val phrases = CommandWords.phrases(h.words)
        val fresh = phrases.filter { r.reported.putIfAbsent(Math.round(it.start * 4), r.frontEnd.gainDb) == null }
        while (r.reported.size > 64) r.reported.remove(r.reported.keys.first())
        val g = r.gate ?: gate
        val words = r.wordGate
        if (words == null) {
            // no gate: every result goes to the session as it is, with what the signal looked like
            for (p in fresh) {
                // "back" inside "go back" is reported with the phrase
                if (p.count == 1 && phrases.any { it.count == 2 && p.first in it.first until it.first + it.count }) continue
                r.onEvidence(CommandWords.evidence(p.text, r.spotter.levelDb(p.start, p.end), r.frontEnd.gainDb, g), true)
            }
            r.onHypothesis(h)
            return
        }
        pass(r, words.result(h, now), g)
    }
}
