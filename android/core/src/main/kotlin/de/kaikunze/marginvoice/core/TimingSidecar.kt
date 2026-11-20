package de.kaikunze.marginvoice.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Optional `<name>.timing.json` written by doc2audio next to the m4b.
 *
 * ```json
 * {"version": 1, "unit": "codepoint", "chunks": [{"start": 0.0, "end": 3.2, "char_start": 0, "char_end": 58}]}
 * ```
 * Times are media seconds in the encoded file (tempo already applied).
 * Offsets are Unicode code point indices into the narration `.txt` (Python `str` indices).
 */
@Serializable
data class TimingSidecar(val version: Int = 1, val chunks: List<Chunk>) {
    @Serializable
    data class Chunk(
        val start: Double,
        val end: Double,
        @SerialName("char_start") val charStart: Int,
        @SerialName("char_end") val charEnd: Int,
    )

    fun encode(): String = json.encodeToString(serializer(), this)

    /** Converts code point offsets to UTF-16 offsets of `text`. */
    fun convertedToCharacterOffsets(text: NarrationText): TimingSidecar {
        val s = text.string
        val scalarToChar = IntArray(s.codePointCount(0, s.length) + 1)
        var cp = 0
        var i = 0
        while (i < s.length) {
            scalarToChar[cp++] = i
            i += Character.charCount(s.codePointAt(i))
        }
        scalarToChar[cp] = s.length
        fun map(x: Int) = scalarToChar[x.coerceIn(0, scalarToChar.size - 1)]
        return copy(chunks = chunks.map { it.copy(charStart = map(it.charStart), charEnd = map(it.charEnd)) })
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(text: String): TimingSidecar = json.decodeFromString(serializer(), text)
    }
}
