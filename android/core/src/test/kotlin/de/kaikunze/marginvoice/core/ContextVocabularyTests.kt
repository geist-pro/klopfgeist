package de.kaikunze.marginvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextVocabularyTests {
    @Test fun picksNamesAcronymsAndCompounds() {
        val text = "Das Projekt WallBOT entwickelt mit dem HAFEN-Konsortium einen Greifer. WallBOT nutzt KI und " +
            "Robots4CleanTech. Die Fassadenreinigung ist ein wichtiges Thema, und es ist gut."
        val terms = ContextVocabulary.terms(text)
        assertEquals("WallBOT", terms.first()) // most frequent
        assertTrue(terms.contains("HAFEN-Konsortium"))
        assertTrue(terms.contains("KI"))
        assertTrue(terms.contains("Robots4CleanTech"))
        assertTrue(terms.contains("Fassadenreinigung"))
        assertFalse(terms.contains("Projekt")) // short common noun
        assertTrue(!terms.contains("wichtiges") && !terms.contains("Das") && !terms.contains("es"))
    }

    @Test fun nearbyTextAndTitle() {
        val text = NarrationText("Anfang mit AlphaWerk. " + "und so weiter ".repeat(300) + "Ende mit BetaWerk.")
        val terms = ContextVocabulary.terms(around = 0, text = text, title = "Projektskizze WallBOT", radius = 200)
        assertEquals(listOf("Projektskizze", "WallBOT"), terms.take(2))
        assertTrue(terms.contains("AlphaWerk") && !terms.contains("BetaWerk"))
    }

    @Test fun respectsTheLimit() {
        val text = (1..50).joinToString(" ") { "Name$it" }
        assertEquals(10, ContextVocabulary.terms(text, limit = 10).size)
    }
}
