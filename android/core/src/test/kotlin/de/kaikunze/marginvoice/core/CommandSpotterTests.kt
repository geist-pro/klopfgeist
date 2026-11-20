package de.kaikunze.marginvoice.core

import de.kaikunze.marginvoice.core.CommandSpotter.Word
import de.kaikunze.marginvoice.core.VoiceCommand.Language.ENGLISH
import de.kaikunze.marginvoice.core.VoiceCommand.Language.GERMAN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

private const val RATE = 16_000

/** A tone of `amplitude` from `from` to `to` seconds, in a signal of `seconds`. */
private fun signal(seconds: Double, vararg parts: Triple<Double, Double, Float>): FloatArray {
    val x = FloatArray((seconds * RATE).toInt())
    for ((from, to, amplitude) in parts) {
        for (i in (from * RATE).toInt() until minOf((to * RATE).toInt(), x.size)) {
            x[i] += amplitude * sin(2 * PI * 220 * i / RATE).toFloat()
        }
    }
    return x
}

private fun CommandSpotter.feed(x: FloatArray, startTime: Double = 0.0) {
    audio(x, x.size, startTime)
}

class CommandSpotterTests {
    private val both = setOf(ENGLISH, GERMAN)

    @Test fun aQuietWordInAQuietRoomFires() {
        val s = CommandSpotter(both)
        s.feed(signal(4.0, Triple(2.0, 2.4, 0.003f), Triple(0.0, 4.0, 0.0001f)))
        s.hypothesis(listOf(Word("stop", 2.0, 2.4)))
        val spot = s.judge(4.0).single()
        assertEquals(VoiceCommand.STOP, spot.command)
        assertTrue(spot.accepted)
    }

    @Test fun aNormalVoiceOverTheBooksEchoFires() {
        val s = CommandSpotter(both)
        // the book (echo) all through, the voice 15 dB above it
        s.feed(signal(4.0, Triple(0.0, 4.0, 0.02f), Triple(2.0, 2.4, 0.11f)))
        s.hypothesis(listOf(Word(CommandSpotter.UNKNOWN, 1.0, 1.95), Word("stop", 2.0, 2.4), Word(CommandSpotter.UNKNOWN, 2.45, 3.0)))
        assertTrue(s.judge(4.0).single().accepted)
    }

    @Test fun aWordOfTheBookAtItsOwnLevelIsRejected() {
        val s = CommandSpotter(both)
        s.feed(signal(4.0, Triple(0.0, 4.0, 0.05f)))
        s.hypothesis(listOf(Word("stop", 2.0, 2.4)))
        assertFalse(s.judge(4.0).single().accepted)
    }

    @Test fun theWordInsideASentenceIsRejected() {
        val s = CommandSpotter(both)
        s.bookPlaying = false
        s.feed(signal(4.0, Triple(1.0, 3.5, 0.05f)))
        s.hypothesis(listOf(Word(CommandSpotter.UNKNOWN, 1.0, 1.9), Word("stop", 2.0, 2.4), Word(CommandSpotter.UNKNOWN, 2.45, 3.5)))
        assertFalse(s.judge(4.0).single().accepted)
    }

    @Test fun aSentenceStartingWithTheWordIsRejected() {
        val s = CommandSpotter(both)
        s.bookPlaying = false
        s.feed(signal(4.0, Triple(2.0, 3.8, 0.05f)))
        s.hypothesis(listOf(Word("stop", 2.0, 2.4), Word(CommandSpotter.UNKNOWN, 2.45, 3.8)))
        assertFalse(s.judge(4.0).single().accepted)
    }

    /** "… the sample is small. — continue": the comment's speech, a pause, the same voice. */
    @Test fun aPauseBeforeTheWordIsEnoughWhenTheBookIsNotPlaying() {
        val s = CommandSpotter(both)
        s.bookPlaying = false
        val x = signal(4.0, Triple(0.5, 2.0, 0.05f), Triple(2.5, 3.0, 0.05f))
        s.feed(x)
        s.hypothesis(listOf(Word("continue", 2.5, 3.0)))
        assertTrue(s.judge(4.0).single().accepted)

        // while the book plays the same pause is not enough: it looks like narration
        val playing = CommandSpotter(both)
        playing.feed(x)
        playing.hypothesis(listOf(Word("continue", 2.5, 3.0)))
        assertFalse(playing.judge(4.0).single().accepted)
    }

    @Test fun waitsUntilWhatFollowsHasBeenHeard() {
        val s = CommandSpotter(both)
        s.feed(signal(2.5, Triple(2.0, 2.4, 0.05f)))
        s.hypothesis(listOf(Word("stop", 2.0, 2.4)))
        assertTrue(s.judge(2.5).isEmpty())
        s.feed(FloatArray(RATE), 2.5)
        assertTrue(s.judge(3.5).single().accepted)
    }

