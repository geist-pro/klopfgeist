package de.kaikunze.marginvoice.core.conversion

/**
 * The text of an RTF document (iOS reads it with `NSAttributedString`): paragraphs
 * from `\par`, characters from `\'hh` and `\uN`, everything in font tables, styles,
 * pictures and other `{\* …}` destinations dropped.
 */
object RtfText {
    private val skippedDestinations = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "headerl", "headerr", "footerl", "footerr",
        "footnote", "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "themedata", "colorschememapping",
        "latentstyles", "datastore", "fldinst", "object", "nonshppict", "shp",
    )

    fun plain(rtf: String): String {
        val out = StringBuilder()
        // per group: skipping, and how many characters follow a \uN as its fallback
        val skip = ArrayDeque<Boolean>().apply { addLast(false) }
        var ucSkip = 1
        var pendingFallback = 0
        var i = 0
        fun emit(c: Char) {
            if (pendingFallback > 0) { pendingFallback--; return }
            if (!skip.last()) out.append(c)
        }
        while (i < rtf.length) {
            val c = rtf[i]
            when (c) {
                '{' -> { skip.addLast(skip.last()); i++ }
                '}' -> { if (skip.size > 1) skip.removeLast(); i++ }
                '\\' -> {
                    i++
                    if (i >= rtf.length) break
                    val n = rtf[i]
                    when {
                        n == '\\' || n == '{' || n == '}' -> { emit(n); i++ }
                        n == '*' -> { skip[skip.size - 1] = true; i++ }
                        n == '\'' -> {
                            val hex = rtf.substring(i + 1, minOf(i + 3, rtf.length))
                            hex.toIntOrNull(16)?.let { emit(String(byteArrayOf(it.toByte()), Charsets.ISO_8859_1).let { s -> cp1252(s[0]) }) }
                            i += 3
                        }
                        n == '\n' || n == '\r' -> { emit('\n'); i++ }
                        n == '~' -> { emit(' '); i++ }
                        n == '-' || n == '_' -> i++
                        n.isLetter() -> {
                            val start = i
                            while (i < rtf.length && rtf[i].isLetter()) i++
                            val word = rtf.substring(start, i)
                            val numStart = i
                            if (i < rtf.length && (rtf[i] == '-' || rtf[i].isDigit())) {
                                i++
                                while (i < rtf.length && rtf[i].isDigit()) i++
                            }
                            val param = rtf.substring(numStart, i).toIntOrNull()
                            if (i < rtf.length && rtf[i] == ' ') i++
                            when (word) {
                                "par", "line", "sect", "page" -> emit('\n')
                                "tab" -> emit('\t')
                                "emdash" -> emit('—')
                                "endash" -> emit('–')
                                "lquote" -> emit('‘')
                                "rquote" -> emit('’')
                                "ldblquote" -> emit('“')
                                "rdblquote" -> emit('”')
                                "bullet" -> emit('•')
                                "uc" -> ucSkip = param ?: 1
                                "u" -> if (param != null) {
                                    emit((if (param < 0) param + 65536 else param).toChar())
                                    pendingFallback = ucSkip
                                }
                                else -> if (word in skippedDestinations) skip[skip.size - 1] = true
                            }
                        }
                        else -> i++
                    }
                }
                '\r', '\n' -> i++
                else -> { emit(c); i++ }
            }
        }
        return out.toString()
    }

    /** `\'hh` is in the document's code page, Windows-1252 in practice. */
    private fun cp1252(c: Char): Char = String(byteArrayOf(c.code.toByte()), charset("windows-1252"))[0]
}
