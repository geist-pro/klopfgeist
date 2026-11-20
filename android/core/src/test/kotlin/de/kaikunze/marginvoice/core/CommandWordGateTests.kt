package de.kaikunze.marginvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The contrast gate in front of the session while the book plays on the loudspeaker.
 * The microphone signal is synthetic: the book's echo as a steady tone, words as louder
 * (or equally loud) stretches of it.
 */
class CommandWordGateTests {
    private val rate = 16_000
    private val spotter = CommandSpotter(VoiceCommand.Language.entries.toSet())
    private var fed = 0

    /** Feeds the spotter up to `until` seconds: the echo at amplitude 0.02, `loud` stretches at 0.12 (15 dB above). */
    private fun hear(until: Double, vararg loud: Pair<Double, Double>) {
        val to = (until * rate).toInt()
        if (to <= fed) return
        val x = FloatArray(to - fed) { k ->
            val i = fed + k
            val t = i / rate.toDouble()
            (if (loud.any { t >= it.first && t < it.second }) 0.12f else 0.02f) * sin(2 * PI * 220 * i / rate).toFloat()
        }
        spotter.audio(x, x.size, fed / rate.toDouble())
        fed = to
    }

    private fun texts(h: Hypothesis) = h.words.joinToString(" ") { it.text }

    @Test fun aCommandWordIsHeldBackUntilItStandsOut() {
        val gate = CommandWordGate(spotter)
        val words = listOf(SpokenWord("[unk]", 1.0, 1.4), SpokenWord("stop", 2.0, 2.3))
        hear(2.35, 2.0 to 2.3)
        val first = gate.result(Hypothesis(words, false), 2.35)
        // still speech at that time, but not yet a command
        assertEquals(listOf("[unk] [unk]"), first.hypotheses.map(::texts))
        assertEquals(false, first.hypotheses.single().isFinal)
        assertEquals(words.map { it.start }, first.hypotheses.single().words.map { it.start })
        assertTrue(first.spots.isEmpty())
        hear(2.5, 2.0 to 2.3)
        assertTrue(gate.advance(2.5).hypotheses.isEmpty())

        hear(2.75, 2.0 to 2.3)
        val judged = gate.advance(2.75)
        assertEquals(listOf("[unk] stop"), judged.hypotheses.map(::texts))
        assertTrue(judged.spots.single().accepted)
        hear(2.85, 2.0 to 2.3)
        assertTrue("nothing new, nothing passed on", gate.advance(2.85).hypotheses.isEmpty())
    }

    @Test fun aWordThatDoesNotStandOutStaysMasked() {
        val gate = CommandWordGate(spotter)
        // the book says "stop" at its own level
        hear(2.35)
        gate.result(Hypothesis(listOf(SpokenWord("stop", 2.0, 2.3)), false), 2.35)
        hear(2.8)
        val judged = gate.advance(2.8)
        assertFalse(judged.spots.single().accepted)
        assertTrue(judged.hypotheses.isEmpty())
        // the recognizer's final result: passed on as final, the word still not a command
        hear(3.0)
        val out = gate.result(Hypothesis(listOf(SpokenWord("stop", 2.0, 2.3)), true), 3.0)
        assertEquals(listOf(Hypothesis(listOf(SpokenWord("[unk]", 2.0, 2.3)), isFinal = true), Hypothesis(emptyList(), isFinal = false)), out.hypotheses)
        assertTrue("the same verdict is not reported twice", out.spots.isEmpty())
    }

    @Test fun aFinalResultWaitsForTheVerdict() {
        val gate = CommandWordGate(spotter)
        val words = listOf(SpokenWord("weiter", 5.0, 5.4))
        hear(5.45, 5.0 to 5.4)
        gate.result(Hypothesis(words, false), 5.45)
        // the recognizer closes the utterance before the surroundings of the word have been heard
        hear(5.6, 5.0 to 5.4)
        val early = gate.result(Hypothesis(words, true), 5.6)
        assertTrue("held, still volatile", early.hypotheses.none { it.isFinal })
        hear(5.85, 5.0 to 5.4)
        assertEquals(Hypothesis(words, isFinal = true), gate.advance(5.85).hypotheses.first())
    }

    @Test fun aFinalResultThatArrivesLateIsJudgedBeforeItIsPassedOn() {
        // The recognizer reports the word for the first time, and as final, long after it was
        // said: its surroundings have been heard already. The verdict is reached in the same
        // step, and the word goes on as itself. (Passed on masked first, a final word could
        // never be recovered by the detector.)
        val gate = CommandWordGate(spotter)
        hear(4.0, 2.0 to 2.3)
        val out = gate.result(Hypothesis(listOf(SpokenWord("stop", 2.0, 2.3)), true), 4.0)
        assertTrue(out.spots.single().accepted)
        assertEquals(Hypothesis(listOf(SpokenWord("stop", 2.0, 2.3)), isFinal = true), out.hypotheses.first())
    }

