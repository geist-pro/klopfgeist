package de.kaikunze.marginvoice.core.conversion

import java.text.Normalizer

/**
 * Text cleaning ported from doc2audio's `cleanup.py`: typography, citations,
 * boilerplate, hyphenation and heading clean-up.
 */
object TextCleanup {
    private val citationBracket = rx("""\s?\[(?:\s*\d+\s*(?:[\s,;–\-]+\s*\d+\s*)*)\]""")
    private val citationKey = rx("""\s?\[[A-Za-z][A-Za-z\-]+\d{2,4}[a-z]?(?:\s*,\s*[A-Za-z][A-Za-z\-]+\d{2,4}[a-z]?)*\]""")
    private val citationParen = rx(
        """\s?\(\s*(?:(?:e\.g\.,?|see|cf\.)\s*)?(?:[A-ZÄÖÜ][\w\-]+(?:\s+(?:and|&|und|et\s+al\.?))?\s*(?:[A-ZÄÖÜ][\w\-]+\s*)?,?\s*\d{4}[a-z]?\s*;?\s*)+\)""")
    private val refPlaceholder = rx("""\s?\[(?:REF|ref|citation needed|TODO)[^\]]*\]""")
    private val url = rx("""(?:https?://|www\.)\S+""")
    private val doi = rx("""\b(?:doi:|DOI:)\s*\S+""", ignoreCase = true)
    private val email = rx("""\b[\w.+-]+@\w[\w.-]*\.\w{2,}\b""")
    private val affiliation = rx(
        """\b(University|Universit[äa]t|Institute|Institut|Laboratory|Department|School|College|Hochschule|Fakult[äa]t|Faculty|Corporation|Inc\.|Ltd\.|GmbH|Research Center|Centre)\b""",
        ignoreCase = true)
    private val personTitle = rx("""\b(Prof\.|Dr\.|Ph\.?D\.?|M\.Sc\.|Dipl\.-Ing\.)""")
    private val referencesHeader = rx(
        """^\s*(?:[\dIVX]+\.?\s*)?(References|Bibliography|Acknowledg(?:e?)ments?|Literatur\w*|Referenzen|Quellen(?:verzeichnis)?|Danksagung)\s*$""",
        ignoreCase = true)
    private val appendixHeader = rx("""^\s*(?:[A-Z]\.?\s+)?Appendi(?:x|ces)\b""", ignoreCase = true)
    private val figureCaption = rx("""^\s*(?:Figure|Fig\.?|Abbildung|Abb\.)\s*(\d+(?:\.\d+)*[a-z]?)\s*[.:\-–]?\s*(.*)$""", ignoreCase = true, dotAll = true)
    private val tableCaption = rx("""^\s*(?:Table|Tabelle|Tab\.)\s*([\dIVX]+(?:\.\d+)*[a-z]?)\s*[.:\-–]?\s*(.*)$""", ignoreCase = true, dotAll = true)
    private val sectionNumber = rx("""^\s*(?:[\dIVXLC]+(?:\.\d+)*\.?|[A-Z]\.)\s+""")
    private val skippedSection = rx(
        """^(acknowledge?ments?|funding|danksagung|conflicts? of interest|competing interests|author contributions|data availability)\b""",
        ignoreCase = true)
    private val frontMatter = rx(
        """^(CCS Concepts|Additional Key Words|Key ?words|Index Terms|Schlüsselwörter|ACM Reference Format|Authors?[’'] ?(Contact Information|addresses)|This work is licensed|Permission to make digital|Publication rights licensed|©|Corresponding author)""",
        ignoreCase = true)
    private val runInHeading = rx("""^(\d+(?:\.\d+){1,3})\s+(\p{Lu}[^.?!]{2,90}?)[.:]\s+(?=\p{Lu}|\()""")
    private val numbering = rx("""^(\d+(?:\.\d+)*)\.?\s+\S""")

    private val boilerplatePrefixes = listOf(
        "permission to make digital", "copyrights for components", "©", "(c) 20", "copyright 20",
        "acm reference format", "acm isbn", "acm/ims", "isbn ", "issn ", "manuscript submitted", "preprint", "arxiv:",
        "this work is licensed", "creative commons", "ccs concepts", "additional key words", "keywords", "author keywords",
        "index terms", "categories and subject descriptors", "general terms", "authors' address", "author's address",
        "authors' addresses", "corresponding author", "* corresponding", "∗ corresponding", "received ", "accepted ",
        "published online", "proceedings of", "in proceedings", "conference on", "ieee international", "acm ",
        "this is the author", "final version", "to appear in", "e-mail:", "email:",
    )

