package de.kaikunze.marginvoice.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NarrationTextTests {
    @Test fun splitsParagraphsAndSentences() {
        val t = NarrationText("Title.\n\nSection: Intro.\n\nFirst one, e.g. this. Second one! Third?\n")
        assertEquals(listOf("Title.", "Section: Intro.", "First one, e.g. this. Second one! Third?"), t.paragraphs.map { it.text })
        val third = t.sentences.filter { it.paragraphIndex == 2 }.map { t.substring(it.range) }
        assertEquals(listOf("First one, e.g. this.", "Second one!", "Third?"), third)
    }

    @Test fun findsHeadings() {
        val t = NarrationText("Section: Introduction.\n\nA long paragraph that has two sentences. It is not a heading.\n\nResults.\n")
        assertEquals(listOf("Introduction", "Results"), t.headings.map { it.title })
    }

    @Test fun sentenceLookup() {
        val t = NarrationText("One two. Three four. Five.")
        assertEquals(0, t.sentenceIndex(containing = 0))
        assertEquals(1, t.sentenceIndex(containing = 10))
        assertEquals(2, t.sentenceIndex(containing = 100))
    }

    @Test fun normalizesWords() {
        assertEquals(listOf("zuruck", "bitte", "dont"), TextSegmentation.tokens("Zurück, bitte! Don't"))
    }
}
