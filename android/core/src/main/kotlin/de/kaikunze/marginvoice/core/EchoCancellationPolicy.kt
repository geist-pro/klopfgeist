package de.kaikunze.marginvoice.core

/**
 * When the command microphone cancels the book's echo (iOS: `wantsEchoCancellation`
 * and `setVoiceProcessing` in MicrophoneController). Turning it on or off rebuilds the
 * microphone, so after a few toggles in a row it stays off: a book on the speaker
 * without echo cancellation still works, a microphone rebuilt every second does not.
 */
class EchoCancellationPolicy(private val window: Double = 30.0, private val maxToggles: Int = 4) {
    private val toggles = ArrayDeque<Double>()

    /**
     * Records a toggle at [now] (seconds). False when turning it on is refused because
     * the route keeps changing; turning it off is always allowed.
     */
    fun mayToggle(on: Boolean, now: Double): Boolean {
        while (toggles.isNotEmpty() && now - toggles.first() >= window) toggles.removeFirst()
        if (on && toggles.size >= maxToggles) return false
        toggles.addLast(now)
        return true
    }

    companion object {
        /**
         * Only for the book heard from the phone's own speaker through the built-in
         * microphone, and only once the book has played: before that there is no book
         * to cancel, and the voice-call input would only cost recognition accuracy.
         */
        fun wants(enabled: Boolean, hasPlayed: Boolean, builtInMicrophone: Boolean, speakerOnly: Boolean): Boolean =
            enabled && hasPlayed && builtInMicrophone && speakerOnly
    }
}
