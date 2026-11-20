package de.kaikunze.marginvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The recognizer's input level: a quiet voice at arm's length (about -45 dBFS at a
 * phone's recognition input) has to arrive like a normal one. Synthetic signals:
 * speech-like bursts (syllables of a harmonic tone, 4 per second) in room noise.
 */
class AutomaticGainTests {
    private val rate = 16_000

    private fun amplitude(dbfs: Double) = 10.0.pow(dbfs / 20).toFloat()

    /** Room noise at `dbfs` RMS for `seconds`. */
    private fun noise(seconds: Double, dbfs: Double, seed: Int = 1): FloatArray {
        val r = Random(seed)
        val a = amplitude(dbfs) * sqrt(3f)
        return FloatArray((seconds * rate).toInt()) { (r.nextFloat() * 2 - 1) * a }
    }

    /** Adds a phrase of syllables (150 ms on, 100 ms off) whose loud parts are at `dbfs` RMS. */
    private fun FloatArray.speak(from: Double, to: Double, dbfs: Double): FloatArray {
        val a = amplitude(dbfs) * sqrt(2f) / sqrt(1 + 0.25f + 0.09f)
        for (i in (from * rate).toInt() until (to * rate).toInt()) {
            val t = (i - from * rate) / rate
            val inSyllable = t % 0.25 < 0.15
            if (!inSyllable) continue
            // soft edges, as a voice has
            val edge = minOf(1.0, (t % 0.25) / 0.02, (0.15 - t % 0.25) / 0.02)
            this[i] += (a * edge * (sin(2 * PI * 180 * i / rate) + 0.5 * sin(2 * PI * 360 * i / rate) + 0.3 * sin(2 * PI * 1250 * i / rate))).toFloat()
        }
        return this
    }

    private fun FloatArray.tone(from: Double, to: Double, dbfs: Double, hz: Double = 784.0): FloatArray {
        val a = amplitude(dbfs) * sqrt(2f)
        for (i in (from * rate).toInt() until (to * rate).toInt()) this[i] += (a * sin(2 * PI * hz * i / rate)).toFloat()
        return this
    }

    private fun run(x: FloatArray, f: RecognizerFrontEnd = RecognizerFrontEnd().apply { gating = false }): ShortArray {
        val out = ArrayList<Short>()
        var i = 0
        while (i < x.size) {
            val n = minOf(1024, x.size - i)
            out += f.process(x.copyOfRange(i, i + n), n).toList()
            i += n
        }
        return out.toShortArray()
    }

    /** Level of the loud frames (90th percentile of 20 ms frames) between two times, dBFS. */
    private fun loud(y: ShortArray, from: Double, to: Double): Double {
        val frames = ArrayList<Double>()
        var i = (from * rate).toInt()
        while (i + 320 <= minOf((to * rate).toInt(), y.size)) {
            var sum = 0.0
            for (k in i until i + 320) sum += (y[k] / 32768.0).pow(2)
            frames.add(sqrt(sum / 320))
            i += 320
        }
        frames.sort()
        return 20 * log10(frames[(frames.size - 1) * 9 / 10] + 1e-9)
    }

    @Test fun aQuietVoiceReachesTheRecognizerAtAUsableLevel() {
        // -45, -38 and -30 dBFS in a quiet room: all of them arrive within a few dB of -20 dBFS
        for (voice in listOf(-45.0, -38.0, -30.0)) {
            val y = run(noise(5.0, -72.0).speak(3.0, 3.9, voice))
            val level = loud(y, 3.05, 3.9)
            assertTrue("voice at $voice dBFS arrives at $level dBFS", level > -24 && level < -15)
        }
    }

    @Test fun quietAndLoudVoicesArriveAlike() {
        val quiet = loud(run(noise(5.0, -72.0).speak(3.0, 3.9, -45.0)), 3.05, 3.9)
        val normal = loud(run(noise(5.0, -72.0).speak(3.0, 3.9, -25.0)), 3.05, 3.9)
        assertEquals(normal, quiet, 3.0)
    }

    @Test fun inAnOrdinaryRoomAQuietVoiceIsStillRaisedAsFarAsTheNoiseAllows() {
        // room noise at -58 dBFS may come up to -40: 18 dB for the voice, from -45 to about -27
        val y = run(noise(6.0, -58.0).speak(4.0, 4.9, -45.0))
        val level = loud(y, 4.05, 4.9)
        assertTrue("arrives at $level dBFS", level > -30 && level < -20)
    }

    @Test fun noAbsoluteFloorKeepsAVeryQuietVoiceOut() {
        // -52 dBFS: below what the old energy detector counted as speech; the gain is at its cap, the voice comes through raised
        val y = run(noise(5.0, -80.0).speak(3.0, 3.9, -52.0))
        val level = loud(y, 3.05, 3.9)
        assertTrue("arrives at $level dBFS", level > -25)
    }

    @Test fun aQuietWordRightAfterALoudSoundIsNotLeftQuiet() {
        // the record-start tone through the speaker (-15 dBFS), and "continue" a second later at -42 dBFS.
        // The archived front end remembered the tone for many seconds and gave the word almost no gain.
        val y = run(noise(6.0, -72.0).tone(3.0, 3.3, -15.0).speak(4.3, 5.0, -42.0))
        val level = loud(y, 4.35, 5.0)
        assertTrue("arrives at $level dBFS", level > -24)
    }

    @Test fun aQuietWordRightAfterARecognizerRestartIsRaisedLikeBefore() {
        // the recognizer restarts on the same microphone (another language, a repair): what was
        // learned about the room and the voice stays, so "stop" said a tenth of a second later
        // arrives as loud as it would have before
        val front = RecognizerFrontEnd().apply { gating = false }
        run(noise(3.0, -72.0).speak(1.0, 1.8, -42.0), front)
        val before = front.gainDb
        assertTrue("gain before the restart $before dB", before > 15)
        front.restart()
        assertEquals("the gain is carried over", before, front.gainDb, 0.0)
        val y = run(noise(1.5, -72.0, seed = 2).speak(0.1, 0.9, -42.0), front)
        val kept = loud(y, 0.15, 0.9)
        assertTrue("arrives at $kept dBFS", kept > -24)
        // and output sample 0 is the first sample after the restart, not a leftover of the old stream
        assertEquals(1.5 * rate - 8 * 160, y.size.toDouble(), 160.0)

        // a new microphone starts from a gain of 1 and has to climb first
        val fresh = RecognizerFrontEnd().apply { gating = false }
        fresh.process(FloatArray(160), 160)
        assertEquals(0.0, fresh.gainDb, 0.01)
        val cold = loud(run(noise(1.5, -72.0, seed = 2).speak(0.1, 0.9, -42.0), fresh), 0.15, 0.4)
        assertTrue("kept state $kept dBFS, cold start $cold dBFS", kept > cold)
    }

    @Test fun atStartTheGainDoesNotRunUpOnARoomsNoise() {
        // the first half second on a microphone in an ordinary room (-52 dBFS): the noise is not
        // lifted above -40 dBFS while the background is still being learned
        val y = run(noise(2.0, -52.0))
        val level = loud(y, 0.1, 0.6)
        assertTrue("noise comes out at $level dBFS", level < -38.0)
    }

    @Test fun theGainIsCapped() {
        val policy = AutomaticGain.Policy()
        val gain = AutomaticGain()
        // near silence for a long time: it stops at the cap
        repeat(1000) { gain.next(coming = 1e-5f, peak = 3e-5f, level = 1e-5f) }
        assertEquals(policy.maximumGain, gain.gain, 0.01f)
        assertEquals(29.5, gain.gainDb, 0.1)
    }

    @Test fun steadyNoiseIsNotPumpedUp() {
        // noise alone at -52 dBFS: not raised above -40 dBFS
        val y = run(noise(8.0, -52.0))
        val level = loud(y, 5.0, 8.0)
        assertTrue("noise comes out at $level dBFS", level < -38.5)
    }

    @Test fun nothingClips() {
        // a quiet stretch (the gain is high), then a shout without warning
        val x = noise(5.0, -70.0).speak(2.0, 2.6, -45.0).speak(3.0, 3.6, -3.0)
        val y = run(x)
        assertTrue("peak ${y.maxOf { abs(it.toInt()) }}", y.maxOf { abs(it.toInt()) } <= (0.9 * 32767).toInt() + 1)
        // and the shout is still there, just not louder than full scale
        assertTrue(loud(y, 3.05, 3.6) > -12)
    }

    @Test fun theGainHoldsThroughTheGapsOfAPhrase() {
        // no pumping between syllables: the gain moves by less than 1 dB once the phrase has begun
        val front = RecognizerFrontEnd().apply { gating = false }
        val x = noise(5.0, -72.0).speak(3.0, 4.5, -40.0)
        val gains = ArrayList<Double>()
        var i = 0
        while (i < x.size) {
            val n = minOf(160, x.size - i)
            front.process(x.copyOfRange(i, i + n), n)
            // the gain applied to the frame 80 ms back
            if (i / rate.toDouble() in 3.2..4.5) gains.add(front.gainDb)
            i += n
        }
        assertTrue("gain moved ${gains.max() - gains.min()} dB", gains.max() - gains.min() < 1.0)
        assertEquals(20.0, gains.average(), 2.5)
    }

    @Test fun theGainRisesSlowlyAndFallsAtOnce() {
        val gain = AutomaticGain()
        repeat(100) { gain.next(0.1f, 0.14f, 0.1f) }
        assertEquals(1f, gain.gain, 0.01f)
        // quiet now: a twentieth of the way per frame once the last half second is forgotten
        repeat(50) { gain.next(0.005f, 0.007f, 0.005f) }
        assertEquals("held for half a second", 1f, gain.gain, 0.01f)
        gain.next(0.005f, 0.007f, 0.005f)
        val step = gain.gain - 1f
        assertTrue("first step $step", step > 0 && step < 1.5f)
        repeat(100) { gain.next(0.005f, 0.007f, 0.005f) }
        val high = gain.gain
        assertTrue(high > 1.5f)
        // something loud is coming: down in one frame
        gain.next(0.2f, 0.28f, 0.005f)
        assertEquals(1f, gain.gain, 0.01f)
    }

    @Test fun outputIsInputTimesGainSampleForSample() {
        // sample in, sample out: output sample i is input sample i (80 ms later), scaled
        val front = RecognizerFrontEnd().apply { gating = false }
        val x = FloatArray(rate * 2) { i -> 0.05f * sin(2 * PI * 200 * i / rate).toFloat() }
        val y = run(x, front)
        assertEquals(x.size - 8 * 160, y.size)
        // a steady tone is "background": passed at its own level
        for (i in rate until rate + 400) assertEquals(x[i] * 32767, y[i].toFloat(), 40f)
    }

    @Test fun onTheSpeakerTheTurnedDownEchoIsNotRaisedAgain() {
        // the book's echo at -37 dBFS all through, a voice 12 dB above it; gating on (speaker, book playing)
        val front = RecognizerFrontEnd()
        val x = FloatArray(rate * 5).tone(0.0, 5.0, -37.0, 220.0).speak(4.0, 4.6, -25.0)
        val y = run(x, front)
        val echo = loud(y, 3.0, 3.8)
        val voice = loud(y, 4.05, 4.6)
        assertTrue("echo $echo voice $voice", voice - echo > 30)
    }
}
