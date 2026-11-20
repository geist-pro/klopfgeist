package de.kaikunze.marginvoice.model

import de.kaikunze.marginvoice.core.BookPackage
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.core.OffsetUnits
import de.kaikunze.marginvoice.core.PackagedBook
import de.kaikunze.marginvoice.core.PackagedComment
import java.util.UUID

// The app's model ↔ the package format (`BookPackage`). Offsets are UTF-16 here and
// code points in packages, so the narration text comes along for the conversion.

fun Book.packaged(hasSource: Boolean): PackagedBook = PackagedBook(
    addedAt = BookPackage.date(addedAt), audioFile = audioFile, chapters = chapters, duration = duration,
    id = id.toString().uppercase(), language = language, lastOpened = lastOpened?.let(BookPackage::date), position = position,
    rate = rate, sourceFile = if (hasSource) BookPackage.SOURCE_PDF else null, textFile = textFile, timingFile = timingFile,
    title = title, updatedAt = BookPackage.date(updatedAt), voiceName = voiceName,
)

fun Comment.packaged(text: String?): PackagedComment {
    fun cp(o: Int?) = o?.let { if (text == null) it else OffsetUnits.toCodePoints(text, it) }
    return PackagedComment(
        audioFile = audioFile, chapter = chapter, confidence = confidence, createdAt = BookPackage.date(createdAt),
        duration = duration, endEstimatedOffset = cp(endEstimatedOffset), endMediaTime = endMediaTime,
        estimatedOffset = cp(estimatedOffset), id = id.toString().uppercase(), kind = kind.raw, mediaTime = mediaTime,
        refinedOffset = cp(refinedOffset), reviewTag = reviewTag, sentenceNudge = sentenceNudge, transcript = transcript,
        transcriptStatus = transcriptStatus.raw, updatedAt = BookPackage.date(updatedAt),
    )
}

fun PackagedBook.toBook(folder: String): Book = Book(
    UUID.fromString(id), title, folder, audioFile, textFile, timingFile, language, voiceName, duration, chapters, position, rate,
    BookPackage.date(addedAt), lastOpened?.let(BookPackage::date),
).also { it.updatedAt = BookPackage.date(updatedAt) }

/** Takes the changeable fields of a newer copy of the same book. */
fun Book.update(from: PackagedBook) {
    title = from.title
    duration = from.duration
    position = from.position
    rate = from.rate
    lastOpened = from.lastOpened?.let(BookPackage::date)
    updatedAt = BookPackage.date(from.updatedAt)
}

fun PackagedComment.toComment(text: String?): Comment {
    fun utf16(o: Int?) = o?.let { if (text == null) it else OffsetUnits.fromCodePoints(text, it) }
    return Comment(
        UUID.fromString(id), if (kind == "bookmark") CommentRecord.Kind.BOOKMARK else CommentRecord.Kind.COMMENT, mediaTime,
        utf16(estimatedOffset), confidence, chapter, audioFile, duration, BookPackage.date(createdAt), transcript,
        Comment.TranscriptStatus.entries.firstOrNull { it.raw == transcriptStatus } ?: Comment.TranscriptStatus.NONE,
        utf16(refinedOffset), sentenceNudge, endMediaTime, utf16(endEstimatedOffset), reviewTag,
    ).also { it.updatedAt = BookPackage.date(updatedAt) }
}

/** Takes the changeable fields of a newer copy of the same comment. */
fun Comment.update(from: PackagedComment, text: String?) {
    val c = from.toComment(text)
    confidence = c.confidence
    transcript = c.transcript
    transcriptStatus = c.transcriptStatus
    refinedOffset = c.refinedOffset
    sentenceNudge = c.sentenceNudge
    endMediaTime = c.endMediaTime
    endEstimatedOffset = c.endEstimatedOffset
    reviewTag = c.reviewTag
    updatedAt = c.updatedAt
}
