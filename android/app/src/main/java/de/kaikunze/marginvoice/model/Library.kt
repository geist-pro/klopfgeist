package de.kaikunze.marginvoice.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.kaikunze.marginvoice.core.ChapterMark
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.core.NarrationText
import de.kaikunze.marginvoice.core.ReviewEntry
import de.kaikunze.marginvoice.core.ReviewTag
import de.kaikunze.marginvoice.core.indexAt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * An imported audiobook: the audio, its narration text and optional timing sidecar,
 * stored together in `files/Books/<folder>/`. Observable like the iOS SwiftData model.
 */
class Book(
    val id: UUID = UUID.randomUUID(),
    title: String,
    val folder: String,
    val audioFile: String,
    val textFile: String?,
    val timingFile: String?,
    /** BCP-47 language of the narration ("en", "de"), detected from the text. */
    val language: String?,
    /** Set for books created on the device: the system voice that narrated them. */
    val voiceName: String? = null,
    duration: Double,
    val chapters: List<ChapterMark>,
    position: Double = 0.0,
    rate: Double,
    val addedAt: Instant = Instant.now(),
    lastOpened: Instant? = null,
) {
    var title by mutableStateOf(title)
    var duration by mutableStateOf(duration)
    var position by mutableStateOf(position)
    var rate by mutableStateOf(rate)
    var lastOpened by mutableStateOf(lastOpened)
    val comments = mutableStateListOf<Comment>()
    /** When anything stored about the book last changed here (stamped by `Library.save`); sync picks the newer side by it. */
    var updatedAt: Instant = addedAt

    val sortedComments: List<Comment> get() = comments.sortedBy { it.mediaTime }

    val progress: Double get() = if (duration > 0) minOf(1.0, position / duration) else 0.0

    /** Listened to the end (within the last few seconds). */
    val isFinished: Boolean get() = duration > 0 && position >= duration - 3

    fun chapterIndex(time: Double): Int? = chapters.indexAt(time)

    fun chapterTitle(time: Double): String? = chapterIndex(time)?.let { chapters[it].title }
}

/** A spoken comment or a bookmark at a position in a book. */
class Comment(
    override val id: UUID = UUID.randomUUID(),
    override val kind: CommentRecord.Kind,
    /** Book media time of the anchor, seconds. */
    override val mediaTime: Double,
    /** Text offset estimated from the time map. */
    val estimatedOffset: Int?,
    confidence: Double,
    val chapter: String?,
    /** File name inside the book folder's `comments/` directory. */
    val audioFile: String?,
    val duration: Double = 0.0,
    val createdAt: Instant = Instant.now(),
    transcript: String = "",
    transcriptStatus: TranscriptStatus = if (kind == CommentRecord.Kind.COMMENT) TranscriptStatus.PENDING else TranscriptStatus.NONE,
    refinedOffset: Int? = null,
    sentenceNudge: Int = 0,
    endMediaTime: Double? = null,
    endEstimatedOffset: Int? = null,
    reviewTag: String? = null,
) : ReviewEntry {
    enum class TranscriptStatus(val raw: String) { NONE("none"), PENDING("pending"), DONE("done"), FAILED("failed") }

    var confidence by mutableStateOf(confidence)
    var transcript by mutableStateOf(transcript)
    var transcriptStatus by mutableStateOf(transcriptStatus)
    /** Text offset after aligning a transcript of the book audio, if that worked. */
    var refinedOffset by mutableStateOf(refinedOffset)
    /** Manual correction in sentences (from the nudge buttons). */
    var sentenceNudge by mutableStateOf(sentenceNudge)
    /** Bookmarked passages: where the passage ends (media seconds, estimated text offset). */
    override var endMediaTime by mutableStateOf(endMediaTime)
    var endEstimatedOffset by mutableStateOf(endEstimatedOffset)
    /** The review category spoken as the first word ("weakness: …"), a `ReviewTag.raw`; null for plain notes and before 0.2.0. */
    var reviewTag by mutableStateOf(reviewTag)

    /** When the comment last changed (stamped by `Library.save`), for sync. */
    var updatedAt: Instant = createdAt

    override var tag: ReviewTag?
        get() = ReviewTag.fromRaw(reviewTag)
        set(value) { reviewTag = value?.raw }

    val isPassage: Boolean get() = endMediaTime != null

    /** Best known text offset including manual nudges. */
    fun textOffset(text: NarrationText?): Int? =
        (refinedOffset ?: estimatedOffset)?.let { CommentRecord.nudgedOffset(it, sentenceNudge, text) }

    /** End of a bookmarked passage, moved by the same amount as the start (refined and nudged). */
    fun endTextOffset(text: NarrationText?): Int? =
        endEstimatedOffset?.let { CommentRecord.passageEnd(it, estimatedOffset, textOffset(text)) }

    fun record(text: NarrationText?, audioName: String? = null) = CommentRecord(
        id = id, kind = kind, mediaTime = mediaTime, textOffset = textOffset(text) ?: 0,
        confidence = if (sentenceNudge != 0) 1.0 else confidence, chapter = chapter, transcript = transcript,
        audioFile = audioName, createdAt = createdAt, endMediaTime = endMediaTime, endTextOffset = endTextOffset(text), tag = tag,
    )
}

