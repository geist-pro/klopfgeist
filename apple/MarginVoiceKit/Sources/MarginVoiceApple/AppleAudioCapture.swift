import AVFoundation
import MarginVoiceCore
import MarginVoiceSession
import os

/// The audio session, the microphone, the command recognizer and the comment recorder.
///
/// It applies an `AudioPlan` and reports what the platform says. It decides nothing:
/// which microphone, whether echo cancellation is on, when a moving route is acted
/// on and how often it may toggle are `AudioPlan` and `AudioCoordinator` (core).
@MainActor
public final class AppleAudioCapture: AudioCapture {
  private static let log = Logger.marginVoice("capture")

  struct NoInput: LocalizedError {
    var errorDescription: String? { "No microphone input is available." }
  }

  private let host: AudioEngineHost
  private let tap = MicrophoneTap()
  private let listener = CommandListener()
  private let recorder = CommentRecorder()
  /// Bumped with every tap, so events of an earlier tap (another timeline) are dropped.
  private var tapGeneration = 0
  /// `apply` is at work: a configuration change now waits until it is done.
  private var applying = false
  private var reconfiguredWhileApplying = false
  private var recognizerLanguage: VoiceCommand.Language?
  private var recognizerError: String?
  private var observers: [NSObjectProtocol] = []

  public var onEvent: (@MainActor (CaptureEvent) -> Void)?

