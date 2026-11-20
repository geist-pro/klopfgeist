package de.kaikunze.marginvoice.audio

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import de.kaikunze.marginvoice.core.QuietPoint
import de.kaikunze.marginvoice.core.ReviewMachine
import de.kaikunze.marginvoice.core.ReviewMachine.Earcon
import de.kaikunze.marginvoice.session.BookPlayer
import de.kaikunze.marginvoice.session.PlayerEvent
import de.kaikunze.marginvoice.voice.PcmDecoder
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

/**
 * The session's [BookPlayer] on Android: ExoPlayer (speed with pitch kept), the
 * earcons with their haptics, and spoken announcements. It reports what the player
 * and the system do as [PlayerEvent]s and decides nothing. All calls on the main
 * thread. The media session in [PlaybackService] wraps [player] for the notification,
 * lock screen and headset buttons.
 *
 * `isPlaying`, `currentTime` and `duration` are Compose state for the screens.
 */
class ExoBookPlayer(
    private val context: Context,
    /** The app itself put the phone in communication mode (the headset's call microphone): not a call. */
    private val ownCommunicationMode: () -> Boolean = { false },
) : BookPlayer {
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            /* handleAudioFocus = */ true,
        )
        // headphones unplugged or the car disconnected: stop rather than blast the speaker
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(15_000)
        .build()
    private val earcons = EarconPlayer()
    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private var tts: TextToSpeech? = null
    private var item: MediaItem? = null
    private var bookFile: File? = null
    private var inCall = false
    /** What the library knows about the next book to load, until the file itself has been read. */
    private var expectedDuration = 0.0
    private var expectedTitle = ""

    override var onEvent: ((PlayerEvent) -> Unit)? = null

    /** Wanting to play (not paused), as opposed to ExoPlayer's `isPlaying`, which drops while it buffers after a seek. */
    override var isPlaying by mutableStateOf(false)
        private set
    var currentTime by mutableDoubleStateOf(0.0)
        private set
    override var duration by mutableDoubleStateOf(0.0)
        private set
    private var rateState = mutableDoubleStateOf(1.0)
    override val rate: Double get() = rateState.doubleValue
    var lastError by mutableStateOf<String?>(null)
        private set

    /** The engine is ready to speak (its start takes a moment the first time). */
    private var ttsReady = false
    /** What to say once the engine is ready. */
    private var waitingAnnouncement: Pair<String, Locale>? = null
    /** The utterance handed to the engine that it has not finished yet. */
    private var speaking: String? = null
    private var utterances = 0

    /** An announcement is waiting for the speech engine, or being spoken. */
    val isAnnouncing: Boolean get() = waitingAnnouncement != null || speaking != null || tts?.isSpeaking == true

    private val ticker = object : Runnable {
        override fun run() {
            currentTime = liveTime
            if (isPlaying) handler.postDelayed(this, 200)
        }
    }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // another app took the audio for good (a call, another player)
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) onEvent?.invoke(PlayerEvent.InterruptionBegan)
                // unplugged headphones (becoming noisy) arrive here too, as a plain pause
                updatePlaying()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    val d = player.duration
                    if (d != C.TIME_UNSET && d > 0) duration = d / 1000.0
                }
                if (state == Player.STATE_ENDED) {
                    currentTime = duration
                    player.playWhenReady = false
                    updatePlaying()
                    onEvent?.invoke(PlayerEvent.Finished)
                } else {
                    updatePlaying()
                }
            }

            override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                // a transient focus loss (a call, navigation): ExoPlayer holds playback itself
                if (reason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) onEvent?.invoke(PlayerEvent.InterruptionBegan)
            }

            override fun onPlayerError(error: PlaybackException) {
                lastError = error.message
                // the player stopped; left "wanting to play" it would start by itself with the next book
                player.playWhenReady = false
                updatePlaying()
            }
        })
        audio.addOnModeChangedListener(context.mainExecutor) { modeChanged(it) }
    }

    /** Every change of `isPlaying` is reported, whoever caused it. */
    private fun updatePlaying() {
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.playbackState != Player.STATE_IDLE
        if (playing == isPlaying) return
        isPlaying = playing
        currentTime = liveTime
        if (playing) handler.post(ticker) else handler.removeCallbacks(ticker)
        onEvent?.invoke(PlayerEvent.PlayingChanged(playing))
    }

    /**
     * A phone call (or a voice call in another app) takes the audio. ExoPlayer only
     * suppresses playback for it, and during a comment the book is paused anyway, so
     * the call is noticed here and reported as an interruption with a beginning and an end.
     */
    private fun modeChanged(mode: Int) {
        val call = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE ||
            mode == AudioManager.MODE_CALL_SCREENING || mode == AudioManager.MODE_CALL_REDIRECT ||
            mode == AudioManager.MODE_COMMUNICATION_REDIRECT ||
            // a headset microphone may switch our own audio to communication mode
            (mode == AudioManager.MODE_IN_COMMUNICATION && !ownCommunicationMode())
        Log.i(TAG, "audio mode $mode${if (call) " (call)" else ""}")
        if (call == inCall) return
        inCall = call
        onEvent?.invoke(if (call) PlayerEvent.InterruptionBegan else PlayerEvent.InterruptionEnded)
    }

    // Loading

    /** Duration and title as the library has them, for the book loaded next (shown until the file is ready). */
    fun expect(duration: Double, title: String) {
        expectedDuration = duration
        expectedTitle = title
    }

    override fun load(file: File, position: Double, rate: Double) {
        if (!file.exists()) throw FileNotFoundException("${file.name} is missing.")
        bookFile = file
        val title = expectedTitle
        val item = MediaItem.Builder().setUri(Uri.fromFile(file)).setMediaId(file.path)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setAlbumTitle(title).build()).build()
        this.item = item
        // a book is loaded paused, whatever the last one was doing (an error leaves the player wanting to play)
        player.playWhenReady = false
        updatePlaying()
        duration = expectedDuration
        val start = position.coerceIn(0.0, maxOf(0.0, duration))
        player.setMediaItem(item, (start * 1000).toLong())
        player.prepare()
        currentTime = start
        setRate(rate)
        lastError = null
    }

    override fun unload() {
        player.playWhenReady = false
        item = null
        bookFile = null
        player.clearMediaItems()
        item = null
        duration = 0.0
        currentTime = 0.0
        updatePlaying()
    }

    val isLoaded: Boolean get() = item != null

    /** Title (the chapter) and artist (the book) on the lock screen and notification. */
    fun setNowPlaying(title: String, book: String) {
        val current = item ?: return
        val metadata = current.mediaMetadata
        if (metadata.title == title && metadata.artist == book) return
        val updated = current.buildUpon()
            .setMediaMetadata(metadata.buildUpon().setTitle(title).setArtist(book).setAlbumTitle(book).build()).build()
        item = updated
        if (player.mediaItemCount > 0) player.replaceMediaItem(0, updated)
    }

    // Transport

    /** Media time right now. */
    override val liveTime: Double get() = if (item == null) currentTime else minOf(duration, player.currentPosition / 1000.0)

    override fun play() {
        if (item == null || isPlaying) return
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (currentTime >= duration - 0.25 || player.playbackState == Player.STATE_ENDED) seek(0.0)
        player.play()
        updatePlaying()
    }

    override fun pause() {
        if (!isPlaying) return
        player.pause()
        currentTime = liveTime
        updatePlaying()
        readLoudness(currentTime)
    }

    override fun seek(to: Double) {
        currentTime = to.coerceIn(0.0, maxOf(0.0, duration))
        player.seekTo((currentTime * 1000).toLong())
    }

    override fun setRate(rate: Double) {
        rateState.doubleValue = rate
        player.playbackParameters = PlaybackParameters(rate.toFloat())
    }

    // Feedback

    /** The tone on the media stream, and the same thing for a hand on the phone. */
    override fun playEarcon(earcon: ReviewMachine.Earcon) {
        earcons.play(earcon)
        val effect = when (earcon) {
            Earcon.RECORD_START -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            Earcon.RECORD_SAVED -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
            Earcon.RECORD_DISCARDED -> VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), -1)
            Earcon.BOOKMARK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            Earcon.BOOKMARK_END -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        }
        runCatching { vibrator?.vibrate(effect) }
    }

    /** Loudness of the audio around the last pause, read in the background: `quietPoint` must not block. */
    private class Loudness(val file: File, val start: Double, val levels: DoubleArray) {
        val end: Double get() = start + levels.size * QuietPoint.FRAME
    }

    @Volatile private var loudness: Loudness? = null
    private val loudnessReader = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "book-loudness").apply { isDaemon = true } }

    /**
     * Called when the book pauses: a resume lands a few seconds before this point (the rewind after a
     * comment, a sentence start before that, and the search window), so that stretch is decoded now.
     */
    private fun readLoudness(around: Double) {
        val file = bookFile ?: return
        val from = maxOf(0.0, around - 12.0)
        loudnessReader.execute {
            val began = System.nanoTime()
            runCatching {
                val samples = PcmDecoder.decode(file, from, around + 2.0)
                loudness = Loudness(file, from, QuietPoint.levels(samples, PcmDecoder.RATE))
                Log.i("audio", "loudness %.1f–%.1f read in %d ms".format(from, around + 2.0, (System.nanoTime() - began) / 1_000_000))
            }.onFailure { Log.w("audio", "no loudness near $around: ${it.message}") }
        }
    }

    /** The pause to resume after, from what was read at the last pause; null when that does not cover `near`. */
    override fun quietPoint(near: Double): Double? {
        val known = loudness?.takeIf { it.file == bookFile } ?: return null
        val from = maxOf(known.start, near - QuietPoint.BEFORE - 0.2)
        val to = near + QuietPoint.AFTER + 0.2
        if (near < known.start || to > known.end + 0.5) return null
        val first = ((from - known.start) / QuietPoint.FRAME).toInt()
        val last = minOf(known.levels.size, ((to - known.start) / QuietPoint.FRAME).toInt())
        if (last <= first) return null
        val found = QuietPoint.find(known.levels.copyOfRange(first, last), known.start + first * QuietPoint.FRAME, near)
        Log.i("audio", "resume near %.2f: pause at %s".format(near, found?.let { "%.2f".format(it) } ?: "none"))
        return found
    }

    override fun announce(text: String, language: String) {
        val locale = Locale.forLanguageTag(language)
        // "Tempo 1.4": a German voice reads the point as the end of a sentence
        speak(if (locale.language == "de") text.replace(decimalPoint, "$1,$2") else text, locale)
    }

    private fun speak(text: String, locale: Locale) {
        val engine = tts
        if (engine == null || !ttsReady) {
            // the engine is still starting: said as soon as it is ready (the newest wins)
            waitingAnnouncement = text to locale
            if (engine == null) {
                tts = TextToSpeech(context) { status -> handler.post { speechEngineStarted(status == TextToSpeech.SUCCESS) } }
            }
            return
        }
        val id = "announce-${++utterances}"
        speaking = id
        engine.language = locale
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) speaking = null
    }

    private fun speechEngineStarted(ok: Boolean) {
        val waiting = waitingAnnouncement
        waitingAnnouncement = null
        if (!ok) {
            // no voice on this device: nothing is said, and nobody waits for it
            tts?.shutdown()
            tts = null
            return
        }
        ttsReady = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished(utteranceId)
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) = finished(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
            private fun finished(id: String?) {
                handler.post { if (speaking == id) speaking = null }
            }
        })
        waiting?.let { speak(it.first, it.second) }
    }

    fun release() {
        handler.removeCallbacks(ticker)
        earcons.release()
        tts?.shutdown()
        player.release()
    }

    private companion object {
        const val TAG = "audio"
        val decimalPoint = Regex("(\\d)\\.(\\d)")
    }
}
