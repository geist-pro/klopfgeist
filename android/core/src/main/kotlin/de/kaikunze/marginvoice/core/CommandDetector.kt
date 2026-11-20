package de.kaikunze.marginvoice.core

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

/**
 * One word the recognizer heard, on the microphone timeline (seconds of microphone
 * audio since capture started).
 */
@Serializable
data class SpokenWord(val text: String, val start: Double, val end: Double)

/**
 * A recognizer result. A volatile hypothesis replaces the previous volatile one; a
 * final one is appended to what is known for good.
 */
@Serializable
/**
 * A recognizer result. [timesApproximate]: the word times are only roughly right (a
 * volatile guess timed over the whole run since the last final result, as Apple's
 * SpeechAnalyzer does). A command in it may fire, but is not rejected on its times
 * until final words arrive.
 */
data class Hypothesis(val words: List<SpokenWord>, val isFinal: Boolean, val timesApproximate: Boolean = false) {
    companion object {
        /**
         * For recognizers that time a result but not its words: the words spread evenly
         * over the range.
         */
        fun of(text: String, start: Double, end: Double, isFinal: Boolean): Hypothesis {
            val parts = text.splitWhere { it.isWhitespace() }
            val step = if (parts.isEmpty()) 0.0 else max(0.0, end - start) / parts.size
            return Hypothesis(parts.mapIndexed { i, w -> SpokenWord(w, start + i * step, start + (i + 1) * step) }, isFinal)
        }

        private inline fun String.splitWhere(isSeparator: (Char) -> Boolean): List<String> {
            val parts = ArrayList<String>()
            val current = StringBuilder()
            for (ch in this) {
                if (isSeparator(ch)) {
                    if (current.isNotEmpty()) parts.add(current.toString())
                    current.setLength(0)
                } else {
                    current.append(ch)
                }
            }
            if (current.isNotEmpty()) parts.add(current.toString())
            return parts
        }
    }
}

/**
 * Turns what the recognizer hears into voice commands.
 *
 * A command is a command phrase at the end of what was said, with a pause before it
 * and nothing after it. Both are read from the recognizer's word times, so it works
 * the same on a microphone the energy detector can't segment (AirPods gate and
 * level their signal). The energy detector only delays a decision while someone is
 * audibly still speaking, and never for long.
 *
 * Every command phrase that stays at the end of what was said ends in exactly one
 * outcome: fired, or rejected with a reason the command log can show.
 */
class CommandDetector(var languages: Set<VoiceCommand.Language> = VoiceCommand.Language.entries.toSet()) {
    @Serializable
    data class Policy(
        /** Pause needed between other speech and the command. */
        val pauseBefore: Double,
        /**
         * How long the command has to stay the last thing heard. Counted from when the
         * recognizer first reported it, not from when it was said: a recognizer that
         * runs behind would otherwise deliver "stop" already "settled", a moment
         * before it delivers the words that followed.
         */
        val settle: Double,
        /** Longest wait for audible speech to end before deciding anyway. */
        val maximumHold: Double = 1.2,
        /** A command phrase stretched over more than this is speech. */
        val maximumDuration: Double = 2.0,
        /** The same command again within this time is the same command. */
        val refractory: Double = 1.0,
        /**
         * A command known only from a guess (not a final result) that speech audibly went on
         * after waits for the final words. For comments: a recognizer that reports the start of
         * a sentence first ("we should continue…") would otherwise end the comment inside it.
         */
        val waitsForFinalWords: Boolean = false,
    ) {
        companion object {
            /** The book plays or is paused: quick, since "stop" should land on the sentence. */
            val listening = Policy(pauseBefore = 0.3, settle = 0.3)
            /**
             * A comment is being spoken: "continue" in the middle of a sentence is not a
             * command, so both pauses are longer.
             */
            val recording = Policy(pauseBefore = 0.3, settle = 0.6, waitsForFinalWords = true)
            /**
             * The book is on a loudspeaker next to the microphone: what remains of it keeps
             * the energy detector busy, so it is waited for only briefly.
             */
            val listeningOverSpeaker = Policy(pauseBefore = 0.3, settle = 0.3, maximumHold = 0.6)
        }
    }

    data class Context(
        val policy: Policy,
        /** Commands that mean something right now; anything else is speech. */
        val allowed: Set<VoiceCommand> = VoiceCommand.entries.toSet(),
        /** What the book narrated in the last few seconds, when the microphone can hear it. */
        val recentBookText: String? = null,
    ) {
        companion object {
            /** While a comment records, only these end it. */
            val recordingCommands: Set<VoiceCommand> = setOf(VoiceCommand.RESUME, VoiceCommand.CANCEL, VoiceCommand.MARK)
        }
    }

    data class Candidate(
        val command: VoiceCommand,
        val text: String,
        /** Where the command phrase starts on the microphone timeline. */
        val start: Double,
        val end: Double,
    )

