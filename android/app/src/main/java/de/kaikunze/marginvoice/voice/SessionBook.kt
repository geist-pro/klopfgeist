package de.kaikunze.marginvoice.voice

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.model.BookContent
import de.kaikunze.marginvoice.model.BookStore
import de.kaikunze.marginvoice.model.Comment
import de.kaikunze.marginvoice.model.Library
import de.kaikunze.marginvoice.session.BookmarkClose
import de.kaikunze.marginvoice.session.ReviewBook
import java.io.File

/**
 * The open book as the session sees it ([ReviewBook]), over the JSON [Library]:
 * where comments, bookmarks and passages are created and the position is kept.
 * Every change is saved at once; each write reports whether the library file took it.
 */
class SessionBook(
    val book: Book,
    private val store: BookStore,
    private val library: Library,
    /** A comment or bookmark was added: transcribe it and pin it to the text. */
    private val added: (Comment) -> Unit = {},
) : ReviewBook {
    /** The narration text with its time map; loads a moment after the audio (null until then, and for books without text). */
    var content by mutableStateOf<BookContent?>(null)

    /** The bookmark a second "bookmark" would extend to a passage. */
    private var openBookmark: Comment? = null

    override val title: String get() = book.title
    override val audioFile: File get() = store.audioFile(book)
    override val position: Double get() = book.position
    override val rate: Double get() = book.rate

    override fun chapterTitle(at: Double): String? = book.chapterTitle(at)

    override fun recentText(before: Double, seconds: Double): String? = content?.recentText(before, seconds)

    override fun sentenceStart(at: Double): Double? = content?.sentenceStartTime(at)

    override fun newRecordingFile(): File? {
        val dir = store.commentsDir(book)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        return File(dir, "comment-${System.currentTimeMillis()}.m4a")
    }

    override fun discardRecording(file: File) {
        file.delete()
    }

    override fun addComment(at: Double, audio: File, duration: Double): Boolean = add(CommentRecord.Kind.COMMENT, at, audio.name, duration)

    override fun addBookmark(at: Double): Boolean = add(CommentRecord.Kind.BOOKMARK, at, null, 0.0)

    private fun add(kind: CommentRecord.Kind, time: Double, audioFile: String?, duration: Double): Boolean {
        val content = content
        val comment = Comment(
            kind = kind, mediaTime = time, estimatedOffset = content?.offset(time),
            confidence = content?.let { if (it.isExact) 0.8 else 0.3 } ?: 0.0,
            chapter = book.chapterTitle(time), audioFile = audioFile, duration = duration,
        )
        book.comments.add(comment)
        if (kind == CommentRecord.Kind.BOOKMARK) openBookmark = comment
        val saved = library.save()
        added(comment)
        return saved
    }

    override fun closeBookmark(at: Double): BookmarkClose {
        val bookmark = openBookmark
        // never set, or deleted since: there is nothing to extend
        if (bookmark == null || !library.exists(bookmark)) {
            openBookmark = null
            return BookmarkClose.MISSING
        }
        bookmark.endMediaTime = at
        bookmark.endEstimatedOffset = content?.offset(at)
        openBookmark = null
        return if (library.save()) BookmarkClose.CLOSED else BookmarkClose.NOT_SAVED
    }

    override fun savePosition(time: Double): Boolean {
        book.position = time
        return library.save()
    }

    /** Written with the next save (the position follows within seconds). */
    override fun saveRate(rate: Double) {
        book.rate = rate
    }
}
