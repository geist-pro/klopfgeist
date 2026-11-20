package de.kaikunze.marginvoice.core

import java.time.Instant
import java.util.UUID

/** A comment or bookmark as seen by the exporters (decoupled from the database). */
data class CommentRecord(
    val id: UUID = UUID.randomUUID(),
    val kind: Kind = Kind.COMMENT,
    /** Book media time of the anchor, seconds. */
    val mediaTime: Double,
    /** Character offset in the narration text where the comment belongs. */
    val textOffset: Int,
    /** 0...1; below `AnnotatedTextExport.LOW_CONFIDENCE` the anchor is only estimated. */
    val confidence: Double,
    val chapter: String? = null,
    val transcript: String,
    /** File name of the recorded comment audio, relative to the export folder. */
    val audioFile: String? = null,
    val createdAt: Instant = Instant.now(),
    /** Bookmarks of a passage: where the passage ends (media seconds and text offset). */
    val endMediaTime: Double? = null,
    val endTextOffset: Int? = null,
    /** The review category the comment was spoken with ("weakness: …"), if any. */
    val tag: ReviewTag? = null,
) {
    enum class Kind(val raw: String) { COMMENT("comment"), BOOKMARK("bookmark") }

    /** Sentences a bookmark covers: the one just heard, or the whole passage. */
    fun sentenceRange(text: NarrationText): IntRange {
        val first = AnnotatedTextExport.attachSentence(textOffset, text)
        val last = endTextOffset?.let { AnnotatedTextExport.attachSentence(it, text) } ?: first
        return first..maxOf(first, last)
    }

    /** The text a comment refers to: the sentence just heard, or a bookmarked passage. */
    fun quotedText(text: NarrationText): String {
        if (text.sentences.isEmpty()) return ""
        val full = sentenceRange(text)
        val range = if (kind == Kind.BOOKMARK) full else full.first..full.first
        val start = text.sentences[range.first].range.first
        val end = text.sentences[minOf(range.last, text.sentences.size - 1)].range.upper
        return text.substring(start until end).replace("\n\n", " ")
    }

    companion object {
        /** Text offset after moving `sentenceNudge` sentences from `base`: the end of the target sentence. */
        fun nudgedOffset(base: Int, sentenceNudge: Int, text: NarrationText?): Int {
            if (sentenceNudge == 0 || text == null || text.sentences.isEmpty()) return base
            val current = AnnotatedTextExport.attachSentence(base, text)
            val target = (current + sentenceNudge).coerceIn(0, text.sentences.size - 1)
            return text.sentences[target].range.upper
        }

        /** End of a bookmarked passage, moved by the same amount as the refined start, and never before it. */
        fun passageEnd(estimatedEnd: Int, estimatedStart: Int?, refinedStart: Int?): Int {
            if (refinedStart == null || estimatedStart == null) return estimatedEnd
            return maxOf(refinedStart, estimatedEnd + refinedStart - estimatedStart)
        }
    }
}

object TimeFormat {
    /** "4:05", "12:34", "1:02:03". */
    fun clock(seconds: Double): String {
        if (!seconds.isFinite()) return "0:00"
        val t = maxOf(0, Math.floor(seconds).toLong())
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) fmt("%d:%02d:%02d", h, m, s) else fmt("%d:%02d", m, s)
    }
}
