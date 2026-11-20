package de.kaikunze.marginvoice.voice

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/** Decodes (part of) an audio file to 16 kHz mono 16-bit PCM, the format the recognizers take. */
object PcmDecoder {
    const val RATE = 16_000

    fun decode(file: File, start: Double = 0.0, end: Double = Double.MAX_VALUE): ShortArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.path)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); error("no audio track in ${file.name}") }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        if (start > 0) extractor.seekTo((start * 1_000_000).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mono = ArrayList<Float>()
        var firstTime = -1L
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || extractor.sampleTime > end * 1_000_000) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        sampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    out >= 0 -> {
                        val buffer = codec.getOutputBuffer(out)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                        if (firstTime < 0) firstTime = info.presentationTimeUs
                        val frames = buffer.remaining() / channels
                        // drop what the sync-frame seek decoded before `start`
                        val skip = if (start > 0) maxOf(0, ((start * 1_000_000 - info.presentationTimeUs) / 1_000_000.0 * sampleRate).toInt()) else 0
                        for (f in 0 until frames) {
                            var sum = 0f
                            for (c in 0 until channels) sum += buffer.get()
                            if (f >= skip) mono.add(sum / channels)
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        val wanted = if (end == Double.MAX_VALUE) mono.size else minOf(mono.size, ((end - maxOf(0.0, start)) * sampleRate).toInt())
        return resample(mono, wanted, sampleRate)
    }

    /** Linear interpolation to 16 kHz; plenty for speech recognition. */
    private fun resample(input: List<Float>, count: Int, rate: Int): ShortArray {
        if (rate == RATE) return ShortArray(count) { input[it].toInt().coerceIn(-32768, 32767).toShort() }
        val outCount = (count.toLong() * RATE / rate).toInt()
        val step = rate.toDouble() / RATE
        return ShortArray(outCount) { i ->
            val x = i * step
            val a = x.toInt().coerceAtMost(count - 1)
            val b = (a + 1).coerceAtMost(count - 1)
            val t = (x - a).toFloat()
            (input[a] * (1 - t) + input[b] * t).toInt().coerceIn(-32768, 32767).toShort()
        }
    }
}
