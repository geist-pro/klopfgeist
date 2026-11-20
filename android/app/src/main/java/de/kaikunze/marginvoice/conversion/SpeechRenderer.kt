package de.kaikunze.marginvoice.conversion

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import de.kaikunze.marginvoice.audio.AacEncoder
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.core.conversion.Narrator
import de.kaikunze.marginvoice.core.conversion.PlanRenderer
import de.kaikunze.marginvoice.core.conversion.SampleVoice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Renders a narration plan to an AAC `.m4a` with the device's text-to-speech voices,
 * one sentence at a time, recording exact timing for every sentence (the same
 * `timing.json` doc2audio writes, with code point offsets).
 */
typealias Output = PlanRenderer.Output

class SpeechRenderer private constructor(private val tts: TextToSpeech) {

    class RenderError(message: String) : Exception(message)

    private val done = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) { done.remove(utteranceId)?.complete(true) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { done.remove(utteranceId)?.complete(false) }
            override fun onError(utteranceId: String, errorCode: Int) { done.remove(utteranceId)?.complete(false) }
        })
    }

    /** Installed voices of a language that work offline, best first. */
    fun voices(language: String): List<Voice> {
        val region = VoiceCommand.Language.fromCode(language).locale
        return tts.voices.orEmpty()
            .filter { it.locale.language == language && !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
            .sortedWith(compareByDescending<Voice> { it.quality }.thenByDescending { it.locale == region }.thenBy { it.name })
    }

    fun bestVoice(language: String): Voice? = voices(language).firstOrNull()

    /** Reads a sentence aloud (the sample in the conversion sheet). */
    fun preview(text: String, voice: Voice?) {
        voice?.let { tts.voice = it }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    fun stopPreview() = tts.stop()

    /** The voice's display name, for the book ("en-us-x-iol-local"). */
    var voiceName: String? = null
        private set

    suspend fun render(plan: Narrator.Plan, voice: Voice?, out: File, scratch: File, progress: (Double) -> Unit = {}): Output {
        voice?.let { tts.voice = it }
        voiceName = tts.voice?.name
        var i = 0
        return render(plan, out, scratch, progress) { text ->
            val wav = File(scratch, "u$i.wav")
            val (pcm, rate) = synthesize(text, wav, "u${i++}")
            wav.delete()
            PlanRenderer.Speech(pcm, rate)
        }
    }

    /** Renders with a neural voice instead of the system's (the engines come later; see the README). */
    suspend fun render(plan: Narrator.Plan, voice: SampleVoice, out: File, progress: (Double) -> Unit = {}): Output {
        voiceName = voice.name
        val scratch = File(out.parentFile, ".render-${out.name}")
        return render(plan, out, scratch, progress) { text -> PlanRenderer.Speech(PlanRenderer.pcm16(voice.synthesize(text)), voice.sampleRate) }
    }

    private suspend fun render(plan: Narrator.Plan, out: File, scratch: File, progress: (Double) -> Unit, speak: suspend (String) -> PlanRenderer.Speech): Output {
        out.delete()
        scratch.mkdirs()
        var encoder: AacEncoder? = null
        try {
            val output = try {
                PlanRenderer.render(plan, speak, { rate -> AacEncoder(out, rate).also { encoder = it } }, progress)
            } catch (e: PlanRenderer.RenderError) {
                throw RenderError(e.message ?: "The voice produced no audio.")
            }
            encoder?.close()
            return output
        } finally {
            runCatching { encoder?.close() }
            scratch.deleteRecursively()
        }
    }

    fun cancel() = tts.stop()

    /** One sentence to a WAV file; a voice that never finishes is given up after 30 s. */
    private suspend fun synthesize(text: String, wav: File, id: String): Pair<ShortArray, Int> {
        val finished = CompletableDeferred<Boolean>()
        done[id] = finished
        if (tts.synthesizeToFile(text, Bundle(), wav, id) != TextToSpeech.SUCCESS) {
            done.remove(id)
            throw RenderError("The voice could not speak “${text.take(40)}”.")
        }
        val ok = withTimeoutOrNull(30_000) { finished.await() }
        done.remove(id)
        if (ok == false) throw RenderError("The voice failed on “${text.take(40)}”.")
        return readWav(wav)
    }

    companion object {
        /** Waits for the system's TTS engine. */
        suspend fun create(context: Context): SpeechRenderer {
            val ready = CompletableDeferred<Int>()
            val tts = TextToSpeech(context.applicationContext) { ready.complete(it) }
            if (ready.await() != TextToSpeech.SUCCESS) throw RenderError("No text-to-speech engine is available.")
            return SpeechRenderer(tts)
        }

        /** 16-bit PCM WAV (mono or the first channel); engines sometimes leave the data size at 0. */
        internal fun readWav(file: File): Pair<ShortArray, Int> {
            if (!file.exists() || file.length() < 44) return ShortArray(0) to 22_050
            RandomAccessFile(file, "r").use { f ->
                val header = ByteArray(12).also { f.readFully(it) }
                require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "not a WAV file" }
                var rate = 22_050
                var channels = 1
                while (f.filePointer + 8 <= f.length()) {
                    val idBytes = ByteArray(4).also { f.readFully(it) }
                    val size = Integer.reverseBytes(f.readInt())
                    when (String(idBytes, Charsets.US_ASCII)) {
                        "fmt " -> {
                            val fmt = ByteArray(size).also { f.readFully(it) }
                            val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                            channels = b.getShort(2).toInt()
                            rate = b.getInt(4)
                        }
                        "data" -> {
                            val available = (f.length() - f.filePointer).toInt()
                            val n = if (size in 1..available) size else available
                            val bytes = ByteArray(n).also { f.readFully(it) }
                            val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frames = shorts.remaining() / channels
                            return ShortArray(frames) { shorts.get(it * channels) } to rate
                        }
                        else -> f.seek(f.filePointer + size)
                    }
                }
                return ShortArray(0) to rate
            }
        }

    }
}
