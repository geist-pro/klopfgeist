package de.kaikunze.marginvoice.core

/**
 * Words from the book around a comment that a speech recognizer is unlikely to
 * know or to spell right: names, acronyms, product and project names, compounds.
 * Passed as biasing strings so "WallBOT" isn't heard as "Wall bot".
 */
object ContextVocabulary {
    fun terms(text: String, limit: Int = 100): List<String> {
        val counts = HashMap<String, Int>()
        val order = ArrayList<String>()
        val token = StringBuilder()
        fun flush() {
            if (token.isEmpty()) return
            val word = token.toString().trim('-')
            token.clear()
            if (!isDistinctive(word)) return
            if (word !in counts) order.add(word)
            counts[word] = (counts[word] ?: 0) + 1
        }
        for (c in text) {
            if (c.isSwiftLetter() || c.isSwiftNumber() || c == '-') token.append(c) else flush()
        }
        flush()
        // most frequent first, then in reading order (the sort is stable)
        return order.sortedByDescending { counts[it]!! }.take(limit)
    }

    internal fun isDistinctive(word: String): Boolean {
        val letters = word.filter { it.isLetter() }
        if (letters.length < 2) return false
        val uppercase = letters.count { it.isSwiftUppercase() }
        if (uppercase == letters.length) return true // acronyms: KI, HAFEN
        if (word.any { it.isSwiftNumber() }) return true // Robots4CleanTech, H2
        if (word.drop(1).any { it.isSwiftUppercase() }) return true // WallBOT, RoboHand
        if (word.contains('-')) return word.length >= 6 // Reinigungs-Roboter
        // names and German nouns, long compounds
        return (word.firstOrNull()?.isSwiftUppercase() == true && word.length >= 8) || word.length >= 12
    }

    /**
     * Terms from the text around `offset` (about a minute of narration either side)
     * plus the title, which names the project more often than not.
     */
    fun terms(around: Int, text: NarrationText, title: String, radius: Int = 1500, limit: Int = 100): List<String> {
        val lower = (around - radius).coerceIn(0, text.count)
        val upper = (around + radius).coerceIn(lower, text.count)
        val titleTerms = terms(title)
        val nearby = terms(text.string.substring(lower, upper), limit).filter { it !in titleTerms }
        return (titleTerms + nearby).take(limit)
    }
}