    @Test fun aPartialResultThatArrivesLateIsJudgedAtOnceToo() {
        val gate = CommandWordGate(spotter)
        hear(3.5, 2.0 to 2.3)
        val out = gate.result(Hypothesis(listOf(SpokenWord("stop", 2.0, 2.3)), false), 3.5)
        assertEquals(listOf("stop"), out.hypotheses.map(::texts))
    }

    @Test fun wordsAroundACommandPassThrough() {
        val gate = CommandWordGate(spotter)
        hear(1.05)
        val out = gate.result(Hypothesis(listOf(SpokenWord("[unk]", 0.2, 0.6), SpokenWord("[unk]", 0.6, 1.0)), false), 1.05)
        assertEquals(listOf("[unk] [unk]"), out.hypotheses.map(::texts))
    }

    @Test fun aTwoWordCommandIsReleasedWhole() {
        val gate = CommandWordGate(spotter)
        val words = listOf(SpokenWord("go", 3.0, 3.2), SpokenWord("back", 3.2, 3.6))
        hear(3.65, 3.0 to 3.6)
        assertEquals(listOf("[unk] [unk]"), gate.result(Hypothesis(words, false), 3.65).hypotheses.map(::texts))
        hear(4.05, 3.0 to 3.6)
        val out = gate.advance(4.05)
        assertEquals(listOf("go back"), out.hypotheses.map(::texts))
        assertEquals(listOf("go back"), out.spots.map { it.text })
    }

    @Test fun aRetimedWordKeepsItsVerdict() {
        val gate = CommandWordGate(spotter)
        hear(2.35, 2.0 to 2.3)
        gate.result(Hypothesis(listOf(SpokenWord("stop", 2.0, 2.3)), false), 2.35)
        hear(2.75, 2.0 to 2.3)
        gate.advance(2.75)
        // the final result moves the word by a few hundredths
        hear(3.0, 2.0 to 2.3)
        val out = gate.result(Hypothesis(listOf(SpokenWord("stop", 2.03, 2.36)), true), 3.0)
        assertEquals(Hypothesis(listOf(SpokenWord("stop", 2.03, 2.36)), isFinal = true), out.hypotheses.first())
    }

    @Test fun whatWasSaidBeforeTheBookPlayedIsNotGated() {
        // "continue" was said while the book was paused; the book plays from 10.0 on and the
        // recognizer still reports the word
        val gate = CommandWordGate(spotter, since = 10.0)
        val words = listOf(SpokenWord("continue", 9.2, 9.7), SpokenWord("stop", 12.0, 12.3))
        hear(12.35)
        assertEquals(listOf("continue [unk]"), gate.result(Hypothesis(words, false), 12.35).hypotheses.map(::texts))
        hear(14.0)
        val later = gate.advance(14.0)
        assertEquals("only the new word is ever judged", listOf("stop"), later.spots.map { it.text })
    }

    @Test fun whenTheGateTurnsOffWaitingWordsAreReleasedAndNamed() {
        // "stop" over the book, and the book is paused by a button before the verdict: there is
        // no echo to tell the word from any more, so it passes; the log names it
        val gate = CommandWordGate(spotter)
        val words = listOf(SpokenWord("[unk]", 1.0, 1.4), SpokenWord("stop", 2.0, 2.3))
        hear(2.35, 2.0 to 2.3)
        gate.result(Hypothesis(words, false), 2.35)
        val out = gate.release(2.4)
        assertEquals(listOf("stop"), out.released)
        assertEquals(listOf(Hypothesis(words, isFinal = false)), out.hypotheses)
        // the spotter no longer waits for it: a gate that comes on again starts clean
        assertTrue(spotter.waiting().isEmpty())
    }

    @Test fun aHeldFinalResultIsReleasedAsFinal() {
        val gate = CommandWordGate(spotter)
        val words = listOf(SpokenWord("go", 5.0, 5.2), SpokenWord("back", 5.2, 5.5))
        hear(5.6, 5.0 to 5.5)
        gate.result(Hypothesis(words, true), 5.6)
        val out = gate.release(5.65)
        assertEquals(Hypothesis(words, isFinal = true), out.hypotheses.first())
        assertEquals("the phrase, not also its last word", listOf("go back"), out.released)
    }

    @Test fun aRejectedWordStaysMaskedWhenTheGateTurnsOff() {
        // the verdict was reached: the word was the book's. Pausing the book does not make it a command.
        val gate = CommandWordGate(spotter)
        hear(2.8)
        gate.result(Hypothesis(listOf(SpokenWord("stop", 2.0, 2.3)), false), 2.8)
        val out = gate.release(2.9)
        assertTrue(out.released.isEmpty())
        assertTrue(out.hypotheses.all { h -> h.words.none { it.text == "stop" } })
    }

    @Test fun releasingWithNothingHeldPassesNothing() {
        val gate = CommandWordGate(spotter)
        val out = gate.release(1.0)
        assertTrue(out.hypotheses.isEmpty() && out.released.isEmpty() && out.spots.isEmpty())
    }
}
