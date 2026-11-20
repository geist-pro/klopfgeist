package de.kaikunze.marginvoice.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTests {
    private val text = NarrationText("Section: Intro.\n\nFirst sentence here. Second sentence here.\n\nThird paragraph.\n")

    private fun end(t: NarrationText, s: String) = t.string.indexOf(s) + s.length

    @Test fun annotatedMarkdown() {
        val offset = end(text, "Second sentence here.")
        val comments = listOf(
            CommentRecord(mediaTime = 12.0, textOffset = offset - 3, confidence = 0.9, transcript = "Too long."),
            CommentRecord(mediaTime = 20.0, textOffset = text.count, confidence = 0.2, transcript = "Rephrase"),
            CommentRecord(kind = CommentRecord.Kind.BOOKMARK, mediaTime = 3.0, textOffset = 15, confidence = 1.0, transcript = ""),
        )
        val md = AnnotatedTextExport.markdown(text, comments, title = "Demo", headingOffsets = setOf(0))
        assertEquals(
            "# Demo\n\n## Intro {>> Bookmark 0:03 <<}\n\nFirst sentence here. Second sentence here. {>> Too long. <<}\n\n" +
                "{==Third paragraph.==} {>> Rephrase <<}\n",
            md,
        )
    }

    @Test fun bookmarkedPassageIsHighlighted() {
        val text = NarrationText("One a. Two b. Three c.\n\nFour d. Five e.\n")
        val passage = CommentRecord(
            kind = CommentRecord.Kind.BOOKMARK, mediaTime = 2.0, textOffset = end(text, "Two b.") - 1, confidence = 1.0, transcript = "",
            endMediaTime = 9.0, endTextOffset = end(text, "Four d.") - 1,
        )
        val md = AnnotatedTextExport.markdown(text, listOf(passage))
        assertEquals("One a. {==Two b. Three c.==}\n\n{==Four d.==} {>> Bookmark 0:02–0:09 <<} Five e.\n", md)
        assertTrue(CommentExport.markdown("T", listOf(passage), text).contains("> Two b. Three c. Four d."))
        // a single bookmark highlights the sentence just heard
        val single = CommentRecord(kind = CommentRecord.Kind.BOOKMARK, mediaTime = 2.0, textOffset = end(text, "Five e."), confidence = 1.0, transcript = "")
        assertTrue(AnnotatedTextExport.markdown(text, listOf(single)).contains("{==Five e.==} {>> Bookmark 0:02 <<}"))
    }

    @Test fun commentListAndCSV() {
        val c = CommentRecord(mediaTime = 75.0, textOffset = 30, confidence = 0.9, chapter = "Intro", transcript = "Say \"why\", first", audioFile = "c1.m4a")
        val md = CommentExport.markdown("Demo", listOf(c), text)
        assertTrue(md.contains("## 1. 1:15 · Intro"))
        assertTrue(md.contains("> First sentence here."))
        val csv = CommentExport.csv(listOf(c), text)
        assertTrue(csv.contains("\"Say \"\"why\"\", first\""))
        val json = CommentExport.json("Demo", listOf(c))
        assertTrue(json.contains("\"transcript\""))
    }

    @Test fun clock() {
        assertEquals("0:05", TimeFormat.clock(5.0))
        assertEquals("12:34", TimeFormat.clock(754.0))
        assertEquals("1:02:03", TimeFormat.clock(3723.0))
    }
}
