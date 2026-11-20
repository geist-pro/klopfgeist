package de.kaikunze.marginvoice.core

import de.kaikunze.marginvoice.core.UtteranceDetector.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class UtteranceDetectorTests {
    private val rate = 16_000.0
    private val random = Random(7)

    private fun tone(seconds: Double, amplitude: Float = 0.3f) = FloatArray((seconds * rate).toInt()) { amplitude * sin(it * 0.2f) }
    private fun noise(seconds: Double, amplitude: Float = 0.001f) =
        FloatArray((seconds * rate).toInt()) { (random.nextFloat() * 2 - 1) * amplitude }

    @Test fun detectsIsolatedUtterance() {
        val d = UtteranceDetector(rate)
        val events = d.append(noise(1.0)) + d.append(tone(0.5)) + d.append(noise(1.0))
        assertEquals(2, events.size)
        val s = events[0] as Event.Started
        val e = events[1] as Event.Ended
        assertTrue(abs(s.time - 1) < 0.05)
        assertTrue(s.pauseBefore > 0.9)
        assertTrue(abs(e.time - 1.5) < 0.05)
        assertTrue(abs(e.duration - 0.5) < 0.05)
    }

    @Test fun chunkingDoesNotMatter() {
        val signal = noise(0.5) + tone(0.4) + noise(0.6)
        val whole = UtteranceDetector(rate).append(signal)
        val b = UtteranceDetector(rate)
        val pieces = ArrayList<Event>()
        var i = 0
        while (i < signal.size) {
            val n = minOf(517, signal.size - i)
            pieces += b.append(signal.copyOfRange(i, i + n))
            i += n
        }
        assertEquals(whole, pieces)
    }

    @Test fun shortGapKeepsOneUtterance() {
        val d = UtteranceDetector(rate)
        val events = d.append(noise(0.5) + tone(0.3) + noise(0.15) + tone(0.3) + noise(0.5))
        assertEquals(1, events.count { it is Event.Started })
    }
}
