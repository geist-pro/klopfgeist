package de.kaikunze.marginvoice.core

import java.util.UUID

/**
 * The comments of a book sorted into the shape of a review: a generic Markdown
 * draft, or the IEEE VR PCS form. Every item keeps the sentence it refers to.
 */
object ReviewDraftExport {
    enum class Layout { GENERIC, PCS_FORM }

    /**
     * @param polished reworded transcripts by comment id (from an on-device model); the spoken words stay underneath as "said: …".
     * @param summary a drafted summary paragraph placed before the summary notes.
     */
    fun markdown(
        title: String, comments: List<CommentRecord>, text: NarrationText?, layout: Layout, language: VoiceCommand.Language,
        polished: Map<UUID, String> = emptyMap(), summary: String? = null,
    ): String {
        val de = language == VoiceCommand.Language.GERMAN
        val sorted = comments.sortedWith(compareBy({ it.textOffset }, { it.mediaTime }))
        val notes = sorted.filter { it.kind == CommentRecord.Kind.COMMENT }
        val bookmarks = sorted.filter { it.kind == CommentRecord.Kind.BOOKMARK }
        fun group(tag: ReviewTag?) = notes.filter { it.tag == tag }

        fun place(c: CommentRecord): String =
            listOfNotNull(c.chapter?.takeIf { it.isNotEmpty() }?.let { "§$it" }, TimeFormat.clock(c.mediaTime)).joinToString(" · ")
        fun quote(c: CommentRecord): String? {
            if (text == null || text.sentences.isEmpty()) return null
            return c.quotedText(text).trimWs().ifEmpty { null }
        }
        fun body(c: CommentRecord): String {
            val raw = c.transcript.trimWs()
            if (raw.isEmpty()) return if (de) "(nicht transkribiert)" else "(not transcribed)"
            return polished[c.id] ?: raw
        }
        // "- text (§Chapter · m:ss)" with the quoted sentence and, when reworded, the spoken words beneath
        fun item(c: CommentRecord, withQuote: Boolean): String {
            val lines = arrayListOf("- ${body(c)} (${place(c)})")
            if (withQuote) quote(c)?.let { lines.add("  > $it") }
            if (polished[c.id] != null && c.transcript.trimWs().isNotEmpty()) lines.add("  > ${if (de) "gesagt" else "said"}: ${c.transcript.trimWs()}")
            return lines.joinToString("\n")
        }
        fun list(items: List<CommentRecord>, withQuote: Boolean, empty: String) =
            if (items.isEmpty()) empty else items.joinToString("\n") { item(it, withQuote) }
        fun summaryText(empty: String): String {
            val paragraphs = ArrayList<String>()
            summary?.trim()?.takeIf { it.isNotEmpty() }?.let(paragraphs::add)
            for (c in group(ReviewTag.SUMMARY)) {
                paragraphs.add(body(c))
                if (polished[c.id] != null) paragraphs.add("> ${if (de) "gesagt" else "said"}: ${c.transcript}")
            }
            return if (paragraphs.isEmpty()) empty else paragraphs.joinToString("\n\n")
        }
        fun passages(empty: String): String {
            if (bookmarks.isEmpty()) return empty
            return bookmarks.joinToString("\n") { b ->
                val range = b.endMediaTime?.let { "${TimeFormat.clock(b.mediaTime)}–${TimeFormat.clock(it)}" } ?: TimeFormat.clock(b.mediaTime)
                "- ${quote(b) ?: if (de) "Lesezeichen" else "Bookmark"} ($range)"
            }
        }

        val none = if (de) "_Noch nichts._" else "_None yet._"
        val noSummary = if (de) "_Sage „Zusammenfassung: …“ beim Hören, oder schreibe sie hier._" else "_Say “summary: …” while listening, or write it here._"

        return when (layout) {
            Layout.GENERIC -> {
                val h = if (de) listOf("Gutachten", "Zusammenfassung", "Stärken", "Schwächen", "Fragen an die Autoren", "Kleinigkeiten", "Weitere Notizen", "Passagen zum Nachlesen")
                else listOf("Review", "Summary", "Strengths", "Weaknesses", "Questions for the authors", "Minor issues", "Other notes", "Passages to re-read")
                """
                |# ${h[0]}: $title
                |
                |## ${h[1]}
                |
                |${summaryText(noSummary)}
                |
                |## ${h[2]}
                |
                |${list(group(ReviewTag.STRENGTH), true, none)}
                |
                |## ${h[3]}
                |
                |${list(group(ReviewTag.WEAKNESS), true, none)}
                |
                |## ${h[4]}
                |
                |${list(group(ReviewTag.QUESTION), true, none)}
                |
                |## ${h[5]}
                |
                |${list(group(ReviewTag.MINOR), true, none)}
                |
                |## ${h[6]}
                |
                |${list(group(null), true, none)}
                |
                |## ${h[7]}
                |
                |${passages(none)}
                |""".trimMargin()
            }
            Layout.PCS_FORM -> {
                // the IEEE VR form: section names stay English, the notes fill the free-text parts
                val ratings = listOf(
                    "Expertise", "Clarity of Presentation", "Technical Soundness", "Reproducibility", "References", "IRB Approval Check",
                    "Participant Demographics", "Participant Diversity",
                ).joinToString("\n") { "## $it\n\nSelected: \n" }
                """
                |# PCS form — $title
                |
                |## Summary of Contributions
                |
                |${summaryText(noSummary)}
                |
                |Strengths:
                |${list(group(ReviewTag.STRENGTH), false, none)}
                |
                |Weaknesses:
                |${list(group(ReviewTag.WEAKNESS), false, none)}
                |
                |$ratings
                |## Overall Rating
                |
                |
                |
                |## Review
                |
                |Questions for the authors:
                |${list(group(ReviewTag.QUESTION), true, none)}
                |
                |Minor issues:
                |${list(group(ReviewTag.MINOR), true, none)}
                |
                |Other notes:
                |${list(group(null), true, none)}
                |
                |Passages to re-read:
                |${passages(none)}
                |""".trimMargin()
            }
        }
    }
}
