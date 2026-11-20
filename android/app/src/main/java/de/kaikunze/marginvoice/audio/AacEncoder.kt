package de.kaikunze.marginvoice.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import de.kaikunze.marginvoice.core.conversion.PcmSink
import java.io.File
import java.nio.ByteOrder

/** Encodes mono 16-bit PCM to an AAC `.m4a` (MediaCodec + MediaMuxer). Not thread-safe. */
class AacEncoder(file: File, override val sampleRate: Int, bitRate: Int = 64_000) : PcmSink {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private val info = MediaCodec.BufferInfo()
    private var closed = false
    private var started = false

    /** Samples written so far. */
    override var frames = 0L
        private set

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    override fun write(samples: ShortArray) = write(samples, 0, samples.size)

    fun write(samples: ShortArray, offset: Int, count: Int) {
        var at = offset
        val end = offset + count
        while (at < end) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) { drain(false); continue }
            val buffer = codec.getInputBuffer(index)!!.order(ByteOrder.nativeOrder())
            val n = minOf(end - at, buffer.capacity() / 2)
            buffer.asShortBuffer().put(samples, at, n)
            codec.queueInputBuffer(index, 0, n * 2, frames * 1_000_000 / sampleRate, 0)
            frames += n
            at += n
            drain(false)
        }
    }

    /** At the end of the stream waits for the end-of-stream buffer, but at most [deadlineMs]. */
    private fun drain(endOfStream: Boolean, deadlineMs: Long = 2_000) {
        val until = System.nanoTime() + deadlineMs * 1_000_000
        while (true) {
            val out = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream || System.nanoTime() > until) return
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                out >= 0 -> {
                    val buffer = codec.getOutputBuffer(out)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && track >= 0) muxer.writeSampleData(track, buffer, info)
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Finishes the file. Safe to call twice; a failure still releases everything. */
    fun close() {
        if (closed) return
        closed = true
        try {
            // The encoder may hold every input buffer: drain and retry for up to a second.
            // Without the end-of-stream flag, drain(true) stops at its deadline.
            val until = System.nanoTime() + 1_000_000_000L
            while (System.nanoTime() < until) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, frames * 1_000_000 / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(false)
            }
            drain(true)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
        }
    }
}
