package de.kaikunze.marginvoice.core

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Decides which command words from a grammar recognizer (Vosk) were meant as commands.
 * A command word stands out: it is clearly louder than what comes just before and just
 * after it. That holds for a normal voice over the book's echo, and a quiet voice in a
 * quiet room; it fails for the same word inside a sentence, or spoken by the book, which
 * are as loud as their surroundings.
 *
 * All calls on one thread. Times are seconds on the microphone timeline. The Apple
 * apps use [CommandDetector] instead: their dictation recognizer returns whole sentences
 * and their echo cancellation leaves far less book in the microphone.
 */
class CommandSpotter(
    private val languages: Set<VoiceCommand.Language>,
    /** The word has to be this much louder than its surroundings, dB (see [CommandGatePolicy.minimumContrast]). */
    @Volatile var minimumContrast: Double = MINIMUM_CONTRAST,
) {
    data class Word(val text: String, val start: Double, val end: Double)

    data class Spot(
        val command: VoiceCommand,
        val text: String,
        val start: Double,
        val end: Double,
        /** How much louder than before and after the word, dB. */
        val before: Double,
        val after: Double,
        val accepted: Boolean,
        /** Level of the word itself at the microphone, dBFS. */
        val level: Double = Double.NaN,
    )

    /**
     * The book plays: the word has to stand out from the last 1.5 s of narration. Otherwise
     * the listener's own speech is all there is, and a short pause before the word is enough.
     */
    @Volatile var bookPlaying = true

    private class Candidate(val command: VoiceCommand, val text: String, val start: Double, var end: Double)

    private val levels = FloatArray(LEVEL_FRAMES)
    private var levelFrame = 0L
    private var frameEnergy = 0f
    private var frameFill = 0
    private val candidates = LinkedHashMap<Long, Candidate>()
    private var spokenUntil = Double.NEGATIVE_INFINITY

    /** The next stretch of microphone audio (mono, 16 kHz, continuing the timeline from [startTime] on). */
    fun audio(samples: FloatArray, count: Int, startTime: Double) {
        if (levelFrame == 0L && frameFill == 0) levelFrame = (startTime / FRAME).toLong()
        for (i in 0 until count) {
            frameEnergy += samples[i] * samples[i]
            if (++frameFill == FRAME_SAMPLES) {
                levels[(levelFrame % LEVEL_FRAMES).toInt()] = sqrt(frameEnergy / FRAME_SAMPLES)
                levelFrame++
                frameEnergy = 0f
                frameFill = 0
            }
        }
    }

    /** The recognizer's current words, including "[unk]" for anything else. */
    fun hypothesis(words: List<Word>) {
        for (k in words.indices) {
            for (n in 1..2) {
                if (k + n > words.size) continue
                val phrase = words.subList(k, k + n)
                if (phrase.any { it.text == UNKNOWN }) continue
                val text = phrase.joinToString(" ") { it.text }
                val command = VoiceCommand.parse(text, languages) ?: continue
                val start = phrase.first().start
                // a revised result keeps its place: the same word at about the same time
                val key = Math.round(start * 4) * 10 + n
                val known = candidates[key]
                if (known != null) known.end = phrase.last().end else candidates[key] = Candidate(command, text, start, phrase.last().end)
            }
        }
    }

    /** Start times of the candidates still waiting for a verdict. */
    fun waiting(): List<Double> = candidates.values.map { it.start }

    /** Candidates not judged yet, forgotten (the gate they were waiting at is gone). Their texts, for the log. */
    fun forgetCandidates(): List<String> {
        val texts = candidates.values.map { it.text }
        candidates.clear()
        return texts
    }

    /** Candidates whose surroundings have now been heard, judged. Longer phrases first. */
    fun judge(now: Double): List<Spot> {
        val ripe = candidates.entries.filter { now >= it.value.end + AFTER_END }
        if (ripe.isEmpty()) return emptyList()
        val spots = ArrayList<Spot>()
        for (entry in ripe.sortedWith(compareBy({ it.value.start }, { -it.value.end }))) {
            candidates.remove(entry.key)
            val c = entry.value
            if (c.start < spokenUntil) continue
            val spot = judged(c)
            spots.add(spot)
            if (spot.accepted) spokenUntil = spot.end + 0.2
        }
        return spots
    }

    private fun judged(c: Candidate): Spot {
        val word = level(c.start - 0.03, c.end + 0.03)
        val before = db(word, if (bookPlaying) level(c.start - 1.5, c.start - 0.08) else level(c.start - 0.25, c.start - 0.05))
        val after = db(word, level(c.end + 0.08, c.end + 0.4))
        val accepted = before >= minimumContrast && after >= minimumContrast && c.end - c.start <= MAXIMUM_DURATION
        return Spot(c.command, c.text, c.start, c.end, before, after, accepted, levelDb(c.start, c.end))
    }

    /** Level of the loud part of the microphone signal between two times, dBFS (full scale: a square wave of ±1). */
    fun levelDb(from: Double, to: Double): Double = 20 * log10(level(from - 0.03, to + 0.03) + 1e-7f).toDouble()

    private fun db(a: Float, b: Float) = 20 * log10((a + 1e-6f) / (b + 1e-6f)).toDouble()

    /** The level of the loud part of a stretch (90th percentile of its frames). */
    private fun level(from: Double, to: Double): Float {
        val first = maxOf((from / FRAME).toLong(), levelFrame - LEVEL_FRAMES, 0L)
        val last = minOf((to / FRAME).toLong(), levelFrame)
        if (last <= first) return 0f
        val frames = FloatArray((last - first).toInt()) { levels[((first + it) % LEVEL_FRAMES).toInt()] }
        frames.sort()
        return frames[(frames.size - 1) * 9 / 10]
    }

    companion object {
        const val UNKNOWN = "[unk]"
        /** The word has to be this much louder than its surroundings, dB, unless the policy says otherwise. */
        const val MINIMUM_CONTRAST = 6.0
        const val MAXIMUM_DURATION = 1.2
        /** Judged when this much after the word has been heard, seconds. */
        const val AFTER_END = 0.42
        private const val FRAME = 0.02
        private const val FRAME_SAMPLES = 320
        private const val LEVEL_FRAMES = 300
    }
}
