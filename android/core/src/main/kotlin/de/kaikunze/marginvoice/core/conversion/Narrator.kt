package de.kaikunze.marginvoice.core.conversion

import de.kaikunze.marginvoice.core.TextSegmentation
import de.kaikunze.marginvoice.core.upper

/**
 * Turns a [SourceDocument] into narration (port of doc2audio's `narrate.py`) and
 * plans the utterances to synthesize, with exact character ranges in the
 * narration text so every comment can be anchored precisely.
 */
object Narrator {
    data class Segment(
        val text: String,
        val pauseAfter: Double,
        /** Set on the first segment of a chapter (title, abstract, level ≤ 2 headings). */
        val chapter: String? = null,
        val kind: Kind,
    ) {
        enum class Kind { TITLE, HEADING, PARAGRAPH, ITEM, FIGURE, TABLE }
    }

    /** One synthesized unit (a sentence), located in the narration text. */
    data class Utterance(
        val text: String,
        /** Offsets into the narration text (same as `NarrationText`). */
        val range: IntRange,
        val pauseAfter: Double,
        val chapter: String?,
    )

    data class Plan(val text: String, val utterances: List<Utterance>) {
        val wordCount: Int get() = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        /** Rough length at a normal speaking rate (150 words/min). */
        val estimatedMinutes: Double get() = wordCount / 150.0
        val chapters: List<String> get() = utterances.mapNotNull { it.chapter }
    }

    private const val PAUSE_HEADING = 0.8
    private const val PAUSE_PARAGRAPH = 0.5
    private const val PAUSE_ITEM = 0.3
    internal const val PAUSE_SENTENCE = 0.15

    // Phrases and abbreviations

    private class Phrases(
        val section: (String) -> String, val abstract: String, val figure: (String, String) -> String,
        val table: (String, String) -> String, val tableSkip: String, val tableNoCaption: (String) -> String,
        val equation: (String) -> String,
    )

    private fun phrases(lang: String) = if (lang == "de") Phrases(
        section = { "Abschnitt: $it." }, abstract = "Zusammenfassung.", figure = { n, c -> "Abbildung $n: $c" },
        table = { n, c -> "Tabelle $n: $c" }, tableSkip = "Tabelle übersprungen.", tableNoCaption = { "Tabelle $it übersprungen." },
        equation = { "Gleichung $it." },
    ) else Phrases(
        section = { "Section: $it." }, abstract = "Abstract.", figure = { n, c -> "Figure $n: $c" },
        table = { n, c -> "Table $n: $c" }, tableSkip = "Table skipped.", tableNoCaption = { "Table $it skipped." },
        equation = { "Equation $it." },
    )

    private val abbreviations: Map<String, List<Pair<String, String>>> = mapOf(
        "en" to listOf(
            """\be\.\s?g\.,?""" to "for example,", """\bi\.\s?e\.,?""" to "that is,", """\bet al\.""" to "and others",
            """\bvs\.""" to "versus", """\bcf\.""" to "compare", """\bw\.r\.t\.""" to "with respect to",
            """\bapprox\.""" to "approximately", """\bFigs?\.\s*(?=\d)""" to "Figure ", """\bEqs?\.\s*(?=\(?\d)""" to "Equation ",
            """\bSec\.\s*(?=\d)""" to "Section ", """\bTab\.\s*(?=\d)""" to "Table ", """\bNo\.\s*(?=\d)""" to "number ",
            """\bResp\.""" to "respectively", """\betc\.""" to "et cetera", """\bPh\.D\.""" to "PhD",
            """\bHCI\b""" to "H C I", """\bUI\b""" to "U I", """\bUX\b""" to "U X", """\bAI\b""" to "A I", """\bVR\b""" to "V R",
            """\bAR\b""" to "A R", """\bEEG\b""" to "E E G", """\bEMS\b""" to "E M S", """\bIQR\b""" to "I Q R", """\bLSTM\b""" to "L S T M",
            """\bTCN\b""" to "T C N", """\bHMD\b""" to "H M D", """\bSD\b""" to "S D", """\bMAP\b""" to "M A P", """\bBIC\b""" to "B I C",
        ),
        "de" to listOf(
            """\bz\.\s?B\.""" to "zum Beispiel", """\bbzw\.""" to "beziehungsweise", """\bd\.\s?h\.""" to "das heißt",
            """\bu\.\s?a\.""" to "unter anderem", """\bvgl\.""" to "vergleiche", """\bca\.""" to "circa",
            """\bAbb\.\s*(?=\d)""" to "Abbildung ", """\bTab\.\s*(?=\d)""" to "Tabelle ", """\bNr\.""" to "Nummer",
            """\bggf\.""" to "gegebenenfalls", """\bevtl\.""" to "eventuell", """\binkl\.""" to "inklusive",
            """\bKI\b""" to "K I", """\bIT\b""" to "I T", """\bMBSE\b""" to "M B S E", """\bLLMs?\b""" to "L L M",
            """\bet al\.""" to "und andere", """\bvs\.""" to "versus", """\bsog\.""" to "sogenannte",
        ),
    )

