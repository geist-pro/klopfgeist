package de.kaikunze.marginvoice.voice

import de.kaikunze.marginvoice.core.ContextAligner
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.model.BookContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Pins a comment to the exact place in the text: transcribes the book audio just
 * before the anchor and aligns those words with the narration text.
 */
class AnchorRefiner(private val transcriber: FileTranscriber) {
    data class Refinement(val offset: Int, val confidence: Double)

    suspend fun refine(bookAudio: File, anchor: Double, content: BookContent, language: VoiceCommand.Language): Refinement? {
        val start = maxOf(0.0, anchor - CONTEXT_SECONDS)
        if (anchor - start <= 2) return null
        val clip = withContext(Dispatchers.IO) { PcmDecoder.decode(bookAudio, start, anchor) }
        val transcript = transcriber.transcribe(clip, language)

        val estimate = content.offset(anchor)
        // search ±90 s of narration around the estimate (chapter-based estimates drift)
        val span = content.offset(anchor + 90) - content.offset(maxOf(0.0, anchor - 90))
        val window = maxOf(1_500, span / 2 + 300)
        val r = ContextAligner.align(transcript, content.text, near = estimate, window = window, minimumMatches = 5) ?: return null
        return Refinement(r.offset, r.confidence)
    }

    companion object {
        const val CONTEXT_SECONDS = 20.0
    }
}
