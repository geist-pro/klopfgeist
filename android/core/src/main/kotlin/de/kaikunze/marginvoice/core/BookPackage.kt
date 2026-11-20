package de.kaikunze.marginvoice.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A book as it travels between devices: a `.drafttalk` file (a zip) or a folder in
 * the sync store, both with the same layout:
 *
 * ```
 * book.json               PackagedBook
 * book.m4b, narration.txt, timing.json, source.pdf
 * comments/<UUID>.json    PackagedComment, one file per comment
 * comments/<audio>.m4a    the comment's recording, under its own name
 * ```
 * UUIDs are uppercase, dates ISO 8601 UTC with milliseconds, text offsets code points.
 * Properties are declared in key order so both apps write the same JSON.
 */
object BookPackage {
    const val FORMAT = 1
    const val EXTENSION = "drafttalk"
    const val BOOK_FILE = "book.json"
    const val COMMENTS_DIR = "comments"
    const val SOURCE_PDF = "source.pdf"

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    fun commentPath(id: String) = "$COMMENTS_DIR/${id.uppercase()}.json"

    fun isCommentPath(path: String) = path.startsWith("$COMMENTS_DIR/") && path.endsWith(".json")

    fun encode(book: PackagedBook): String = json.encodeToString(PackagedBook.serializer(), book)
    fun encode(comment: PackagedComment): String = json.encodeToString(PackagedComment.serializer(), comment)
    fun decodeBook(s: String): PackagedBook = json.decodeFromString(PackagedBook.serializer(), s).also {
        require(it.format <= FORMAT) { "the book was written by a newer Klopfgeist (format ${it.format})" }
    }
    fun decodeComment(s: String): PackagedComment = json.decodeFromString(PackagedComment.serializer(), s)

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    fun date(i: Instant): String = dateFormat.format(i)
    fun date(s: String): Instant = Instant.parse(s)

    /** SHA-256 of the content without `updatedAt`: equal hashes mean nothing changed. */
    fun hash(book: PackagedBook): String = sha256(encode(book.copy(updatedAt = "")))
    fun hash(comment: PackagedComment): String = sha256(encode(comment.copy(updatedAt = "")))

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** A file in the package: its path inside and how to read it. */
    class Entry(val path: String, val open: () -> InputStream) {
        companion object {
            fun of(path: String, file: File) = Entry(path) { file.inputStream() }
            fun of(path: String, text: String) = Entry(path) { text.byteInputStream() }
        }
    }

    /** Book, comments and files of one book folder, ready to zip. */
    fun entries(book: PackagedBook, comments: List<PackagedComment>, files: List<Entry>): List<Entry> =
        listOf(Entry.of(BOOK_FILE, encode(book))) + comments.map { Entry.of(commentPath(it.id), encode(it)) } + files

    /**
     * Writes a zip. Media is stored as is (already compressed, and a stored entry can
     * be read without inflating); JSON and text are deflated.
     */
    fun writeZip(entries: List<Entry>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            for (entry in entries) {
                val stored = BookFiles.extension(entry.path) in storedExtensions
                val e = ZipEntry(entry.path)
                if (stored) {
                    val crc = CRC32()
                    var size = 0L
                    entry.open().use { input ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            crc.update(buf, 0, n)
                            size += n
                        }
                    }
                    e.method = ZipEntry.STORED
                    e.size = size
                    e.compressedSize = size
                    e.crc = crc.value
                }
                zip.putNextEntry(e)
                entry.open().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private val storedExtensions = setOf("m4a", "m4b", "mp3", "aac", "wav", "pdf")

    /** What a `.drafttalk` file holds once unpacked into `dir`. */
    data class Unpacked(val book: PackagedBook, val comments: List<PackagedComment>, val dir: File)

    /** Unpacks a zip into `dir` (which should be empty), refusing paths that leave it. */
    fun unzip(input: InputStream, dir: File): Unpacked {
        dir.mkdirs()
        val root = dir.canonicalFile
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val dest = File(root, e.name).canonicalFile
                require(dest.path.startsWith(root.path + File.separator)) { "bad path in package: ${e.name}" }
                if (e.isDirectory) dest.mkdirs() else {
                    dest.parentFile.mkdirs()
                    dest.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        val bookFile = File(root, BOOK_FILE)
        require(bookFile.exists()) { "not a Klopfgeist book: $BOOK_FILE is missing" }
        val comments = File(root, COMMENTS_DIR).listFiles { f -> f.extension == "json" }.orEmpty()
            .sortedBy { it.name }.map { decodeComment(it.readText()) }
        return Unpacked(decodeBook(bookFile.readText()), comments, root)
    }
}

@Serializable
data class PackagedBook(
    val addedAt: String,
    val audioFile: String,
    val chapters: List<ChapterMark> = emptyList(),
    val duration: Double,
    val format: Int = BookPackage.FORMAT,
    val id: String,
    val language: String? = null,
    val lastOpened: String? = null,
    val position: Double = 0.0,
    val rate: Double = 1.0,
    /** `source.pdf` when the book carries the paper. */
    val sourceFile: String? = null,
    val textFile: String? = null,
    val timingFile: String? = null,
    val title: String,
    val updatedAt: String,
    val voiceName: String? = null,
)

@Serializable
data class PackagedComment(
    val audioFile: String? = null,
    val chapter: String? = null,
    val confidence: Double = 0.0,
    val createdAt: String,
    val duration: Double = 0.0,
    val endEstimatedOffset: Int? = null,
    val endMediaTime: Double? = null,
    val estimatedOffset: Int? = null,
    val id: String,
    /** "comment" or "bookmark". */
    val kind: String,
    val mediaTime: Double,
    val refinedOffset: Int? = null,
    val reviewTag: String? = null,
    val sentenceNudge: Int = 0,
    val transcript: String = "",
    /** "none", "pending", "done", "failed". */
    val transcriptStatus: String = "none",
    val updatedAt: String,
)
