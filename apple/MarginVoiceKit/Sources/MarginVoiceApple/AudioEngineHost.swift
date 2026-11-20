import AVFoundation
import os

/// What the engine host needs from the book player when the engine has to stop,
/// be replaced or reconnect.
@MainActor
protocol EnginePlayback: AnyObject {
  var isPlaying: Bool { get }
  /// Pauses the book if it plays (reported as `.playingChanged`); returns whether it did.
  func pauseForEngineChange() -> Bool
  func play()
  /// Attaches the player's nodes to `engine` (first use, or a fresh engine).
  func attachNodes(to engine: AVAudioEngine)
  /// The engine was replaced: make new nodes and connect the book again.
  func engineWasReplaced()
  /// The I/O unit was replaced (voice processing): connect the book again with its format.
  func reconnectBook()
  /// The engine reconfigured itself (route or format change). `reinstall` lets the
  /// microphone side put its tap back between stopping and restarting the book.
  func engineConfigurationChanged(reinstall: () -> Void)
}

/// The one `AVAudioEngine` the book player and the microphone share: echo
/// cancellation (voice processing on the input node) can only take the book out
/// of the microphone signal when both run on the same engine.
///
/// Owned by `EnginePlayer` and `AppleAudioCapture`. It carries out engine
/// operations; when they happen is the session's business.
@MainActor
public final class AudioEngineHost {
  private static let log = Logger.marginVoice("engine")

  /// Replaced by `rebuild()`: an engine whose input node was touched records whenever it runs.
  public private(set) var engine = AVAudioEngine()
  public private(set) var voiceProcessingEnabled = false
  /// The microphone has been part of the engine since it was first needed; an
  /// engine with an input node records whenever it runs.
  public private(set) var hasInput = false

  weak var playback: (any EnginePlayback)?
  /// The engine reconfigured itself: input taps must be reinstalled.
  var onConfigurationChange: (() -> Void)?
  let otherAudio = OtherAudioMonitor()

  /// The graph is built on first use, not at launch: creating the output unit
  /// activates the audio session implicitly, and in iOS's default category that
  /// stops whatever else is playing. The session gets a mixing category first.
  private var graphReady = false
  private var configurationObserver: NSObjectProtocol?

  public init() {}

  func prepareGraph() {
    guard !graphReady else { return }
    SystemAudioSession.prepareForEngine()
    playback?.attachNodes(to: engine)
    if let configurationObserver { NotificationCenter.default.removeObserver(configurationObserver) }
    configurationObserver = NotificationCenter.default.addObserver(
      forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main
    ) { [weak self] _ in
      MainActor.assumeIsolated { self?.handleConfigurationChange() }
    }
    graphReady = true
  }

  var isGraphReady: Bool { graphReady }

  /// A fresh engine and nodes, the book reconnected where it was. Needed after a
  /// media services reset (the old objects are dead) and to let go of the
  /// microphone for good (an engine keeps its input node once it was touched).
  func rebuild() {
    let resume = playback?.pauseForEngineChange() ?? false
    engine.stop()
    engine = AVAudioEngine()
    hasInput = false
    voiceProcessingEnabled = false
    graphReady = false
    playback?.engineWasReplaced()
    if resume { playback?.play() }
  }

  /// The microphone is no longer needed: drop it from the engine, so playing no
  /// longer records (orange indicator, record session) and the book plays like
  /// any other audiobook. The caller has set the playback category first.
  func releaseInput() {
    guard hasInput else { return }
    Self.log.info("releasing the microphone")
    rebuild()
  }

  /// Enables or disables voice processing (echo cancellation). Only possible while stopped.
  func setVoiceProcessing(_ enabled: Bool) {
    guard enabled != voiceProcessingEnabled else { return }
    let wasRunning = engine.isRunning
    let resume = playback?.pauseForEngineChange() ?? false
    if wasRunning { engine.stop() }
    prepareGraph()
    // AVFAudio raises an Objective-C exception (uncatchable from Swift) if the
    // input has no valid format, e.g. before the record session is active.
    try? SystemAudioSession.activate()
    hasInput = true
    let input = engine.inputNode.inputFormat(forBus: 0)
    guard input.sampleRate > 0, input.channelCount > 0 else {
      Self.log.error("voice processing skipped: no valid input format")
      if wasRunning { try? startEngine() }
      if resume { playback?.play() }
      return
    }
    do {
      try engine.inputNode.setVoiceProcessingEnabled(enabled)
      if enabled {
        engine.inputNode.voiceProcessingOtherAudioDuckingConfiguration = .init(enableAdvancedDucking: false, duckingLevel: .min)
      }
      voiceProcessingEnabled = enabled
    } catch {
      Self.log.error("voice processing: \(error.localizedDescription)")
    }
    // voice processing replaces the I/O unit; reconnect the book with its format
    playback?.reconnectBook()
    if wasRunning { try? startEngine() }
    if resume { playback?.play() }
  }

  /// The engine only records if its input node exists before it starts; an engine
  /// started for playback alone delivers no microphone audio (0 Hz input format).
  func startEngineWithInput() throws {
    prepareGraph()
    if !hasInput {
      if engine.isRunning {
        let resume = playback?.pauseForEngineChange() ?? false
        engine.stop()
        _ = engine.inputNode
        hasInput = true
        try startEngine()
        if resume { playback?.play() }
        return
      }
      _ = engine.inputNode
      hasInput = true
    }
    try startEngine()
  }

  func startEngine() throws {
    guard !engine.isRunning else { return }
    prepareGraph()
    try SystemAudioSession.activate()
    engine.prepare()
    try engine.start()
  }

  /// Stops the engine when nothing plays, so the microphone is released (and the
  /// orange indicator goes away) while the book is paused or closed.
  func stopIfIdle() {
    guard playback?.isPlaying != true, engine.isRunning else { return }
    engine.stop()
  }

  private func handleConfigurationChange() {
    if let playback {
      playback.engineConfigurationChanged { onConfigurationChange?() }
    } else {
      onConfigurationChange?()
    }
  }
}
