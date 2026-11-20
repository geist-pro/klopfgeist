package de.kaikunze.marginvoice.core

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Energy-based speech segmentation with an adaptive noise floor.
 *
 * Time comes from sample counts, never a clock, so every decision is a pure
 * function of the samples (same approach as ai-Memo's `TranscriptionWindow`).
 * It reports when an utterance starts and ends. `CommandDetector` uses that only as a
 * hint that someone is still speaking; the recorder uses it to notice speech and silence.
 */
class UtteranceDetector(val sampleRate: Double, val policy: Policy = Policy()) {
    data class Policy(
        val frameDuration: Double = 0.02,
        /** Consecutive speech frames needed to start an utterance. */
        val onsetFrames: Int = 3,
        /** Quiet time that ends an utterance. */
        val endSilence: Double = 0.3,
        val absoluteFloor: Float = 1e-5f,
        /** A frame is speech when its energy exceeds `noiseFloor * multiplier`. */
        val multiplier: Float = 6f,
    )

    sealed interface Event {
        /** `time` is seconds since the detector started; `pauseBefore` is the silence before it. */
        data class Started(val time: Double, val pauseBefore: Double) : Event
        data class Ended(val time: Double, val duration: Double) : Event
    }

    var noiseFloor: Float = policy.absoluteFloor
        private set
    var inUtterance = false
        private set
    /** Seconds of silence since the last speech frame. */
    var currentSilence = 0.0
        private set

    private val frameSize = max(1, (sampleRate * policy.frameDuration).roundToInt())
    private var pending = FloatArray(0)
    private var processedFrames = 0
    private var speechRun = 0
    private var quietRun = 0
    private var utteranceStart = 0.0
    private var lastEnd = 0.0

    val time: Double get() = processedFrames * policy.frameDuration

    fun append(samples: FloatArray, count: Int = samples.size): List<Event> {
        val buffer = if (pending.isEmpty()) samples.copyOf(count) else pending + samples.copyOf(count)
        val events = ArrayList<Event>(1)
        val n = frameSize
        var consumed = 0
        while (buffer.size - consumed >= n) {
            var energy = 0f
            for (i in consumed until consumed + n) energy += buffer[i] * buffer[i]
            energy /= n.toFloat()
            consumed += n
            classify(energy)?.let(events::add)
        }
        pending = buffer.copyOfRange(consumed, buffer.size)
        return events
    }

    private fun classify(energy: Float): Event? {
        processedFrames++
        val threshold = max(policy.absoluteFloor, noiseFloor * policy.multiplier)
        val isSpeech = energy > threshold
        if (isSpeech) {
            speechRun++
            quietRun = 0
            // very slow drift so a raised floor (e.g. residual echo) can recover
            noiseFloor = noiseFloor * 0.999f + energy / policy.multiplier * 0.001f
        } else {
            speechRun = 0
            quietRun++
            noiseFloor = max(policy.absoluteFloor, noiseFloor * 0.95f + energy * 0.05f)
        }
        currentSilence = if (isSpeech) 0.0 else currentSilence + policy.frameDuration

        if (!inUtterance && speechRun >= policy.onsetFrames) {
            inUtterance = true
            val start = time - policy.onsetFrames * policy.frameDuration
            // silence before = time since the previous utterance ended (or since start)
            val pauseBefore = start - lastEnd
            utteranceStart = start
            return Event.Started(start, max(0.0, pauseBefore))
        }
        if (inUtterance && quietRun * policy.frameDuration >= policy.endSilence) {
            inUtterance = false
            val end = time - quietRun * policy.frameDuration
            lastEnd = end
            return Event.Ended(end, end - utteranceStart)
        }
        return null
    }
}
