import AVFoundation
import MarginVoiceCore
import MarginVoiceSession
import os

#if os(iOS)

/// One audio session for playback, command listening and comment recording.
///
/// The book plays over Bluetooth A2DP (full quality). Which microphone listens:
/// - AirPods that support high-quality Bluetooth recording (iOS 26, H2 AirPods):
///   their own microphone, without dropping to call quality. Works with the iPad
///   closed or in a bag.
/// - Otherwise the device's built-in microphone, with echo cancellation.
/// - The headset/car microphone over HFP only when the user opts in, because it
///   switches the car to narrow-band "phone call" audio.
///
/// Which of these applies is decided by `AudioPlan` (core); this only carries it out.
@MainActor
enum SystemAudioSession {
  private static let log = Logger.marginVoice("session")

  /// The book plays and the microphone is in use (or about to be). While the book
  /// is paused the session mixes with other apps: listening for "play" must not
  /// stop someone's music the moment a book is opened. `takeOverPlayback()` makes
  /// it the Now Playing app when the book starts.
  static func configure(useHeadsetMicrophone: Bool, mixWithOthers: Bool) throws {
    let session = AVAudioSession.sharedInstance()
    // Keep playing when a closed iPad cover mutes the built-in microphone (instead of being interrupted).
    var options: AVAudioSession.CategoryOptions = [
      .allowBluetoothA2DP, .defaultToSpeaker, .allowAirPlay, .overrideMutedMicrophoneInterruption,
      .bluetoothHighQualityRecording,
    ]
    if useHeadsetMicrophone { options.insert(.allowBluetoothHFP) }
    if mixWithOthers { options.insert(.mixWithOthers) }
    // setting the same category again still reconfigures the route; skip it
    if session.category == .playAndRecord, session.categoryOptions == options,
      session.mode == .default || session.mode == .spokenAudio
    {
      return
    }
    log.notice("configure playAndRecord mix=\(mixWithOthers) headset=\(useHeadsetMicrophone) (was \(session.category.rawValue, privacy: .public), active others=\(session.isOtherAudioPlaying))")
    // high-quality Bluetooth recording only works in the default mode
    do {
      try session.setCategory(.playAndRecord, mode: .default, policy: .default, options: options)
    } catch {
      options.remove(.bluetoothHighQualityRecording)
      try session.setCategory(.playAndRecord, mode: .spokenAudio, policy: .default, options: options)
    }
    try? session.setPreferredIOBufferDuration(0.02)
  }

  /// The book plays without a microphone (voice commands off, or no permission):
  /// a plain playback session, so the book keeps playing in the background and on
  /// the lock screen and goes to AirPods like any other player. Mixing until the
  /// book first plays, like `configure`.
  static func configureForPlayback(mixWithOthers: Bool) throws {
    let session = AVAudioSession.sharedInstance()
    let options: AVAudioSession.CategoryOptions = mixWithOthers ? [.mixWithOthers] : []
    guard session.category != .playback || session.categoryOptions != options else { return }
    log.notice("configure playback mix=\(mixWithOthers) (was \(session.category.rawValue, privacy: .public))")
    try session.setCategory(.playback, mode: .spokenAudio, policy: .default, options: options)
  }

  /// Before the engine first touches the audio hardware (which activates the
  /// session on its own): never in iOS's default, non-mixing category.
  static func prepareForEngine() {
    guard AVAudioSession.sharedInstance().category == .soloAmbient else { return }
    try? configureForPlayback(mixWithOthers: true)
  }

  /// The book starts playing: stop mixing, so other apps pause and the lock
  /// screen and headset buttons belong to the book. Other apps are interrupted
  /// when a non-mixing session activates, so it is activated again.
  static func takeOverPlayback() {
    let session = AVAudioSession.sharedInstance()
    guard session.categoryOptions.contains(.mixWithOthers) else { return }
    var options = session.categoryOptions
    options.remove(.mixWithOthers)
    log.notice("take over playback")
    do {
      try session.setCategory(session.category, mode: session.mode, policy: .default, options: options)
      try session.setActive(true)
    } catch {
      log.error("take over playback: \(error.localizedDescription)")
    }
  }

  static func activate() throws {
    let session = AVAudioSession.sharedInstance()
    log.notice("activate \(session.category.rawValue, privacy: .public) mix=\(session.categoryOptions.contains(.mixWithOthers)) others=\(session.isOtherAudioPlaying)")
    try session.setActive(true)
  }

  static func deactivate() {
    log.notice("deactivate")
    try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
  }

  /// The route as the session reports it right now.
  static var routeDescription: RouteDescription {
    let session = AVAudioSession.sharedInstance()
    let route = session.currentRoute
    return RouteDescription(
      outputs: route.outputs.map { kind($0.portType) },
      input: route.inputs.first.map {
        RouteDescription.Input(kind($0.portType), highQualityRecording: $0.bluetoothMicrophoneExtension?.highQualityRecording.isEnabled == true)
      },
      availableInputs: (session.availableInputs ?? []).map { kind($0.portType) })
  }

