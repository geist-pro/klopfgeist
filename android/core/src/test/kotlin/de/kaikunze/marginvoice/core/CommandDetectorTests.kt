package de.kaikunze.marginvoice.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs `spec/vectors/command-detector.json`; the Swift core runs the same file. */
class CommandDetectorTests {
    @Serializable
    data class VectorFile(val cases: List<Case>)

    @Serializable
    data class Case(val name: String, val context: ContextSpec, val steps: List<Step>)

    @Serializable
    data class ContextSpec(val policy: String, val allowed: String? = null, val bookText: String? = null) {
        val context: CommandDetector.Context
            get() = CommandDetector.Context(
                policy = when (policy) {
                    "recording" -> CommandDetector.Policy.recording
                    "listeningOverSpeaker" -> CommandDetector.Policy.listeningOverSpeaker
                    else -> CommandDetector.Policy.listening
                },
                allowed = if (allowed == "recording") CommandDetector.Context.recordingCommands else VoiceCommand.entries.toSet(),
                recentBookText = bookText,
            )
    }

    @Serializable
    data class Timed(val text: String, val start: Double, val end: Double)

    @Serializable
    data class Expected(val fire: String? = null, val start: Double? = null, val reject: String? = null, val text: String)

    @Serializable
    data class Step(
        val at: Double,
        /** Each word as `[text, start, end]`. */
        val words: List<JsonArray>? = null,
        @SerialName("final") val isFinal: Boolean? = null,
        val approximate: Boolean? = null,
        val text: Timed? = null,
        val speech: Boolean? = null,
        val tick: Boolean? = null,
        val forget: Double? = null,
        val reset: Boolean? = null,
        val expect: List<Expected>? = null,
    ) {
        val spokenWords: List<SpokenWord>?
            get() = words?.map { SpokenWord(it[0].jsonPrimitive.content, it[1].jsonPrimitive.double, it[2].jsonPrimitive.double) }
    }

    private fun cases(): List<Case> = SpecVectors.load("command-detector", VectorFile.serializer()).cases

    @Test fun theVectorFileLoads() {
        val count = cases().size
        assertTrue("command-detector.json has only $count cases", count >= 20)
    }

    @Test fun conformsTo() {
        val cases = cases()
        assertTrue("command-detector.json has only ${cases.size} cases", cases.size >= 20)
        val failures = ArrayList<String>()
        for (c in cases) {
            val detector = CommandDetector()
            val context = c.context.context
            for (step in c.steps) {
                var outcomes: List<CommandDetector.Outcome> = emptyList()
                val words = step.spokenWords
                val t = step.text
                if (words != null) {
                    outcomes = detector.hypothesis(Hypothesis(words, step.isFinal ?: false, step.approximate ?: false), now = step.at, context = context)
                } else if (t != null) {
                    outcomes = detector.hypothesis(Hypothesis.of(t.text, t.start, t.end, step.isFinal ?: false), now = step.at, context = context)
                } else if (step.speech != null) {
                    detector.speech(step.speech, step.at)
                } else if (step.forget != null) {
                    detector.forget(before = step.forget)
                } else if (step.reset == true) {
                    detector.reset()
                } else {
                    outcomes = detector.tick(now = step.at, context = context)
                }
                val actual = describeOutcomes(outcomes)
                val expected = describeExpected(step.expect ?: emptyList())
                if (actual != expected) failures.add("${c.name}, at ${step.at}: expected $expected, got $actual")
            }
        }
        SpecVectors.report("command-detector", cases.size, failures)
    }

    companion object {
        fun describeOutcomes(outcomes: List<CommandDetector.Outcome>): List<String> = outcomes.map {
            when (it) {
                is CommandDetector.Outcome.Fire ->
                    "fire ${it.candidate.command.name.lowercase()} “${it.candidate.text}” @${fmt("%.2f", it.candidate.start)}"
                is CommandDetector.Outcome.Rejected -> "reject ${it.rejection.raw} “${it.text}”"
            }
        }

        fun describeExpected(expected: List<Expected>): List<String> = expected.map {
            if (it.fire != null) "fire ${it.fire} “${it.text}” @${fmt("%.2f", it.start ?: -1.0)}" else "reject ${it.reject ?: "?"} “${it.text}”"
        }
    }
}
