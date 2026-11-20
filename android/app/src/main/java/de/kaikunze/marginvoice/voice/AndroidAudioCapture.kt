package de.kaikunze.marginvoice.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import de.kaikunze.marginvoice.core.AudioCoordinator
import de.kaikunze.marginvoice.core.AudioPlan
import de.kaikunze.marginvoice.core.AudioRoute
import de.kaikunze.marginvoice.core.CommandGate
import de.kaikunze.marginvoice.core.CommandGatePolicy
import de.kaikunze.marginvoice.core.RecognizerFrontEnd
import de.kaikunze.marginvoice.core.UtteranceDetector
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.session.AudioCapture
import de.kaikunze.marginvoice.session.CaptureEvent
import de.kaikunze.marginvoice.session.CaptureStatus
import de.kaikunze.marginvoice.session.MicrophonePermission
import de.kaikunze.marginvoice.session.MicrophoneSnapshot
import de.kaikunze.marginvoice.session.RecordingResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.vosk.Model
import java.io.File
import kotlin.math.sqrt

/**
 * The microphone side of the review session on Android: `AudioRecord` on its own
 * thread, the Vosk command recognizer, the comment recorder and the foreground service
 * that keeps the microphone while the screen is off.
 *
 * It applies the [AudioPlan] it is handed and reports what the platform does. Which
 * microphone, when to rebuild, how long a route has to settle and how often echo
 * cancellation may toggle are decided by `AudioCoordinator` in `:core`, not here.
 * Public methods are called on the main thread.
 */
