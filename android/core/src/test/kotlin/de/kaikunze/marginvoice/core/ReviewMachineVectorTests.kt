package de.kaikunze.marginvoice.core

import de.kaikunze.marginvoice.core.ReviewMachine.Effect
import de.kaikunze.marginvoice.core.ReviewMachine.Event
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs `spec/vectors/review-machine.json`; the Swift core runs the same file. */
class ReviewMachineVectorTests {
    @Serializable
    data class VectorFile(val cases: List<Case>)

    @Serializable
    data class Case(val name: String, val steps: List<Step>)

    @Serializable
    data class Step(
        @SerialName("do") val action: String,
        val time: Double,
        val rate: Double,
        val now: Double,
        val effects: List<String>? = null,
        val state: String? = null,
    )

    private fun cases(): List<Case> = SpecVectors.load("review-machine", VectorFile.serializer()).cases

    @Test fun theVectorFileLoads() {
        val count = cases().size
        assertTrue("review-machine.json has only $count cases", count >= 12)
    }

    @Test fun conformsTo() {
        val cases = cases()
        assertTrue("review-machine.json has only ${cases.size} cases", cases.size >= 12)
        val failures = ArrayList<String>()
        for (c in cases) {
            val machine = ReviewMachine()
            for ((i, step) in c.steps.withIndex()) {
                val where = "${c.name}, step ${i + 1} (${step.action})"
                val parts = step.action.split(":").filter { it.isNotEmpty() }
                val argument = parts.getOrElse(1) { "" }
                var effects: List<Effect> = emptyList()
                fun handle(event: Event?) {
                    if (event == null) failures.add("$where: unknown argument") else effects = machine.handle(event, step.time, step.rate, step.now)
                }
                when (parts[0]) {
                    "setPlaying" -> machine.setPlaying(argument == "true")
                    "command" -> handle(VoiceCommand.entries.firstOrNull { camel(it.name) == argument }?.let(Event::Command))
                    "remote" -> handle(ReviewMachine.RemoteButton.entries.firstOrNull { camel(it.name) == argument }?.let(Event::Remote))
                    "silenceTimeout" -> handle(Event.SilenceTimeout)
                    "commentAbandoned" -> handle(Event.CommentAbandoned(argument == "true"))
                    "commentButton" -> handle(Event.CommentButton)
                    else -> failures.add("$where: unknown step")
                }
                if (step.effects != null) {
                    val actual = effects.map(::describe)
                    if (actual != step.effects) failures.add("$where: expected ${step.effects}, got $actual")
                }
                if (step.state != null) {
                    val actual = describe(machine.state)
                    if (actual != step.state) failures.add("$where: expected state ${step.state}, got $actual")
                }
            }
        }
        SpecVectors.report("review-machine", cases.size, failures)
    }

    @Test fun numbersPrintLikeTheSwiftCore() {
        assertEquals("85", number(85.0))
        assertEquals("98.5", number(98.5))
        assertEquals("0", number(0.0))
        assertEquals("1.4", number(1.4))
    }

    companion object {
        /** `PLAY_PAUSE` → `playPause`: the Swift case name. */
        fun camel(name: String): String =
            name.lowercase().split("_").mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar { it.uppercase() } }.joinToString("")

        /** Whole numbers print without a decimal point. */
        fun number(x: Double): String = if (x == x.swiftRounded()) x.toLong().toString() else x.toString()

        fun describe(state: ReviewMachine.State): String = when (state) {
            ReviewMachine.State.Paused -> "paused"
            ReviewMachine.State.Playing -> "playing"
            is ReviewMachine.State.Recording -> "recording"
        }

        fun describe(effect: Effect): String = when (effect) {
            Effect.Play -> "play"
            Effect.Pause -> "pause"
            is Effect.Seek -> "seek:${number(effect.to)}"
            is Effect.SeekToSentence -> "seekToSentence:${number(effect.near)}"
            is Effect.Beep -> "beep:${camel(effect.earcon.name)}"
            is Effect.StartRecording -> "startRecording:${number(effect.anchor)}"
            is Effect.FinishRecording -> "finishRecording:${effect.save}"
            is Effect.AddBookmark -> "addBookmark:${number(effect.at)}"
            is Effect.CloseBookmark -> "closeBookmark:${number(effect.at)}"
            is Effect.SetRate -> "setRate:${number(effect.rate)}"
            Effect.RestartSentence -> "restartSentence"
            is Effect.Announce -> "announce:${effect.text}"
        }
    }
}
