import Foundation

/// Where the book comes out and which microphone listens, as the platform reports it.
public struct AudioRoute: Sendable, Equatable, Codable {
  public enum Port: String, Sendable, Codable, CaseIterable {
    case builtInSpeaker, builtInReceiver, builtInMic
    case wiredHeadphones, wiredHeadset
    case bluetoothA2DP, bluetoothHFP, bluetoothLE
    case carAudio, airPlay, usb, hdmi, other
  }

  public var outputs: [Port]
  public var input: Port?
  /// A microphone other than the built-in one could be chosen (AirPods, a headset, a car).
  public var headsetMicrophoneAvailable: Bool

  public init(outputs: [Port], input: Port?, headsetMicrophoneAvailable: Bool = false) {
    self.outputs = outputs
    self.input = input
    self.headsetMicrophoneAvailable = headsetMicrophoneAvailable
  }

  /// Nothing connected: the device's own speaker and microphone.
  public static let builtIn = AudioRoute(outputs: [.builtInSpeaker], input: .builtInMic)

  private static let loud: Set<Port> = [.builtInSpeaker, .carAudio, .airPlay, .hdmi, .usb, .other]
  private static let device: Set<Port> = [.builtInSpeaker, .builtInReceiver, .carAudio]

  /// The book plays into the room, where a microphone can hear it.
  public var isLoud: Bool { outputs.contains { Self.loud.contains($0) } }

  /// The book comes out of the device itself (or the car it is plugged into) and nothing else.
  public var isDeviceSpeaker: Bool { !outputs.isEmpty && outputs.allSatisfy { Self.device.contains($0) } }

  /// The built-in microphone can hear the book.
  public var microphoneHearsBook: Bool { input == .builtInMic && isLoud }
}

/// What the session wants from the audio system right now.
public struct AudioNeeds: Sendable, Equatable, Codable {
  public var bookOpen = false
  /// The open book has played: from then on the session is ours alone (no mixing),
  /// so the lock screen and the headset buttons belong to the book.
  public var hasPlayed = false
  public var voiceCommands = false
  /// Voice commands are on, but the microphone was let go while nobody used the book.
  public var listeningPaused = false
  public var recording = false
  public var microphonePermission = false
  /// The user chose the headset's call-quality microphone (cars, older headsets).
  public var useHeadsetMicrophone = false

  public init() {}
}

/// How the audio system should be set up. The platform adapter applies it; deciding
/// it is pure, so every combination is covered by `spec/vectors/audio-plan.json`.
public struct AudioPlan: Sendable, Equatable, Codable {
  public enum Session: String, Sendable, Codable {
    case inactive, playback, playAndRecord
  }

  public var session: Session
  /// Other apps keep playing (until the book first plays).
  public var mixWithOthers: Bool
  /// The microphone runs.
  public var capture: Bool
  /// The command recognizer runs on it.
  public var recognize: Bool
  /// Take the book out of the microphone signal. Only when the built-in microphone
  /// hears the device's own speaker: it would pull the book off Bluetooth headphones.
  public var echoCancellation: Bool
  public var allowHeadsetCallMicrophone: Bool
  /// The recognizer may hear the book: commands are checked against what it just said.
  public var bookAudible: Bool

  public static let inactive = AudioPlan(
    session: .inactive, mixWithOthers: true, capture: false, recognize: false, echoCancellation: false,
    allowHeadsetCallMicrophone: false, bookAudible: false)

  public init(
    session: Session, mixWithOthers: Bool, capture: Bool, recognize: Bool, echoCancellation: Bool,
    allowHeadsetCallMicrophone: Bool, bookAudible: Bool
  ) {
    self.session = session
    self.mixWithOthers = mixWithOthers
    self.capture = capture
    self.recognize = recognize
    self.echoCancellation = echoCancellation
    self.allowHeadsetCallMicrophone = allowHeadsetCallMicrophone
    self.bookAudible = bookAudible
  }

  /// - Parameter echoCancellationAllowed: false once the route kept flapping (see `AudioCoordinator`).
  public static func make(needs: AudioNeeds, route: AudioRoute, echoCancellationAllowed: Bool = true) -> AudioPlan {
    guard needs.bookOpen else { return .inactive }
    let listen = needs.voiceCommands && !needs.listeningPaused
    let capture = (listen || needs.recording) && needs.microphonePermission
    // Before the book has played there is nothing to cancel, and echo cancellation
    // (voice-chat mode) would stop other apps' audio even in a mixing session.
    let cancel = capture && needs.hasPlayed && echoCancellationAllowed && route.input == .builtInMic && route.isDeviceSpeaker
    return AudioPlan(
      session: capture ? .playAndRecord : .playback,
      mixWithOthers: !needs.hasPlayed,
      capture: capture,
      recognize: capture && listen,
      echoCancellation: cancel,
      allowHeadsetCallMicrophone: capture && needs.useHeadsetMicrophone,
      bookAudible: capture && route.microphoneHearsBook)
  }

  /// How much has to be rebuilt to get from one plan to another.
  public enum Change: Int, Sendable, Comparable, Codable {
    case none
    /// Start or stop the recognizer; the microphone keeps running.
    case recognizer
    /// Session options only.
    case session
    /// The microphone (and with it the engine's input) is rebuilt.
    case capture

