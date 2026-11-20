package de.kaikunze.marginvoice.voice

import de.kaikunze.marginvoice.core.CommandDetector
import de.kaikunze.marginvoice.core.Hypothesis
import de.kaikunze.marginvoice.core.SpokenWord
import de.kaikunze.marginvoice.core.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vosk's JSON → the session's word-timed [Hypothesis]. */
class VoskResultsTests {
    @Test fun aPartialResultBecomesAVolatileHypothesisOnTheMicrophoneTimeline() {
        val json = """{"partial" : "[unk] stop", "partial_result" : [
            {"conf": 1.0, "end": 1.26, "start": 0.9, "word": "[unk]"},
            {"conf": 0.8, "end": 2.1, "start": 1.74, "word": "stop"}]}"""
        // the recognizer was started 10 s into the microphone's audio
        val h = VoskResults.hypothesis(json, origin = 10.0)!!
        assertEquals(false, h.isFinal)
        assertEquals(listOf("[unk]", "stop"), h.words.map { it.text })
        assertEquals(10.9, h.words[0].start, 1e-9)
        assertEquals(11.26, h.words[0].end, 1e-9)
        assertEquals(11.74, h.words[1].start, 1e-9)
        assertEquals(12.1, h.words[1].end, 1e-9)
    }

    @Test fun aResultIsFinal() {
        val json = """{"result" : [{"conf": 1.0, "end": 0.63, "start": 0.3, "word": "weiter"}], "text" : "weiter"}"""
        assertEquals(Hypothesis(listOf(SpokenWord("weiter", 0.3, 0.63)), isFinal = true), VoskResults.hypothesis(json, 0.0))
    }

    @Test fun resultsWithoutWordsAreEmptyHypotheses() {
        // the guess was withdrawn / the utterance ended with nothing recognized
        assertEquals(Hypothesis(emptyList(), isFinal = false), VoskResults.hypothesis("""{"partial" : ""}""", 3.0))
        assertEquals(Hypothesis(emptyList(), isFinal = true), VoskResults.hypothesis("""{"text" : ""}""", 3.0))
    }

    @Test fun wordsWithoutTimesAndOtherTextAreSkipped() {
        val json = """{"partial": "stop go", "partial_result": [{"word": "stop"}, {"word": "go", "start": 1, "end": 1.5}, 7]}"""
        assertEquals(listOf(SpokenWord("go", 1.0, 1.5)), VoskResults.hypothesis(json, 0.0)!!.words)
        assertNull(VoskResults.hypothesis("not json", 0.0))
        assertNull(VoskResults.hypothesis("""{"something": "else"}""", 0.0))
        assertNull(VoskResults.hypothesis("[]", 0.0))
    }

    @Test fun theGrammarHoldsEveryCommandPhraseOfBothLanguagesAsTheModelsSpellThem() {
        val grammar = VoskResults.grammar()
        for (word in listOf("stop", "continue", "go back", "weiter", "lesezeichen setzen", "zurück", "löschen", "[unk]", "please", "bitte")) {
            assertTrue(word, grammar.contains("\"$word\""))
        }
        assertTrue("umlauts are spelled out for the model", !grammar.contains("\"zuruck\""))
    }

    @Test fun aConvertedResultIsACommandForTheDetector() {
        // "[unk] [unk]" then, after a pause, "stop": what a sentence followed by a command looks like through the grammar
        val detector = CommandDetector()
        val context = CommandDetector.Context(CommandDetector.Policy.listening)
        val json = """{"partial": "[unk] [unk] stop", "partial_result": [
            {"word": "[unk]", "start": 0.5, "end": 0.9}, {"word": "[unk]", "start": 0.9, "end": 1.3}, {"word": "stop", "start": 2.0, "end": 2.3}]}"""
        assertTrue(detector.hypothesis(VoskResults.hypothesis(json, 0.0)!!, 2.35, context).isEmpty())
        val fired = detector.tick(2.7, context).single() as CommandDetector.Outcome.Fire
        assertEquals(VoiceCommand.STOP, fired.candidate.command)

        // the same word right after the others is speech
        val run = """{"partial": "[unk] stop", "partial_result": [{"word": "[unk]", "start": 5.0, "end": 5.4}, {"word": "stop", "start": 5.45, "end": 5.8}]}"""
        detector.hypothesis(VoskResults.hypothesis(run, 0.0)!!, 5.85, context)
        val rejected = detector.tick(6.2, context).single() as CommandDetector.Outcome.Rejected
        assertEquals(CommandDetector.Rejection.NOT_ISOLATED, rejected.rejection)
    }
}
