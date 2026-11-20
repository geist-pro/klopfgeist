package de.kaikunze.marginvoice.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * A bounded automatic gain for the command recognizer's input, so a quiet voice at
 * arm's length reaches it at the same level as a loud one next to the phone. The
 * recognition input of a phone has no gain control of its own.
 *
 * Per 10 ms frame it is told how loud the signal is about to be and answers with the
 * gain for the frame leaving now:
 * - the loudest of the coming frames and the last half second is brought to [Policy.target];
 * - never more than [Policy.maximumGain], and never so much that the room's steady
 *   noise (or the book's steady echo) would come out above [Policy.noiseCeiling];
 * - never so much that a sample would pass [Policy.ceiling]: it falls at once (the
 *   caller looks ahead, so before the loud sample arrives) and rises slowly.
 *
 * The archived app followed the loudest frame with a release of several seconds
 * instead: after the record-start tone or a stretch of the book, a quiet "continue"
 * got next to no gain for a long while. Pure; one thread.
 */
class AutomaticGain(val policy: Policy = Policy()) {
    data class Policy(
        /** Level the loud part of speech is brought to (RMS of a frame, full scale 1): -20 dBFS. */
        val target: Float = 0.1f,
        /** The most a signal is raised: ×30, +29.5 dB (-50 dBFS speech reaches the target). */
        val maximumGain: Float = 30f,
        /** No sample leaves above this. */
        val ceiling: Float = 0.9f,
        /** Steady background is not raised above this (RMS): -40 dBFS. */
        val noiseCeiling: Float = 0.01f,
        /** Frames a level is remembered, so the gain holds through the gaps inside a phrase: 0.5 s. */
        val holdFrames: Int = 50,
        /** Frames the background is judged over: 3 s. */
        val noiseFrames: Int = 300,
        /** Share of the way to a higher gain taken per frame: about 0.2 s to get there. */
        val rise: Float = 0.05f,
    )

    /** The gain of the frame that left last. */
    var gain = 1f
        private set

    val gainDb: Double get() = 20 * log10(gain.toDouble())

    private val recent = FloatArray(policy.noiseFrames)
    private var count = 0L
    private val sorted = FloatArray(policy.noiseFrames)

    fun reset() {
        gain = 1f
        count = 0
    }

    /**
     * @param coming RMS of the loudest frame from the one leaving now to the end of the look-ahead.
     * @param peak largest absolute sample in those frames.
     * @param level RMS of the frame leaving now.
     * @return the gain for the frame leaving now.
     */
    fun next(coming: Float, peak: Float, level: Float): Float {
        var loudest = coming
        val held = min(count, policy.holdFrames.toLong()).toInt()
        for (i in 1..held) loudest = max(loudest, recent[((count - i) % recent.size).toInt()])
        var wanted = policy.target / max(loudest, 1e-6f)
        wanted = min(wanted, min(policy.maximumGain, policy.noiseCeiling / max(background(), 1e-6f)))
        wanted = max(1f, wanted)
        // whatever the levels say: no sample above the ceiling
        if (peak * wanted > policy.ceiling) wanted = policy.ceiling / peak
        gain = if (wanted < gain) wanted else gain + (wanted - gain) * policy.rise
        recent[(count % recent.size).toInt()] = level
        count++
        return gain
    }

    /** The steady part of what was heard lately: the level a tenth of the frames stay under. */
    private fun background(): Float {
        val n = min(count, recent.size.toLong()).toInt()
        if (n == 0) return 0f
        for (i in 0 until n) sorted[i] = recent[((count - 1 - i) % recent.size).toInt()]
        java.util.Arrays.sort(sorted, 0, n)
        // In the first half second there is too little to take a tenth of: the quietest frame
        // so far stands in, so the gain does not run up to its cap on a noisy room meanwhile.
        return if (n < policy.holdFrames) sorted[0] else sorted[n / 10]
    }
}
