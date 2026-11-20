package de.kaikunze.marginvoice.core.conversion

import java.text.Normalizer

/**
 * A document's readable structure, independent of the file format it came from
 * (mirrors doc2audio's `ir.py`).
 */
data class SourceDocument(val title: String?, val blocks: List<Block>) {
    sealed interface Block {
        data class Heading(val text: String, val level: Int) : Block
        data class Paragraph(val text: String) : Block
        data class ListItem(val text: String) : Block
        data class Figure(val number: String?, val caption: String) : Block
        data class Table(val number: String?, val caption: String) : Block
        /** A display equation; announced by number ("Equation 12."). */
        data class Equation(val number: String?) : Block
    }

    /** All readable text, for language detection and statistics. */
    val plainText: String
        get() {
            val parts = ArrayList<String>()
            title?.let(parts::add)
            for (b in blocks) when (b) {
                is Block.Heading -> parts.add(b.text)
                is Block.Paragraph -> parts.add(b.text)
                is Block.ListItem -> parts.add(b.text)
                is Block.Figure -> parts.add(b.caption)
                is Block.Table -> parts.add(b.caption)
                is Block.Equation -> {}
            }
            return parts.joinToString("\n")
        }

    val headings: List<String> get() = blocks.mapNotNull { (it as? Block.Heading)?.takeIf { h -> h.level <= 2 }?.text }

    companion object {
        /**
         * Plain text / Markdown: paragraphs end at blank lines, `# ` lines are headings,
         * `- ` and `* ` lines are list items. In Markdown, headings and list items also
         * end the paragraph above; in plain text they must start a block, so a wrapped
         * line that happens to begin with "- " stays part of its paragraph.
         */
        fun fromPlainText(text: String, fallbackTitle: String?, markdown: Boolean = false): SourceDocument {
            val blocks = ArrayList<Block>()
            var title: String? = null
            val paragraph = ArrayList<String>()
            // The line above was a list item or its continuation (no blank line since).
            var inListItem = false
            fun endParagraph() {
                if (paragraph.isEmpty()) return
                blocks.add(Block.Paragraph(TextCleanup.joinLines(paragraph)))
                paragraph.clear()
            }
            // macOS often stores umlauts decomposed ("u" + combining diaeresis)
            val composed = Normalizer.normalize(text, Normalizer.Form.NFC)
            for (raw in composed.split(Regex("\r\n|[\n\r\u000B\u000C\u0085  ]"))) {
                val line = raw.trim { it == ' ' || it == '\t' }
                val startsBlock = markdown || paragraph.isEmpty()
                val level = line.takeWhile { it == '#' }.length
                if (line.isEmpty()) {
                    endParagraph()
                    inListItem = false
                } else if (startsBlock && level in 1..6 && (line.length == level || line[level] == ' ')) {
                    endParagraph()
                    inListItem = false
                    val heading = line.drop(level).trim { it == ' ' || it == '\t' }
                    if (level == 1 && title == null && blocks.isEmpty()) title = heading
                    else blocks.add(Block.Heading(heading, maxOf(1, level - 1)))
                } else if (startsBlock && (line.startsWith("- ") || line.startsWith("* "))) {
                    endParagraph()
                    inListItem = true
                    blocks.add(Block.ListItem(line.drop(2).trim { it == ' ' || it == '\t' }))
                } else if (inListItem && raw.firstOrNull()?.isWhitespace() == true && blocks.lastOrNull() is Block.ListItem) {
                    // an indented line continues the list item above
                    val item = blocks.last() as Block.ListItem
                    blocks[blocks.size - 1] = Block.ListItem(item.text + " " + line)
                } else {
                    inListItem = false
                    paragraph.add(line)
                }
            }
            endParagraph()
            return SourceDocument(title ?: fallbackTitle, blocks)
        }
    }
}
