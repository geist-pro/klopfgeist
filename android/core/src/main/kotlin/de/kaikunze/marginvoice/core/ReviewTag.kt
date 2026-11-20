package de.kaikunze.marginvoice.core

/**
 * The review category a comment opens with: "weakness: the baseline is never tuned".
 * Spoken in English or German, stripped from the transcript, and used to sort the
 * comments into a review draft.
 */
enum class ReviewTag(val raw: String) {
    SUMMARY("summary"), STRENGTH("strength"), WEAKNESS("weakness"), QUESTION("question"), MINOR("minor");

    fun displayName(language: VoiceCommand.Language): String = when (language) {
        VoiceCommand.Language.ENGLISH -> when (this) {
            SUMMARY -> "Summary"; STRENGTH -> "Strength"; WEAKNESS -> "Weakness"; QUESTION -> "Question"; MINOR -> "Minor"
        }
        VoiceCommand.Language.GERMAN -> when (this) {
            SUMMARY -> "Zusammenfassung"; STRENGTH -> "Stärke"; WEAKNESS -> "Schwäche"; QUESTION -> "Frage"; MINOR -> "Kleinigkeit"
        }
    }

    companion object {
        fun fromRaw(raw: String?): ReviewTag? = entries.firstOrNull { it.raw == raw }

        /** Accepted opening words per language, normalized like commands (`TextSegmentation.normalize`). */
        fun phrases(language: VoiceCommand.Language): Map<ReviewTag, List<String>> = when (language) {
            VoiceCommand.Language.ENGLISH -> mapOf(
                SUMMARY to listOf("summary"),
                STRENGTH to listOf("strength", "strengths"),
                WEAKNESS to listOf("weakness", "weaknesses"),
                QUESTION to listOf("question", "questions"),
                MINOR to listOf("minor", "minor issue", "minor issues", "nitpick", "nit", "typo"),
            )
            VoiceCommand.Language.GERMAN -> mapOf(
                SUMMARY to listOf("zusammenfassung"),
                STRENGTH to listOf("starke", "starken"),
                WEAKNESS to listOf("schwache", "schwachen"),
                QUESTION to listOf("frage", "fragen"),
                MINOR to listOf("kleinigkeit", "kleinigkeiten", "minor", "detail"),
            )
        }

        private val leadIns = setOf("a", "an", "oh", "hey")
        /** Words that make "a weakness …" part of a sentence rather than a tag. */
        private val continuations = setOf("of", "is", "was", "that", "here", "in", "for")

        data class Extraction(val tag: ReviewTag?, val text: String)

        /**
         * The tag said at the start of a transcript, and the transcript without it.
         * "Weakness, the baseline is missing" → (WEAKNESS, "The baseline is missing").
         * Fillers before the tag ("um, weakness…") are dropped too, and so is a lead-in the
         * recognizer hears in the beep ("a weakness …"), unless the sentence goes on with the
         * tag word in it ("a weakness of the method is…"). No tag: the text comes back as is.
         */
        fun extract(transcript: String): Extraction {
            val words = transcript.split(Regex("\\s+")).filter { it.isNotEmpty() }
            fun token(index: Int) = words.getOrNull(index)?.let { TextSegmentation.tokens(it).firstOrNull() } ?: ""
            var i = 0
            var ledIn = false
            while (i < words.size && (token(i) in VoiceCommand.fillers || token(i) in leadIns)) {
                ledIn = ledIn || token(i) in leadIns
                i++
            }
            for (n in listOf(2, 1)) {
                if (i + n > words.size) continue
                val phrase = TextSegmentation.tokens(words.subList(i, i + n).joinToString(" ")).joinToString(" ")
                if (phrase.isEmpty()) continue
                for (language in VoiceCommand.Language.entries) {
                    for ((tag, list) in phrases(language)) {
                        if (phrase !in list) continue
                        if (ledIn && token(i + n) in continuations) return Extraction(null, transcript)
                        var rest = words.drop(i + n).joinToString(" ")
                        rest = rest.trimStart { it in ":,.;-–—" }.trim { it == ' ' || it == '\t' }
                        if (rest.isNotEmpty()) rest = rest.substring(0, 1).uppercase() + rest.substring(1)
                        return Extraction(tag, rest)
                    }
                }
            }
            return Extraction(null, transcript)
        }
    }
}
