package de.kaikunze.marginvoice.core

import java.util.Locale

enum class VoiceCommand {
    STOP, RESUME, CANCEL, BACK, AGAIN, MARK, FASTER, SLOWER;

    enum class Language(val code: String) {
        ENGLISH("en"), GERMAN("de");

        /** Region used for speech recognition and voices ("en-US", "de-DE"). */
        val regionIdentifier: String get() = if (this == GERMAN) "de-DE" else "en-US"

        val locale: Locale get() = Locale.forLanguageTag(regionIdentifier)

        val displayName: String get() = if (this == GERMAN) "Deutsch" else "English"

        companion object {
            /** The language of a book's `language` code ("de", "de-DE"); anything else is read as English. */
            fun fromCode(code: String?): Language = if (code?.lowercase()?.startsWith("de") == true) GERMAN else ENGLISH

            /** English or German, whichever the text is closer to. */
            fun detect(text: String): Language = LanguageDetector.detect(text)
        }
    }

    companion object {
        /** Accepted phrases per language, already normalized (`TextSegmentation.normalize`). */
        fun phrases(language: Language): Map<VoiceCommand, List<String>> = when (language) {
            Language.ENGLISH -> linkedMapOf(
                STOP to listOf("stop", "note", "comment"),
                RESUME to listOf("continue", "go on", "resume", "play"),
                CANCEL to listOf("cancel", "discard", "delete that"),
                BACK to listOf("back", "go back", "rewind"),
                AGAIN to listOf("again", "repeat"),
                MARK to listOf("bookmark", "mark", "bookmark this", "bookmark that", "mark this"),
                FASTER to listOf("faster"),
                SLOWER to listOf("slower"),
            )
            Language.GERMAN -> linkedMapOf(
                STOP to listOf("stopp", "stop", "notiz", "kommentar"),
                RESUME to listOf("weiter", "weiter machen", "weitermachen", "fortsetzen"),
                CANCEL to listOf("abbrechen", "verwerfen", "loschen"),
                BACK to listOf("zuruck", "zuruckspulen"),
                AGAIN to listOf("nochmal", "noch mal", "wiederholen"),
                MARK to listOf("lesezeichen", "markierung", "markieren", "lesezeichen setzen", "merken"),
                FASTER to listOf("schneller"),
                SLOWER to listOf("langsamer"),
            )
        }

        val fillers = setOf("please", "bitte", "okay", "ok", "uh", "um", "ah", "ahm")

        /**
         * Parses a standalone utterance. Only short utterances that consist of a command
         * phrase (plus fillers like "please") count; anything longer is speech, not a command.
         */
        fun parse(utterance: String, languages: Set<Language>): VoiceCommand? {
            val tokens = TextSegmentation.tokens(utterance).filter { it !in fillers }
            if (tokens.isEmpty() || tokens.size > 2) return null
            val phrase = tokens.joinToString(" ")
            for (language in Language.entries) {
                if (language !in languages) continue
                for ((command, list) in phrases(language)) if (phrase in list) return command
            }
            return null
        }

        /**
         * Removes a closing "continue" / "weiter" (or "cancel") that ended the recording
         * and was captured at the end of the comment.
         */
        fun strippingTrailingCommand(text: String, languages: Set<Language>): String {
            val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
            repeat(2) {
                var stripped = false
                for (n in listOf(2, 1)) {
                    if (words.size < n) continue
                    val tail = words.takeLast(n).joinToString(" ")
                    val c = parse(tail, languages)
                    if (c == RESUME || c == CANCEL) {
                        repeat(n) { words.removeAt(words.size - 1) }
                        stripped = true
                        break
                    }
                }
                if (!stripped) return finish(words)
            }
            return finish(words)
        }

        private fun finish(words: List<String>): String {
            var result = words.joinToString(" ")
            while (result.isNotEmpty() && result.last() in ",;-") result = result.dropLast(1)
            return result.trim { it == ' ' || it == '\t' }
        }
    }
}
