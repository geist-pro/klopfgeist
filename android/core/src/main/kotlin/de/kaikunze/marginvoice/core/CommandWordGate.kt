package de.kaikunze.marginvoice.core

/**
 * The contrast gate in front of the session ([CommandGate.Kind.CONTRAST]: the book is on
 * the loudspeaker and playing): a command word reaches the session only once
 * [CommandSpotter] found it standing out from its surroundings. Until it is judged, and
 * when it is rejected, the word is passed on as "[unk]": still speech at that time, so
 * the pauses `CommandDetector` measures stay right, but not a command.
 *
 * It decides nothing about commands itself; it only withholds words the microphone
 * signal does not support. It drives the spotter, so the order is always: the
 * recognizer's words to the spotter, the spotter's verdicts, and only then what is
 * passed on (a final result is never passed on with a word masked that the same
 * moment's verdict accepts). All times are microphone seconds. One thread.
 *
 * @param spotter fed with the microphone audio by the caller.
 * @param since when the gate came on: words that ended before were heard without a book
 *   playing, and pass as they are.
 */
class CommandWordGate(
    private val spotter: CommandSpotter,
    private val languages: Set<VoiceCommand.Language> = VoiceCommand.Language.entries.toSet(),
    private val since: Double = Double.NEGATIVE_INFINITY,
) {
    /** What one step of the gate produced. */
    data class Output(
        /** What to pass on to the session, in order. */
        val hypotheses: List<Hypothesis> = emptyList(),
        /** The verdicts reached in this step, for the command log. */
        val spots: List<CommandSpotter.Spot> = emptyList(),
        /** Command words let through without a verdict because the gate turned off. */
        val released: List<String> = emptyList(),
    )

    /** Final words with a command word that is still being judged. */
    private var held: List<SpokenWord> = emptyList()
    private var partial: List<SpokenWord> = emptyList()
    private val accepted = ArrayList<ClosedFloatingPointRange<Double>>()
    private var lastVolatile: List<SpokenWord>? = null
    private val verdicts = LinkedHashMap<String, Boolean>()

    /** A recognizer result arrived at microphone time `now`. */
    fun result(h: Hypothesis, now: Double): Output {
        spotter.hypothesis(h.words.filter { it.end > since }.map { CommandSpotter.Word(it.text, it.start, it.end) })
        if (h.isFinal) {
            held = held + h.words
            partial = emptyList()
        } else {
            partial = h.words
        }
        return step(now)
    }

    /** Time passed (audio up to `now` has been given to the spotter). */
    fun advance(now: Double): Output = step(now)

    /**
     * The gate turns off (the book paused, a comment began): the reason for withholding
     * is gone. Words still waiting for a verdict pass as they are; rejected ones stay
     * masked. Nothing is dropped without being named in the output.
     */
    fun release(now: Double): Output {
        val verdicts = judge(now)
        val out = ArrayList<Hypothesis>(2)
        val released = LinkedHashSet<String>()
        fun unmask(words: List<SpokenWord>): List<SpokenWord> {
            val states = states(words, now)
            for (p in CommandWords.phrases(words, languages)) {
                if ((p.first until p.first + p.count).all { states[it] == State.PENDING }) released.add(p.text)
            }
            return words.mapIndexed { i, w -> if (states[i] == State.REJECTED) w.copy(text = CommandSpotter.UNKNOWN) else w }
        }
        if (held.isNotEmpty()) out.add(Hypothesis(unmask(held), isFinal = true))
        val volatile = unmask(partial)
        if (volatile != lastVolatile && !(lastVolatile == null && volatile.isEmpty())) out.add(Hypothesis(volatile, isFinal = false))
        // "back" inside "go back" is released with the phrase
        val phrases = released.filter { text -> released.none { it != text && it.endsWith(" $text") } }
        held = emptyList()
        partial = emptyList()
        lastVolatile = null
        spotter.forgetCandidates()
        return Output(out, verdicts, phrases)
    }

    /** The spotter's new verdicts. A word the recognizer keeps reporting is judged again each time: reported once. */
    private fun judge(now: Double): List<CommandSpotter.Spot> {
        val fresh = ArrayList<CommandSpotter.Spot>()
        for (spot in spotter.judge(now)) {
            if (spot.accepted) accepted.add(spot.start..spot.end)
            val key = "${Math.round(spot.start * 4)} ${spot.text}"
            if (verdicts.put(key, spot.accepted) != spot.accepted) fresh.add(spot)
        }
        while (verdicts.size > 64) verdicts.remove(verdicts.keys.first())
        return fresh
    }

    private fun step(now: Double): Output {
        // verdicts first: what is passed on below already knows them
        val spots = judge(now)
        accepted.removeAll { it.endInclusive < now - MEMORY }
        val out = ArrayList<Hypothesis>(2)
        if (held.isNotEmpty()) {
            val states = states(held, now)
            if (State.PENDING !in states) {
                out.add(Hypothesis(masked(held, states), isFinal = true))
                held = emptyList()
            }
        }
        val all = held + partial
        val volatile = masked(all, states(all, now))
        if (volatile != lastVolatile && !(lastVolatile == null && volatile.isEmpty())) {
            out.add(Hypothesis(volatile, isFinal = false))
            lastVolatile = volatile
        }
        return Output(out, spots)
    }

    private enum class State { PLAIN, REJECTED, PENDING, ACCEPTED }

    /** For each word: not part of a command phrase, or how the phrase it belongs to was judged. */
    private fun states(words: List<SpokenWord>, now: Double): List<State> {
        val states = MutableList(words.size) { State.PLAIN }
        val waiting = spotter.waiting()
        for (phrase in CommandWords.phrases(words, languages)) {
            val state = when {
                phrase.end <= since -> State.PLAIN
                accepted.any { phrase.start >= it.start - TOLERANCE && phrase.end <= it.endInclusive + TOLERANCE } -> State.ACCEPTED
                // the spotter still has it: no verdict yet, however late the recognizer reported it
                waiting.any { kotlin.math.abs(it - phrase.start) < TOLERANCE } -> State.PENDING
                else -> State.REJECTED
            }
            // a word of two phrases ("back" in "go back") takes the better verdict
            for (i in phrase.first until phrase.first + phrase.count) if (state > states[i]) states[i] = state
        }
        return states
    }

    private fun masked(words: List<SpokenWord>, states: List<State>): List<SpokenWord> = words.mapIndexed { i, w ->
        if (states[i] == State.PENDING || states[i] == State.REJECTED) w.copy(text = CommandSpotter.UNKNOWN) else w
    }

    private companion object {
        /** A revised result re-times a word a little; the spotter files candidates in quarter seconds. */
        const val TOLERANCE = 0.3
        const val MEMORY = 15.0
    }
}
