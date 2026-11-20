package de.kaikunze.marginvoice.voice

import android.media.AudioDeviceInfo
import de.kaikunze.marginvoice.core.AudioRoute

/** Android's audio device types as the core's [AudioRoute]: a pure mapping, tested on the JVM. */
object AudioRoutes {
    /** The port a device type (`AudioDeviceInfo.TYPE_…`) stands for. */
    fun port(type: Int): AudioRoute.Port = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> AudioRoute.Port.BUILT_IN_SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.Port.BUILT_IN_RECEIVER
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioRoute.Port.BUILT_IN_MIC
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioRoute.Port.WIRED_HEADPHONES
        // a USB headset sits on the head like a wired one: the room does not hear the book
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute.Port.WIRED_HEADSET
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioRoute.Port.BLUETOOTH_A2DP
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioRoute.Port.BLUETOOTH_HFP
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID -> AudioRoute.Port.BLUETOOTH_LE
        // Android Automotive's own speakers
        AudioDeviceInfo.TYPE_BUS -> AudioRoute.Port.CAR_AUDIO
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_DOCK -> AudioRoute.Port.USB
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> AudioRoute.Port.HDMI
        else -> AudioRoute.Port.OTHER
    }

    /** A microphone other than the built-in one. */
    fun isHeadsetMicrophone(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_USB_HEADSET

    /** Headphones, a headset or a car: a device whose coming or going moves the book or the microphone. */
    fun isHeadphones(type: Int): Boolean = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
        type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO

    /**
     * @param mediaOutputs where the system sends media now.
     * @param input the device the microphone records from; null when it is not known (not running yet): the built-in one.
     * @param communicationDevices the devices a call could use.
     */
    fun route(mediaOutputs: List<Int>, input: Int?, communicationDevices: List<Int>): AudioRoute = AudioRoute(
        outputs = mediaOutputs.map(::port).distinct(),
        input = port(input ?: AudioDeviceInfo.TYPE_BUILTIN_MIC),
        headsetMicrophoneAvailable = communicationDevices.any(::isHeadsetMicrophone),
    )
}
