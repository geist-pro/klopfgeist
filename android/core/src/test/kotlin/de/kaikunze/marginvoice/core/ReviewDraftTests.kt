package de.kaikunze.marginvoice.core

import de.kaikunze.marginvoice.core.ReviewDraftExport.Layout
import de.kaikunze.marginvoice.core.VoiceCommand.Language
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewDraftTests {
    private val text = NarrationText("Section: Intro.\n\nFirst sentence here. Second sentence here.\n\nThird paragraph.\n")
    private fun end(s: String) = text.string.indexOf(s) + s.length

    private val comments = listOf(
        CommentRecord(mediaTime = 70.0, textOffset = end("Third paragraph."), confidence = 0.9, chapter = "Results", transcript = "No baseline.", tag = ReviewTag.WEAKNESS),
        CommentRecord(mediaTime = 20.0, textOffset = end("First sentence here."), confidence = 0.9, chapter = "Intro", transcript = "Ambiguous n.", tag = ReviewTag.WEAKNESS),
        CommentRecord(mediaTime = 30.0, textOffset = end("Second sentence here."), confidence = 0.9, chapter = "Intro", transcript = "Clean manipulation check.", tag = ReviewTag.STRENGTH),
        CommentRecord(mediaTime = 5.0, textOffset = 5, confidence = 0.9, chapter = "Intro", transcript = "The paper asks whether awe leaves a signature.", tag = ReviewTag.SUMMARY),
        CommentRecord(mediaTime = 40.0, textOffset = end("Second sentence here."), confidence = 0.9, chapter = "Intro", transcript = "Check the figure.", tag = null),
        CommentRecord(kind = CommentRecord.Kind.BOOKMARK, mediaTime = 60.0, textOffset = end("Third paragraph.") - 1, confidence = 1.0, transcript = ""),
    )

    @Test fun genericGroupsByTagInReadingOrder() {
        val md = ReviewDraftExport.markdown("Demo", comments, text, Layout.GENERIC, Language.ENGLISH)
        assertTrue(md, md.startsWith("# Review: Demo\n\n## Summary\n\nThe paper asks whether awe leaves a signature.\n\n## Strengths\n\n- Clean manipulation check. (§Intro · 0:30)\n  > Second sentence here.\n\n## Weaknesses\n\n- Ambiguous n. (§Intro · 0:20)\n  > First sentence here.\n- No baseline. (§Results · 1:10)\n  > Third paragraph.\n"))
        assertTrue(md.contains("## Questions for the authors\n\n_None yet._\n\n## Minor issues\n\n_None yet._"))
    }

    @Test fun pcsFormMatchesTheVenueLayout() {
        val md = ReviewDraftExport.markdown("Demo", comments, text, Layout.PCS_FORM, Language.ENGLISH)
        assertTrue(md, md.startsWith("# PCS form — Demo\n\n## Summary of Contributions\n\nThe paper asks whether awe leaves a signature.\n\nStrengths:\n- Clean manipulation check. (§Intro · 0:30)\n\nWeaknesses:\n- Ambiguous n. (§Intro · 0:20)\n- No baseline. (§Results · 1:10)\n\n## Expertise\n\nSelected: \n"))
        assertTrue(md.contains("## Overall Rating\n\n"))
        assertTrue(md, md.contains("## Review\n\nQuestions for the authors:\n_None yet._\n\nMinor issues:\n_None yet._\n\nOther notes:\n- Check the figure. (§Intro · 0:40)\n  > Second sentence here."))
    }

    @Test fun germanHeadings() {
        val md = ReviewDraftExport.markdown("Demo", emptyList(), text, Layout.GENERIC, Language.GERMAN)
        assertTrue(md, md.startsWith("# Gutachten: Demo\n\n## Zusammenfassung\n\n_Sage „Zusammenfassung: …“ beim Hören, oder schreibe sie hier._\n\n## Stärken\n\n_Noch nichts._\n\n## Schwächen"))
        assertTrue(md.contains("## Fragen an die Autoren") && md.contains("## Kleinigkeiten") && md.contains("## Weitere Notizen") && md.contains("## Passagen zum Nachlesen"))
    }

    @Test fun untaggedAndBookmarksLandInTheirSections() {
        val md = ReviewDraftExport.markdown("Demo", comments, text, Layout.GENERIC, Language.ENGLISH)
        assertTrue(md, md.contains("## Other notes\n\n- Check the figure. (§Intro · 0:40)\n  > Second sentence here.\n\n## Passages to re-read\n\n- Third paragraph. (1:00)\n"))
    }

    @Test fun emptyGroupsKeepPlaceholders() {
        val md = ReviewDraftExport.markdown("Demo", emptyList(), null, Layout.GENERIC, Language.ENGLISH)
        assertTrue(md.contains("## Summary\n\n_Say “summary: …” while listening, or write it here._\n\n## Strengths\n\n_None yet._"))
        assertTrue(md, md.endsWith("## Passages to re-read\n\n_None yet._\n"))
    }

    @Test fun polishedTextKeepsTheSpokenWords() {
        val c = comments[0]
        val md = ReviewDraftExport.markdown("Demo", listOf(c), text, Layout.GENERIC, Language.ENGLISH, polished = mapOf(c.id to "The evaluation lacks a baseline."), summary = "A drafted summary.")
        assertTrue(md.contains("## Summary\n\nA drafted summary.\n\n"))
        assertTrue(md, md.contains("- The evaluation lacks a baseline. (§Results · 1:10)\n  > Third paragraph.\n  > said: No baseline.\n"))
    }
}
