package de.kaikunze.marginvoice.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Where the book comes out and which microphone listens, as the platform reports it. */
@Serializable
data class AudioRoute(
    val outputs: List<Port>,
    val input: Port?,
    /** A microphone other than the built-in one could be chosen (AirPods, a headset, a car). */
    val headsetMicrophoneAvailable: Boolean = false,
) {
    @Serializable
    enum class Port {
        @SerialName("builtInSpeaker") BUILT_IN_SPEAKER,
        @SerialName("builtInReceiver") BUILT_IN_RECEIVER,
        @SerialName("builtInMic") BUILT_IN_MIC,
        @SerialName("wiredHeadphones") WIRED_HEADPHONES,
        @SerialName("wiredHeadset") WIRED_HEADSET,
        @SerialName("bluetoothA2DP") BLUETOOTH_A2DP,
        @SerialName("bluetoothHFP") BLUETOOTH_HFP,
        @SerialName("bluetoothLE") BLUETOOTH_LE,
        @SerialName("carAudio") CAR_AUDIO,
        @SerialName("airPlay") AIR_PLAY,
        @SerialName("usb") USB,
        @SerialName("hdmi") HDMI,
        @SerialName("other") OTHER,
    }

    /** The book plays into the room, where a microphone can hear it. */
    val isLoud: Boolean get() = outputs.any { it in loud }

    /** The book comes out of the device itself (or the car it is plugged into) and nothing else. */
    val isDeviceSpeaker: Boolean get() = outputs.isNotEmpty() && outputs.all { it in device }

    /** The built-in microphone can hear the book. */
    val microphoneHearsBook: Boolean get() = input == Port.BUILT_IN_MIC && isLoud

    companion object {
        /** Nothing connected: the device's own speaker and microphone. */
        val builtIn = AudioRoute(outputs = listOf(Port.BUILT_IN_SPEAKER), input = Port.BUILT_IN_MIC)

        private val loud = setOf(Port.BUILT_IN_SPEAKER, Port.CAR_AUDIO, Port.AIR_PLAY, Port.HDMI, Port.USB, Port.OTHER)
        private val device = setOf(Port.BUILT_IN_SPEAKER, Port.BUILT_IN_RECEIVER, Port.CAR_AUDIO)
    }
}

/** What the session wants from the audio system right now. */
@Serializable
data class AudioNeeds(
    val bookOpen: Boolean = false,
    /**
     * The open book has played: from then on the session is ours alone (no mixing),
     * so the lock screen and the headset buttons belong to the book.
     */
    val hasPlayed: Boolean = false,
    val voiceCommands: Boolean = false,
    /** Voice commands are on, but the microphone was let go while nobody used the book. */
    val listeningPaused: Boolean = false,
    val recording: Boolean = false,
    val microphonePermission: Boolean = false,
    /** The user chose the headset's call-quality microphone (cars, older headsets). */
    val useHeadsetMicrophone: Boolean = false,
)

/**
 * How the audio system should be set up. The platform adapter applies it; deciding
 * it is pure, so every combination is covered by `spec/vectors/audio-plan.json`.
 */
@Serializable
data class AudioPlan(
    val session: Session,
    /** Other apps keep playing (until the book first plays). */
    val mixWithOthers: Boolean,
    /** The microphone runs. */
    val capture: Boolean,
    /** The command recognizer runs on it. */
    val recognize: Boolean,
    /**
     * Take the book out of the microphone signal. Only when the built-in microphone
     * hears the device's own speaker: it would pull the book off Bluetooth headphones.
     */
    val echoCancellation: Boolean,
    val allowHeadsetCallMicrophone: Boolean,
    /** The recognizer may hear the book: commands are checked against what it just said. */
    val bookAudible: Boolean,
) {
    @Serializable
    enum class Session {
        @SerialName("inactive") INACTIVE,
        @SerialName("playback") PLAYBACK,
        @SerialName("playAndRecord") PLAY_AND_RECORD,
    }

    /** How much has to be rebuilt to get from one plan to another; ordered from least to most. */
    @Serializable
    enum class Change {
        @SerialName("none") NONE,
        /** Start or stop the recognizer; the microphone keeps running. */
        @SerialName("recognizer") RECOGNIZER,
        /** Session options only. */
        @SerialName("session") SESSION,
        /** The microphone (and with it the engine's input) is rebuilt. */
        @SerialName("capture") CAPTURE,
    }

    fun change(to: AudioPlan): Change {
        if (capture != to.capture || echoCancellation != to.echoCancellation || session != to.session) return Change.CAPTURE
        if (mixWithOthers != to.mixWithOthers || allowHeadsetCallMicrophone != to.allowHeadsetCallMicrophone) return Change.SESSION
        if (recognize != to.recognize) return Change.RECOGNIZER
        return Change.NONE
    }

    companion object {
        val inactive = AudioPlan(
            session = Session.INACTIVE, mixWithOthers = true, capture = false, recognize = false, echoCancellation = false,
            allowHeadsetCallMicrophone = false, bookAudible = false,
        )

        /** @param echoCancellationAllowed false once the route kept flapping (see [AudioCoordinator]). */
        fun make(needs: AudioNeeds, route: AudioRoute, echoCancellationAllowed: Boolean = true): AudioPlan {
            if (!needs.bookOpen) return inactive
            val listen = needs.voiceCommands && !needs.listeningPaused
            val capture = (listen || needs.recording) && needs.microphonePermission
            // Before the book has played there is nothing to cancel, and echo cancellation
            // (voice-chat mode) would stop other apps' audio even in a mixing session.
            val cancel = capture && needs.hasPlayed && echoCancellationAllowed &&
                route.input == AudioRoute.Port.BUILT_IN_MIC && route.isDeviceSpeaker
            return AudioPlan(
                session = if (capture) Session.PLAY_AND_RECORD else Session.PLAYBACK,
                mixWithOthers = !needs.hasPlayed,
                capture = capture,
                recognize = capture && listen,
                echoCancellation = cancel,
                allowHeadsetCallMicrophone = capture && needs.useHeadsetMicrophone,
                bookAudible = capture && route.microphoneHearsBook,
            )
        }
    }
}

