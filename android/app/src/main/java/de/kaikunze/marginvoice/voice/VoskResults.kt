package de.kaikunze.marginvoice.voice

import de.kaikunze.marginvoice.core.Hypothesis
import de.kaikunze.marginvoice.core.SpokenWord
import de.kaikunze.marginvoice.core.VoiceCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Vosk's JSON results as the session's [Hypothesis]: a pure conversion, tested on the JVM. */
object VoskResults {
    /**
     * A result of a recognizer with word results on (`setWords`, `setPartialWords`).
     *
     * - `{"partial": "…", "partial_result": [{"word","start","end","conf"}…]}`: the current guess, not final.
     * - `{"text": "…", "result": […]}`: final.
     *
     * Vosk counts word times in seconds of audio it was fed; [origin] is the microphone
     * time of the first sample fed, which puts the words on the microphone timeline.
     * A result without words is an empty hypothesis (the guess was withdrawn), a text
     * that is no result is null.
     */
    fun hypothesis(json: String, origin: Double): Hypothesis? {
        val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val isFinal = when {
            "partial" in obj || "partial_result" in obj -> false
            "text" in obj || "result" in obj -> true
            else -> return null
        }
        val list = obj[if (isFinal) "result" else "partial_result"] as? JsonArray
        val words = list.orEmpty().mapNotNull { element ->
            val word = element as? JsonObject ?: return@mapNotNull null
            val text = (word["word"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val start = word.time("start") ?: return@mapNotNull null
            val end = word.time("end") ?: return@mapNotNull null
            SpokenWord(text, origin + start, origin + end)
        }
        return Hypothesis(words, isFinal)
    }

    private fun JsonObject.time(key: String): Double? = (this[key] as? JsonPrimitive)?.jsonPrimitive?.doubleOrNull

    /** Model spellings of the normalized command phrases (`VoiceCommand` folds umlauts). */
    private val spelled = mapOf("zuruck" to "zurück", "zuruckspulen" to "zurückspulen", "loschen" to "löschen")

    /**
     * The recognizer's grammar: the command phrases of the language that is listened for, and
     * "[unk]" for everything else. The grammar keeps a small model from hearing command words in
     * ordinary speech; the words around a command arrive as "[unk]" with their times, which is all
     * `CommandDetector` needs of them.
     *
     * Only one language: Vosk takes the phrases as a word list, and every word in it is something
     * another word can be mistaken for. With both languages in, the English model heard "mark" and
     * "note" as the German "mal" (device traces, 2026-11-20).
     */
    fun grammar(language: VoiceCommand.Language): String {
        val phrases = VoiceCommand.phrases(language).values.flatten()
            .filterNot { p -> p.split(' ').any { it in misheard } }
            .map { p -> p.split(' ').joinToString(" ") { spelled[it] ?: it } } +
            listOf(if (language == VoiceCommand.Language.GERMAN) "bitte" else "please", UNKNOWN)
        return JsonArray(phrases.distinct().map { JsonPrimitive(it) }).toString()
    }

    /**
     * Words that are no command on their own and that the small English model hears in place of one:
     * "stop" and "slower" came back as "go", "back" and "faster" as "that" (device traces, 2026-11-20,
     * 6 of 10 misses over a playing book). Phrases with these words ("go on", "go back", "delete that",
     * "bookmark that") are left out of the grammar: each has one-word forms that stay.
     *
     * "zurückspulen" is left out because the small German model never returned it (0 of 12 trials); it
     * came back as "zurück stopp" or as nothing. "zurück" stays.
     *
     * Tried and not kept: leaving out "mark", which "back" was heard as for three of six voices. "back"
     * was then simply missed as often, and "slower" was heard as "note" more often.
     */
    val misheard = setOf("go", "that", "zuruckspulen")

    const val UNKNOWN = "[unk]"
}
