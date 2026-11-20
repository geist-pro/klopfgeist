package de.kaikunze.marginvoice.core

import kotlinx.serialization.Serializable

/** A chapter marker read from the m4b (media time, seconds). */
@Serializable
data class ChapterMark(val title: String, val start: Double, val end: Double)

/** The chapter playing at `time` (a chapter starts counting 50 ms early). */
fun List<ChapterMark>.indexAt(time: Double): Int? = indexOfLast { it.start <= time + 0.05 }.takeIf { it >= 0 }

/** Matches m4b chapters to heading lines in the narration text. */
object ChapterMatcher {
    data class Match(val chapterIndex: Int, val textOffset: Int)

    fun normalizeTitle(s: String): String {
        val folded = TextSegmentation.normalize(s)
        val kept = buildString {
            var i = 0
            while (i < folded.length) {
                val cp = folded.codePointAt(i)
                if (Character.isLetterOrDigit(cp)) appendCodePoint(cp) else append(' ')
                i += Character.charCount(cp)
            }
        }
        return kept.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    private val abstractAliases = setOf("abstract", "zusammenfassung", "summary")

    internal fun titlesMatch(a: String, b: String): Boolean {
        if (a == b) return true
        if (a in abstractAliases && b in abstractAliases) return true
        // doc2audio truncates chapter titles to 120 characters
        val shorter = if (a.length < b.length) a else b
        val longer = if (a.length < b.length) b else a
        return shorter.length >= 20 && longer.startsWith(shorter)
    }

    /** Greedy in-order matching. Chapters that cannot be found are skipped. */
    fun match(chapters: List<ChapterMark>, text: NarrationText): List<Match> {
        val headings = text.headings.map { it.offset to normalizeTitle(it.title) }
        val result = ArrayList<Match>()
        var cursor = 0
        for ((ci, ch) in chapters.withIndex()) {
            val wanted = normalizeTitle(ch.title)
            if (wanted.isEmpty()) continue
            var j = cursor
            while (j < headings.size && !titlesMatch(headings[j].second, wanted)) j++
            if (j < headings.size) {
                result.add(Match(ci, headings[j].first))
                cursor = j + 1
            }
        }
        return result
    }
}

/** Piecewise-linear mapping between book media time and character offsets. */
data class TimeTextMap(val anchors: List<Anchor>, val isExact: Boolean) {
    data class Anchor(val time: Double, val offset: Int)

    fun offset(at: Double): Int {
        val first = anchors.firstOrNull() ?: return 0
        if (at <= first.time) return first.offset
        for (i in 1 until anchors.size) {
            if (at <= anchors[i].time) {
                val a = anchors[i - 1]
                val b = anchors[i]
                val f = (at - a.time) / maxOf(b.time - a.time, 1e-9)
                return a.offset + ((b.offset - a.offset) * f).swiftRounded().toInt()
            }
        }
        return anchors.last().offset
    }

    fun time(at: Int): Double {
        val first = anchors.firstOrNull() ?: return 0.0
        if (at <= first.offset) return first.time
        for (i in 1 until anchors.size) {
            if (at <= anchors[i].offset) {
                val a = anchors[i - 1]
                val b = anchors[i]
                val span = b.offset - a.offset
                if (span == 0) return a.time
                val f = (at - a.offset).toDouble() / span
                return a.time + (b.time - a.time) * f
            }
        }
        return anchors.last().time
    }

    companion object {
        operator fun invoke(raw: List<Anchor>, duration: Double, textLength: Int, isExact: Boolean = false): TimeTextMap {
            val pts = raw.filter { it.time >= 0 && it.offset >= 0 && it.offset <= textLength }.sortedBy { it.time }
            val mono = arrayListOf(Anchor(0.0, 0))
            for (p in pts) {
                if (p.time > mono.last().time && p.offset >= mono.last().offset) mono.add(p)
            }
            if (duration > mono.last().time) mono.add(Anchor(duration, maxOf(textLength, mono.last().offset)))
            return TimeTextMap(mono, isExact)
        }

        /** Build from m4b chapters matched against the text (approximate). */
        fun fromChapters(chapters: List<ChapterMark>, text: NarrationText, duration: Double): TimeTextMap {
            val anchors = ChapterMatcher.match(chapters, text).map { Anchor(chapters[it.chapterIndex].start, it.textOffset) }
            return invoke(anchors, duration, text.count)
        }

        /** Build from a doc2audio timing sidecar (exact at chunk boundaries). */
        fun fromSidecar(sidecar: TimingSidecar, textLength: Int, duration: Double): TimeTextMap {
            val anchors = ArrayList<Anchor>()
            for (c in sidecar.chunks) {
                anchors.add(Anchor(c.start, c.charStart))
                anchors.add(Anchor(c.end, c.charEnd))
            }
            return invoke(anchors, maxOf(duration, sidecar.chunks.lastOrNull()?.end ?: 0.0), textLength, isExact = true)
        }
    }
}

/** Swift `rounded()`: half away from zero (Kotlin's `roundToLong` rounds half up). */
internal fun Double.swiftRounded(): Double = if (this < 0) -(-this).let { Math.floor(it + 0.5) } else Math.floor(this + 0.5)

