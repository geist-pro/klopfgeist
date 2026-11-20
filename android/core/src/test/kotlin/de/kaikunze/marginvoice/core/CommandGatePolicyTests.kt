package de.kaikunze.marginvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where the loudness gate applies: only where the microphone hears the book playing. */
class CommandGatePolicyTests {
    private val policy = CommandGatePolicy()
    private val listening = AudioNeeds(bookOpen = true, hasPlayed = true, voiceCommands = true, microphonePermission = true)
    private val speaker = AudioPlan.make(listening, AudioRoute.builtIn)
    private val headphones = AudioPlan.make(listening, AudioRoute(listOf(AudioRoute.Port.BLUETOOTH_A2DP), AudioRoute.Port.BUILT_IN_MIC))

    @Test fun onTheSpeakerWhileTheBookPlaysACommandHasToStandOut() {
        val gate = policy.gate(speaker, bookPlaying = true, recording = false)
        assertEquals(CommandGate.Kind.CONTRAST, gate.kind)
        assertTrue(gate.echoTurnDown)
        assertEquals(6.0, gate.minimumContrast, 0.0)
        assertEquals("contrast gate ≥ 6 dB (speaker, book playing)", gate.description)
    }

    @Test fun aPausedBookHasNoEchoSoContinueNeedsNoContrast() {
        val gate = policy.gate(speaker, bookPlaying = false, recording = false)
        assertEquals(CommandGate.Kind.NONE, gate.kind)
        assertFalse(gate.echoTurnDown)
        assertEquals("no gate (book paused)", gate.description)
    }

    @Test fun whileACommentIsRecordedNothingIsGated() {
        // "continue" and "cancel" end a comment said at any volume
        for (playing in listOf(false, true)) {
            val gate = policy.gate(speaker, bookPlaying = playing, recording = true)
            assertEquals(CommandGate.Kind.NONE, gate.kind)
            assertFalse(gate.echoTurnDown)
            assertEquals("no gate (recording)", gate.description)
        }
    }

    @Test fun onHeadphonesNothingIsEverGated() {
        for (playing in listOf(false, true)) for (recording in listOf(false, true)) {
            val gate = policy.gate(headphones, playing, recording)
            assertEquals(CommandGate.Kind.NONE, gate.kind)
            assertFalse(gate.echoTurnDown)
            assertEquals("no gate (headphones)", gate.description)
        }
    }

    @Test fun everyPlanIsGatedOnlyWhenItsMicrophoneHearsThePlayingBook() {
        val routes = listOf(
            AudioRoute.builtIn,
            AudioRoute(listOf(AudioRoute.Port.BLUETOOTH_A2DP), AudioRoute.Port.BUILT_IN_MIC),
            AudioRoute(listOf(AudioRoute.Port.BLUETOOTH_A2DP), AudioRoute.Port.BLUETOOTH_HFP, true),
            AudioRoute(listOf(AudioRoute.Port.WIRED_HEADSET), AudioRoute.Port.WIRED_HEADSET),
            AudioRoute(listOf(AudioRoute.Port.HDMI), AudioRoute.Port.BUILT_IN_MIC),
            AudioRoute(listOf(AudioRoute.Port.CAR_AUDIO), AudioRoute.Port.BUILT_IN_MIC),
        )
        for (route in routes) for (hasPlayed in listOf(false, true)) for (playing in listOf(false, true)) for (recording in listOf(false, true)) {
            val plan = AudioPlan.make(listening.copy(hasPlayed = hasPlayed, recording = recording), route)
            val gated = policy.gate(plan, playing, recording).kind == CommandGate.Kind.CONTRAST
            assertEquals("$route playing=$playing recording=$recording", plan.bookAudible && playing && !recording, gated)
        }
    }

    @Test fun theThresholdIsThePolicys() {
        val gate = CommandGatePolicy(minimumContrast = 9.0).gate(speaker, bookPlaying = true, recording = false)
        assertEquals(9.0, gate.minimumContrast, 0.0)
        // and the spotter judges by it: 7 dB above the echo is enough for 6, not for 9
        fun accepted(threshold: Double): Boolean {
            val s = CommandSpotter(setOf(VoiceCommand.Language.ENGLISH), threshold)
            val x = FloatArray(4 * 16_000) { i -> (if (i in 32_000 until 38_400) 0.045f else 0.02f) * kotlin.math.sin(2 * Math.PI * 220 * i / 16_000).toFloat() }
            s.audio(x, x.size, 0.0)
            s.hypothesis(listOf(CommandSpotter.Word("stop", 2.0, 2.4)))
            return s.judge(4.0).single().accepted
        }
        assertTrue(accepted(6.0))
        assertFalse(accepted(9.0))
    }
}

class CommandWordsTests {
    private val paused = CommandGatePolicy().gate(AudioPlan.inactive.copy(bookAudible = true), bookPlaying = false, recording = false)
    private val playing = CommandGatePolicy().gate(AudioPlan.inactive.copy(bookAudible = true), bookPlaying = true, recording = false)

    @Test fun findsCommandPhrasesAmongTheWords() {
        val words = listOf(SpokenWord("[unk]", 0.0, 0.4), SpokenWord("go", 1.0, 1.2), SpokenWord("back", 1.2, 1.6), SpokenWord("weiter", 3.0, 3.4))
        val found = CommandWords.phrases(words)
        assertEquals(listOf("go back", "back", "weiter"), found.map { it.text })
        assertEquals(listOf(VoiceCommand.BACK, VoiceCommand.BACK, VoiceCommand.RESUME), found.map { it.command })
        assertEquals(1.0, found[0].start, 0.0)
        assertEquals(1.6, found[0].end, 0.0)
    }

    @Test fun theEvidenceLineSaysLevelGainAndGate() {
        assertEquals("“continue” heard at -41 dBFS, gain +18 dB, no gate (book paused)", CommandWords.evidence("continue", -41.2, 18.3, paused))
        val stoodOut = CommandSpotter.Spot(VoiceCommand.STOP, "stop", 2.0, 2.4, 14.0, 18.2, true, -33.0)
        assertEquals(
            "“stop” stands out (+14/+18 dB) at -33 dBFS, gain +9 dB, contrast gate ≥ 6 dB (speaker, book playing)",
            CommandWords.evidence("stop", -33.0, 9.0, playing, stoodOut),
        )
        val drowned = stoodOut.copy(before = 3.0, after = 2.0, accepted = false)
        assertEquals(
            "Ignored “stop” (not on its own: +3/+2 dB) at -33 dBFS, gain +9 dB, contrast gate ≥ 6 dB (speaker, book playing)",
            CommandWords.evidence("stop", -33.0, 9.0, playing, drowned),
        )
    }

    @Test fun aWordReleasedWithoutAVerdictIsNamed() {
        assertEquals("“stop” let through without a contrast verdict: no gate (book paused)", CommandWords.released("stop", paused))
    }

    @Test fun theSpotterReportsTheWordsLevel() {
        val s = CommandSpotter(setOf(VoiceCommand.Language.ENGLISH))
        // a tone of amplitude 0.01: RMS -43 dBFS
        val x = FloatArray(4 * 16_000) { i -> if (i in 32_000 until 38_400) 0.01f * kotlin.math.sin(2 * Math.PI * 220 * i / 16_000).toFloat() else 0f }
        s.audio(x, x.size, 0.0)
        s.hypothesis(listOf(CommandSpotter.Word("stop", 2.0, 2.4)))
        assertEquals(-43.0, s.judge(4.0).single().level, 1.0)
        assertEquals(-43.0, s.levelDb(2.0, 2.4), 1.0)
    }
}
