package de.kaikunze.marginvoice.voice

import android.util.Log
import de.kaikunze.marginvoice.core.CommentRecord
import de.kaikunze.marginvoice.core.ContextVocabulary
import de.kaikunze.marginvoice.core.ReviewTag
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.model.Book
import de.kaikunze.marginvoice.model.BookContent
import de.kaikunze.marginvoice.model.BookStore
import de.kaikunze.marginvoice.model.Comment
import de.kaikunze.marginvoice.model.Library
import de.kaikunze.marginvoice.model.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * After a comment is saved: transcribes its recording in the comment language,
 * primed with the terms of the passage, and pins it to the exact place in the
 * text. Runs each comment once at a time, survives the comment being deleted
 * meanwhile, and picks up transcriptions an app kill cut off. Main thread.
 */
class CommentProcessor(
    private val store: BookStore,
    private val library: Library,
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val engines: Engines,
) {
    /** The speech work, replaceable in tests. */
    interface Engines {
        suspend fun transcribe(file: File, language: VoiceCommand.Language, vocabulary: List<String>): String
        suspend fun refine(bookAudio: File, anchor: Double, content: BookContent, language: VoiceCommand.Language): AnchorRefiner.Refinement?
    }

    /** Saves after each step ("transcript"); the session reports failures. */
    var save: (String) -> Unit = {}
    var note: (String, Boolean) -> Unit = { _, _ -> }

    /** Comments being transcribed and anchored right now. */
    private val running = HashMap<UUID, CompletableDeferred<Unit>>()
    /** Running comments to transcribe once more when they finish (the language changed). */
    private val rerun = HashSet<UUID>()

    /** Transcribes the comment and pins it to the text. Safe to call again (retry). */
    suspend fun process(comment: Comment) {
        val id = comment.id
        if (!library.exists(comment) || running.containsKey(id)) return
        val done = CompletableDeferred<Unit>()
        running[id] = done
        try {
            do {
                rerun.remove(id)
                run(comment)
            } while (id in rerun && library.exists(comment))
        } finally {
            running.remove(id)
            done.complete(Unit)
        }
    }

    private suspend fun run(comment: Comment) {
        val book = library.bookOf(comment) ?: return
        val content = store.content(book)
        // deleted (or its book) while the text loaded
        if (!library.exists(comment)) return
        val audio = store.audioFile(comment, book)
        if (comment.kind == CommentRecord.Kind.COMMENT && audio != null) {
            comment.transcriptStatus = Comment.TranscriptStatus.PENDING
            val language = settings.commentLanguage.resolved(book.language)
            // the passage commented on primes the recognizer with its names and terms
            val vocabulary = content?.let { c ->
                ContextVocabulary.terms(comment.textOffset(c.text) ?: c.offset(comment.mediaTime), c.text, book.title)
            } ?: ContextVocabulary.terms(book.title)
            try {
                val raw = engines.transcribe(audio, language, vocabulary)
                // deleted while it was being transcribed
                if (!library.exists(comment)) return
                val stripped = VoiceCommand.strippingTrailingCommand(raw, VoiceCommand.Language.entries.toSet())
                // "weakness: …" sorts the comment into the review draft; the tag word leaves the transcript
                val (tag, text) = ReviewTag.extract(stripped)
                comment.transcript = text
                if (tag != null) comment.tag = tag
                comment.transcriptStatus = Comment.TranscriptStatus.DONE
            } catch (e: Exception) {
                if (!library.exists(comment)) return
                comment.transcriptStatus = Comment.TranscriptStatus.FAILED
                Log.e(TAG, "transcribe: ${e.message}")
            }
            save("transcript")
        }
        if (comment.refinedOffset != null || content == null) return
        try {
            val r = engines.refine(store.audioFile(book), comment.mediaTime, content, VoiceCommand.Language.fromCode(book.language))
            if (r != null && r.confidence >= 0.4 && library.exists(comment)) {
                comment.refinedOffset = r.offset
                comment.confidence = maxOf(comment.confidence, r.confidence)
                save("refined anchor")
            }
        } catch (e: Exception) {
            Log.e(TAG, "refine: ${e.message}")
        }
    }

    /** Comments still marked as being transcribed when the app last quit. */
    fun resumeCutOff(book: Book) {
        for (comment in book.comments.toList()) {
            if (comment.transcriptStatus == Comment.TranscriptStatus.PENDING && !running.containsKey(comment.id)) {
                scope.launch { process(comment) }
            }
        }
    }

    /**
     * Transcribes every recorded comment of a book again (after changing the
     * comment language). Replaces the transcripts, including edited ones.
     */
    suspend fun transcribeAgain(book: Book) {
        val comments = book.sortedComments.filter { it.kind == CommentRecord.Kind.COMMENT && it.audioFile != null }
        for (comment in comments) {
            comment.transcriptStatus = Comment.TranscriptStatus.PENDING
            comment.tag = null
        }
        for (comment in comments) {
            // one already running started in the old language: run it once more after
            val task = running[comment.id]
            if (task != null) {
                rerun.add(comment.id)
                task.await()
            } else {
                process(comment)
            }
        }
        note("Transcribed ${comments.size} comments again", true)
    }

    companion object {
        private const val TAG = "CommentProcessor"
    }
}
