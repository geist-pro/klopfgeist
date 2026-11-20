package de.kaikunze.marginvoice.core.conversion

import de.kaikunze.marginvoice.core.ChapterMark
import de.kaikunze.marginvoice.core.TimingSidecar
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** A neural voice (iOS: `SpeechRenderer.SampleVoice`): one sentence in, mono samples (−1…1) out at [sampleRate]. */
class SampleVoice(val name: String, val sampleRate: Int, val synthesize: suspend (String) -> FloatArray)

/** Where rendered audio goes: the AAC encoder in the app, a counter in tests. */
interface PcmSink {
    val sampleRate: Int
    /** Samples written so far. */
    val frames: Long
    val seconds: Double get() = frames.toDouble() / sampleRate
    fun write(samples: ShortArray)
    fun writeSilence(seconds: Double) {
        if (seconds > 0) write(ShortArray((seconds * sampleRate).toInt()))
    }
}

/**
 * Renders a narration plan one sentence at a time, recording exact timing for every
 * sentence (the `timing.json` doc2audio writes, with code point offsets). The voice is
 * the system's or a neural one; either way the narration text, and with it every
 * comment's anchor, stays the same.
 */
object PlanRenderer {
    data class Output(val timing: TimingSidecar, val chapters: List<ChapterMark>, val duration: Double)

    class RenderError(message: String) : Exception(message)

    /** One sentence's 16-bit samples and their rate. */
    class Speech(val samples: ShortArray, val sampleRate: Int)

    /**
     * @param speak synthesizes one sentence.
     * @param sink opens the output at the first sentence's sample rate; later sentences are resampled to it.
     */
    suspend fun render(
        plan: Narrator.Plan,
        speak: suspend (String) -> Speech,
        sink: (Int) -> PcmSink,
        progress: (Double) -> Unit = {},
    ): Output {
        val text = plan.text
        var out: PcmSink? = null
        val chunks = ArrayList<TimingSidecar.Chunk>()
        val chapterStarts = ArrayList<Pair<String, Double>>()
        for ((i, utterance) in plan.utterances.withIndex()) {
            coroutineContext.ensureActive()
            val speech = speak(utterance.text)
            coroutineContext.ensureActive()
            val o = out ?: sink(speech.sampleRate).also { out = it }
            val start = o.seconds
            if (speech.samples.isNotEmpty()) {
                o.write(if (speech.sampleRate == o.sampleRate) speech.samples else resample(speech.samples, speech.sampleRate, o.sampleRate))
            }
            val end = o.seconds
            if (end > start) {
                chunks.add(TimingSidecar.Chunk(start, end, text.codePointCount(0, utterance.range.first), text.codePointCount(0, utterance.range.last + 1)))
            }
            utterance.chapter?.let { chapterStarts.add(it to start) }
            o.writeSilence(utterance.pauseAfter)
            progress((i + 1).toDouble() / plan.utterances.size)
        }
        val duration = out?.seconds ?: 0.0
        if (duration <= 0) throw RenderError("The voice produced no audio.")
        val chapters = chapterStarts.mapIndexed { i, (title, start) ->
            ChapterMark(title, start, chapterStarts.getOrNull(i + 1)?.second ?: duration)
        }
        return Output(TimingSidecar(chunks = chunks), chapters, duration)
    }

    suspend fun render(plan: Narrator.Plan, voice: SampleVoice, sink: (Int) -> PcmSink, progress: (Double) -> Unit = {}): Output =
        render(plan, { Speech(pcm16(voice.synthesize(it)), voice.sampleRate) }, sink, progress)

    fun pcm16(samples: FloatArray): ShortArray =
        ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }

    fun resample(pcm: ShortArray, from: Int, to: Int): ShortArray {
        val count = (pcm.size.toLong() * to / from).toInt()
        val step = from.toDouble() / to
        return ShortArray(count) { i ->
            val x = i * step
            val a = x.toInt().coerceAtMost(pcm.size - 1)
            val b = (a + 1).coerceAtMost(pcm.size - 1)
            val t = x - a
            (pcm[a] * (1 - t) + pcm[b] * t).toInt().toShort()
        }
    }
}
