package de.kaikunze.marginvoice.core

import java.util.Locale

/**
 * What stands between the recognizer and a command word right now.
 *
 * The archived Android app judged every command word by its loudness (6 dB above its
 * surroundings) and turned the microphone signal down around it whenever the book
 * played, whatever the route. That is what made "stop" and "continue" need a raised
 * voice. Both exist to tell the listener's voice from the book's echo, so they apply
 * only where there is an echo: the microphone hears the book, and the book is playing.
 */
data class CommandGate(
    val kind: Kind,
    /** Turn down what is no louder than the last moments before it reaches the recognizer. */
    val echoTurnDown: Boolean,
    /** [Kind.CONTRAST]: the word has to be this much louder than its surroundings, dB. */
    val minimumContrast: Double,
    /** Why, for the command log: "headphones", "book paused", "recording", "speaker, book playing". */
    val reason: String,
) {
    enum class Kind {
        /** Every recognizer result goes to the session as it is. */
        NONE,
        /** A command word has to stand out from the book's echo ([CommandSpotter], [CommandWordGate]). */
        CONTRAST,
    }

    /** For the command log: "no gate (book paused)", "contrast gate ≥ 6 dB (speaker, book playing)". */
    val description: String
        get() = when (kind) {
            Kind.NONE -> "no gate ($reason)"
            Kind.CONTRAST -> "contrast gate ≥ ${fmt("%.0f", minimumContrast)} dB ($reason)"
        }
}

/** Decides the [CommandGate] from the audio plan and what the book is doing. Pure. */
class CommandGatePolicy(
    /**
     * How far a command word has to stand above the echo-suppressed residual of the book.
     * 6 dB took false triggers in 32 min of narration on the speaker from 8 to 0 in the archived project.
     */
    val minimumContrast: Double = CommandSpotter.MINIMUM_CONTRAST,
) {
    fun gate(plan: AudioPlan, bookPlaying: Boolean, recording: Boolean): CommandGate = when {
        // headphones, a headset: the microphone hears no book
        !plan.bookAudible -> none("headphones")
        // a comment is being spoken: the book is silent, and "continue" has to end it
        recording -> none("recording")
        // nothing to tell the voice from
        !bookPlaying -> none("book paused")
        else -> CommandGate(CommandGate.Kind.CONTRAST, echoTurnDown = true, minimumContrast = minimumContrast, reason = "speaker, book playing")
    }

    private fun none(reason: String) = CommandGate(CommandGate.Kind.NONE, echoTurnDown = false, minimumContrast = 0.0, reason = reason)
}

/** Command phrases among a recognizer's words, and the evidence line the command log shows for each. */
object CommandWords {
    data class Phrase(val text: String, val command: VoiceCommand, val start: Double, val end: Double, val first: Int, val count: Int)

    /** Every one- or two-word command phrase in `words` ("[unk]" is never part of one). */
    fun phrases(words: List<SpokenWord>, languages: Set<VoiceCommand.Language> = VoiceCommand.Language.entries.toSet()): List<Phrase> {
        val found = ArrayList<Phrase>()
        for (k in words.indices) {
            for (n in 1..2) {
                if (k + n > words.size) continue
                val phrase = words.subList(k, k + n)
                if (phrase.any { it.text == CommandSpotter.UNKNOWN }) continue
                val text = phrase.joinToString(" ") { it.text }
                val command = VoiceCommand.parse(text, languages) ?: continue
                found.add(Phrase(text, command, phrase.first().start, phrase.last().end, k, n))
            }
        }
        return found
    }

    /**
     * What the microphone signal said about a command word, for the command log and the trace:
     * its level, the gain the recognizer's input had, and which gate it met with what result.
     *
     * - `“continue” heard at -41 dBFS, gain +18 dB, no gate (book paused)`
     * - `“stop” stands out (+14/+18 dB) at -33 dBFS, gain +9 dB, contrast gate ≥ 6 dB (speaker, book playing)`
     * - `Ignored “stop” (not on its own: +3/+2 dB) at -33 dBFS, gain +9 dB, contrast gate ≥ 6 dB (speaker, book playing)`
     *
     * @param levelDb level of the word at the microphone, dBFS.
     * @param gainDb gain applied to the recognizer's input at the word, dB.
     * @param spot the contrast verdict, when the contrast gate applied.
     */
    /**
     * A command word that was waiting for its contrast verdict when the gate turned off, and went on without one:
     * `“stop” let through without a contrast verdict: no gate (book paused)`.
     */
    fun released(text: String, gate: CommandGate): String = "“$text” let through without a contrast verdict: ${gate.description}"

    fun evidence(text: String, levelDb: Double, gainDb: Double, gate: CommandGate, spot: CommandSpotter.Spot? = null): String {
        val signal = "at ${fmt("%.0f", levelDb)} dBFS, gain ${fmt("%+.0f", gainDb)} dB, ${gate.description}"
        if (spot == null) return "“$text” heard $signal"
        val contrast = String.format(Locale.ROOT, "%+.0f/%+.0f dB", spot.before, spot.after)
        return if (spot.accepted) "“$text” stands out ($contrast) $signal" else "Ignored “$text” (not on its own: $contrast) $signal"
    }
}
