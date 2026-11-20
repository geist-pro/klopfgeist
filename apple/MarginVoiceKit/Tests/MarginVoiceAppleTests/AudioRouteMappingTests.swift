import MarginVoiceCore
import Testing

@testable import MarginVoiceApple

@Suite struct AudioRouteMappingTests {
  @Test func phoneAlone() {
    let route = AudioRouteMapping.route(.init(outputs: [.builtInSpeaker], input: .init(.builtInMic), availableInputs: [.builtInMic]))
    #expect(route == .builtIn)
    #expect(route.microphoneHearsBook)
    #expect(!route.headsetMicrophoneAvailable)
  }

  @Test func airPodsWithHighQualityRecordingAreNotACallMicrophone() {
    // whatever port type the session gives the AirPods' input, high quality is not HFP
    for kind in [RouteDescription.PortKind.bluetoothHFP, .bluetoothLE, .other] {
      let route = AudioRouteMapping.route(
        .init(outputs: [.bluetoothA2DP], input: .init(kind, highQualityRecording: true), availableInputs: [.builtInMic, kind]))
      #expect(route.input == .bluetoothLE)
      #expect(route.outputs == [.bluetoothA2DP])
      #expect(route.headsetMicrophoneAvailable)
      #expect(!route.isLoud)
    }
  }

  @Test func callQualityHeadsetIsHFP() {
    let route = AudioRouteMapping.route(.init(outputs: [.bluetoothHFP], input: .init(.bluetoothHFP), availableInputs: [.builtInMic, .bluetoothHFP]))
    #expect(route.input == .bluetoothHFP)
    #expect(route.outputs == [.bluetoothHFP])
  }

  @Test func airPodsPlayingWhileTheBuiltInMicrophoneListens() {
    let route = AudioRouteMapping.route(.init(outputs: [.bluetoothA2DP], input: .init(.builtInMic), availableInputs: [.builtInMic, .bluetoothHFP]))
    #expect(route.input == .builtInMic)
    #expect(route.headsetMicrophoneAvailable)
    #expect(!route.microphoneHearsBook)
    #expect(!route.isDeviceSpeaker)
  }

  @Test func carAndWiredAndOthers() {
    #expect(AudioRouteMapping.route(.init(outputs: [.carAudio], input: .init(.builtInMic))).isDeviceSpeaker)
    let wired = AudioRouteMapping.route(.init(outputs: [.headphones], input: .init(.headsetMic), availableInputs: [.builtInMic, .headsetMic]))
    #expect(wired.outputs == [.wiredHeadphones])
    #expect(wired.input == .wiredHeadset)
    #expect(AudioRouteMapping.port(.usbAudio) == .usb)
    #expect(AudioRouteMapping.port(.hdmi) == .hdmi)
    #expect(AudioRouteMapping.port(.airPlay) == .airPlay)
    #expect(AudioRouteMapping.port(.lineOut) == .other)
    #expect(AudioRouteMapping.port(.builtInReceiver) == .builtInReceiver)
  }

  @Test func aRouteStillSwitchingIsEmpty() {
    let route = AudioRouteMapping.route(.init(outputs: [], input: nil))
    #expect(route.outputs.isEmpty)
    #expect(route.input == nil)
    // an empty route does not ask for echo cancellation
    #expect(!route.isDeviceSpeaker)
  }

  @Test func everyPortKindMaps() {
    for kind in RouteDescription.PortKind.allCases { _ = AudioRouteMapping.port(kind) }
  }

  @Test func macBuiltIn() {
    let route = AudioRouteMapping.macRoute(outputTransport: AudioRouteMapping.Transport.builtIn, inputTransport: AudioRouteMapping.Transport.builtIn)
    #expect(route == .builtIn)
    // no default device counts as built in
    #expect(AudioRouteMapping.macRoute(outputTransport: nil, inputTransport: nil) == .builtIn)
  }

  @Test func macWithAirPods() {
    let t = AudioRouteMapping.Transport.self
    let listening = AudioRouteMapping.macRoute(outputTransport: t.bluetooth, inputTransport: t.builtIn)
    #expect(listening.outputs == [.bluetoothA2DP])
    #expect(!listening.microphoneHearsBook)
    let call = AudioRouteMapping.macRoute(outputTransport: t.bluetooth, inputTransport: t.bluetooth)
    #expect(call.input == .bluetoothHFP)
  }

  @Test func macOtherDevices() {
    let t = AudioRouteMapping.Transport.self
    #expect(AudioRouteMapping.macOutput(t.usb) == .usb)
    #expect(AudioRouteMapping.macOutput(t.hdmi) == .hdmi)
    #expect(AudioRouteMapping.macOutput(t.displayPort) == .hdmi)
    #expect(AudioRouteMapping.macOutput(t.airPlay) == .airPlay)
    #expect(AudioRouteMapping.macOutput(t.aggregate) == .other)
    #expect(AudioRouteMapping.macInput(t.usb) == .usb)
    #expect(AudioRouteMapping.macInput(0) == .other)
  }

  #if os(macOS)
    @Test func transportCodesMatchCoreAudio() {
      let t = AudioRouteMapping.Transport.self
      #expect(t.builtIn == kAudioDeviceTransportTypeBuiltIn)
      #expect(t.bluetooth == kAudioDeviceTransportTypeBluetooth)
      #expect(t.bluetoothLE == kAudioDeviceTransportTypeBluetoothLE)
      #expect(t.usb == kAudioDeviceTransportTypeUSB)
      #expect(t.hdmi == kAudioDeviceTransportTypeHDMI)
      #expect(t.displayPort == kAudioDeviceTransportTypeDisplayPort)
      #expect(t.airPlay == kAudioDeviceTransportTypeAirPlay)
      #expect(t.aggregate == kAudioDeviceTransportTypeAggregate)
    }
  #endif
}

#if os(macOS)
  import CoreAudio
#endif