    private fun String.trimSpaces() = trim { it == ' ' || it == '\t' }

    /** Unicode normalisation plus the typographic fixes TTS engines trip on. */
    fun normalize(input: String): String {
        var s = Normalizer.normalize(input, Normalizer.Form.NFKC) // also expands ﬁ ﬂ ligatures
        val map = listOf(
            "­" to "", "​" to "", " " to " ", " " to " ", " " to " ",
            "’" to "'", "‘" to "'", "“" to "\"", "”" to "\"", "„" to "\"",
        )
        for ((a, b) in map) s = s.replace(a, b)
        s = s.rxReplace("[ \\t]+", " ")
        return s.trim { it.isWhitespace() }
    }

    fun stripCitations(input: String): String {
        var s = input
        for (re in listOf(citationBracket, citationKey, citationParen, refPlaceholder, url, doi)) s = re.replace(s, "")
        s = s.rxReplace("""\s+([.,;:!?])""", "$1")
        s = s.rxReplace("""([,;])\s*([,;])""", "$1")
        s = s.rxReplace("""\(\s*\)""", "")
        return s.rxReplace("""\s{2,}""", " ").trimSpaces()
    }

    fun isBoilerplate(line: String): Boolean {
        val low = line.trimSpaces().lowercase()
        return boilerplatePrefixes.any { low.startsWith(it) }
    }

    /** Author / affiliation lines on a title page. */
    fun looksLikeAuthorLine(line: String): Boolean {
        val s = line.trimSpaces()
        if (s.isEmpty()) return false
        if (email.containsMatchIn(s)) return true
        if (personTitle.containsMatchIn(s) && s.length < 220) return true
        if (affiliation.containsMatchIn(s) && s.length < 220 && s.count { it == '.' } <= 3) return true
        val words = s.split(' ').filter { it.isNotEmpty() }
        if (words.size > 1 && words.size <= 12 && !(s.endsWith(".") || s.endsWith("?") || s.endsWith("!"))) {
            val caps = words.count { it.first().isUpperCase() || it in listOf("and", "&", "und") }
            if (caps.toDouble() / words.size > 0.8) return true
        }
        return false
    }

    /** Joins PDF lines: "collabora-" + "tive" → "collaborative". */
    fun joinLines(lines: List<String>): String {
        val out = StringBuilder()
        for (raw in lines) {
            val line = raw.trimSpaces()
            if (line.isEmpty()) continue
            if (out.endsWith("-") && line.first().isLowerCase() && out.length > 1) {
                out.setLength(out.length - 1)
                out.append(line)
            } else {
                if (out.isNotEmpty()) out.append(' ')
                out.append(line)
            }
        }
        return out.toString()
    }

    fun isReferencesHeading(s: String) = referencesHeader.containsMatchIn(s)

    /** Sections that are left out with everything up to the next heading. */
    fun isSkippedSection(s: String) = skippedSection.containsMatchIn(s)

    /**
     * Title-page metadata that runs on for a few lines (ACM, Springer, Elsevier):
     * keywords, CCS concepts, contact information, license and citation format.
     */
    fun startsFrontMatterBlock(s: String) = frontMatter.containsMatchIn(s)

    /**
     * "SHILONG LIU, Institute of …", "CHAO ZHOU∗", ", Institute of …": author
     * lines set in capitals (ACM journals) and their split-off affiliations.
     */
    fun looksLikeCapitalizedAuthorLine(s: String): Boolean {
        if (s.startsWith(",")) return true
        val words = s.split(' ').filter { it.isNotEmpty() }.take(4)
        val caps = words.takeWhile { w ->
            val letters = w.filter { it.isLetter() }
            letters.length >= 2 && letters == letters.uppercase()
        }
        if (caps.size < 2) return false
        return s.contains(",") || s.contains("∗") || s.contains("*") || caps.size == words.size
    }

    data class RunIn(val heading: String, val level: Int, val rest: String)

    /**
     * "2.3.1 Pain Relief Based on Visual Distraction. The exploration …":
     * a numbered run-in heading at the start of a paragraph.
     */
    fun splitRunInHeading(s: String): RunIn? {
        val m = runInHeading.find(s) ?: return null
        val head = m.value
        val depth = numberingDepth(head) ?: return null
        val title = head.rxReplace("""^[\d.]+\s+""", "").trim { it in ".: " }
        return RunIn(title, minOf(depth, 3), s.substring(m.range.last + 1))
    }

