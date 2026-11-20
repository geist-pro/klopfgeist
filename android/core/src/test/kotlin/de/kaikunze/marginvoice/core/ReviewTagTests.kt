package de.kaikunze.marginvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewTagTests {
    @Test fun extractsEnglishAndGermanTags() {
        val w = ReviewTag.extract("Weakness: the baseline is never tuned.")
        assertTrue(w.tag == ReviewTag.WEAKNESS && w.text == "The baseline is never tuned.")
        val s = ReviewTag.extract("Schwäche, die Baseline wird nie optimiert")
        assertTrue(s.tag == ReviewTag.WEAKNESS && s.text == "Die Baseline wird nie optimiert")
        assertEquals(ReviewTag.STRENGTH, ReviewTag.extract("strength the manipulation check is decisive").tag)
        assertEquals(ReviewTag.QUESTION, ReviewTag.extract("Frage an die Autoren: wie groß war n?").tag)
        assertEquals(ReviewTag.QUESTION, ReviewTag.extract("Frage: wie groß war n?").tag)
        assertEquals("Typo in figure 3", ReviewTag.extract("minor issue, typo in figure 3").text)
        assertEquals(ReviewTag.MINOR, ReviewTag.extract("Kleinigkeit Tippfehler in Abbildung 3").tag)
        assertEquals(ReviewTag.SUMMARY, ReviewTag.extract("Summary. The paper asks whether awe leaves a signature.").tag)
    }

    @Test fun stripsPunctuationAfterTheTag() {
        assertEquals("Why n equals 29", ReviewTag.extract("question - why n equals 29").text)
        assertEquals("No baseline", ReviewTag.extract("um, weakness, no baseline").text)
        assertEquals("", ReviewTag.extract("Weakness.").text)
    }

    @Test fun leadInHeardInTheBeepIsDropped() {
        val r = ReviewTag.extract("A weakness Maybe 24 participants enough")
        assertTrue(r.tag == ReviewTag.WEAKNESS && r.text == "Maybe 24 participants enough")
        assertEquals(ReviewTag.QUESTION, ReviewTag.extract("hey question how loud are the wings").tag)
        assertNull(ReviewTag.extract("A weakness of the method is the small sample").tag)
    }

    @Test fun leavesUntaggedTextAlone() {
        val r = ReviewTag.extract("This claim needs a citation.")
        assertTrue(r.tag == null && r.text == "This claim needs a citation.")
    }

    @Test fun tagWordInsideTheSentenceIsNotATag() {
        assertNull(ReviewTag.extract("The question is whether n is enough").tag)
        assertNull(ReviewTag.extract("Eine Schwäche sehe ich hier nicht").tag)
    }

    @Test fun exportsCarryTheTag() {
        val text = NarrationText("One a. Two b.\n")
        val c = CommentRecord(mediaTime = 5.0, textOffset = 6, confidence = 0.9, chapter = "Intro", transcript = "No baseline.", tag = ReviewTag.WEAKNESS)
        assertTrue(CommentExport.markdown("T", listOf(c), text).contains("## 1. 0:05 · Intro · Weakness"))
        assertTrue(CommentExport.csv(listOf(c), text).contains(",comment,weakness,0.90,"))
        assertTrue(AnnotatedTextExport.markdown(text, listOf(c)).contains("{>> [Weakness] No baseline. <<}"))
        assertTrue(CommentExport.json("T", listOf(c)).contains("\"tag\": \"weakness\""))
    }
}