    enum class Rejection(val raw: String) {
        /** Said right after other words, without a pause. */
        NOT_ISOLATED("notIsolated"),
        /** More words followed it. */
        SPEECH_CONTINUED("speechContinued"),
        /** The book itself just said it. */
        BOOK_ECHO("bookEcho"),
        /** The same command was acted on a moment ago. */
        DUPLICATE("duplicate"),
        /** The phrase was drawn out like ordinary speech. */
        TOO_LONG("tooLong"),
    }

    sealed interface Outcome {
        data class Fire(val candidate: Candidate) : Outcome
        data class Rejected(val text: String, val rejection: Rejection) : Outcome
    }

    private data class Pending(
        val candidate: Candidate,
        val pauseBefore: Double,
        /** Some of its words are timed only approximately: timing rejections wait for final words. */
        val isVolatile: Boolean,
        /** How long the command phrase itself took (without the repeats before it). */
        val duration: Double,
        /** When the recognizer first reported the phrase as it stands. */
        val seen: Double,
        /** Some of its words are still the recognizer's guess: more words may follow in the final result. */
        val isGuess: Boolean = false,
    )

    private class Token(val text: String, val start: Double, val end: Double, val isVolatile: Boolean, val isGuess: Boolean)

    private class Found(val candidate: Candidate, val pauseBefore: Double, val isVolatile: Boolean, val duration: Double, val isGuess: Boolean)

    private class Fired(val command: VoiceCommand, val at: Double)

    /** Words known for good, oldest first. */
    private var finalWords: List<SpokenWord> = emptyList()
    /** The recognizer's current guess at what follows them. */
    private var volatileWords: List<SpokenWord> = emptyList()
    /** The guess is timed over the whole run, not word by word. */
    private var volatileApproximate = false
    /** Words that start before this have been decided. */
    private var decidedUntil = Double.NEGATIVE_INFINITY
    private var pending: Pending? = null
    private var speechActive = false
    /** When the energy detector last stopped hearing speech (microphone time). */
    private var speechEndedAt = Double.NEGATIVE_INFINITY
    private var lastFire: Fired? = null

    /** The microphone restarted (its timeline starts at zero again) or another book opened. */
    fun reset() {
        finalWords = emptyList()
        volatileWords = emptyList()
        volatileApproximate = false
        decidedUntil = Double.NEGATIVE_INFINITY
        pending = null
        speechActive = false
        speechEndedAt = Double.NEGATIVE_INFINITY
        lastFire = null
    }

    /** Nothing said before `time` can become a command any more (a comment begins here). */
    fun forget(before: Double) {
        decidedUntil = max(decidedUntil, before)
        pending?.let { if (it.candidate.start < before) pending = null }
    }

    /** The energy detector hears speech (or stopped hearing it) at microphone time `at`. */
    fun speech(active: Boolean, at: Double? = null) {
        speechActive = active
        if (!active && at != null) speechEndedAt = at
    }

    /** A recognizer result arrived at microphone time `now`. */
    fun hypothesis(h: Hypothesis, now: Double, context: Context): List<Outcome> {
        if (h.isFinal) {
            // the guess it replaces: everything it covers
            val covered = h.words.maxOfOrNull { it.end } ?: Double.NEGATIVE_INFINITY
            finalWords = (finalWords + h.words).sortedBy { it.start }.filterNot { it.end < now - MEMORY }
            volatileWords = volatileWords.filterNot { it.start < covered }
        } else {
            volatileWords = h.words
            volatileApproximate = h.timesApproximate
        }
        return evaluate(now, context)
    }

    /** Time passed; call a few times a second. */
    fun tick(now: Double, context: Context): List<Outcome> = evaluate(now, context)

    private fun evaluate(now: Double, context: Context): List<Outcome> {
        val outcomes = ArrayList<Outcome>()
        val found = trailingCommand(context.allowed)

        // a command that was waiting and is no longer the end of what was said
        pending?.let { p ->
            if (found == null || !isSame(found.candidate, p.candidate)) {
                if (stillHeard(p.candidate)) {
                    outcomes.add(Outcome.Rejected(p.candidate.text, Rejection.SPEECH_CONTINUED))
                    decidedUntil = max(decidedUntil, p.candidate.end)
                }
                pending = null
            }
        }
        if (found == null) return outcomes

        val waiting = pending
        val p = if (waiting != null) {
            // the same command in a newer result: re-timed, or grown ("mark" → "mark this")
            val changed = found.candidate.end - waiting.candidate.end > 0.05 || found.candidate.text != waiting.candidate.text
            Pending(found.candidate, found.pauseBefore, found.isVolatile, found.duration, if (changed) now else waiting.seen, found.isGuess)
        } else {
            Pending(found.candidate, found.pauseBefore, found.isVolatile, found.duration, now, found.isGuess)
        }
        pending = p
        if (!isSettled(p, now, context.policy)) return outcomes

        val c = p.candidate
        // a guess that speech went on after: its final words decide whether it was the end of what was said
        val spokenOn = speechActive || speechEndedAt > c.end + SPEECH_TAIL
        if (context.policy.waitsForFinalWords && p.isGuess && spokenOn && now - p.seen < FINAL_WAIT) return outcomes
        val last = lastFire
        // the command just acted on, said again: whatever its timing, it is that one
        val isDuplicate = last != null && last.command == c.command && c.start - last.at < context.policy.refractory
        val tooLong = p.duration > context.policy.maximumDuration
        val notIsolated = p.pauseBefore < context.policy.pauseBefore
        // an approximately timed guess: its final words decide
        if (!isDuplicate && p.isVolatile && (tooLong || notIsolated) && now - p.seen < FINAL_WAIT) return outcomes

        pending = null
        decidedUntil = max(decidedUntil, c.end)
        val book = context.recentBookText
        if (isDuplicate) {
            outcomes.add(Outcome.Rejected(c.text, Rejection.DUPLICATE))
        } else if (tooLong) {
            outcomes.add(Outcome.Rejected(c.text, Rejection.TOO_LONG))
        } else if (notIsolated) {
            outcomes.add(Outcome.Rejected(c.text, Rejection.NOT_ISOLATED))
        } else if (book != null && isBookEcho(c.text, book)) {
            outcomes.add(Outcome.Rejected(c.text, Rejection.BOOK_ECHO))
        } else {
            lastFire = Fired(c.command, c.end)
            outcomes.add(Outcome.Fire(c))
        }
        return outcomes
    }

