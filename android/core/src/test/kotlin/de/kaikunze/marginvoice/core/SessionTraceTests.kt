package de.kaikunze.marginvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTraceTests {
    private val everyKind = listOf(
        TraceEvent.Kind.Hypothesis(Hypothesis(listOf(SpokenWord("stop", 1.0, 1.3), SpokenWord("“weiter”", 2.0, 2.5)), isFinal = true), mic = 2.6),
        TraceEvent.Kind.Speech(active = true, mic = 0.9),
        TraceEvent.Kind.Mode("listeningOverSpeaker"),
        TraceEvent.Kind.MicrophoneRestarted,
        TraceEvent.Kind.Remote(ReviewMachine.RemoteButton.PLAY_PAUSE),
        TraceEvent.Kind.Remote(ReviewMachine.RemoteButton.NEXT),
        TraceEvent.Kind.Outcome("fire stop “stop”"),
        TraceEvent.Kind.Machine("Command(command=STOP)", "recording", listOf("Pause", "Beep(earcon=RECORD_START)")),
        TraceEvent.Kind.Route(AudioRoute(listOf(AudioRoute.Port.BLUETOOTH_A2DP), AudioRoute.Port.BLUETOOTH_LE, true), "deviceArrived"),
        TraceEvent.Kind.Route(AudioRoute(emptyList(), null), "other"),
        TraceEvent.Kind.Plan(AudioPlan.make(AudioNeeds(bookOpen = true, voiceCommands = true, microphonePermission = true), AudioRoute.builtIn), "capture"),
        TraceEvent.Kind.Note("Ignored “stop” (the book just said it)\nsecond line"),
    )

    @Test fun everyEventSurvivesJsonLines() {
        val recorder = TraceRecorder()
        everyKind.forEachIndexed { i, kind -> recorder.record(kind, 1000 + i * 0.1) }
        val text = recorder.jsonLines()
        assertEquals(everyKind.size, text.lines().size)
        assertEquals(recorder.events, TraceRecorder.parse(text))
    }

    @Test fun theLinesUseTheSwiftEventKindsAndFieldNames() {
        val recorder = TraceRecorder()
        recorder.record(TraceEvent.Kind.Speech(active = true, mic = 1.5), 1000.0)
        recorder.record(TraceEvent.Kind.Hypothesis(Hypothesis(listOf(SpokenWord("stop", 1.0, 1.3)), isFinal = false), mic = 1.5), 1000.5)
        val lines = recorder.jsonLines().lines()
        assertEquals("""{"kind":{"speech":{"active":true,"mic":1.5}},"t":1000.0}""", lines[0])
        assertEquals("""{"kind":{"hypothesis":{"_0":{"words":[{"text":"stop","start":1.0,"end":1.3}],"isFinal":false,"timesApproximate":false},"mic":1.5}},"t":1000.5}""", lines[1])
    }

    @Test fun theRecorderKeepsOnlyTheLastEvents() {
        val recorder = TraceRecorder(capacity = 3)
        repeat(5) { recorder.record(TraceEvent.Kind.Note("$it"), it.toDouble()) }
        assertEquals(listOf("2", "3", "4"), recorder.events.map { (it.kind as TraceEvent.Kind.Note).text })
        recorder.clear()
        assertTrue(recorder.events.isEmpty())
    }

    @Test fun linesThatAreNotEventsAreSkipped() {
        val good = """{"kind":{"note":{"_0":"x"}},"t":1.0}"""
        val parsed = TraceRecorder.parse("$good\n\n{\"kind\":{\"future\":{}},\"t\":2}\n{\"kind\":{\"note\"")
        assertEquals(listOf(TraceEvent(1.0, TraceEvent.Kind.Note("x"))), parsed)
    }

    @Test fun replayFiresACommandSaidOnItsOwn() {
        // session time 1000 is microphone time 10
        val events = listOf(
            TraceEvent(1000.0, TraceEvent.Kind.Hypothesis(Hypothesis(listOf(SpokenWord("stop", 9.65, 9.95)), false), 10.0)),
            TraceEvent(1002.0, TraceEvent.Kind.Mode("recording")),
            TraceEvent(1004.0, TraceEvent.Kind.Hypothesis(
                Hypothesis(listOf(SpokenWord("we", 13.1, 13.3), SpokenWord("should", 13.3, 13.55), SpokenWord("continue", 13.55, 13.95)), false), 14.0,
            )),
        )
        assertEquals(listOf("fire stop “stop”", "reject notIsolated “continue”"), TraceReplay.detectorOutcomes(events))
        // the same through the exported text
        val recorder = TraceRecorder()
        events.forEach { recorder.record(it.kind, it.t) }
        assertEquals(TraceReplay.detectorOutcomes(events), TraceReplay.detectorOutcomes(TraceRecorder.parse(recorder.jsonLines())))
    }
}
