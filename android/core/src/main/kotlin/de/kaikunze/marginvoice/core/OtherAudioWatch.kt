package de.kaikunze.marginvoice.core

/**
 * Decides from repeated looks at the system whether another app is really playing.
 * Sounds of our own (an earcon, the tail of the paused book) and a short system
 * sound don't count: the other audio has to last.
 */
class OtherAudioWatch {
    /** How long the other audio has to last before it counts. */
    var settle = 2.0
    /** How long after a sound of our own the system's answer can't be trusted. */
    var ownSoundMask = 1.5

    var isPlaying = false
        private set
    private var activeSince: Double? = null
    private var maskedUntil = Double.NEGATIVE_INFINITY

    /** A sound of our own just played (or the book just paused). */
    fun ownSound(now: Double) {
        maskedUntil = now + ownSoundMask
        activeSince = null
    }

    /** Returns whether another app is playing now. */
    fun update(active: Boolean, now: Double): Boolean {
        if (!active) {
            activeSince = null
            isPlaying = false
            return false
        }
        if (isPlaying) return true
        if (now < maskedUntil) return false
        val since = activeSince ?: now
        activeSince = since
        isPlaying = now - since >= settle
        return isPlaying
    }

    fun reset() {
        activeSince = null
        isPlaying = false
        maskedUntil = Double.NEGATIVE_INFINITY
    }
}
