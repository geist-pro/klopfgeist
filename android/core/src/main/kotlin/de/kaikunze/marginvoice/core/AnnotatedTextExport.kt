package de.kaikunze.marginvoice.core

/**
 * The narration text with every comment inserted after the sentence it refers to,
 * as Markdown with CriticMarkup: `{>> comment <<}`. Sentences whose anchor is only
 * estimated are additionally highlighted `{==like this==}` so they can be checked.
 */
object AnnotatedTextExport {
    const val LOW_CONFIDENCE = 0.5

    /**
     * @param headingOffsets paragraph start offsets that are chapter headings
     *   (from `ChapterMatcher`); those paragraphs become `##` headings.
     */
    fun markdown(text: NarrationText, comments: List<CommentRecord>, title: String? = null, headingOffsets: Set<Int> = emptySet()): String {
        // group comments by the sentence they attach to; bookmarks attach after the
        // last sentence of their passage and highlight all of it
        val bySentence = HashMap<Int, MutableList<CommentRecord>>()
        val highlighted = HashSet<Int>()
        for (c in comments.sortedWith(compareBy({ it.textOffset }, { it.mediaTime }))) {
            val range = if (c.kind == CommentRecord.Kind.BOOKMARK) c.sentenceRange(text)
            else attachSentence(c.textOffset, text).let { it..it }
            bySentence.getOrPut(range.last) { ArrayList() }.add(c)
            if (c.kind == CommentRecord.Kind.BOOKMARK || c.confidence < LOW_CONFIDENCE) highlighted.addAll(range)
        }

        val out = StringBuilder()
        if (title != null) out.append("# $title\n\n")
        var sentenceIndex = 0
        var closedForNotes = false
        for ((pi, p) in text.paragraphs.withIndex()) {
            if (p.range.first in headingOffsets) {
                out.append("## ${headingTitle(p.text)}")
            } else {
                val line = StringBuilder()
                var cursor = p.range.first
                while (sentenceIndex < text.sentences.size && text.sentences[sentenceIndex].paragraphIndex == pi) {
                    val s = text.sentences[sentenceIndex]
                    line.append(text.substring(cursor until s.range.first))
                    val sentence = text.substring(s.range)
                    val notes = bySentence[sentenceIndex].orEmpty()
                    // one {== … ==} per paragraph run of highlighted sentences
                    val lit = sentenceIndex in highlighted
                    val opens = lit && (closedForNotes || sentenceIndex == 0 || (sentenceIndex - 1) !in highlighted ||
                        text.sentences[sentenceIndex - 1].paragraphIndex != pi)
                    val next = sentenceIndex + 1
                    val closes = lit && (notes.isNotEmpty() || next >= text.sentences.size || next !in highlighted ||
                        text.sentences[next].paragraphIndex != pi)
                    line.append(if (opens) "{==" else "").append(sentence).append(if (closes) "==}" else "")
                    for (c in notes) line.append(critic(c))
                    closedForNotes = closes && notes.isNotEmpty()
                    cursor = s.range.upper
                    sentenceIndex++
                }
                line.append(text.substring(cursor until p.range.upper))
                out.append(line)
            }
            // comments attached to a heading paragraph (no sentence of its own beyond the heading)
            if (p.range.first in headingOffsets) {
                while (sentenceIndex < text.sentences.size && text.sentences[sentenceIndex].paragraphIndex == pi) {
                    for (c in bySentence[sentenceIndex].orEmpty()) out.append(critic(c))
                    sentenceIndex++
                }
            }
            out.append("\n\n")
        }
        return out.toString().trimWs() + "\n"
    }

    /**
     * A comment made at `offset` refers to what was just heard: the sentence that
     * contains the last character before the offset.
     */
    fun attachSentence(offset: Int, text: NarrationText): Int = text.sentenceIndex(containing = maxOf(0, offset - 1)) ?: 0

    internal fun critic(c: CommentRecord): String {
        val body = when (c.kind) {
            CommentRecord.Kind.BOOKMARK -> c.endMediaTime?.let { "Bookmark ${TimeFormat.clock(c.mediaTime)}–${TimeFormat.clock(it)}" }
                ?: "Bookmark ${TimeFormat.clock(c.mediaTime)}"
            CommentRecord.Kind.COMMENT -> {
                val text = c.transcript.trimWs().ifEmpty { "Voice comment ${TimeFormat.clock(c.mediaTime)} (not transcribed)" }
                c.tag?.let { "[${it.displayName(VoiceCommand.Language.ENGLISH)}] $text" } ?: text
            }
        }
        val safe = body.replace("<<}", "<< }").replace("\n", " ")
        return " {>> $safe <<}"
    }

    internal fun headingTitle(s: String): String {
        var t = s.trimWs()
        for (prefix in listOf("Section: ", "Abschnitt: ")) if (t.startsWith(prefix)) t = t.removePrefix(prefix)
        if (t.endsWith(".")) t = t.dropLast(1)
        return t
    }
}
