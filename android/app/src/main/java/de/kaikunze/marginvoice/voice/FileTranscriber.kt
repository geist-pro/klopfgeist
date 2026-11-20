package de.kaikunze.marginvoice.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import de.kaikunze.marginvoice.core.VoiceCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Transcribes recorded audio on the device: Android's on-device recognizer
 * (Google's, on a Pixel) fed from the file, primed with terms from the book;
 * the offline Vosk model when that is missing or fails.
 */
class FileTranscriber(private val context: Context, private val models: VoskModels) {
    class Failure(message: String) : Exception(message)

    /** @param vocabulary words the speaker is likely to use (names, terms from the book). */
    suspend fun transcribe(file: File, language: VoiceCommand.Language, vocabulary: List<String> = emptyList()): String {
        val pcm = withContext(Dispatchers.IO) { PcmDecoder.decode(file) }
        return transcribe(pcm, language, vocabulary)
    }

    suspend fun transcribe(pcm: ShortArray, language: VoiceCommand.Language, vocabulary: List<String> = emptyList()): String {
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            try {
                return withTimeout(60_000 + pcm.size / 16L) { onDevice(pcm, language, vocabulary) }
            } catch (e: Exception) {
                Log.w(TAG, "on-device recognizer: ${e.message}; using Vosk")
            }
        }
        return withContext(Dispatchers.Default) { vosk(pcm, language) }
    }

    private fun intent(language: VoiceCommand.Language) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.regionIdentifier)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private suspend fun onDevice(pcm: ShortArray, language: VoiceCommand.Language, vocabulary: List<String>): String =
        withContext(Dispatchers.Main) {
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            var pipe: Array<ParcelFileDescriptor>? = null
            try {
                ensureLanguage(recognizer, language)
                pipe = ParcelFileDescriptor.createPipe()
                val intent = intent(language).apply {
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe!![0])
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, PcmDecoder.RATE)
                    // one session for the whole file, reported segment by segment
                    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                    if (vocabulary.isNotEmpty()) putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(vocabulary))
                }
                suspendCancellableCoroutine { cont ->
                    val parts = ArrayList<String>()
                    fun finish() {
                        Log.i(TAG, "on-device: ${pcm.size / PcmDecoder.RATE} s → ${parts.size} segments")
                        if (cont.isActive) cont.resume(parts.joinToString(" ").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" "))
                    }
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onSegmentResults(segmentResults: Bundle) {
                            segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                                ?.takeIf { it.isNotBlank() }?.let(parts::add)
                        }
                        override fun onEndOfSegmentedSession() = finish()
                        override fun onResults(results: Bundle) {
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                                ?.takeIf { it.isNotBlank() }?.let(parts::add)
                            finish()
                        }
                        override fun onError(error: Int) {
                            // silence only: nothing was said
                            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) finish()
                            else if (cont.isActive) cont.resumeWithException(Failure("recognizer error $error"))
                        }
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    cont.invokeOnCancellation { recognizer.cancel() }
                    // startListening only queues the call until the recognition service is
                    // bound, so the read end stays open until the session is over.
                    recognizer.startListening(intent)
                    feed(pipe!![1], pcm)
                }
            } finally {
                recognizer.destroy()
                // Closing the read end also ends a write the recognizer never read.
                pipe?.get(0)?.let { runCatching { it.close() } }
            }
        }

    /**
     * Writes the audio into the recognizer's pipe on a background thread, then closes it.
     * Paced: fed all at once, Google's recognizer drops most of it and hears only the end.
     */
    private fun feed(out: ParcelFileDescriptor, pcm: ShortArray) {
        writer.execute {
            runCatching {
                FileOutputStream(out.fileDescriptor).use { stream ->
                    val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                    bytes.asShortBuffer().put(pcm)
                    // tail of silence so the last words are finalized
                    val audio = bytes.array() + ByteArray(PcmDecoder.RATE * 2)
                    val chunk = PcmDecoder.RATE * 2 / 10  // 100 ms
                    var offset = 0
                    while (offset < audio.size) {
                        val n = minOf(chunk, audio.size - offset)
                        stream.write(audio, offset, n)
                        offset += n
                        Thread.sleep(100 / SPEEDUP)
                    }
                }
            }
            runCatching { out.close() }
        }
    }

    /** The language must be installed on the device; asks for the download once when it isn't. */
    private suspend fun ensureLanguage(recognizer: SpeechRecognizer, language: VoiceCommand.Language) {
        val support = suspendCancellableCoroutine<RecognitionSupport?> { cont ->
            recognizer.checkRecognitionSupport(intent(language), Executors.newSingleThreadExecutor(), object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) { if (cont.isActive) cont.resume(recognitionSupport) }
                override fun onError(error: Int) { if (cont.isActive) cont.resume(null) }
            })
        } ?: return
        val tag = language.regionIdentifier
        if (support.installedOnDeviceLanguages.any { it.equals(tag, true) }) return
        if (support.supportedOnDeviceLanguages.any { it.equals(tag, true) } && support.pendingOnDeviceLanguages.none { it.equals(tag, true) }) {
            recognizer.triggerModelDownload(intent(language))
        }
        throw Failure("$tag speech recognition is not installed yet")
    }

    private fun vosk(pcm: ShortArray, language: VoiceCommand.Language): String {
        val recognizer = Recognizer(models.model(language), PcmDecoder.RATE.toFloat())
        recognizer.use { r ->
            val texts = ArrayList<String>()
            var offset = 0
            while (offset < pcm.size) {
                val n = minOf(8000, pcm.size - offset)
                if (r.acceptWaveForm(pcm.copyOfRange(offset, offset + n), n)) texts.add(text(r.result))
                offset += n
            }
            texts.add(text(r.finalResult))
            return texts.filter { it.isNotBlank() }.joinToString(" ")
        }
    }

    private fun text(json: String) = Regex("\"text\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1).orEmpty()

    companion object {
        private const val TAG = "FileTranscriber"
        private val writer = Executors.newCachedThreadPool()
        /** How much faster than real time the recognizer is fed. */
        private const val SPEEDUP = 2L
    }
}