  public init(host: AudioEngineHost) {
    self.host = host
    host.onConfigurationChange = { [weak self] in self?.engineConfigurationChanged() }
    #if os(iOS)
      observers.append(
        NotificationCenter.default.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
          let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init)
          MainActor.assumeIsolated { self?.routeChanged(reason) }
        })
    #endif
  }

  // MARK: Route and permission

  public var route: AudioRoute {
    #if os(iOS)
      AudioRouteMapping.route(SystemAudioSession.routeDescription)
    #else
      let t = SystemAudioSession.defaultTransports
      return AudioRouteMapping.macRoute(outputTransport: t.output, inputTransport: t.input)
    #endif
  }

  public var microphonePermission: MicrophonePermission { SystemAudioSession.recordPermission }

  public func requestMicrophonePermission() async -> Bool {
    await SystemAudioSession.requestRecordPermission()
  }

  #if os(iOS)
    private func routeChanged(_ reason: AVAudioSession.RouteChangeReason?) {
      let why: AudioCoordinator.RouteReason =
        switch reason {
        case .newDeviceAvailable: .deviceArrived
        case .oldDeviceUnavailable: .deviceLeft
        default: .other
        }
      onEvent?(.routeChanged(route, why))
    }
  #endif

  // MARK: Applying a plan

  public func apply(_ plan: AudioPlan, change: AudioPlan.Change, language: VoiceCommand.Language) async throws -> CaptureStatus {
    applying = true
    reconfiguredWhileApplying = false
    defer {
      applying = false
      // the engine reconfigured itself during the pass (changing echo cancellation
      // does that): the tap this pass installed needs installing again
      if reconfiguredWhileApplying {
        reconfiguredWhileApplying = false
        engineConfigurationChanged()
      }
    }
    switch change {
    case .none:
      break
    case .recognizer:
      // the microphone keeps running: only the recognizer starts or stops
      if plan.recognize { await startRecognizer(language) } else { await stopRecognizer() }
    case .session:
      // category options only (mixing, the headset's call microphone)
      switch plan.session {
      case .playAndRecord:
        try SystemAudioSession.configure(useHeadsetMicrophone: plan.allowHeadsetCallMicrophone, mixWithOthers: plan.mixWithOthers)
      case .playback:
        try SystemAudioSession.configureForPlayback(mixWithOthers: plan.mixWithOthers)
      case .inactive:
        break
      }
      await matchRecognizer(to: plan, language: language)
    case .capture:
      try await rebuild(plan, language: language)
    }
    return status(for: plan)
  }

  /// Session category, the engine's input, voice processing, the tap, then the recognizer.
  private func rebuild(_ plan: AudioPlan, language: VoiceCommand.Language) async throws {
    removeTap()
    await stopRecognizer()
    guard plan.capture else {
      // Nothing needs the microphone. Echo cancellation left on would keep the
      // book on the phone speaker when AirPods connect, so it goes too.
      if host.voiceProcessingEnabled { host.setVoiceProcessing(false) }
      if plan.session == .playback {
        try SystemAudioSession.configureForPlayback(mixWithOthers: plan.mixWithOthers)
        // an engine that once had the microphone would record whenever the book plays
        host.releaseInput()
      }
      // a paused engine lets go of the microphone
      host.stopIfIdle()
      // release the session only after the microphone has stopped: turning echo
      // cancellation off on the way activates it again
      if plan.session == .inactive { SystemAudioSession.deactivate() }
      return
    }
    try SystemAudioSession.configure(useHeadsetMicrophone: plan.allowHeadsetCallMicrophone, mixWithOthers: plan.mixWithOthers)
    try SystemAudioSession.activate()
    if plan.echoCancellation != host.voiceProcessingEnabled {
      host.setVoiceProcessing(plan.echoCancellation)
      // voice processing may have changed the session mode; restore ours so A2DP comes back
      if !plan.echoCancellation {
        try? SystemAudioSession.configure(useHeadsetMicrophone: plan.allowHeadsetCallMicrophone, mixWithOthers: plan.mixWithOthers)
      }
    }
    try host.startEngineWithInput()
    guard installTap() else {
      Self.log.error("no microphone input (input format \(self.host.engine.inputNode.outputFormat(forBus: 0)))")
      throw NoInput()
    }
    if plan.recognize { await startRecognizer(language) }
  }

  private func status(for plan: AudioPlan) -> CaptureStatus {
    CaptureStatus(
      microphone: SystemAudioSession.microphoneDescription, output: SystemAudioSession.outputDescription,
      latency: plan.capture && host.hasInput ? SystemAudioSession.latency(of: host.engine) : 0,
      recognizerError: plan.recognize ? recognizerError : nil)
  }

  // MARK: Tap

  @discardableResult
  private func installTap() -> Bool {
    tapGeneration += 1
    let generation = tapGeneration
    let listener = self.listener
    let recorder = self.recorder
    let format = tap.install(
      on: host.engine.inputNode,
      onEvent: { [weak self] event in Task { @MainActor in self?.detector(event, generation: generation) } },
      sink: { buffer, micTime in
        listener.feed(buffer, micTime: micTime)
        recorder.write(buffer)
      })
    // microphone time starts at zero with every tap
    listener.microphoneTimeRestarted()
    if format != nil { Self.log.notice("tap installed, \(self.tap.channelCount) ch") }
    return format != nil
  }

  private func removeTap() {
    tapGeneration += 1
    tap.remove()
  }

  private func detector(_ event: UtteranceDetector.Event, generation: Int) {
    guard generation == tapGeneration else { return }
    switch event {
    case .started(let time, _): onEvent?(.speech(active: true, micTime: time))
    case .ended(let time, _): onEvent?(.speech(active: false, micTime: time))
    }
  }

  /// The engine reconfigured itself (route or sample-rate change): the tap has to
  /// be installed again with the new format. The recognizer and a running comment
  /// recording carry on; both convert from whatever format arrives.
  private func engineConfigurationChanged() {
    guard !applying else {
      reconfiguredWhileApplying = true
      return
    }
    guard tap.isInstalled else { return }
    removeTap()
    guard installTap() else {
      onEvent?(.failed(NoInput().localizedDescription))
      return
    }
    do {
      try host.startEngineWithInput()
      onEvent?(.restarted)
      #if os(macOS)
        // The Mac has no route notification: a reconfigured engine is how AirPods
        // connecting shows. The coordinator decides again about echo cancellation.
        onEvent?(.routeChanged(route, .other))
      #endif
    } catch {
      removeTap()
      onEvent?(.failed(error.localizedDescription))
    }
  }

  public func snapshot() -> MicrophoneSnapshot {
    let s = tap.snapshot()
    return MicrophoneSnapshot(level: s.level, micTime: s.micTime, silence: s.silence, isRunning: tap.isInstalled)
  }

  // MARK: Recognizer

  private func startRecognizer(_ language: VoiceCommand.Language) async {
    guard let format = tap.format else {
      recognizerError = NoInput().localizedDescription
      return
    }
    do {
      try await listener.start(locale: language.locale, inputFormat: format) { [weak self] hypothesis, epoch in
        Task { @MainActor in
          // words timed before the microphone restarted are on a timeline that no longer exists
          guard let self, epoch == self.listener.epoch else { return }
          self.onEvent?(.hypothesis(hypothesis))
        }
      }
      recognizerLanguage = language
      recognizerError = nil
    } catch {
      recognizerLanguage = nil
      recognizerError = error.localizedDescription
    }
  }

  private func stopRecognizer() async {
    await listener.stop()
    recognizerLanguage = nil
    recognizerError = nil
  }

  /// After a change that left the microphone alone: the recognizer runs when the
  /// plan says so, in the plan's language.
  private func matchRecognizer(to plan: AudioPlan, language: VoiceCommand.Language) async {
    if !plan.recognize {
      if listener.isRunning { await stopRecognizer() }
    } else if !listener.isRunning || recognizerLanguage != language {
      await startRecognizer(language)
    }
  }

  // MARK: Recording

  public func startRecording(to url: URL) throws {
    guard let format = tap.format else { throw NoInput() }
    try recorder.start(url: url, inputFormat: format)
  }

  public func stopRecording() -> (duration: Double, error: String?) {
    let (duration, error) = recorder.stop()
    return (duration, error?.localizedDescription)
  }

  // MARK: Other apps

  public func isOtherAudioPlaying() -> Bool { host.otherAudio.isPlaying() }
}
