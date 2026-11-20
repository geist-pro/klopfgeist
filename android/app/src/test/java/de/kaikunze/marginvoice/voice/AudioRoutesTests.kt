package de.kaikunze.marginvoice.voice

import android.media.AudioDeviceInfo
import de.kaikunze.marginvoice.core.AudioNeeds
import de.kaikunze.marginvoice.core.AudioPlan
import de.kaikunze.marginvoice.core.AudioRoute
import de.kaikunze.marginvoice.core.AudioRoute.Port
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `AudioDeviceInfo` types → the core's [AudioRoute], and what the plan makes of the usual Android routes. */
class AudioRoutesTests {
    private val listening = AudioNeeds(bookOpen = true, hasPlayed = true, voiceCommands = true, microphonePermission = true)

    @Test fun deviceTypesMapToPorts() {
        val expected = mapOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to Port.BUILT_IN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE to Port.BUILT_IN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE to Port.BUILT_IN_RECEIVER,
            AudioDeviceInfo.TYPE_BUILTIN_MIC to Port.BUILT_IN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES to Port.WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET to Port.WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET to Port.WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to Port.BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO to Port.BLUETOOTH_HFP,
            AudioDeviceInfo.TYPE_BLE_HEADSET to Port.BLUETOOTH_LE,
            AudioDeviceInfo.TYPE_BLE_SPEAKER to Port.BLUETOOTH_LE,
            AudioDeviceInfo.TYPE_HEARING_AID to Port.BLUETOOTH_LE,
            AudioDeviceInfo.TYPE_BUS to Port.CAR_AUDIO,
            AudioDeviceInfo.TYPE_USB_DEVICE to Port.USB,
            AudioDeviceInfo.TYPE_DOCK to Port.USB,
            AudioDeviceInfo.TYPE_HDMI to Port.HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC to Port.HDMI,
            AudioDeviceInfo.TYPE_TELEPHONY to Port.OTHER,
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX to Port.OTHER,
            AudioDeviceInfo.TYPE_UNKNOWN to Port.OTHER,
        )
        for ((type, port) in expected) assertEquals("type $type", port, AudioRoutes.port(type))
    }

    @Test fun thePhoneAloneIsTheBuiltInRoute() {
        val route = AudioRoutes.route(
            mediaOutputs = listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER), input = null,
            communicationDevices = listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
        )
        assertEquals(AudioRoute.builtIn, route)
        // the old app's rule: echo cancellation for the speaker heard by the built-in microphone, once the book has played
        val plan = AudioPlan.make(listening, route)
        assertTrue(plan.echoCancellation)
        assertTrue(plan.bookAudible)
        assertFalse(AudioPlan.make(listening.copy(hasPlayed = false), route).echoCancellation)
    }

    @Test fun bluetoothHeadphonesKeepThePhonesMicrophoneWithoutEchoCancellation() {
        val route = AudioRoutes.route(
            mediaOutputs = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), input = AudioDeviceInfo.TYPE_BUILTIN_MIC,
            communicationDevices = listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
        )
        assertEquals(AudioRoute(listOf(Port.BLUETOOTH_A2DP), Port.BUILT_IN_MIC, headsetMicrophoneAvailable = true), route)
        val plan = AudioPlan.make(listening, route)
        assertFalse(plan.echoCancellation)
        // every recognizer result goes to the session: no loudness gate on headphones
        assertFalse(plan.bookAudible)
    }

    @Test fun aWiredOrUsbHeadsetIsNotLoud() {
        for (type in listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)) {
            val route = AudioRoutes.route(listOf(type), AudioDeviceInfo.TYPE_BUILTIN_MIC, emptyList())
            assertFalse("type $type", route.isLoud)
            assertFalse("type $type", AudioPlan.make(listening, route).bookAudible)
        }
    }

    @Test fun aCallQualityHeadsetMicrophoneIsReportedAsTheInput() {
        val route = AudioRoutes.route(
            mediaOutputs = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP), input = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            communicationDevices = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO),
        )
        assertEquals(Port.BLUETOOTH_HFP, route.input)
        assertTrue(route.headsetMicrophoneAvailable)
    }

    @Test fun aSpeakerOverHdmiOrADockIsHeardByTheMicrophoneButNotCancelled() {
        for (type in listOf(AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_DOCK)) {
            val route = AudioRoutes.route(listOf(type), null, emptyList())
            val plan = AudioPlan.make(listening, route)
            assertTrue("type $type", plan.bookAudible)
            assertFalse("type $type", plan.echoCancellation)
        }
    }

    @Test fun mediaOnTwoDevicesOfAKindIsOnePort() {
        val route = AudioRoutes.route(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE), null, emptyList())
        assertEquals(listOf(Port.BUILT_IN_SPEAKER), route.outputs)
    }

    @Test fun whichDevicesCountAsHeadphonesArrivingOrLeaving() {
        assertTrue(AudioRoutes.isHeadphones(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertTrue(AudioRoutes.isHeadphones(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertTrue(AudioRoutes.isHeadphones(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertFalse(AudioRoutes.isHeadphones(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertFalse(AudioRoutes.isHeadphones(AudioDeviceInfo.TYPE_TELEPHONY))
    }
}
