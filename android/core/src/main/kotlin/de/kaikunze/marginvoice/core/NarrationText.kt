package de.kaikunze.marginvoice.core

/**
 * The narration text written by doc2audio next to the audiobook, parsed into
 * paragraphs, sentences, words and chapter headings with character offsets.
 *
 * All offsets are UTF-16 indices into [string], which keeps slicing and
 * insertion trivial for exports.
 */
class NarrationText(text: String) {
    data class Paragraph(val range: IntRange, val text: String)
    data class Sentence(val range: IntRange, val paragraphIndex: Int)
    data class Word(val range: IntRange, val normalized: String)

    /** A heading as it appears in the text: "Section: Introduction." / "Abstract." / "Results." */
    data class Heading(val paragraphIndex: Int, val title: String, val offset: Int)

    val string: String = text.replace("\r\n", "\n")
    val paragraphs: List<Paragraph> = splitParagraphs(string)
    val sentences: List<Sentence>
    val words: List<Word>

    val count: Int get() = string.length

    init {
        val sents = ArrayList<Sentence>()
        for ((pi, p) in paragraphs.withIndex()) {
            for (r in TextSegmentation.sentenceRanges(string, p.range)) sents.add(Sentence(r, pi))
        }
        sentences = sents
        words = TextSegmentation.words(string, 0 until string.length)
    }

    fun substring(range: IntRange): String {
        val lo = range.first.coerceIn(0, count)
        val hi = (range.last + 1).coerceIn(lo, count)
        return string.substring(lo, hi)
    }

    fun substring(from: Int, to: Int): String = substring(from until to)

    /** Sentence containing `offset` (or the closest one before it). */
    fun sentenceIndex(containing: Int): Int? {
        val offset = containing
        if (sentences.isEmpty()) return null
        var lo = 0
        var hi = sentences.size - 1
        if (offset < sentences[0].range.first) return 0
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (sentences[mid].range.first <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Index of the first word whose range starts at or after `offset`. */
    fun wordIndex(atOrAfter: Int): Int {
        val offset = atOrAfter
        var lo = 0
        var hi = words.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (words[mid].range.first < offset) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Candidate headings: short paragraphs that look like doc2audio heading lines. */
    val headings: List<Heading> by lazy {
        val result = ArrayList<Heading>()
        for ((i, p) in paragraphs.withIndex()) {
            val t = p.text.trimWs()
            if (t.length > 160 || t.contains('\n')) continue
            var title = t
            var explicit = false
            for (prefix in listOf("Section: ", "Abschnitt: ")) {
                if (title.startsWith(prefix)) {
                    title = title.removePrefix(prefix)
                    explicit = true
                }
            }
            if (title.endsWith(".")) title = title.dropLast(1)
            // Implicit headings (level-2 headings, "Abstract.") are a single short sentence.
            if (!explicit) {
                val sentenceCount = sentences.count { it.paragraphIndex == i }
                if (sentenceCount != 1 || title.split(' ').count { it.isNotEmpty() } > 14) continue
            }
            result.add(Heading(i, title, p.range.first))
        }
        result
    }

    companion object {
        internal fun splitParagraphs(chars: String): List<Paragraph> {
            val result = ArrayList<Paragraph>()
            var i = 0
            val n = chars.length
            while (i < n) {
                // skip blank lines / whitespace
                while (i < n && chars[i].isSwiftWhitespace()) i++
                if (i >= n) break
                val start = i
                var end = i
                while (i < n) {
                    if (chars[i] == '\n') {
                        // paragraph ends at a blank line
                        var j = i + 1
                        while (j < n && (chars[j] == ' ' || chars[j] == '\t')) j++
                        if (j >= n || chars[j] == '\n') break
                    }
                    i++
                    if (!chars[i - 1].isSwiftWhitespace()) end = i
                }
                if (end > start) result.add(Paragraph(start until end, chars.substring(start, end)))
            }
            return result
        }
    }
}

/** Sentence and word segmentation, mirroring doc2audio's `chunking.split_sentences`. */
object TextSegmentation {
    internal val abbreviations = setOf(
        "e.g", "i.e", "cf", "vs", "etc", "fig", "figs", "eq", "eqs", "tab", "prof", "dr", "mr", "mrs", "ms",
        "st", "no", "approx", "al", "z.b", "bzw", "ca", "d.h", "u.a", "vgl", "abb", "nr", "resp", "ref", "sec", "ch",
    )

    private fun isSentenceStart(c: Char): Boolean =
        c.isSwiftUppercase() || c.isSwiftNumber() || c == '"' || c == '\'' || c == '(' || c == '“'

    fun sentenceRanges(chars: String, range: IntRange): List<IntRange> {
        val result = ArrayList<IntRange>()
        var start = range.first
        var i = range.first
        val end = range.last + 1
        while (i < end) {
            val c = chars[i]
            if (c == '.' || c == '!' || c == '?') {
                var j = i + 1
                if (j < end && chars[j] in "\"')]”") j++
                val boundaryEnd = j
                var k = j
                var sawSpace = false
                while (k < end && chars[k].isSwiftWhitespace()) { k++; sawSpace = true }
                if (sawSpace && k < end && isSentenceStart(chars[k]) && !isAbbreviation(chars, start, i)) {
                    result.add(start until boundaryEnd)
                    start = k
                    i = k
                    continue
                }
            }
            i++
        }
        if (start < end) {
            // trim trailing whitespace
            var e = end
            while (e > start && chars[e - 1].isSwiftWhitespace()) e--
            if (e > start) result.add(start until e)
        }
        return result
    }

    internal fun isAbbreviation(chars: String, start: Int, dotIndex: Int): Boolean {
        if (chars[dotIndex] != '.') return false
        // last "word" (letters and dots) before the dot, max 12 chars back
        var k = dotIndex - 1
        val lower = maxOf(start, dotIndex - 12)
        while (k >= lower && (chars[k].isSwiftLetter() || chars[k].isSwiftNumber() || chars[k] == '.' || chars[k] == '_')) k--
        val raw = chars.substring(k + 1, dotIndex).trim('.')
        if (raw.isEmpty()) return false
        if (raw.lowercase() in abbreviations) return true
        if (raw.length == 1 && raw[0].isLetter() && raw[0].isSwiftUppercase()) return true // initials
        return false
    }

    fun words(chars: String, range: IntRange): List<NarrationText.Word> {
        val result = ArrayList<NarrationText.Word>()
        var i = range.first
        val end = range.last + 1
        while (i < end) {
            if (chars[i].isLetter() || chars[i].isSwiftNumber()) {
                val s = i
                while (i < end && (chars[i].isSwiftLetter() || chars[i].isSwiftNumber() || chars[i] == '\'' || chars[i] == '’')) i++
                val token = normalize(chars.substring(s, i))
                if (token.isNotEmpty()) result.add(NarrationText.Word(s until i, token))
            } else {
                i++
            }
        }
        return result
    }

    /** Lowercased, diacritic-folded, apostrophes removed. */
    fun normalize(s: String): String = fold(s).replace("'", "").replace("’", "")

    /** Splits free text (e.g. a recognizer transcript) into normalized tokens. */
    fun tokens(text: String): List<String> = words(text, text.indices).map { it.normalized }
}
