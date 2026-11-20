package de.kaikunze.marginvoice.voice

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import de.kaikunze.marginvoice.core.AudioCoordinator
import de.kaikunze.marginvoice.core.AudioNeeds
import de.kaikunze.marginvoice.core.AudioPlan
import de.kaikunze.marginvoice.core.AudioRoute
import de.kaikunze.marginvoice.core.VoiceCommand
import de.kaikunze.marginvoice.session.CaptureEvent
import de.kaikunze.marginvoice.session.MicrophonePermission
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The foreground-service rules of the microphone, now in [AndroidAudioCapture]:
 * the service runs while a plan captures and is never started from the background.
 * (The archived tests covered the hand-over to the next book; the session no longer
 * releases the microphone between books, so the adapter only has to keep the service
 * across consecutive capturing plans.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MicrophoneServiceTests {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var starts = 0
    private var stops = 0
    private var mayStart = true

    private val listening = AudioPlan.make(
        AudioNeeds(bookOpen = true, voiceCommands = true, microphonePermission = true), AudioRoute.builtIn,
    )
    private val afterPlay = AudioPlan.make(
        AudioNeeds(bookOpen = true, hasPlayed = true, voiceCommands = true, microphonePermission = true), AudioRoute.builtIn,
    )
    private val playbackOnly = AudioPlan.make(AudioNeeds(bookOpen = true), AudioRoute.builtIn)

    private fun capture(permission: Boolean = true): AndroidAudioCapture {
        if (permission) shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO) else shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        return AndroidAudioCapture(
            app, VoskModels(app),
            CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob() + CoroutineExceptionHandler { _, _ -> }),
            startService = { if (mayStart) starts++; mayStart },
            stopServiceNow = { stops++ },
        )
    }

    /** Applies a plan; the recognizer's native library and a real microphone aren't there in unit tests, so it may fail after the service. */
    private fun AndroidAudioCapture.applying(plan: AudioPlan, change: AudioPlan.Change = AudioPlan.Change.CAPTURE): Throwable? =
        runCatching { runBlocking { apply(plan, change, VoiceCommand.Language.ENGLISH) } }.exceptionOrNull()

    @Test fun theServiceStartsWithTheMicrophoneAndStaysAcrossPlans() {
        val capture = capture()
        capture.applying(listening)
        // the first Play on the speaker turns echo cancellation on: the microphone is rebuilt, the service is not
        capture.applying(afterPlay)
        capture.applying(afterPlay.copy(mixWithOthers = true), AudioPlan.Change.SESSION)
        assertEquals(1, starts)
        assertEquals(0, stops)
    }

    @Test fun aPlanWithoutCaptureLetsTheServiceGo() {
        val capture = capture()
        capture.applying(listening)
        assertEquals(null, capture.applying(playbackOnly))
        assertEquals(1, stops)
        assertFalse(capture.isInstalled)
        // and again for the inactive plan: nothing left to stop
        assertEquals(null, capture.applying(AudioPlan.inactive))
        assertEquals(1, stops)
        assertFalse(capture.snapshot().isRunning)
    }

    @Test fun theServiceIsNeverStartedFromTheBackground() {
        val capture = capture()
        val events = ArrayList<CaptureEvent>()
        capture.onEvent = { events.add(it) }
        mayStart = false
        val failure = capture.applying(listening)
        assertTrue(failure?.message, failure?.message?.startsWith("In the background") == true)
        assertEquals(0, starts)
        assertFalse("no microphone without its service", capture.isInstalled)

        // back on screen: reported, so that the session asks for its plan again
        mayStart = true
        capture.cameToForeground()
        val event = events.single() as CaptureEvent.RouteChanged
        assertEquals(AudioCoordinator.RouteReason.OTHER, event.reason)
        capture.applying(listening)
        assertEquals(1, starts)
        // nothing was waiting any more
        capture.cameToForeground()
        assertEquals(1, events.size)
    }

    @Test fun withoutPermissionNothingStarts() {
        val capture = capture(permission = false)
        assertEquals(MicrophonePermission.UNDETERMINED, capture.microphonePermission)
        val failure = capture.applying(listening)
        assertTrue(failure?.message, failure?.message?.startsWith("Microphone access is off") == true)
        assertEquals(0, starts)
    }

    @Test fun aRefusedPermissionDialogIsRemembered() {
        val capture = capture(permission = false)
        var asked = 0
        capture.requestPermission = { asked++; false }
        assertFalse(runBlocking { capture.requestMicrophonePermission() })
        assertEquals(MicrophonePermission.DENIED, capture.microphonePermission)
        // granted in the system settings meanwhile
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals(MicrophonePermission.GRANTED, capture.microphonePermission)
        assertTrue(runBlocking { capture.requestMicrophonePermission() })
        assertEquals(1, asked)
    }

    @Test fun theRouteOfAPhoneAloneIsBuiltIn() {
        val route = capture().route
        assertEquals(AudioRoute.Port.BUILT_IN_MIC, route.input)
        assertFalse(route.headsetMicrophoneAvailable)
    }
}