/**
 * Decides when the audio setup is rebuilt.
 *
 * The microphone and recognizer are expensive to rebuild, and a command said during
 * a rebuild is lost. So: a route that is still moving (AirPods connect in several
 * steps) is waited out and applied once, a comment keeps its microphone until it is
 * saved, and a route that flaps stops toggling echo cancellation.
 */
class AudioCoordinator(var policy: Policy = Policy()) {
    @Serializable
    enum class RouteReason {
        @SerialName("deviceArrived") DEVICE_ARRIVED,
        @SerialName("deviceLeft") DEVICE_LEFT,
        @SerialName("other") OTHER,
    }

    data class Policy(
        /** Quiet time after the last route change before acting on it. */
        val settle: Double = 0.6,
        /** At most this many times may echo cancellation be turned on within `toggleWindow`. */
        val toggleLimit: Int = 4,
        val toggleWindow: Double = 30.0,
    )

    data class Action(val plan: AudioPlan, val change: AudioPlan.Change)

    var needs = AudioNeeds()
        private set
    var route = AudioRoute.builtIn
        private set
    /** What the adapter last applied. */
    var applied = AudioPlan.inactive
        private set
    var echoCancellationAllowed = true
        private set

    /**
     * The route changed and has not been acted on yet; due at this time. Infinity:
     * put off until the comment that keeps the microphone ends.
     */
    private var routeDue: Double? = null
    /**
     * Headphones arrived while echo cancellation held the book on the speaker: it is
     * off until the route has settled, so the headphones can take the book.
     */
    private var uncovering = false
    private var toggles: List<Double> = emptyList()

    /** The book closed: start over. */
    fun reset() {
        needs = AudioNeeds()
        route = AudioRoute.builtIn
        applied = AudioPlan.inactive
        echoCancellationAllowed = true
        routeDue = null
        uncovering = false
        toggles = emptyList()
    }

    /**
     * The session wants something else (a book opened, play, a comment started…).
     * Acted on at once: these are things the user just did.
     */
    fun needsChanged(new: AudioNeeds, now: Double): Action? {
        val wasRecording = needs.recording
        needs = new
        if (!new.bookOpen) {
            // the next book starts with a clean slate
            echoCancellationAllowed = true
            toggles = emptyList()
        }
        // a comment just ended: what was put off for it is due now
        if (wasRecording && !new.recording && routeDue != null) routeDue = now
        return decide(now)
    }

    /** The platform reported a route change. */
    fun routeChanged(new: AudioRoute, reason: RouteReason, now: Double): Action? {
        route = new
        routeDue = now + policy.settle
        if (!(reason == RouteReason.DEVICE_ARRIVED && applied.echoCancellation && !needs.recording)) return null
        // Echo cancellation keeps Bluetooth out of the route: turn it off so the headphones can show up.
        uncovering = true
        val plan = applied.copy(echoCancellation = false)
        return Action(plan, applied.change(plan))
    }

    /** Call a few times a second with the current route. */
    fun tick(route: AudioRoute, now: Double): Action? {
        if (route != this.route) {
            // the route moved without a notification (AirPods settling late)
            this.route = route
            routeDue = routeDue ?: (now + policy.settle)
        }
        val due = routeDue ?: return null
        if (!(now >= due)) return null
        return decide(now)
    }

    /** The adapter applied a plan (or what it could of it). */
    fun didApply(plan: AudioPlan, now: Double) {
        if (plan.echoCancellation && !applied.echoCancellation) {
            toggles = toggles.filter { now - it < policy.toggleWindow } + now
        }
        applied = plan
    }

    private fun decide(now: Double): Action? {
        val settled = routeDue?.let { now >= it } ?: true
        if (settled) {
            routeDue = null
            uncovering = false
        }
        toggles = toggles.filter { now - it < policy.toggleWindow }
        if (toggles.size >= policy.toggleLimit) echoCancellationAllowed = false
        val wanted = AudioPlan.make(needs, route, echoCancellationAllowed)
        var plan = wanted
        // A route still moving says nothing reliable about echo: keep what is applied.
        if (!settled) plan = plan.copy(echoCancellation = if (uncovering) false else applied.echoCancellation && plan.capture)
        var change = applied.change(plan)
        // A comment keeps its microphone: rebuilding is put off until it is saved
        // (unless the microphone isn't running at all yet).
        if (needs.recording && applied.capture && change == AudioPlan.Change.CAPTURE) {
            plan = plan.copy(echoCancellation = applied.echoCancellation, session = applied.session)
            change = applied.change(plan)
            if (routeDue == null && plan != wanted) routeDue = Double.POSITIVE_INFINITY
        }
        return if (change == AudioPlan.Change.NONE && plan == applied) null else Action(plan, change)
    }
}