class AndroidAudioCapture(
    private val context: Context,
    private val models: VoskModels,
    private val scope: CoroutineScope,
    /** Settings › Echo Cancellation (Android only): off keeps the plain recognition input whatever the plan says. */
    private val echoCancellationEnabled: () -> Boolean = { true },
    /** Starts the foreground service the microphone needs; false when Android refuses (the app is in the background). */
    private val startService: () -> Boolean = { ListeningService.start(context) },
    private val stopServiceNow: () -> Unit = { ListeningService.stop(context) },
) : AudioCapture {
    override var onEvent: ((CaptureEvent) -> Unit)? = null

    /** Set by the activity: shows the system's microphone permission dialog. */
    var requestPermission: (suspend () -> Boolean)? = null
    /** A line for the command log: the evidence for each command word (level, gain, contrast, gate). */
    var note: (String, Boolean) -> Unit = { _, _ -> }
    /** A sound of the app's own is playing (a comment's recording, an announcement): not "another app". */
    var ownAudioPlaying: () -> Boolean = { false }

    /** The book plays. Told by the host from the player's events; with the plan it says which [CommandGate] applies. */
    var bookPlaying = false
        set(value) {
            field = value
            updateGate()
        }

    /** Where the loudness gate applies is decided in `:core`; this adapter only hands the decision to the recognizer. */
    private val gatePolicy = CommandGatePolicy()

    private fun updateGate() = recognizer.setGate(gatePolicy.gate(applied, bookPlaying, recorder.isRecording))

    val sampleRate = 16_000
    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val recognizer = VoskRecognizer()
    private val recorder = CommentRecorder().apply {
        onFinishFailed = { file, e -> main.post { note("Could not finish the recording ${file.name}: ${e.message}", false) } }
    }
    private val lock = Any()
    private var record: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var reader: Thread? = null
    @Volatile private var reading = false
    private var detector: UtteranceDetector? = null
    private var level = 0f
    private var serviceRunning = false
    /** One rebuild at a time: `apply` and the repair after a dead recorder never interleave. */
    private val rebuilding = Mutex()
    private var applied = AudioPlan.inactive
    private var language = VoiceCommand.Language.ENGLISH
    private var recognizerLanguage: VoiceCommand.Language? = null
    private var permissionRefused = false
    /** Android refused the microphone service because the app is in the background. */
    private var waitingForForeground = false
    /**
     * Counts the microphones (and so the microphone timelines) built so far. What the reader
     * thread of an older one still posts to the main thread is dropped there.
     */
    private var micEpoch = 0
    /** Counts recognizer runs, for the same reason: results of a stopped run are dropped. */
    private var recognizerEpoch = 0
    /**
     * The recognizer's input stage for this microphone. It learns the room and the voice, so it
     * stays across recognizer restarts and is new only with a new microphone.
     */
    private var frontEnd = RecognizerFrontEnd(sampleRate).apply { gating = false }
    private var cachedRoute: AudioRoute? = null
    private var routeReadAt = 0L

    private val routingListener = AudioRouting.OnRoutingChangedListener {
        // the microphone moved to another device without one arriving or leaving
        cachedRoute = null
        onEvent?.invoke(CaptureEvent.RouteChanged(route, AudioCoordinator.RouteReason.OTHER))
    }

    init {
        audio.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = deviceChanged(added, AudioCoordinator.RouteReason.DEVICE_ARRIVED)
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = deviceChanged(removed, AudioCoordinator.RouteReason.DEVICE_LEFT)
        }, main)
    }

    // MARK: Route

    /** Where the book comes out and which microphone listens. Asked ten times a second, so read at most every 250 ms. */
    override val route: AudioRoute
        get() {
            val now = SystemClock.elapsedRealtime()
            cachedRoute?.let { if (now - routeReadAt < 250) return it }
            val input = synchronized(lock) { record }?.routedDevice?.type
                // not running: the headset microphone if one was chosen for calls, else the built-in one
                ?: audio.communicationDevice?.type?.takeIf { AudioRoutes.isHeadsetMicrophone(it) }
            return AudioRoutes.route(
                mediaOutputs = mediaOutputs().map { it.type },
                input = input,
                communicationDevices = audio.availableCommunicationDevices.map { it.type },
            ).also {
                cachedRoute = it
                routeReadAt = now
            }
        }

    /** Where the system sends media right now. */
    private fun mediaOutputs(): List<AudioDeviceInfo> =
        audio.getAudioDevicesForAttributes(
            android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build(),
        )

    /** Headphones or a car arrived or left. What follows from it (wait, rebuild) is the coordinator's business. */
    private fun deviceChanged(devices: Array<out AudioDeviceInfo>, reason: AudioCoordinator.RouteReason) {
        if (devices.none { AudioRoutes.isHeadphones(it.type) }) return
        Log.i(TAG, "device ${if (reason == AudioCoordinator.RouteReason.DEVICE_ARRIVED) "connected" else "disconnected"}: ${devices.joinToString { name(it) }}")
        cachedRoute = null
        onEvent?.invoke(CaptureEvent.RouteChanged(route, reason))
    }

    // MARK: Permission

    override val microphonePermission: MicrophonePermission
        get() = when {
            hasPermission() -> MicrophonePermission.GRANTED
            // Android does not tell "never asked" from "refused": refused is what the dialog answered this run
            permissionRefused -> MicrophonePermission.DENIED
            else -> MicrophonePermission.UNDETERMINED
        }

    override suspend fun requestMicrophonePermission(): Boolean {
        if (hasPermission()) return true
        val granted = requestPermission?.invoke() ?: false
        permissionRefused = !granted
        return granted
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // MARK: Applying a plan

    val isInstalled: Boolean get() = synchronized(lock) { record != null }

    override suspend fun apply(plan: AudioPlan, change: AudioPlan.Change, language: VoiceCommand.Language): CaptureStatus = rebuilding.withLock {
        applied = plan
        this.language = language
        if (!plan.capture) {
            // `INACTIVE` and playback only: everything of the microphone is released
            stopRecognizer()
            removeTap()
            stopService()
            waitingForForeground = false
            return@withLock CaptureStatus(output = outputName())
        }
        // the model first (seconds on first use, off the main thread). One rebuild at a time
        // (the mutex), so the microphone cannot change under the recognizer started on it.
        var problem: String? = null
        val model = if (plan.recognize) {
            try {
                withContext(Dispatchers.IO) { models.model(language) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e.message ?: "Speech recognition failed."
                null
            } catch (e: LinkageError) {
                // the recognizer's native library is not in this build (an ABI it was not packaged for)
                problem = "Speech recognition is not available on this device."
                null
            }
        } else null

        val rebuilt = change >= AudioPlan.Change.CAPTURE || !isInstalled
        if (rebuilt) {
            stopRecognizer()
            removeTap()
            start(plan)
        } else if (change >= AudioPlan.Change.SESSION) {
            chooseCommunicationDevice(plan.allowHeadsetCallMicrophone)
            preferCommunicationDevice(synchronized(lock) { record }, plan.allowHeadsetCallMicrophone)
        }
        updateGate()
        if (model == null) {
            stopRecognizer()
        } else if (rebuilt || change >= AudioPlan.Change.RECOGNIZER || !recognizer.isRunning || recognizerLanguage != language) {
            startRecognizer(model, language)
        }
        status(problem)
    }

    /**
     * Starts a recognizer run on the running microphone. Building the recognizer takes a moment
     * and happens on the recognizer's thread; if it fails, that is reported as `Failed`.
     */
    private fun startRecognizer(model: Model, language: VoiceCommand.Language) {
        val token = ++recognizerEpoch
        recognizer.start(
            model, language, sampleRate, frontEnd,
            onHypothesis = { h -> main.post { if (token == recognizerEpoch) onEvent?.invoke(CaptureEvent.Hypothesis(h)) } },
            onEvidence = { text, accepted -> main.post { if (token == recognizerEpoch) note(text, accepted) } },
            onFailed = { why -> main.post { if (token == recognizerEpoch) onEvent?.invoke(CaptureEvent.Failed(why)) } },
        )
        recognizerLanguage = language
        updateGate()
    }

    private fun stopRecognizer() {
        // results of the run that ends here may still be on their way to the main thread
        recognizerEpoch++
        recognizer.stop()
        recognizerLanguage = null
    }

    /** Service, then microphone. Throws with a sentence for the user when either can't start. */
    private fun start(plan: AudioPlan) {
        if (!hasPermission()) error("Microphone access is off. Allow it in Settings › Apps › Klopfgeist › Permissions.")
        if (!serviceRunning) serviceRunning = startService()
        if (!serviceRunning) {
            // a microphone without the service records silence in the background, and the
            // service can only start while the app is on screen
            waitingForForeground = true
            error("In the background: the microphone starts when Klopfgeist is open again")
        }
        waitingForForeground = false
        install(plan)
    }

    private fun status(problem: String?): CaptureStatus {
        val r = synchronized(lock) { record }
        return CaptureStatus(microphone = r?.let(::describe) ?: "", output = outputName(), latency = outputLatency(), recognizerError = problem)
    }

    /** The app is on screen again: a microphone Android refused in the background can start now. */
    fun cameToForeground() {
        if (!waitingForForeground) return
        waitingForForeground = false
        // reported like any change of the audio environment: the coordinator looks again and asks for its plan
        cachedRoute = null
        onEvent?.invoke(CaptureEvent.RouteChanged(route, AudioCoordinator.RouteReason.OTHER))
    }

    /** Starts the microphone as the plan says. */
    @Suppress("MissingPermission")
    private fun install(plan: AudioPlan) {
        chooseCommunicationDevice(plan.allowHeadsetCallMicrophone)
        val echo = plan.echoCancellation && echoCancellationEnabled()
        if (plan.echoCancellation && !echo) Log.i(TAG, "echo cancellation is off in Settings: recognition input")
        // the voice-call input is the one the echo canceler works on; otherwise the
        // recognition input, without the call path's noise suppression and gain
        val source = if (echo) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val r = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * 4 / 2))
            .build()
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            error("No microphone input is available.")
        }
        preferCommunicationDevice(r, plan.allowHeadsetCallMicrophone)
        echoCanceler = if (echo && AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(r.audioSessionId)?.also { it.enabled = true }
        } else null
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            echoCanceler?.release()
            echoCanceler = null
            r.release()
            error("Another app is using the microphone.")
        }
        synchronized(lock) {
            record = r
            detector = UtteranceDetector(sampleRate.toDouble())
            level = 0f
        }
        // a new microphone: a new timeline, and nothing known yet about its levels
        val epoch = ++micEpoch
        frontEnd = RecognizerFrontEnd(sampleRate).apply { gating = false }
        r.addOnRoutingChangedListener(routingListener, main)
        cachedRoute = null
        reading = true
        reader = Thread({ read(r, epoch) }, "microphone").apply { priority = Thread.MAX_PRIORITY; start() }
        Log.i(TAG, "microphone on: source ${if (echo) "voice communication (echo cancellation)" else "voice recognition"}, output ${outputName()}")
    }

    private fun read(r: AudioRecord, epoch: Int) {
        val buffer = FloatArray(1024)
        /** Samples read so far: the microphone time of the next buffer. */
        var position = 0L
        while (reading) {
            val n = r.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (n < 0) {
                // the audio server restarted (ERROR_DEAD_OBJECT): build the microphone again now
                // rather than leave it to the session's stall check
                if (reading) main.post { recorderDied(r, n) }
                break
            }
            if (n == 0) continue
            var meanSquare = 0f
            for (i in 0 until n) meanSquare += buffer[i] * buffer[i]
            meanSquare /= n
            val events = synchronized(lock) {
                val d = detector ?: return
                val e = d.append(buffer, n)
                // RMS ×10, smoothed, for the level meter
                level = level * 0.6f + minOf(1f, sqrt(meanSquare) * 10) * 0.4f
                e
            }
            recognizer.feed(buffer, n, position.toDouble() / sampleRate)
            position += n
            recorder.write(buffer, n)
            for (e in events) main.post {
                // this microphone was replaced meanwhile: its times mean nothing on the new timeline
                if (epoch != micEpoch) return@post
                onEvent?.invoke(
                    when (e) {
                        is UtteranceDetector.Event.Started -> CaptureEvent.Speech(active = true, micTime = e.time)
                        is UtteranceDetector.Event.Ended -> CaptureEvent.Speech(active = false, micTime = e.time)
                    },
                )
            }
        }
    }

    /** The recorder stopped delivering: the same microphone again, outside `apply`. */
    private fun recorderDied(r: AudioRecord, code: Int) {
        if (synchronized(lock) { record } !== r) return
        Log.i(TAG, "microphone read failed ($code): rebuilding")
        rebuildOutsideApply { synchronized(lock) { record } === r }
    }

    /**
     * Settings › Echo Cancellation was switched: the running microphone changes its input
     * now (the plan stays what it was). Nothing happens when the plan has no echo cancellation.
     */
    fun echoCancellationSettingChanged() {
        if (!applied.echoCancellation) return
        rebuildOutsideApply { isInstalled }
    }

    /** Builds the microphone of the plan applied last once more and reports it as `Restarted`. */
    private fun rebuildOutsideApply(stillNeeded: () -> Boolean) {
        scope.launch {
            rebuilding.withLock {
                if (!stillNeeded() || !applied.capture) return@withLock
                stopRecognizer()
                removeTap()
                try {
                    start(applied)
                    // whatever the old microphone still had on its way is dropped by its epoch
                    onEvent?.invoke(CaptureEvent.Restarted)
                    if (applied.recognize) {
                        val model = withContext(Dispatchers.IO) { models.model(language) }
                        if (isInstalled) startRecognizer(model, language)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onEvent?.invoke(CaptureEvent.Failed(e.message ?: "The microphone could not start."))
                } catch (e: LinkageError) {
                    onEvent?.invoke(CaptureEvent.Failed("Speech recognition is not available on this device."))
                }
            }
        }
    }

    /**
     * Lets the microphone go. Stopping it and waiting for its reader thread (up to half a
     * second) happen off the main thread.
     */
    private suspend fun removeTap() {
        reading = false
        val r = synchronized(lock) {
            val r = record
            record = null
            detector = null
            r
        }
        // from here on nothing its reader posts is delivered
        micEpoch++
        val thread = reader
        reader = null
        val canceler = echoCanceler
        echoCanceler = null
        cachedRoute = null
        if (r == null && thread == null) return
        runCatching { r?.removeOnRoutingChangedListener(routingListener) }
        withContext(Dispatchers.IO) {
            runCatching { r?.stop() }
            thread?.join(500)
            canceler?.release()
            r?.release()
        }
    }

    private fun stopService() {
        if (!serviceRunning) return
        stopServiceNow()
        serviceRunning = false
        audio.clearCommunicationDevice()
    }

    /** The car or headset microphone over Bluetooth only when the plan allows it: it drops the book to call quality. */
    private fun chooseCommunicationDevice(allowed: Boolean) {
        if (!allowed) {
            audio.clearCommunicationDevice()
            return
        }
        val headset = audio.availableCommunicationDevices.firstOrNull { AudioRoutes.isHeadsetMicrophone(it.type) }
        if (headset != null) audio.setCommunicationDevice(headset) else audio.clearCommunicationDevice()
    }

    private fun preferCommunicationDevice(r: AudioRecord?, allowed: Boolean) {
        r ?: return
        r.setPreferredDevice(if (allowed) audio.communicationDevice?.let(::inputFor) else null)
    }

    private fun inputFor(output: AudioDeviceInfo): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == output.type && it.address == output.address }

    // MARK: Snapshot, recording, other audio

    override fun snapshot(): MicrophoneSnapshot = synchronized(lock) {
        val d = detector ?: return MicrophoneSnapshot()
        MicrophoneSnapshot(level, d.time, d.currentSilence, isRunning = record != null)
    }

    override fun startRecording(to: File) {
        recorder.start(to, sampleRate)
        updateGate()
    }

    /**
     * Answers at once with the duration; the file is finished on a thread of its own
     * (encoder and container take up to a few seconds). Readers wait with [awaitRecording].
     */
    override fun stopRecording(): RecordingResult {
        val (duration, failure) = recorder.stop()
        updateGate()
        return RecordingResult(duration, failure?.let { it.message ?: it.toString() })
    }

    /** Returns when the comment recording in `file` is complete on disk. */
    suspend fun awaitRecording(file: File) = recorder.awaitFinished(file)

    /** Is another app playing? Only meaningful while the book itself is not. */
    override fun isOtherAudioPlaying(): Boolean = audio.isMusicActive && !ownAudioPlaying()

    // MARK: Descriptions

    private fun describe(r: AudioRecord): String {
        val input = r.routedDevice
        if (input == null || input.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) return "Built-in microphone"
        return if (input.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) "${input.productName}, call quality" else input.productName.toString()
    }

    private fun outputName(): String = mediaOutputs().joinToString { name(it) }.ifEmpty { "no output" }

    private fun name(d: AudioDeviceInfo) =
        if (d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE) "the speaker" else d.productName.toString()

    /** Bluetooth adds ~0.2 s between rendering and hearing; the speaker next to nothing. */
    private fun outputLatency(): Double {
        val bluetooth = mediaOutputs().any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
        return if (bluetooth) 0.25 else 0.05
    }

    private companion object {
        const val TAG = "audio"
    }
}
