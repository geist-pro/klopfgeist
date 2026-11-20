package de.kaikunze.marginvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextAlignerTests {
    @Test fun recoversOffsetFromNoisyTranscript() {
        val text = Fixture.text("icra")
        // pick ~45 words ending at a known word in the middle of the text
        val endWord = text.words.size / 2
        val spoken = text.words.subList(endWord - 45, endWord).map { text.substring(it.range) }.toMutableList()
        // recognizer errors: drop, substitute, misspell
        spoken.removeAt(10)
        spoken[20] = "banana"
        spoken[30] = spoken[30].dropLast(1) + "x"
        repeat(2) { spoken.removeAt(0) } // cut-off start
        val truth = text.words[endWord - 1].range.upper
        val estimate = truth + 900 // chapter-based estimate is off by ~15 s of speech
        val r = ContextAligner.align(spoken.joinToString(" "), text, near = estimate)!!
        assertEquals(truth, r.offset)
        assertTrue(r.confidence > 0.8)
    }

    @Test fun rejectsUnrelatedTranscript() {
        val text = Fixture.text("icra")
        assertNull(ContextAligner.align("the weather in Berlin is sunny and the bakery opens", text, near = 20_000, minimumMatches = 5))
    }

    @Test fun repeatedPhrasePrefersClosestToEstimate() {
        val text = NarrationText("Alpha beta gamma delta. Filler words here. Alpha beta gamma delta. More filler.")
        assertEquals(22, ContextAligner.align("alpha beta gamma delta", text, near = 10)?.offset)
        assertEquals(65, ContextAligner.align("alpha beta gamma delta", text, near = 60)?.offset)
    }
}