    private fun symbols(lang: String) = if (lang == "de") listOf(
        "≤" to " kleiner gleich ", "≥" to " größer gleich ", "≠" to " ungleich ", "≈" to " ungefähr ",
        "±" to " plus minus ", "→" to " zu ", "×" to " mal ",
    ) else listOf(
        "≤" to " less than or equal to ", "≥" to " greater than or equal to ", "≠" to " not equal to ",
        "≈" to " approximately ", "±" to " plus or minus ", "→" to " to ", "×" to " times ",
    )

    // Prose

    private val greek = mapOf(
        'α' to "alpha", 'β' to "beta", 'γ' to "gamma", 'δ' to "delta", 'ε' to "epsilon", 'ζ' to "zeta", 'η' to "eta", 'θ' to "theta",
        'κ' to "kappa", 'λ' to "lambda", 'μ' to "mu", 'ν' to "nu", 'ξ' to "xi", 'π' to "pi", 'ρ' to "rho", 'σ' to "sigma", 'τ' to "tau",
        'φ' to "phi", 'χ' to "chi", 'ψ' to "psi", 'ω' to "omega", 'Γ' to "Gamma", 'Δ' to "Delta", 'Θ' to "Theta", 'Λ' to "Lambda",
        'Σ' to "Sigma", 'Φ' to "Phi", 'Ψ' to "Psi", 'Ω' to "Omega",
    )

    private fun isCombining(c: Char) = c.code in 0x300..0x36F || c.code in 0xFE00..0xFE0F

