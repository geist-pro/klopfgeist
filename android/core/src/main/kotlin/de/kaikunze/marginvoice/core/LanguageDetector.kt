package de.kaikunze.marginvoice.core

/**
 * English or German, whichever the text is closer to (iOS uses NLLanguageRecognizer
 * constrained to the same two). Counts frequent function words of each language.
 */
internal object LanguageDetector {
    private val german = setOf(
        "der", "die", "das", "und", "ist", "nicht", "ein", "eine", "einen", "dem", "den", "des", "mit", "sich",
        "auf", "fur", "von", "zu", "im", "auch", "als", "wird", "werden", "dass", "bei", "nach", "wie", "oder",
        "aus", "sind", "wir", "sie", "es", "zum", "zur", "uber", "durch", "einer", "eines", "noch", "kann", "nur",
        "wenn", "aber", "schneller", "bisher", "ergebnisse", "zeigen",
    )
    private val english = setOf(
        "the", "and", "of", "to", "is", "in", "that", "it", "for", "with", "as", "on", "are", "this", "be", "by",
        "an", "we", "from", "at", "or", "which", "was", "were", "not", "can", "has", "have", "these", "their",
        "than", "our", "show", "results", "before", "more", "also", "between", "into",
    )

    fun detect(text: String): VoiceCommand.Language {
        var de = 0
        var en = 0
        for (t in TextSegmentation.tokens(text.take(6000))) {
            if (t in german) de++
            if (t in english) en++
        }
        return if (de > en) VoiceCommand.Language.GERMAN else VoiceCommand.Language.ENGLISH
    }
}
