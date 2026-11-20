package de.kaikunze.marginvoice.core

/**
 * Text offsets between devices are Unicode code points (like `timing.json`): Kotlin
 * counts UTF-16 units, Swift counts Characters, so each side converts at the edge.
 */
object OffsetUnits {
    /** UTF-16 offset in `s` → code point offset. */
    fun toCodePoints(s: String, utf16: Int): Int = s.codePointCount(0, utf16.coerceIn(0, s.length))

    /** Code point offset in `s` → UTF-16 offset (past the end clamps to the end). */
    fun fromCodePoints(s: String, codePoints: Int): Int {
        if (codePoints <= 0) return 0
        return runCatching { s.offsetByCodePoints(0, codePoints) }.getOrDefault(s.length)
    }
}