    /**
     * Inline math left in prose: Greek letters by name, operators as words,
     * accents and decorative brackets dropped.
     */
    internal fun speakInlineMath(input: String, lang: String): String {
        val de = lang == "de"
        val out = StringBuilder()
        for (ch in input) {
            // drop combining accents (hats, bars, dots from math typesetting)
            if (isCombining(ch) || ch in "ˆ˜¯˙¨⃦︂︁") continue
            val name = greek[ch]
            if (name != null) {
                out.append(" $name ")
                continue
            }
            when (ch) {
                '=' -> out.append(if (de) " gleich " else " equals ")
                '∈' -> out.append(" in ")
                '−' -> out.append(" minus ")
                '∝' -> out.append(if (de) " proportional zu " else " proportional to ")
                '∥' -> out.append(if (de) " Norm " else " norm ")
                '∑' -> out.append(if (de) " Summe " else " sum ")
                '∫' -> out.append(if (de) " Integral " else " integral ")
                '√' -> out.append(if (de) " Wurzel " else " square root of ")
                '∞' -> out.append(if (de) " unendlich " else " infinity ")
                '⊤' -> out.append(if (de) " transponiert " else " transpose ")
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    fun prepareProse(input: String, language: String): String {
        val lang = language
        var t = input
        for ((pattern, replacement) in abbreviations[lang] ?: abbreviations.getValue("en")) {
            t = t.rxReplace(pattern, java.util.regex.Matcher.quoteReplacement(replacement))
        }
        // "p < .001" → "p less than 0.001" (TTS reads ".001" badly and "<" not at all)
        val lt = if (lang == "de") " kleiner als " else " less than "
        val gt = if (lang == "de") " größer als " else " greater than "
        t = t.rxReplace("""\b(p|r|t|F|d|n|N|R2|R²|β)\s*([<>=])\s*\.(\d)""", "$1 $2 0.$3")
        t = t.rxReplace("""\b(p|r|t|F|d|n|N|R2|R²|β)\s*<\s*""", "$1$lt")
        t = t.rxReplace("""\b(p|r|t|F|d|n|N|R2|R²|β)\s*>\s*""", "$1$gt")
        for ((sym, word) in symbols(lang)) t = t.replace(sym, word)
        t = speakInlineMath(t, lang)
        if (lang == "en") {
            // "16/20" → "16 out of 20"
            t = t.rxReplace("""(?<![\d/.])\b(\d{1,2})/(\d{1,3})\b(?![\d/%.])""", "$1 out of $2")
        }
        t = t.rxReplace("""\^\{([^}]*)\}""", if (lang == "de") " hoch $1 " else " to the $1 ")
        t = t.rxReplace("""_\{([^}]*)\}""", if (lang == "de") " Index $1 " else " sub $1 ")
        t = t.rxReplace("""\b(\d+)-(?=[A-Za-z]{2,})""", "$1 ")
        t = t.rxReplace("""\s+([.,;:!?])""", "$1")
        t = t.rxReplace("""\s{2,}""", " ").trim { it == ' ' || it == '\t' }
        if (t.isNotEmpty() && t.last() !in ".!?:;\"'") t += "."
        return t
    }

    internal fun ensurePeriod(s: String): String {
        val t = s.trim { it == ' ' || it == '\t' }
        if (t.isEmpty() || t.last() in ".!?") return t
        return "$t."
    }

    // Segments

    fun segments(doc: SourceDocument, language: String): List<Segment> {
        val lang = language
        val p = phrases(lang)
        val segs = ArrayList<Segment>()
        val title = doc.title?.trim { it == ' ' || it == '\t' }
        if (!title.isNullOrEmpty()) segs.add(Segment(ensurePeriod(title), PAUSE_HEADING, title, Segment.Kind.TITLE))
        for (block in doc.blocks) {
            when (block) {
                is SourceDocument.Block.Heading -> {
                    val heading = block.text.trim { it == ' ' || it == '\t' }
                    if (heading.isEmpty()) continue
                    val spoken = when {
                        heading.lowercase() in listOf("abstract", "zusammenfassung", "summary") -> p.abstract
                        block.level <= 1 -> p.section(heading)
                        else -> ensurePeriod(heading)
                    }
                    segs.add(Segment(spoken, PAUSE_HEADING, if (block.level <= 2) heading else null, Segment.Kind.HEADING))
                }
                is SourceDocument.Block.Paragraph -> {
                    val text = prepareProse(block.text, lang)
                    if (text.isNotEmpty()) segs.add(Segment(text, PAUSE_PARAGRAPH, kind = Segment.Kind.PARAGRAPH))
                }
                is SourceDocument.Block.ListItem -> {
                    val text = prepareProse(block.text, lang)
                    if (text.isNotEmpty()) segs.add(Segment(text, PAUSE_ITEM, kind = Segment.Kind.ITEM))
                }
                is SourceDocument.Block.Figure -> {
                    val cap = prepareProse(block.caption, lang)
                    if (cap.isEmpty()) continue // images without caption are not worth announcing
                    segs.add(Segment(p.figure(block.number ?: "", cap), PAUSE_PARAGRAPH, kind = Segment.Kind.FIGURE))
                }
                is SourceDocument.Block.Table -> {
                    val cap = prepareProse(block.caption, lang)
                    val text = when {
                        cap.isNotEmpty() -> p.table(block.number ?: "", cap) + " " + p.tableSkip
                        block.number != null -> p.tableNoCaption(block.number)
                        else -> continue
                    }
                    segs.add(Segment(text, PAUSE_PARAGRAPH, kind = Segment.Kind.TABLE))
                }
                is SourceDocument.Block.Equation -> {
                    val number = block.number ?: continue // unnumbered display math is skipped
                    segs.add(Segment(p.equation(number), PAUSE_ITEM, kind = Segment.Kind.ITEM))
                }
            }
        }
        return segs
    }

    /**
     * The narration text in doc2audio's format: one segment per line, blank lines
     * between, extra blank line before headings.
     */
    fun narrationText(segments: List<Segment>): String {
        val lines = ArrayList<String>()
        for (s in segments) {
            if (s.kind == Segment.Kind.TITLE || s.kind == Segment.Kind.HEADING) lines += listOf("", s.text, "")
            else lines += listOf(s.text, "")
        }
        val joined = lines.joinToString("\n").rxReplace("""\n{3,}""", "\n\n")
        return joined.trim { it.isWhitespace() } + "\n"
    }

    // Plan

    fun plan(doc: SourceDocument, language: String): Plan {
        val segs = segments(doc, language)
        val text = narrationText(segs)
        val utterances = ArrayList<Utterance>()
        var cursor = 0
        for (seg in segs) {
            if (seg.text.isEmpty()) continue
            val start = text.indexOf(seg.text, cursor)
            if (start < 0) continue
            val range = start until start + seg.text.length
            cursor = range.upper
            val sentences = TextSegmentation.sentenceRanges(text, range)
            for ((i, r) in sentences.withIndex()) {
                val last = i == sentences.size - 1
                utterances.add(Utterance(
                    text.substring(r.first, r.upper), r,
                    if (last) seg.pauseAfter else PAUSE_SENTENCE,
                    if (i == 0) seg.chapter else null,
                ))
            }
        }
        return Plan(text, utterances)
    }
}
