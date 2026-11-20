package de.kaikunze.marginvoice.core

import java.text.Normalizer

// Swift `Character` properties on Kotlin chars. Offsets everywhere are UTF-16
// indices (Swift uses grapheme clusters); combining marks count as part of the
// letter before them so decomposed umlauts stay inside their word.

internal fun Char.isSwiftWhitespace(): Boolean = isWhitespace()

internal fun Char.isSwiftNumber(): Boolean = when (Character.getType(this).toByte()) {
    Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
    else -> false
}

internal fun Char.isMark(): Boolean = when (Character.getType(this).toByte()) {
    Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> true
    else -> false
}

/** Letters plus the combining marks that belong to them. */
internal fun Char.isSwiftLetter(): Boolean = isLetter() || isMark()

internal fun Char.isSwiftUppercase(): Boolean = isUpperCase()

/** Swift `folding([.caseInsensitive, .diacriticInsensitive, .widthInsensitive])`. */
internal fun fold(s: String): String {
    val nfkd = Normalizer.normalize(s, Normalizer.Form.NFKD)
    val sb = StringBuilder(nfkd.length)
    for (c in nfkd) if (!c.isMark()) sb.append(c)
    return Normalizer.normalize(sb, Normalizer.Form.NFC).lowercase()
}

/** Swift `String.trimmingCharacters(in: .whitespacesAndNewlines)`. */
internal fun String.trimWs(): String = trim { it.isSwiftWhitespace() }

/** Swift `String(format: "%.1f")`: always a dot, whatever the default locale. */
internal fun fmt(pattern: String, vararg args: Any): String = String.format(java.util.Locale.ROOT, pattern, *args)

/** Swift `Range.upperBound` of a half-open `start until end` range. */
val IntRange.upper: Int get() = last + 1