    @Test fun aRevisedResultFiresOnce() {
        val s = CommandSpotter(both)
        s.feed(signal(4.0, Triple(2.0, 2.4, 0.05f)))
        s.hypothesis(listOf(Word("stop", 2.0, 2.3)))
        s.hypothesis(listOf(Word("stop", 2.0, 2.4)))
        assertEquals(1, s.judge(4.0).count { it.accepted })
        assertTrue(s.judge(5.0).isEmpty())
    }

    @Test fun theTwoWordPhraseWinsOverItsLastWord() {
        val s = CommandSpotter(both)
        s.feed(signal(4.0, Triple(2.0, 2.7, 0.05f)))
        s.hypothesis(listOf(Word("go", 2.0, 2.2), Word("back", 2.3, 2.7)))
        val accepted = s.judge(4.0).filter { it.accepted }
        assertEquals(listOf("go back"), accepted.map { it.text })
    }

    @Test fun readsGermanSpelling() {
        val s = CommandSpotter(both)
        s.feed(signal(4.0, Triple(2.0, 2.4, 0.05f)))
        s.hypothesis(listOf(Word("zurück", 2.0, 2.4)))
        assertEquals(VoiceCommand.BACK, s.judge(4.0).single().command)
    }

    @Test fun keepsOnlyWordsOfTheGivenLanguages() {
        val s = CommandSpotter(setOf(ENGLISH))
        s.feed(signal(4.0, Triple(2.0, 2.4, 0.05f)))
        s.hypothesis(listOf(Word("weiter", 2.0, 2.4)))
        assertTrue(s.judge(4.0).isEmpty())
    }

    @Test fun ignoresAudioOlderThanItRemembers() {
        val s = CommandSpotter(both)
        s.feed(FloatArray(RATE * 10))
        s.feed(signal(0.5, Triple(0.0, 0.4, 0.05f)), 10.0)
        s.hypothesis(listOf(Word("stop", 10.0, 10.4)))
        s.feed(FloatArray(RATE), 10.5)
        assertTrue(s.judge(11.5).single().accepted)
    }
}

private fun rms(x: ShortArray, from: Int, to: Int): Double {
    var sum = 0.0
    for (i in from until minOf(to, x.size)) sum += x[i].toDouble() * x[i].toDouble()
    return sqrt(sum / max(1, to - from)) / 32768
}

class RecognizerFrontEndTests {
    private fun run(f: RecognizerFrontEnd, x: FloatArray): ShortArray {
        val out = ArrayList<Short>()
        var i = 0
        while (i < x.size) {
            val n = minOf(1024, x.size - i)
            out += f.process(x.copyOfRange(i, i + n), n).toList()
            i += n
        }
        return out.toShortArray()
    }

    @Test fun outputStaysOnTheInputTimeline() {
        val f = RecognizerFrontEnd()
        f.gating = false
        val x = signal(3.0, Triple(2.0, 2.4, 0.2f))
        val y = run(f, x)
        // silent before, loud during (the output's sample numbers are the input's)
        assertTrue(rms(y, (1.8 * RATE).toInt(), (1.99 * RATE).toInt()) < 0.01)
        assertTrue(rms(y, (2.1 * RATE).toInt(), (2.3 * RATE).toInt()) > 0.1)
    }

    @Test fun raisesAQuietVoice() {
        val f = RecognizerFrontEnd()
        f.gating = false
        val y = run(f, signal(3.0, Triple(2.0, 2.4, 0.005f)))
        val level = rms(y, (2.1 * RATE).toInt(), (2.3 * RATE).toInt())
        assertTrue("level $level", level > 0.05)
    }

    @Test fun neverClipsALoudVoice() {
        val f = RecognizerFrontEnd()
        f.gating = false
        val y = run(f, signal(3.0, Triple(2.0, 2.4, 0.5f)))
        assertTrue(y.maxOf { abs(it.toInt()) } < 32000)
    }

    @Test fun turnsDownTheEchoAndKeepsTheVoiceAboveIt() {
        val f = RecognizerFrontEnd()
        val y = run(f, signal(4.0, Triple(0.0, 4.0, 0.02f), Triple(3.0, 3.4, 0.11f)))
        val echo = rms(y, (2.5 * RATE).toInt(), (2.9 * RATE).toInt())
        val voice = rms(y, (3.1 * RATE).toInt(), (3.3 * RATE).toInt())
        // 15 dB apart at the microphone, much further for the recognizer
        assertTrue("echo $echo voice $voice", voice > echo * 10)
    }

    @Test fun passesEverythingWhenNotGating() {
        val f = RecognizerFrontEnd()
        f.gating = false
        val y = run(f, signal(4.0, Triple(0.0, 4.0, 0.05f)))
        assertTrue(rms(y, (3.0 * RATE).toInt(), (3.5 * RATE).toInt()) > 0.03)
    }
}
