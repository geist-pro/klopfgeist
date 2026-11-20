package de.kaikunze.marginvoice.core.conversion

import java.util.regex.Pattern

// NSRegularExpression is ICU: \w, \d, \s and \b are Unicode-aware and case-insensitive
// matching folds Unicode. Java needs UNICODE_CHARACTER_CLASS for the same.
internal fun rx(pattern: String, ignoreCase: Boolean = false, dotAll: Boolean = false): Regex {
    var flags = Pattern.UNICODE_CHARACTER_CLASS
    if (ignoreCase) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    if (dotAll) flags = flags or Pattern.DOTALL
    return Pattern.compile(pattern, flags).toRegex()
}

private val cache = HashMap<String, Regex>()

/** `s.replacingOccurrences(of: pattern, with: template, options: .regularExpression)`. */
internal fun String.rxReplace(pattern: String, template: String): String =
    synchronized(cache) { cache.getOrPut(pattern) { rx(pattern) } }.replace(this, template)

/** `s.range(of: pattern, options: .regularExpression) != nil`. */
internal fun String.rxContains(pattern: String, ignoreCase: Boolean = false): Boolean =
    synchronized(cache) { cache.getOrPut((if (ignoreCase) "i:" else "") + pattern) { rx(pattern, ignoreCase) } }.containsMatchIn(this)

/** Swift `String.capitalized`: the first letter of each word upper case, the rest lower case. */
internal fun String.capitalizedWords(): String {
    val sb = StringBuilder(length)
    var startOfWord = true
    for (c in this) {
        if (c.isLetterOrDigit()) {
            sb.append(if (startOfWord) c.titlecaseChar() else c.lowercaseChar())
            startOfWord = false
        } else {
            sb.append(c)
            startOfWord = c != '\'' && c != '’'
        }
    }
    return sb.toString()
}
