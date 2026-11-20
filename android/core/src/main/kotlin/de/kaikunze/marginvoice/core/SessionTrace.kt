package de.kaikunze.marginvoice.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max

/**
 * The flight recorder: everything that went into the review session and what it
 * decided, in order. When "stop" was not heard on some headset, the trace from that
 * moment can be exported, read, and replayed through the same code in a test.
 *
 * @param t seconds on the session clock.
 */
data class TraceEvent(val t: Double, val kind: Kind) {
    sealed interface Kind {
        /** A recognizer result, at microphone time `mic`. */
        data class Hypothesis(val hypothesis: de.kaikunze.marginvoice.core.Hypothesis, val mic: Double) : Kind
        /** The energy detector started or stopped hearing speech. */
        data class Speech(val active: Boolean, val mic: Double) : Kind
        /** What the detector listens for changed: listening | listeningOverSpeaker | recording. */
        data class Mode(val mode: String) : Kind
        /** The microphone restarted; its timeline begins at zero again. */
        data object MicrophoneRestarted : Kind
        data class Remote(val button: ReviewMachine.RemoteButton) : Kind
        /** A decision of the command detector ("fire stop", "reject notIsolated continue"). */
        data class Outcome(val text: String) : Kind
        /** An event the state machine handled, the state afterwards and what it asked for. */
        data class Machine(val event: String, val state: String, val effects: List<String>) : Kind
        data class Route(val route: AudioRoute, val reason: String) : Kind
        data class Plan(val plan: AudioPlan, val change: String) : Kind
        /** A line of the command log. */
        data class Note(val text: String) : Kind
    }
}

/**
 * Keeps the last events (a few minutes of use) and writes them as JSON lines.
 *
 * The lines have the shape Swift's `Codable` gives the same type
 * (`{"kind":{"speech":{"active":true,"mic":1.2}},"t":1000.3}`, unlabelled values as `_0`),
 * so a trace reads the same from either platform; they are not guaranteed byte-identical.
 */
class TraceRecorder(var capacity: Int = 4000) {
    private val list = ArrayList<TraceEvent>()

    val events: List<TraceEvent> get() = list

    fun record(kind: TraceEvent.Kind, at: Double) {
        list.add(TraceEvent(at, kind))
        if (list.size > capacity) list.subList(0, list.size - capacity).clear()
    }

    fun clear() {
        list.clear()
    }

    /** One JSON object per line, oldest first. */
    fun jsonLines(): String = list.joinToString("\n") { encode(it).toString() }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val strings = ListSerializer(String.serializer())

        /** Lines that are not a trace event (a newer kind, a cut-off last line) are skipped. */
        fun parse(jsonLines: String): List<TraceEvent> = jsonLines.split('\n').filter { it.isNotBlank() }.mapNotNull {
            try {
                decode(json.parseToJsonElement(it).jsonObject)
            } catch (_: Exception) {
                null
            }
        }

        private fun button(b: ReviewMachine.RemoteButton): String = when (b) {
            ReviewMachine.RemoteButton.PLAY_PAUSE -> "playPause"
            ReviewMachine.RemoteButton.PLAY -> "play"
            ReviewMachine.RemoteButton.PAUSE -> "pause"
            ReviewMachine.RemoteButton.NEXT -> "next"
            ReviewMachine.RemoteButton.PREVIOUS -> "previous"
        }

        private fun encode(event: TraceEvent): JsonObject {
            fun kind(name: String, fields: Map<String, JsonElement>) = JsonObject(mapOf(name to JsonObject(fields)))
            val kind = when (val k = event.kind) {
                is TraceEvent.Kind.Hypothesis -> kind(
                    "hypothesis", mapOf("_0" to json.encodeToJsonElement(Hypothesis.serializer(), k.hypothesis), "mic" to JsonPrimitive(k.mic)),
                )
                is TraceEvent.Kind.Speech -> kind("speech", mapOf("active" to JsonPrimitive(k.active), "mic" to JsonPrimitive(k.mic)))
                is TraceEvent.Kind.Mode -> kind("mode", mapOf("_0" to JsonPrimitive(k.mode)))
                TraceEvent.Kind.MicrophoneRestarted -> kind("microphoneRestarted", emptyMap())
                is TraceEvent.Kind.Remote -> kind("remote", mapOf("_0" to JsonPrimitive(button(k.button))))
                is TraceEvent.Kind.Outcome -> kind("outcome", mapOf("_0" to JsonPrimitive(k.text)))
                is TraceEvent.Kind.Machine -> kind(
                    "machine",
                    mapOf("effects" to json.encodeToJsonElement(strings, k.effects), "event" to JsonPrimitive(k.event), "state" to JsonPrimitive(k.state)),
                )
                is TraceEvent.Kind.Route -> kind(
                    "route", mapOf("_0" to json.encodeToJsonElement(AudioRoute.serializer(), k.route), "reason" to JsonPrimitive(k.reason)),
                )
                is TraceEvent.Kind.Plan -> kind(
                    "plan", mapOf("_0" to json.encodeToJsonElement(AudioPlan.serializer(), k.plan), "change" to JsonPrimitive(k.change)),
                )
                is TraceEvent.Kind.Note -> kind("note", mapOf("_0" to JsonPrimitive(k.text)))
            }
            return buildJsonObject {
                put("kind", kind)
                put("t", JsonPrimitive(event.t))
            }
        }

