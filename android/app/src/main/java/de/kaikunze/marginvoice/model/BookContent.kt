package de.kaikunze.marginvoice.model

import de.kaikunze.marginvoice.core.AnnotatedTextExport
import de.kaikunze.marginvoice.core.ChapterMark
import de.kaikunze.marginvoice.core.ChapterMatcher
import de.kaikunze.marginvoice.core.NarrationText
import de.kaikunze.marginvoice.core.TimeTextMap
import de.kaikunze.marginvoice.core.TimingSidecar

/** The parsed narration text of a book plus its time↔text map. */
class BookContent(val text: NarrationText, chapters: List<ChapterMark>, duration: Double, sidecar: TimingSidecar?) {
    val map: TimeTextMap = if (sidecar != null && sidecar.chunks.isNotEmpty()) {
        TimeTextMap.fromSidecar(sidecar.convertedToCharacterOffsets(text), text.count, duration)
    } else {
        TimeTextMap.fromChapters(chapters, text, duration)
    }

    /** Paragraph offsets that are chapter headings (matched to m4b chapters). */
    val headingOffsets: Set<Int> = ChapterMatcher.match(chapters, text).map { it.textOffset }.toSet()

    val isExact: Boolean get() = map.isExact

    fun offset(time: Double): Int = map.offset(at = time)

    fun sentenceIndex(time: Double): Int? = if (text.sentences.isEmpty()) null else text.sentenceIndex(containing = offset(time))

    fun sentence(index: Int): String? = text.sentences.getOrNull(index)?.let { text.substring(it.range) }

    /** Start time of the sentence playing at `time`. */
    fun sentenceStartTime(time: Double): Double {
        val i = sentenceIndex(time) ?: return time
        return map.time(at = text.sentences[i].range.first)
    }

    /** What the book narrated in the `seconds` before `time` (for echo rejection). */
    fun recentText(before: Double, seconds: Double): String {
        val a = offset(maxOf(0.0, before - seconds))
        val b = offset(before)
        // the map is approximate without a sidecar: widen by a sentence's worth
        val pad = if (isExact) 20 else 120
        return text.substring(maxOf(0, a - pad) until minOf(text.count, b + pad))
    }

    /** The sentence a comment anchored at `offset` refers to. */
    fun attachedSentence(offset: Int): String? =
        if (text.sentences.isEmpty()) null else sentence(AnnotatedTextExport.attachSentence(offset, text))
}
