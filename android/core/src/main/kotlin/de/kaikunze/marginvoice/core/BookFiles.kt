package de.kaikunze.marginvoice.core

/**
 * Which files make a book, and how they pair up by name. Works on file names,
 * so the app can feed it content-URI display names as well as paths.
 */
object BookFiles {
    val audioExtensions = setOf("m4b", "m4a", "mp3", "aac", "wav")
    /** Documents that can be turned into a book on the device. */
    val documentExtensions = setOf("pdf", "txt", "md", "markdown", "rtf", "rtfd")

    fun extension(name: String): String = fileName(name).substringAfterLast('.', "").lowercase()
    fun isAudio(name: String) = extension(name) in audioExtensions
    fun isDocument(name: String) = extension(name) in documentExtensions

    private fun fileName(path: String) = path.substringAfterLast('/')

    /** `x.m4b`, `x.txt`, `x.narration.txt` and `x.timing.json` all have the stem `x`. */
    fun stem(name: String): String {
        val file = fileName(name)
        var base = if (file.contains('.')) file.substringBeforeLast('.') else file
        for (suffix in listOf(".narration", ".timing")) if (base.endsWith(suffix)) base = base.dropLast(suffix.length)
        return base
    }

    data class Group<T>(val stem: String, val audio: T, val text: T?, val timing: T?)

    data class Grouped<T>(val books: List<Group<T>>, val orphans: List<String>)

    /** One group per audio file, sorted by stem, plus the stems that have no audio. */
    fun <T> group(files: List<T>, name: (T) -> String): Grouped<T> {
        val byStem = files.groupBy { stem(name(it)) }
        val books = ArrayList<Group<T>>()
        val orphans = ArrayList<String>()
        for ((stem, group) in byStem.toSortedMap()) {
            val audio = group.firstOrNull { isAudio(name(it)) }
            if (audio == null) {
                orphans.add(stem)
                continue
            }
            books.add(Group(
                stem, audio,
                text = group.firstOrNull { fileName(name(it)).lowercase().endsWith(".narration.txt") }
                    ?: group.firstOrNull { extension(name(it)) == "txt" },
                timing = group.firstOrNull { extension(name(it)) == "json" },
            ))
        }
        return Grouped(books, orphans)
    }

    fun group(paths: List<String>): Grouped<String> = group(paths) { it }

    data class Inbox<T>(val audiobookFiles: List<T>, val documents: List<T>)

    /** Splits an inbox listing into files that complete an audiobook and documents to convert. */
    fun <T> sortInbox(files: List<T>, name: (T) -> String): Inbox<T> {
        val stemsWithAudio = files.filter { isAudio(name(it)) }.map { stem(name(it)) }.toSet()
        val audiobook = files.filter { stem(name(it)) in stemsWithAudio }
        val documents = files.filter { isDocument(name(it)) && stem(name(it)) !in stemsWithAudio }
            .sortedBy { fileName(name(it)) }
        return Inbox(audiobook, documents)
    }

    fun sortInbox(paths: List<String>): Inbox<String> = sortInbox(paths) { it }

    /** A file-system-safe name for exports: letters, digits, `-` and `_`, at most 60 characters. */
    fun safeName(title: String): String {
        val cleaned = buildString {
            var i = 0
            while (i < title.length) {
                val cp = title.codePointAt(i)
                if (Character.isLetterOrDigit(cp) || cp == '-'.code || cp == '_'.code || cp == ' '.code ||
                    Character.getType(cp).toByte() in setOf(Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK)
                ) appendCodePoint(cp) else append('-')
                i += Character.charCount(cp)
            }
        }
        return cleaned.take(60).trim(' ').replace(" ", "-")
    }
}