/**
 * All books and their comments, saved as one JSON file. Every change is written
 * through `save`, atomically (a temporary file renamed over the old one), so a
 * crash leaves either the old or the new library, never a torn one.
 *
 * The file carries a schema version. Bump it only for a change an older app would
 * misread (a rename, a changed type or meaning) and read the old version too, so
 * existing comments survive the update (iOS: the SwiftData migration plan). A new
 * optional field with a null default needs no bump: old and new apps read both files.
 */
class Library(private val file: File) {
    val books = mutableStateListOf<Book>()

    /** Called after every successful save (sync waits for things to be quiet, then runs). */
    var onSaved: (() -> Unit)? = null

    /** Set when the file could not be read; nothing is saved then, so the stored comments stay. */
    var loadError: String? = null
        private set

    /** Newest first, like the iOS `@Query(sort: \Book.addedAt, order: .reverse)`. */
    val sorted: List<Book> get() = books.sortedByDescending { it.addedAt }

    fun book(id: UUID?): Book? = books.firstOrNull { it.id == id }

    fun bookOf(comment: Comment): Book? = books.firstOrNull { b -> b.comments.any { it === comment } }

    /** Whether the comment is still in the library (it may be deleted while it is processed). */
    fun exists(comment: Comment): Boolean = bookOf(comment) != null

    /**
     * Reads the library. A file that can't be read leaves the library empty and
     * read-only (`loadError`): saving over it would delete every comment.
     */
    fun load() {
        books.clear()
        loadError = null
        if (!file.exists()) return
        try {
            val stored = json.decodeFromString(StoredLibrary.serializer(), file.readText())
            require(stored.version <= VERSION) { "library version ${stored.version} is newer than this app" }
            books.addAll(stored.books.map { it.toBook() })
            remember()
        } catch (e: Exception) {
            books.clear()
            loadError = "Could not open the library: ${e.message}"
        }
    }

    /** The stored form of every book and comment as last saved (without `updatedAt`), to see what changed. */
    private val saved = HashMap<UUID, Any>()

    private fun remember() {
        saved.clear()
        for (b in books) {
            saved[b.id] = StoredBook.from(b).copy(comments = emptyList(), updatedAt = null)
            for (c in b.comments) saved[c.id] = StoredComment.from(c).copy(updatedAt = null)
        }
    }

    /**
     * Takes a book and its comments as saved, so values that came from another device
     * keep their `updatedAt` instead of being stamped as a change made here.
     */
    fun adopt(book: Book) {
        saved[book.id] = StoredBook.from(book).copy(comments = emptyList(), updatedAt = null)
        for (c in book.comments) saved[c.id] = StoredComment.from(c).copy(updatedAt = null)
    }

    /** Stamps `updatedAt` on what changed since the last save. */
    private fun stamp() {
        val now = Instant.now()
        for (b in books) {
            if (saved[b.id] != StoredBook.from(b).copy(comments = emptyList(), updatedAt = null)) b.updatedAt = now
            for (c in b.comments) if (saved[c.id] != StoredComment.from(c).copy(updatedAt = null)) c.updatedAt = now
        }
    }