    fun isAppendixHeading(s: String) = appendixHeader.containsMatchIn(s)

    data class Caption(val number: String, val caption: String)

    /** ("3", "Caption text") for "Figure 3: Caption text". */
    fun figureCaption(s: String): Caption? = caption(figureCaption, s)
    fun tableCaption(s: String): Caption? = caption(tableCaption, s)

    private fun caption(re: Regex, s: String): Caption? {
        val m = re.find(s) ?: return null
        val n = m.groups[1] ?: return null
        val c = m.groups[2] ?: return null
        // "Figure 6c evaluates …", "Figure 5a, the …" are sentences about a figure, not its caption
        val between = s.substring(n.range.last + 1, c.range.first).trimSpaces()
        val first = c.value.firstOrNull()
        if (between.isEmpty() && first != null && (first.isLowerCase() || first == ',')) return null
        return Caption(n.value, c.value.trimSpaces())
    }

    /** "3.2 RESULTS." → "Results" */
    fun cleanHeading(input: String): String {
        // a second number appears when two overlapping heading lines were merged ("3.4 3.4.1 Experiment 2")
        var s = sectionNumber.replace(sectionNumber.replace(normalize(input), ""), "")
        while (s.isNotEmpty() && s.last() in " .:") s = s.dropLast(1)
        if (s.length > 3 && s == s.uppercase() && s.any { it.isLetter() }) s = s.capitalizedWords()
        return s
    }

    // As on iOS, Ω and ∥ are left out: there the combining accents after them fuse into one Character.
    private val mathSymbols = "=+−∑∫√∂∇≤≥≈±×·^_[]{}|∞∈∀∃⊂→←↦αβγδεζηθικλμνξπρστυφχψωΓΔΘΛΞΠΣΦΨ".toSet()

    private fun isCombining(c: Char) = c.code in 0x300..0x36F || c.code in 0xFE00..0xFE0F

    /** Share of characters that are math symbols, Greek letters or combining accents. */
    fun mathRatio(s: String): Double {
        val chars = s.filter { !it.isWhitespace() }
        if (chars.isEmpty()) return 0.0
        val math = chars.count { it in mathSymbols || isCombining(it) }
        return math.toDouble() / chars.length
    }

    /** A display-equation line: math-heavy with few real words, or ending in "(n)". */
    fun isEquationLine(s: String): Boolean {
        val tokens = s.split(' ').filter { it.isNotEmpty() }
        val words = tokens.count { w -> w.length >= 4 && w.all { it.isLetter() } }
        val numbered = s.rxContains("""\(\d{1,3}\)\s*$""")
        val ratio = mathRatio(s)
        val shortNumbered = numbered && tokens.size <= 3 // "a. (6)", ". (14)"
        return (ratio > 0.12 && words <= 2) || (numbered && ratio > 0.05 && words <= 4) || shortNumbered
    }

    /** The "(12)" equation number at the end of a line. */
    fun equationNumber(s: String): String? {
        val m = rx("""\((\d{1,3})\)\s*$""").find(s) ?: return null
        return m.value.filter { it.isDigit() }
    }

    /** Text that can be a heading: mostly letters, no formulas, units or percentages. */
    fun looksLikeHeadingText(s: String): Boolean {
        if (!s.any { it.isLetter() } || s.any { it in "=+%/<>∑∫∥[]{}|" }) return false
        val chars = s.filter { !it.isWhitespace() }
        val letters = chars.count { it.isLetter() }
        return letters.toDouble() / maxOf(1, chars.length) >= 0.6 && mathRatio(s) < 0.05
    }

    /** Section numbering depth: "2" → 1, "2.1" → 2, "2.1.3" → 3; null if unnumbered. */
    fun numberingDepth(s: String): Int? {
        val t = s.trimSpaces()
        val m = numbering.find(t)
        if (m == null) {
            if (t.rxContains("""^[IVX]+\.\s+\S""")) return 1 // "IV. RESULTS"
            if (t.rxContains("""^[A-H]\.\s+[A-Z]""")) return 2 // "B. Related Work"
            return null
        }
        val parts = m.groupValues[1].split('.').mapNotNull { it.toIntOrNull() }
        val first = parts.firstOrNull() ?: return null
        if (first > 20 || parts.any { it > 30 }) return null // "200 Hz" is not a section
        return parts.size
    }
}
