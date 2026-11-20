package de.kaikunze.marginvoice.core

/**
 * Places a streaming recognizer's word times on the microphone timeline.
 *
 * A recognizer counts time in seconds of audio it was fed. Its zero is therefore the
 * microphone time of the first buffer it actually accepted, not the moment it was
 * asked to start: building it takes tens to hundreds of milliseconds, and words
 * placed by the earlier moment would all be early by that much (a "stop" anchored
 * before it was said, pauses measured against the wrong speech events). Pure.
 */
class RecognizerTimeline(private val sampleRate: Int) {
    /** Microphone time of the first sample the recognizer accepted; null until it accepted one. */
    var origin: Double? = null
        private set
    private var fed = 0L

    /** The recognizer accepts `count` samples that start at microphone time `micTime`. */
    fun accept(micTime: Double, count: Int) {
        if (origin == null) origin = micTime
        fed += count
    }

    /** Microphone time of the end of what was fed so far. */
    val now: Double get() = (origin ?: 0.0) + fed.toDouble() / sampleRate

    /** Microphone time at which the buffer accepted next begins. */
    val next: Double get() = now

    /** A recognizer time (seconds into what it was fed) on the microphone timeline. */
    fun micTime(recognizerSeconds: Double): Double = (origin ?: 0.0) + recognizerSeconds
}