    /** Saves; returns whether it worked. */
    fun save(): Boolean = loadError == null && runCatching {
        stamp()
        val stored = StoredLibrary(VERSION, books.map { StoredBook.from(it) })
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(StoredLibrary.serializer(), stored))
        if (!tmp.renameTo(file)) error("rename failed")
        remember()
    }.isSuccess.also { if (it) onSaved?.invoke() }

    companion object {
        const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = false }
    }
}

@Serializable
private data class StoredLibrary(val version: Int, val books: List<StoredBook>)

@Serializable
private data class StoredBook(
    val id: String, val title: String, val folder: String, val audioFile: String, val textFile: String? = null,
    val timingFile: String? = null, val language: String? = null, val voiceName: String? = null, val duration: Double,
    val chapters: List<ChapterMark> = emptyList(), val position: Double = 0.0, val rate: Double = 1.0, val addedAt: Long,
    val lastOpened: Long? = null, val comments: List<StoredComment> = emptyList(), val updatedAt: Long? = null,
) {
    fun toBook() = Book(
        UUID.fromString(id), title, folder, audioFile, textFile, timingFile, language, voiceName, duration, chapters,
        position, rate, Instant.ofEpochMilli(addedAt), lastOpened?.let(Instant::ofEpochMilli),
    ).also { b ->
        b.comments.addAll(comments.map { it.toComment() })
        updatedAt?.let { b.updatedAt = Instant.ofEpochMilli(it) }
    }

    companion object {
        fun from(b: Book) = StoredBook(
            b.id.toString(), b.title, b.folder, b.audioFile, b.textFile, b.timingFile, b.language, b.voiceName, b.duration,
            b.chapters, b.position, b.rate, b.addedAt.toEpochMilli(), b.lastOpened?.toEpochMilli(),
            b.comments.map { StoredComment.from(it) }, b.updatedAt.toEpochMilli(),
        )
    }
}

@Serializable
private data class StoredComment(
    val id: String, val kind: String, val mediaTime: Double, val estimatedOffset: Int? = null, val endMediaTime: Double? = null,
    val endEstimatedOffset: Int? = null, val refinedOffset: Int? = null, val sentenceNudge: Int = 0, val confidence: Double = 0.0,
    val chapter: String? = null, val transcript: String = "", val transcriptStatus: String = "none", val audioFile: String? = null,
    val duration: Double = 0.0, val createdAt: Long, val reviewTag: String? = null, val updatedAt: Long? = null,
) {
    fun toComment() = Comment(
        UUID.fromString(id), if (kind == "bookmark") CommentRecord.Kind.BOOKMARK else CommentRecord.Kind.COMMENT, mediaTime,
        estimatedOffset, confidence, chapter, audioFile, duration, Instant.ofEpochMilli(createdAt), transcript,
        Comment.TranscriptStatus.entries.firstOrNull { it.raw == transcriptStatus } ?: Comment.TranscriptStatus.NONE,
        refinedOffset, sentenceNudge, endMediaTime, endEstimatedOffset, reviewTag,
    ).also { c -> updatedAt?.let { c.updatedAt = Instant.ofEpochMilli(it) } }

    companion object {
        fun from(c: Comment) = StoredComment(
            c.id.toString(), c.kind.raw, c.mediaTime, c.estimatedOffset, c.endMediaTime, c.endEstimatedOffset, c.refinedOffset,
            c.sentenceNudge, c.confidence, c.chapter, c.transcript, c.transcriptStatus.raw, c.audioFile, c.duration,
            c.createdAt.toEpochMilli(), c.reviewTag, c.updatedAt.toEpochMilli(),
        )
    }
}

/** What deleting this note loses, for the confirmation. */
val Comment.deleteMessage: String
    get() = when {
        kind == CommentRecord.Kind.BOOKMARK -> "The bookmark is removed from the book."
        audioFile == null -> "The comment is removed from the book."
        else -> "The comment and its voice recording are deleted. This can’t be undone."
    }
