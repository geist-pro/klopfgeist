package de.kaikunze.marginvoice.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import java.time.temporal.ChronoUnit

/** Comment lists: Markdown for reading, CSV for spreadsheets, JSON for tools. */
object CommentExport {
    fun markdown(title: String, comments: List<CommentRecord>, text: NarrationText?): String {
        val out = StringBuilder("# Comments: $title\n\n")
        val sorted = comments.sortedBy { it.mediaTime }
        if (sorted.isEmpty()) return out.append("No comments yet.\n").toString()
        for ((i, c) in sorted.withIndex()) {
            var header = "## ${i + 1}. ${TimeFormat.clock(c.mediaTime)}"
            if (!c.chapter.isNullOrEmpty()) header += " · ${c.chapter}"
            c.tag?.let { header += " · ${it.displayName(VoiceCommand.Language.ENGLISH)}" }
            if (c.kind == CommentRecord.Kind.BOOKMARK) {
                header += c.endMediaTime?.let { " · Bookmark to ${TimeFormat.clock(it)}" } ?: " · Bookmark"
            }
            out.append(header).append("\n\n")
            if (text != null && text.sentences.isNotEmpty()) {
                val marker = if (c.confidence < AnnotatedTextExport.LOW_CONFIDENCE) " *(position estimated)*" else ""
                out.append("> ${c.quotedText(text)}$marker\n\n")
            }
            val transcript = c.transcript.trimWs()
            if (c.kind == CommentRecord.Kind.COMMENT) {
                out.append(transcript.ifEmpty { "*(not transcribed)*" }).append("\n\n")
            }
            c.audioFile?.let { out.append("Audio: [$it]($it)\n\n") }
        }
        return out.toString()
    }

    fun csv(comments: List<CommentRecord>, text: NarrationText?): String {
        val rows = arrayListOf("index,time,seconds,chapter,kind,tag,confidence,sentence,comment,audio")
        for ((i, c) in comments.sortedBy { it.mediaTime }.withIndex()) {
            val sentence = if (text != null && text.sentences.isNotEmpty()) c.quotedText(text) else ""
            val fields = listOf(
                (i + 1).toString(), TimeFormat.clock(c.mediaTime), fmt("%.1f", c.mediaTime), c.chapter ?: "",
                c.kind.raw, c.tag?.raw ?: "", fmt("%.2f", c.confidence), sentence, c.transcript, c.audioFile ?: "",
            )
            rows.add(fields.joinToString(",") { quote(it) })
        }
        return rows.joinToString("\n") + "\n"
    }

    private val prettyJson = Json { prettyPrint = true }

    /** `{title, comments}` with sorted keys and ISO 8601 dates, like the iOS JSONEncoder output. */
    fun json(title: String, comments: List<CommentRecord>): String {
        val list = buildJsonArray {
            for (c in comments.sortedBy { it.mediaTime }) add(record(c))
        }
        return prettyJson.encodeToString(JsonElement.serializer(), JsonObject(sortedMapOf("comments" to list, "title" to JsonPrimitive(title))))
    }

    private fun record(c: CommentRecord): JsonObject {
        val m = sortedMapOf<String, JsonElement>(
            "id" to JsonPrimitive(c.id.toString().uppercase()),
            "kind" to JsonPrimitive(c.kind.raw),
            "mediaTime" to JsonPrimitive(c.mediaTime),
            "textOffset" to JsonPrimitive(c.textOffset),
            "confidence" to JsonPrimitive(c.confidence),
            "transcript" to JsonPrimitive(c.transcript),
            "createdAt" to JsonPrimitive(c.createdAt.truncatedTo(ChronoUnit.SECONDS).toString()),
        )
        c.chapter?.let { m["chapter"] = JsonPrimitive(it) }
        c.audioFile?.let { m["audioFile"] = JsonPrimitive(it) }
        c.endMediaTime?.let { m["endMediaTime"] = JsonPrimitive(it) }
        c.endTextOffset?.let { m["endTextOffset"] = JsonPrimitive(it) }
        c.tag?.let { m["tag"] = JsonPrimitive(it.raw) }
        return JsonObject(m)
    }

    internal fun quote(field: String): String {
        // a transcript starting with = + - @ would run as a spreadsheet formula
        val f = if (field.isNotEmpty() && field[0] in "=+-@") "'$field" else field
        if (f.none { it == ',' || it == '"' || it == '\n' || it == '\r' || it == ' ' || it == ' ' || it == '\u0085' }) return f
        return "\"" + f.replace("\"", "\"\"") + "\""
    }
}
