package de.kaikunze.marginvoice.core

/**
 * Where playback resumes so that it starts after a pause in the narration. Sentence times are
 * interpolated inside a timing chunk (a chunk can be one 20 s sentence), so a resume point taken
 * from them often lands inside a word: a fragment, the pause, then the text. The audio knows better.
 * Pinned by `spec/vectors/quiet-point.json`.
 */
object QuietPoint {
    /** Length of one loudness frame, seconds. */
    const val FRAME = 0.02
    /** How far before and after the wanted time a pause may end. */
    const val BEFORE = 3.0
    const val AFTER = 1.0
    const val MIN_PAUSE = 0.15
    /** A frame is quiet below this share of the loudest frame (about −30 dB). */
    const val QUIET_SHARE = 0.03
    /** Playback starts this long before the pause ends. */
    const val LEAD = 0.1

    /**
     * @param levels linear loudness (RMS) of consecutive [FRAME]s, the first starting at `start`
     * @return the time to resume at, or null when no pause ends near `target`
     */
    fun find(levels: DoubleArray, start: Double, target: Double): Double? {
        val peak = levels.maxOrNull() ?: return null
        if (peak <= 0) return null
        val quiet = peak * QUIET_SHARE
        var best: Double? = null
        var bestDistance = Double.MAX_VALUE
        var i = 0
        while (i < levels.size) {
            if (levels[i] >= quiet) { i++; continue }
            var j = i
            while (j < levels.size && levels[j] < quiet) j++
            // a pause still running at the end of the window has no end to start from
            if (j < levels.size) {
                val from = start + i * FRAME
                val to = start + j * FRAME
                val distance = kotlin.math.abs(to - target)
                if (to - from >= MIN_PAUSE - 1e-9 && to >= target - BEFORE && to <= target + AFTER + 1e-9 && distance < bestDistance) {
                    best = maxOf(from, to - LEAD)
                    bestDistance = distance
                }
            }
            i = j
        }
        return best
    }

    /** RMS per [FRAME] of 16-bit mono samples. */
    fun levels(samples: ShortArray, sampleRate: Int): DoubleArray {
        val size = (sampleRate * FRAME).toInt()
        if (size <= 0) return DoubleArray(0)
        return DoubleArray(samples.size / size) { f ->
            var sum = 0.0
            for (k in f * size until (f + 1) * size) sum += samples[k].toDouble() * samples[k]
            kotlin.math.sqrt(sum / size)
        }
    }
}