        private fun decode(line: JsonObject): TraceEvent {
            val t = line.getValue("t").jsonPrimitive.double
            val (name, value) = line.getValue("kind").jsonObject.entries.single()
            val f = value.jsonObject
            fun text(key: String) = f.getValue(key).jsonPrimitive.content
            val kind: TraceEvent.Kind = when (name) {
                "hypothesis" -> TraceEvent.Kind.Hypothesis(
                    json.decodeFromJsonElement(Hypothesis.serializer(), f.getValue("_0")), f.getValue("mic").jsonPrimitive.double,
                )
                "speech" -> TraceEvent.Kind.Speech(f.getValue("active").jsonPrimitive.boolean, f.getValue("mic").jsonPrimitive.double)
                "mode" -> TraceEvent.Kind.Mode(text("_0"))
                "microphoneRestarted" -> TraceEvent.Kind.MicrophoneRestarted
                "remote" -> TraceEvent.Kind.Remote(ReviewMachine.RemoteButton.entries.first { button(it) == text("_0") })
                "outcome" -> TraceEvent.Kind.Outcome(text("_0"))
                "machine" -> TraceEvent.Kind.Machine(text("event"), text("state"), json.decodeFromJsonElement(strings, f.getValue("effects")))
                "route" -> TraceEvent.Kind.Route(json.decodeFromJsonElement(AudioRoute.serializer(), f.getValue("_0")), text("reason"))
                "plan" -> TraceEvent.Kind.Plan(json.decodeFromJsonElement(AudioPlan.serializer(), f.getValue("_0")), text("change"))
                "note" -> TraceEvent.Kind.Note(text("_0"))
                else -> throw IllegalArgumentException("unknown trace event $name")
            }
            return TraceEvent(t, kind)
        }
    }
}

/** Runs the recognizer results of a trace through a fresh [CommandDetector]. */
object TraceReplay {
    /** The detector's decisions, as the session would describe them in `outcome`. */
    fun detectorOutcomes(events: List<TraceEvent>, tickEvery: Double = 0.1): List<String> {
        val detector = CommandDetector()
        var mode = "listening"
        var mic = 0.0
        val result = ArrayList<String>()

        fun context(): CommandDetector.Context = when (mode) {
            "recording" -> CommandDetector.Context(CommandDetector.Policy.recording, CommandDetector.Context.recordingCommands)
            "listeningOverSpeaker" -> CommandDetector.Context(CommandDetector.Policy.listeningOverSpeaker)
            else -> CommandDetector.Context(CommandDetector.Policy.listening)
        }

        fun advance(to: Double) {
            // the session ticks a few times a second between results
            while (mic + tickEvery < to) {
                mic += tickEvery
                detector.tick(mic, context()).mapTo(result, ::describe)
            }
            mic = max(mic, to)
        }

        // Events carry session time; results and speech also carry microphone time.
        // The difference places every other event on the microphone timeline.
        var offset: Double? = null
        for (event in events) {
            offset?.let { advance(event.t + it) }
            when (val k = event.kind) {
                is TraceEvent.Kind.Hypothesis -> {
                    advance(k.mic)
                    offset = k.mic - event.t
                    detector.hypothesis(k.hypothesis, k.mic, context()).mapTo(result, ::describe)
                }
                is TraceEvent.Kind.Speech -> {
                    advance(k.mic)
                    offset = k.mic - event.t
                    detector.speech(k.active, k.mic)
                }
                is TraceEvent.Kind.Mode -> {
                    mode = k.mode
                    if (k.mode == "recording") detector.forget(mic)
                }
                TraceEvent.Kind.MicrophoneRestarted -> {
                    detector.reset()
                    mic = 0.0
                    offset = -event.t
                }
                else -> {}
            }
        }
        advance(mic + 2)
        return result
    }

    fun describe(outcome: CommandDetector.Outcome): String = when (outcome) {
        is CommandDetector.Outcome.Fire -> "fire ${outcome.candidate.command.rawValue} “${outcome.candidate.text}”"
        is CommandDetector.Outcome.Rejected -> "reject ${outcome.rejection.raw} “${outcome.text}”"
    }
}

/** The command's name as the Swift core spells it ("stop", "resume"): used in logs and traces. */
val VoiceCommand.rawValue: String get() = name.lowercase()
