package de.kaikunze.marginvoice.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import de.kaikunze.marginvoice.core.ReviewMachine
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Short generated tones that tell the driver what happened without looking.
 *
 * - record start: rising two-note "ready" (C5 → G5)
 * - saved: falling two-note (G5 → C5)
 * - discarded: two low blips
 * - bookmark: single short tick
 * - bookmark end (passage): two rising ticks
 */
object Earcons {
    const val SAMPLE_RATE = 44_100

    private class Note(val frequency: Double, val duration: Double, val gap: Double = 0.03)

    fun samples(earcon: ReviewMachine.Earcon): ShortArray = when (earcon) {
        ReviewMachine.Earcon.RECORD_START -> render(listOf(Note(523.25, 0.11), Note(783.99, 0.16)))
        ReviewMachine.Earcon.RECORD_SAVED -> render(listOf(Note(783.99, 0.11), Note(523.25, 0.16)))
        ReviewMachine.Earcon.RECORD_DISCARDED -> render(listOf(Note(220.0, 0.09, gap = 0.07), Note(220.0, 0.09)))
        ReviewMachine.Earcon.BOOKMARK -> render(listOf(Note(1318.5, 0.05)))
        ReviewMachine.Earcon.BOOKMARK_END -> render(listOf(Note(1318.5, 0.05, gap = 0.05), Note(1760.0, 0.06)))
    }

    private fun render(notes: List<Note>, amplitude: Float = 0.35f): ShortArray {
        val rate = SAMPLE_RATE.toDouble()
        val out = ArrayList<Short>()
        for (note in notes) {
            val n = (note.duration * rate).toInt()
            val attack = (0.008 * rate).toInt()
            for (k in 0 until n) {
                // short attack, exponential decay: a soft "bell" rather than a harsh beep
                val env = min(1.0, k.toDouble() / attack) * exp(-3.0 * k / n)
                val phase = 2 * PI * note.frequency * k / rate
                val v = amplitude * env * (sin(phase) + 0.25 * sin(2 * phase))
                out.add((v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort())
            }
            repeat((note.gap * rate).toInt()) { out.add(0) }
        }
        return out.toShortArray()
    }
}

/**
 * Plays earcons over the book on the media stream, so they follow the media volume and
 * go wherever the book goes. A bare AudioTrack takes no audio focus, so the book plays on.
 */
class EarconPlayer {
    private val tracks = HashMap<ReviewMachine.Earcon, AudioTrack>()

    fun play(earcon: ReviewMachine.Earcon) {
        if (!start(track(earcon))) {
            // A dead track (the audio server restarted): build it again once.
            tracks.remove(earcon)?.release()
            runCatching { start(track(earcon)) }
        }
    }

    private fun track(earcon: ReviewMachine.Earcon): AudioTrack = tracks.getOrPut(earcon) {
        val pcm = Earcons.samples(earcon)
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(Earcons.SAMPLE_RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .also { it.write(pcm, 0, pcm.size) }
    }

    private fun start(track: AudioTrack): Boolean = runCatching {
        track.stop()
        check(track.reloadStaticData() == AudioTrack.SUCCESS)
        track.play()
    }.isSuccess

    fun release() {
        tracks.values.forEach { it.release() }
        tracks.clear()
    }
}