  private static func kind(_ port: AVAudioSession.Port) -> RouteDescription.PortKind {
    switch port {
    case .builtInSpeaker: .builtInSpeaker
    case .builtInReceiver: .builtInReceiver
    case .builtInMic: .builtInMic
    case .headphones: .headphones
    case .headsetMic: .headsetMic
    case .bluetoothA2DP: .bluetoothA2DP
    case .bluetoothHFP: .bluetoothHFP
    case .bluetoothLE: .bluetoothLE
    case .carAudio: .carAudio
    case .airPlay: .airPlay
    case .usbAudio: .usbAudio
    case .HDMI: .hdmi
    case .lineIn: .lineIn
    case .lineOut: .lineOut
    default: .other
    }
  }

  /// The microphone in use, for Settings and the command log ("AirPods Pro, high quality").
  static var microphoneDescription: String {
    let route = AVAudioSession.sharedInstance().currentRoute
    guard let input = route.inputs.first else { return "No microphone" }
    if input.portType == .builtInMic {
      let output = route.outputs.first.map { " (book on \($0.portName))" } ?? ""
      return "Built-in microphone\(output)"
    }
    if input.bluetoothMicrophoneExtension?.highQualityRecording.isEnabled == true {
      return "\(input.portName), high quality"
    }
    return input.portType == .bluetoothHFP ? "\(input.portName), call quality" : input.portName
  }

  /// Where the book is heard right now ("AirPods Pro", "Speaker").
  static var outputDescription: String {
    let names = AVAudioSession.sharedInstance().currentRoute.outputs.map(\.portName).joined(separator: " + ")
    return names.isEmpty ? "no output" : names
  }

  /// Output plus input latency of the route: the book is heard this long after it renders,
  /// and the microphone delivers speech this long after it was said (Bluetooth: ~0.2–0.3 s).
  static func latency(of engine: AVAudioEngine) -> Double {
    let audio = AVAudioSession.sharedInstance()
    return max(0, min(1, audio.outputLatency + audio.inputLatency))
  }

  static var isOtherAudioPlaying: Bool { AVAudioSession.sharedInstance().isOtherAudioPlaying }

  static func requestRecordPermission() async -> Bool {
    await AVAudioApplication.requestRecordPermission()
  }

  static var recordPermission: MicrophonePermission {
    switch AVAudioApplication.shared.recordPermission {
    case .granted: .granted
    case .denied: .denied
    default: .undetermined
    }
  }
}
#else
import CoreAudio

/// macOS has no audio session: the engine takes the system's default devices.
/// Echo cancellation (voice processing) is on only when the built-in microphone
/// can hear the book from the built-in speakers. With AirPods or a headset it
/// stays off: on the Mac, voice processing moves Bluetooth headphones into
/// call-quality mode. (`AudioPlan` decides that from the route reported here.)
@MainActor
enum SystemAudioSession {
  static func configure(useHeadsetMicrophone: Bool, mixWithOthers: Bool) throws {}
  static func configureForPlayback(mixWithOthers: Bool) throws {}
  static func prepareForEngine() {}
  static func takeOverPlayback() {}
  static func activate() throws {}
  static func deactivate() {}

  /// Transport types of the default output and input devices.
  static var defaultTransports: (output: UInt32?, input: UInt32?) {
    (defaultDevice(kAudioHardwarePropertyDefaultOutputDevice).map(transport), defaultDevice(kAudioHardwarePropertyDefaultInputDevice).map(transport))
  }

  static var microphoneDescription: String {
    defaultDevice(kAudioHardwarePropertyDefaultInputDevice).flatMap(name) ?? AVCaptureDevice.default(for: .audio)?.localizedName
      ?? "No microphone"
  }

  static var outputDescription: String {
    defaultDevice(kAudioHardwarePropertyDefaultOutputDevice).flatMap(name) ?? "the Mac's output"
  }

  static func latency(of engine: AVAudioEngine) -> Double {
    max(0, min(1, engine.outputNode.presentationLatency + engine.inputNode.presentationLatency))
  }

  static func requestRecordPermission() async -> Bool {
    await AVCaptureDevice.requestAccess(for: .audio)
  }

  static var recordPermission: MicrophonePermission {
    switch AVCaptureDevice.authorizationStatus(for: .audio) {
    case .authorized: .granted
    case .notDetermined: .undetermined
    default: .denied
    }
  }

  // MARK: Core Audio

  private static func defaultDevice(_ selector: AudioObjectPropertySelector) -> AudioObjectID? {
    var address = AudioObjectPropertyAddress(
      mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
    var id = AudioObjectID(kAudioObjectUnknown)
    var size = UInt32(MemoryLayout<AudioObjectID>.size)
    let status = AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &id)
    return status == noErr && id != kAudioObjectUnknown ? id : nil
  }

  private static func transport(_ device: AudioObjectID) -> UInt32 {
    var address = AudioObjectPropertyAddress(
      mSelector: kAudioDevicePropertyTransportType, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
    var value: UInt32 = 0
    var size = UInt32(MemoryLayout<UInt32>.size)
    return AudioObjectGetPropertyData(device, &address, 0, nil, &size, &value) == noErr ? value : 0
  }

  private static func name(_ device: AudioObjectID) -> String? {
    var address = AudioObjectPropertyAddress(
      mSelector: kAudioObjectPropertyName, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
    var value: Unmanaged<CFString>?
    var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
    guard AudioObjectGetPropertyData(device, &address, 0, nil, &size, &value) == noErr, let value else { return nil }
    return value.takeRetainedValue() as String
  }
}
#endif