    private fun isSettled(p: Pending, now: Double, policy: Policy): Boolean {
        if (!(now - p.seen >= policy.settle && now - p.candidate.end >= policy.settle)) return false
        // someone is audibly still speaking: wait, but not for long (noise looks like speech too)
        if (speechActive && now - p.seen < policy.maximumHold) return false
        return true
    }

    private fun isSame(a: Candidate, b: Candidate): Boolean = a.command == b.command && abs(a.start - b.start) < 0.5

    /**
     * Whether the recognizer still reports words where the candidate was (as opposed
     * to having withdrawn the whole guess, which is not worth a log line).
     */
    private fun stillHeard(c: Candidate): Boolean = (finalWords + volatileWords).any { it.end > c.end + 0.01 }

    /** The command phrase at the very end of what was heard, if there is one. */
    private fun trailingCommand(allowed: Set<VoiceCommand>): Found? {
        val spoken = ArrayList<Token>()
        val heard = finalWords.map { Triple(it, false, false) } + volatileWords.map { Triple(it, volatileApproximate, true) }
        for ((word, isVolatile, isGuess) in heard.sortedBy { it.first.start }) {
            val parts = TextSegmentation.tokens(word.text)
            // a recognizer run of several words: spread them over the run
            val step = (word.end - word.start) / max(1, parts.size)
            for ((i, part) in parts.withIndex()) {
                if (part in VoiceCommand.fillers) continue
                spoken.add(Token(part, word.start + i * step, word.start + (i + 1) * step, isVolatile, isGuess))
            }
        }
        for (n in intArrayOf(2, 1)) {
            if (spoken.size < n) continue
            val first = spoken.size - n
            // every word of the phrase is still undecided (a final result may re-time a word a little)
            if (!(spoken[first].start >= decidedUntil - 0.05)) continue
            val text = spoken.subList(first, spoken.size).joinToString(" ") { it.text }
            val command = VoiceCommand.parse(text, languages) ?: continue
            if (command !in allowed) return null
            // "stop, stop": a word repeated because nothing happened yet is one command, timed
            // from the first (a late recognizer delivers both at once)
            var head = first
            while (n == 1 && head > 0 && spoken[head - 1].start >= decidedUntil - 0.05 &&
                spoken[head].start - spoken[head - 1].end < REPEAT_GAP &&
                VoiceCommand.parse(spoken[head - 1].text, languages) == command
            ) {
                head--
            }
            val start = spoken[head].start
            val pause = if (head > 0) start - spoken[head - 1].end else Double.POSITIVE_INFINITY
            val isVolatile = spoken.subList(head, spoken.size).any { it.isVolatile }
            val isGuess = spoken.subList(head, spoken.size).any { it.isGuess }
            val end = spoken.last().end
            return Found(Candidate(command, text, start, end), pause, isVolatile, end - spoken[first].start, isGuess)
        }
        return null
    }

    companion object {
        /** How long heard words are kept for the pause before a command. */
        private const val MEMORY = 12.0

        /** How long a command timed only approximately waits for final words before a timing rejection. */
        private const val FINAL_WAIT = 2.5

        /** Speech heard this long past a command's end means more was said (the energy detector's release is shorter). */
        private const val SPEECH_TAIL = 0.4

        /** A command word said again within this gap is the same command repeated. */
        private const val REPEAT_GAP = 1.0

        /** True when every word of the utterance was just narrated by the book. */
        fun isBookEcho(utterance: String, recentBookText: String): Boolean {
            val spoken = TextSegmentation.tokens(recentBookText).toSet()
            val said = TextSegmentation.tokens(utterance).filter { it !in VoiceCommand.fillers }
            return said.isNotEmpty() && said.all { it in spoken }
        }
    }
}