    public static func < (a: Change, b: Change) -> Bool { a.rawValue < b.rawValue }
  }

  public func change(to new: AudioPlan) -> Change {
    if capture != new.capture || echoCancellation != new.echoCancellation || session != new.session { return .capture }
    if mixWithOthers != new.mixWithOthers || allowHeadsetCallMicrophone != new.allowHeadsetCallMicrophone { return .session }
    if recognize != new.recognize { return .recognizer }
    return .none
  }
}

/// Decides when the audio setup is rebuilt.
///
/// The microphone and recognizer are expensive to rebuild, and a command said during
/// a rebuild is lost. So: a route that is still moving (AirPods connect in several
/// steps) is waited out and applied once, a comment keeps its microphone until it is
/// saved, and a route that flaps stops toggling echo cancellation.
public struct AudioCoordinator: Sendable {
  public enum RouteReason: String, Sendable, Codable {
    case deviceArrived, deviceLeft, other
  }

  public struct Policy: Sendable, Equatable {
    /// Quiet time after the last route change before acting on it.
    public var settle: Double = 0.6
    /// At most this many times may echo cancellation be turned on within `toggleWindow`.
    public var toggleLimit = 4
    public var toggleWindow: Double = 30
    public init() {}
  }

  public struct Action: Sendable, Equatable {
    public let plan: AudioPlan
    public let change: AudioPlan.Change

    public init(plan: AudioPlan, change: AudioPlan.Change) {
      self.plan = plan
      self.change = change
    }
  }

  public var policy: Policy
  public private(set) var needs = AudioNeeds()
  public private(set) var route = AudioRoute.builtIn
  /// What the adapter last applied.
  public private(set) var applied = AudioPlan.inactive
  public private(set) var echoCancellationAllowed = true

  /// The route changed and has not been acted on yet; due at this time.
  private var routeDue: Double?
  /// Headphones arrived while echo cancellation held the book on the speaker: it is
  /// off until the route has settled, so the headphones can take the book.
  private var uncovering = false
  private var toggles: [Double] = []

  public init(policy: Policy = Policy()) {
    self.policy = policy
  }

  /// The book closed: start over.
  public mutating func reset() {
    self = AudioCoordinator(policy: policy)
  }

  /// The session wants something else (a book opened, play, a comment started…).
  /// Acted on at once: these are things the user just did.
  public mutating func needsChanged(_ new: AudioNeeds, now: Double) -> Action? {
    let wasRecording = needs.recording
    needs = new
    if !new.bookOpen {
      // the next book starts with a clean slate
      echoCancellationAllowed = true
      toggles.removeAll()
    }
    // a comment just ended: what was put off for it is due now
    if wasRecording, !new.recording, routeDue != nil { routeDue = now }
    return decide(now: now)
  }

  /// The platform reported a route change.
  public mutating func routeChanged(_ new: AudioRoute, reason: RouteReason, now: Double) -> Action? {
    route = new
    routeDue = now + policy.settle
    guard reason == .deviceArrived, applied.echoCancellation, !needs.recording else { return nil }
    // Echo cancellation keeps Bluetooth out of the route: turn it off so the headphones can show up.
    uncovering = true
    var plan = applied
    plan.echoCancellation = false
    return Action(plan: plan, change: applied.change(to: plan))
  }

  /// Call a few times a second with the current route.
  public mutating func tick(route current: AudioRoute, now: Double) -> Action? {
    if current != route {
      // the route moved without a notification (AirPods settling late)
      route = current
      routeDue = routeDue ?? now + policy.settle
    }
    guard let due = routeDue, now >= due else { return nil }
    return decide(now: now)
  }

  /// The adapter applied a plan (or what it could of it).
  public mutating func didApply(_ plan: AudioPlan, now: Double) {
    if plan.echoCancellation, !applied.echoCancellation {
      toggles = toggles.filter { now - $0 < policy.toggleWindow }
      toggles.append(now)
    }
    applied = plan
  }

  private mutating func decide(now: Double) -> Action? {
    let settled = routeDue.map { now >= $0 } ?? true
    if settled {
      routeDue = nil
      uncovering = false
    }
    toggles = toggles.filter { now - $0 < policy.toggleWindow }
    if toggles.count >= policy.toggleLimit { echoCancellationAllowed = false }
    var plan = AudioPlan.make(needs: needs, route: route, echoCancellationAllowed: echoCancellationAllowed)
    // A route still moving says nothing reliable about echo: keep what is applied.
    if !settled { plan.echoCancellation = uncovering ? false : applied.echoCancellation && plan.capture }
    var change = applied.change(to: plan)
    // A comment keeps its microphone: rebuilding is put off until it is saved
    // (unless the microphone isn't running at all yet).
    if needs.recording, applied.capture, change == .capture {
      plan.echoCancellation = applied.echoCancellation
      plan.session = applied.session
      change = applied.change(to: plan)
      if routeDue == nil, plan != AudioPlan.make(needs: needs, route: route, echoCancellationAllowed: echoCancellationAllowed) {
        routeDue = .infinity
      }
    }
    return change == .none && plan == applied ? nil : Action(plan: plan, change: change)
  }
}
