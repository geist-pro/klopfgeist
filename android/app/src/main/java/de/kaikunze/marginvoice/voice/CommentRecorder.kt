package de.kaikunze.marginvoice.voice

import de.kaikunze.marginvoice.audio.AacEncoder
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Writes microphone audio to an AAC `.m4a` while a comment is being recorded.
 * `write` is called on the microphone thread; all methods are synchronized.
 *
 * Stopping answers at once with the duration (the samples written); finishing the
 * file (draining the encoder, writing the container: up to a few seconds) happens on
 * a thread of its own. Whoever reads the file calls [awaitFinished] first.
 */
class CommentRecorder {
    private var encoder: AacEncoder? = null
    private var file: File? = null
    private var frames = 0L
    private var sampleRate = 16_000
    private var failure: Exception? = null
    private val finisher = Executors.newSingleThreadExecutor { Thread(it, "comment-file").apply { isDaemon = true } }
    private val finishing = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** The file could not be finished (after `stop` had answered). */
    var onFinishFailed: (File, Exception) -> Unit = { _, _ -> }

    @get:Synchronized
    val isRecording: Boolean get() = encoder != null

    @Synchronized
    fun start(file: File, sampleRate: Int) {
        stop()
        encoder = AacEncoder(file, sampleRate, bitRate = 48_000)
        this.file = file
        frames = 0
        failure = null
        this.sampleRate = sampleRate
    }

    /** Mono float samples, -1...1. */
    @Synchronized
    fun write(samples: FloatArray, count: Int) {
        val e = encoder ?: return
        try {
            e.write(ShortArray(count) { (samples[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() })
            frames = e.frames
        } catch (ex: Exception) {
            failure = ex
            encoder = null
            finish(e)
        }
    }

    /**
     * Ends the recording and returns the recorded duration in seconds, and the write
     * error if the recording stopped early (what was written up to then is kept).
     * The file is complete once [awaitFinished] returns.
     */
    @Synchronized
    fun stop(): Pair<Double, Exception?> {
        encoder?.let(::finish)
        encoder = null
        return frames.toDouble() / sampleRate to failure
    }

    private fun finish(e: AacEncoder) {
        val f = file ?: return
        val done = CompletableDeferred<Unit>()
        finishing[f.path] = done
        finisher.execute {
            try {
                e.close()
            } catch (ex: Exception) {
                onFinishFailed(f, ex)
            } finally {
                finishing.remove(f.path, done)
                done.complete(Unit)
            }
        }
    }

    /** Returns when the recording in `file` is complete on disk (at once for any other file). */
    suspend fun awaitFinished(file: File) {
        finishing[file.path]?.await()
    }
}
