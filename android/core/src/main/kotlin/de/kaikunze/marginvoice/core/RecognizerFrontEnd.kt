package de.kaikunze.marginvoice.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Prepares microphone audio for the command recognizer, so a command said at a normal
 * or quiet volume reaches it clearly: the voice is brought to one level ([AutomaticGain];
 * the recognition input of a phone has no gain control), and, only while [gating] is on
 * (the book plays on the loudspeaker, see [CommandGatePolicy]), what is no louder than
 * the sound of the last moments (the book's echo) is turned down.
 *
 * Output sample i is input sample i; it comes out [delay] seconds later, which lets the
 * gain open before a word begins. The recognizer's word times therefore stay on the
 * input's timeline. Only for the recognizer: comments are recorded from the raw input.
 */
class RecognizerFrontEnd(sampleRate: Int = 16_000) {
    /** Turn down the background: on while the book plays into the microphone, off when the only voice is the listener's. */
    @Volatile var gating = true

    /** The gain the recognizer's input has right now, dB (for the command log). */
    val gainDb: Double get() = leveler.gainDb

    private val frameSize = sampleRate / 100
    private val pending = FloatArray(frameSize)
    private var filled = 0
    private val queue = ArrayDeque<Frame>()
    private val history = ArrayDeque<Float>()
    private var holdLeft = 0
    private var gate = closedGain
    private val leveler = AutomaticGain()
    private val sorted = FloatArray(HISTORY_FRAMES)

    private class Frame(val samples: FloatArray, val level: Float, val peak: Float)

    val delay: Double get() = LOOKAHEAD_FRAMES / 100.0

    /**
     * A new recognizer takes over on the same microphone (another language, a restart):
     * audio still on its way through belonged to the old one and is dropped, so output
     * sample 0 is again the next input sample. What was learned about the room and the
     * voice (the gain, the background) stays: a word said right after the restart is
     * raised like one said before it. A new microphone gets a new front end instead.
     */
    fun restart() {
        filled = 0
        queue.clear()
    }

    /** Mono samples, -1...1. Returns 16-bit PCM (possibly empty, at most a little more than went in). */
    fun process(samples: FloatArray, count: Int): ShortArray {
        val out = ArrayList<FloatArray>()
        var i = 0
        while (i < count) {
            val n = min(count - i, frameSize - filled)
            samples.copyInto(pending, filled, i, i + n)
            filled += n
            i += n
            if (filled == frameSize) {
                filled = 0
                frame()?.let(out::add)
            }
        }
        val pcm = ShortArray(out.size * frameSize)
        var at = 0
        for (f in out) for (s in f) pcm[at++] = (s.coerceIn(-1f, 1f) * 32767).toInt().toShort()
        return pcm
    }

    private fun frame(): FloatArray? {
        var sum = 0f
        var top = 0f
        for (s in pending) {
            sum += s * s
            top = max(top, abs(s))
        }
        queue.addLast(Frame(pending.copyOf(), sqrt(sum / frameSize) + 1e-7f, top))
        if (queue.size <= LOOKAHEAD_FRAMES) return null
        var coming = 0f
        var peak = 0f
        for (f in queue) {
            coming = max(coming, f.level)
            peak = max(peak, f.peak)
        }
        val current = queue.removeFirst()
        var g = 1f
        if (gating) {
            if (coming > background() * OPEN_RATIO && coming > MINIMUM_LEVEL) holdLeft = HOLD_FRAMES else if (holdLeft > 0) holdLeft--
            val target = if (holdLeft > 0) 1f else closedGain
            gate = if (target > gate) target else gate * 0.7f + target * 0.3f
            g = gate
        } else {
            gate = 1f
        }
        history.addLast(current.level)
        if (history.size > HISTORY_FRAMES) history.removeFirst()
        // the gain follows the signal as the microphone heard it, not the turned-down one:
        // the echo that was just lowered is not to be raised again
        g *= leveler.next(coming, peak, current.level)
        return FloatArray(frameSize) { current.samples[it] * g }
    }

    /** How loud the last moments were, leaving out the newest (the word itself may have begun). */
    private fun background(): Float {
        val n = history.size - RECENT_FRAMES
        if (n < 10) return 0f
        for (i in 0 until n) sorted[i] = history[i]
        java.util.Arrays.sort(sorted, 0, n)
        return sorted[(n - 1) * 9 / 10]
    }

    private companion object {
        const val LOOKAHEAD_FRAMES = 8
        const val HISTORY_FRAMES = 150
        const val RECENT_FRAMES = 15
        const val HOLD_FRAMES = 30
        const val OPEN_RATIO = 2f // 6 dB
        const val MINIMUM_LEVEL = 2e-4f
        val closedGain = 10f.pow(-30 / 20f)
    }
}
