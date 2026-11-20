import Foundation
import MarginVoiceCore

/// The audio route as the platform describes it, without platform types, so the
/// translation to `AudioRoute` is a pure function (and tested without hardware).
public struct RouteDescription: Sendable, Equatable {
  /// The kinds of port iOS knows (`AVAudioSession.Port`).
  public enum PortKind: String, Sendable, CaseIterable {
    case builtInSpeaker, builtInReceiver, builtInMic
    case headphones, headsetMic
    case bluetoothA2DP, bluetoothHFP, bluetoothLE
    case carAudio, airPlay, usbAudio, hdmi
    case lineIn, lineOut, other
  }

  public struct Input: Sendable, Equatable {
    public var kind: PortKind
    /// AirPods recording over Bluetooth without dropping to call quality (iOS 26).
    public var highQualityRecording: Bool

    public init(_ kind: PortKind, highQualityRecording: Bool = false) {
      self.kind = kind
      self.highQualityRecording = highQualityRecording
    }
  }

  public var outputs: [PortKind]
  public var input: Input?
  /// The selectable inputs (not only the one in use).
  public var availableInputs: [PortKind]

  public init(outputs: [PortKind], input: Input?, availableInputs: [PortKind] = []) {
    self.outputs = outputs
    self.input = input
    self.availableInputs = availableInputs
  }
}

public enum AudioRouteMapping {
  public static func route(_ d: RouteDescription) -> AudioRoute {
    AudioRoute(
      outputs: d.outputs.map(port),
      input: d.input.map(inputPort),
      // AirPods, a headset or a car microphone is connected and selectable
      headsetMicrophoneAvailable: d.availableInputs.contains { $0 != .builtInMic })
  }

  static func port(_ kind: RouteDescription.PortKind) -> AudioRoute.Port {
    switch kind {
    case .builtInSpeaker: .builtInSpeaker
    case .builtInReceiver: .builtInReceiver
    case .builtInMic: .builtInMic
    case .headphones: .wiredHeadphones
    case .headsetMic: .wiredHeadset
    case .bluetoothA2DP: .bluetoothA2DP
    case .bluetoothHFP: .bluetoothHFP
    case .bluetoothLE: .bluetoothLE
    case .carAudio: .carAudio
    case .airPlay: .airPlay
    case .usbAudio: .usb
    case .hdmi: .hdmi
    case .lineIn, .lineOut, .other: .other
    }
  }

  static func inputPort(_ input: RouteDescription.Input) -> AudioRoute.Port {
    // AirPods in high-quality recording keep the book on A2DP: not a call-quality (HFP) microphone,
    // whatever port type the session reports for them
    if input.highQualityRecording { return .bluetoothLE }
    return port(input.kind)
  }

  // MARK: macOS (Core Audio transport types)

  /// Four-character codes of `kAudioDeviceTransportType…`, spelled out so this file needs no CoreAudio.
  public enum Transport {
    public static let builtIn = fourCC("bltn")
    public static let bluetooth = fourCC("blue")
    public static let bluetoothLE = fourCC("blea")
    public static let usb = fourCC("usb ")
    public static let hdmi = fourCC("hdmi")
    public static let displayPort = fourCC("dprt")
    public static let airPlay = fourCC("airp")
    public static let thunderbolt = fourCC("thun")
    public static let virtual = fourCC("virt")
    public static let aggregate = fourCC("grup")

    static func fourCC(_ s: String) -> UInt32 { s.utf8.reduce(0) { $0 << 8 | UInt32($1) } }
  }

  /// The Mac's default devices. macOS has no audio session: the engine takes the
  /// system's default devices. A missing device counts as built in (as the
  /// archived app did): the built-in microphone hears the built-in speakers.
  public static func macRoute(outputTransport: UInt32?, inputTransport: UInt32?) -> AudioRoute {
    AudioRoute(outputs: [macOutput(outputTransport)], input: macInput(inputTransport), headsetMicrophoneAvailable: false)
  }

  static func macOutput(_ transport: UInt32?) -> AudioRoute.Port {
    switch transport {
    case nil, Transport.builtIn: .builtInSpeaker
    case Transport.bluetooth: .bluetoothA2DP
    case Transport.bluetoothLE: .bluetoothLE
    case Transport.usb: .usb
    case Transport.hdmi, Transport.displayPort: .hdmi
    case Transport.airPlay: .airPlay
    default: .other
    }
  }

  static func macInput(_ transport: UInt32?) -> AudioRoute.Port {
    switch transport {
    case nil, Transport.builtIn: .builtInMic
    // on the Mac a Bluetooth microphone means the headset's call mode
    case Transport.bluetooth: .bluetoothHFP
    case Transport.bluetoothLE: .bluetoothLE
    case Transport.usb: .usb
    default: .other
    }
  }
}
