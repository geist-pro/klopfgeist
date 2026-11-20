import AVFoundation
import MarginVoiceCore
import MarginVoiceSession
import os

/// Plays the book through `AVAudioEngine` so voice processing on the input node
/// can cancel the book's own audio from the microphone signal (echo cancellation).
///
/// Graph: book player → time-pitch (speed) → main mixer; earcon player → main mixer.
/// The engine itself belongs to `AudioEngineHost`, shared with the microphone.
@MainActor
public final class EnginePlayer: BookPlayer, EnginePlayback {
  private static let log = Logger.marginVoice("playback")

  private let host: AudioEngineHost
  private var player = AVAudioPlayerNode()
  private var timePitch = AVAudioUnitTimePitch()
  private var earconPlayer = AVAudioPlayerNode()
  private var file: AVAudioFile?
  private var fileURL: URL?
  private var connectedFormat: AVAudioFormat?
  /// Media time where the currently scheduled segment starts.
  private var segmentStart: Double = 0
  /// Bumped on every reschedule so completions of old segments are ignored.
  private var generation = 0
  private var observers: [NSObjectProtocol] = []
  private var resumeAfterInterruption = false
  /// Position while paused (and the last known one while playing).
  private var currentTime: Double = 0
  private let speech = AVSpeechSynthesizer()

  public private(set) var isPlaying = false
  public private(set) var duration: Double = 0
  public private(set) var rate: Double = 1
  public private(set) var lastError: String?

  public var onEvent: (@MainActor (PlayerEvent) -> Void)?
  /// An earcon is about to sound (the app adds a haptic).
  public var onEarcon: (@MainActor (ReviewMachine.Earcon) -> Void)?

  public init(host: AudioEngineHost) {
    self.host = host
    host.playback = self
    observeNotifications()
  }

  public var isLoaded: Bool { file != nil }

  /// "Up next: …" or "Speed 1.4" is still being spoken.
  public var isAnnouncing: Bool { speech.isSpeaking }

  /// The loaded book has played: the audio session stays ours (no mixing
  /// with other apps) until the book is closed, so remote buttons keep reaching it.
  public private(set) var hasPlayed = false

  // MARK: Engine (EnginePlayback)

  func attachNodes(to engine: AVAudioEngine) {
    engine.attach(player)
    engine.attach(timePitch)
    engine.attach(earconPlayer)
    engine.connect(earconPlayer, to: engine.mainMixerNode, format: Earcons.format)
  }

  func pauseForEngineChange() -> Bool {
    let resume = isPlaying
    if resume { pause() }
    return resume
  }

  func engineWasReplaced() {
    player = AVAudioPlayerNode()
    timePitch = AVAudioUnitTimePitch()
    earconPlayer = AVAudioPlayerNode()
    timePitch.rate = Float(rate)
    if let format = connectedFormat { connectBook(format: format, force: true) }
  }

  func reconnectBook() {
    if let format = connectedFormat { connectBook(format: format, force: true) }
  }

  func engineConfigurationChanged(reinstall: () -> Void) {
    let wasPlaying = isPlaying
    if wasPlaying { currentTime = liveTime }
    stopPlayer()
    if let format = connectedFormat { connectBook(format: format, force: true) }
    reinstall()
    guard wasPlaying else { return }
    guard currentTime < duration - 0.05 else { return finish() }
    do {
      try host.startEngine()
      schedule(from: currentTime)
      player.play()
    } catch {
      setPlaying(false)
    }
  }

  private func connectBook(format: AVAudioFormat, force: Bool = false) {
    guard force || connectedFormat != format || !host.isGraphReady else { return }
    host.prepareGraph()
    let engine = host.engine
    engine.disconnectNodeOutput(player)
    engine.disconnectNodeOutput(timePitch)
    engine.connect(player, to: timePitch, format: format)
    engine.connect(timePitch, to: engine.mainMixerNode, format: format)
    connectedFormat = format
  }

  // MARK: Loading

  public func load(_ url: URL, position: Double, rate: Double) throws {
    stopPlayer()
    let f = try Self.openAudioFile(url)
    file = f
    fileURL = url
    duration = Double(f.length) / f.processingFormat.sampleRate
    connectBook(format: f.processingFormat)
    currentTime = min(max(0, position), duration)
    setRate(rate)
    lastError = nil
    // what plays from here on is someone else's doing
    host.otherAudio.markBaseline()
  }

  public func unload() {
    hasPlayed = false
    stopPlayer()
    file = nil
    fileURL = nil
    duration = 0
    currentTime = 0
  }

  /// Reads the few seconds around `time` from a second handle on the file (the player's own keeps
  /// its position) and lets the core find the pause to resume after.
  public func quietPoint(near time: Double) -> Double? {
    guard let fileURL, let reader = try? Self.openAudioFile(fileURL) else { return nil }
    let format = reader.processingFormat
    let rate = format.sampleRate
    let from = max(0, time - QuietPoint.before - 0.2)
    let first = AVAudioFramePosition(from * rate)
    let count = min(AVAudioFrameCount((time + QuietPoint.after + 0.2 - from) * rate), AVAudioFrameCount(max(0, reader.length - first)))
    guard count > 0, let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: count) else { return nil }
    reader.framePosition = first
    guard (try? reader.read(into: buffer, frameCount: count)) != nil, let channels = buffer.floatChannelData else { return nil }
    let frames = Int(buffer.frameLength)
    let channelCount = Int(format.channelCount)
    var mono = [Float](repeating: 0, count: frames)
    for c in 0..<channelCount {
      let data = channels[c]
      for i in 0..<frames { mono[i] += data[i] / Float(channelCount) }
    }
    let found = QuietPoint.find(levels: QuietPoint.levels(samples: mono, sampleRate: rate), start: from, target: time)
    Self.log.info("resume near \(time, format: .fixed(precision: 2)): pause at \(found.map { String(format: "%.2f", $0) } ?? "none", privacy: .public)")
    return found
  }

  /// `.m4b` is MPEG-4 audio; if Core Audio rejects the extension, read it as `.m4a`.
  private static func openAudioFile(_ url: URL) throws -> AVAudioFile {
    do {
      return try AVAudioFile(forReading: url)
    } catch where url.pathExtension.lowercased() == "m4b" {
      let alias = url.deletingPathExtension().appendingPathExtension("m4a")
      if !FileManager.default.fileExists(atPath: alias.path) {
        try FileManager.default.linkItem(at: url, to: alias)
      }
      return try AVAudioFile(forReading: alias)
    }
  }

  // MARK: Transport

  /// Media time right now (interpolated from the render clock while playing).
  public var liveTime: Double {
    guard isPlaying, let nodeTime = player.lastRenderTime, nodeTime.isSampleTimeValid,
      let playerTime = player.playerTime(forNodeTime: nodeTime)
    else { return currentTime }
    let time = min(duration, segmentStart + Double(playerTime.sampleTime) / playerTime.sampleRate)
    // Remember it: once the system stops the engine (a route change, a call) the
    // render clock is gone, and whoever asks then must not get the last seek position.
    currentTime = time
    return time
  }

  public func play() {
    guard file != nil, !isPlaying else { return }
    do {
      try host.startEngine()
    } catch {
      lastError = error.localizedDescription
      Self.log.error("start: \(error.localizedDescription)")
      // whoever asked to play (the review machine) learns it is still paused
      onEvent?(.playingChanged(false))
      return
    }
    SystemAudioSession.takeOverPlayback()
    hasPlayed = true
    if currentTime >= duration - 0.25 { currentTime = 0 }
    schedule(from: currentTime)
    player.play()
    setPlaying(true)
  }

  public func pause() {
    guard isPlaying else { return }
    currentTime = liveTime
    stopPlayer()
    setPlaying(false)
  }

  public func seek(to time: Double) {
    currentTime = min(max(0, time), duration)
    guard isPlaying else { return }
    guard currentTime < duration else { return finish() }
    // the system may have stopped the engine (interruption, route change) before its notification arrived
    do {
      try host.startEngine()
    } catch {
      stopPlayer()
      setPlaying(false)
      return
    }
    schedule(from: currentTime)
    player.play()
  }

  public func setRate(_ newRate: Double) {
    rate = newRate
    timePitch.rate = Float(newRate)
  }

  public func playEarcon(_ earcon: ReviewMachine.Earcon) {
    onEarcon?(earcon)
    do { try host.startEngine() } catch { return }
    earconPlayer.scheduleBuffer(Earcons.buffer(earcon), at: nil, options: [.interrupts])
    earconPlayer.play()
  }

  public func announce(_ text: String, language: String) {
    let utterance = AVSpeechUtterance(string: text)
    utterance.voice = AVSpeechSynthesisVoice(language: language)
    #if os(iOS)
      speech.usesApplicationAudioSession = true
    #endif
    speech.speak(utterance)
  }

  private func setPlaying(_ playing: Bool) {
    guard playing != isPlaying else { return }
    isPlaying = playing
    // the book paused: what plays from here on is someone else's doing
    if !playing { host.otherAudio.markBaseline() }
    onEvent?(.playingChanged(playing))
  }

  private func stopPlayer() {
    generation += 1
    player.stop()
  }

  private func schedule(from time: Double) {
    guard let file else { return }
    stopPlayer()
    let gen = generation
    let sampleRate = file.processingFormat.sampleRate
    let startFrame = AVAudioFramePosition(time * sampleRate)
    let remaining = file.length - startFrame
    segmentStart = Double(startFrame) / sampleRate
    guard remaining > 0 else { return }
    player.scheduleSegment(
      file, startingFrame: startFrame, frameCount: AVAudioFrameCount(remaining), at: nil,
      completionCallbackType: .dataPlayedBack
    ) { @Sendable [weak self] _ in
      Task { @MainActor in self?.segmentFinished(gen) }
    }
  }

  private func segmentFinished(_ gen: Int) {
    guard gen == generation, isPlaying else { return }
    finish()
  }

  private func finish() {
    currentTime = duration
    stopPlayer()
    setPlaying(false)
    onEvent?(.finished)
  }

  // MARK: System events

  private func observeNotifications() {
    #if os(iOS)
      let center = NotificationCenter.default
      observers.append(
        center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
          let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
          let options = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map(AVAudioSession.InterruptionOptions.init)
          let reason = (note.userInfo?[AVAudioSessionInterruptionReasonKey] as? UInt).flatMap(AVAudioSession.InterruptionReason.init)
          // a closed iPad cover mutes the built-in microphone; keep the book playing
          if reason == .builtInMicMuted { return }
          MainActor.assumeIsolated { self?.handleInterruption(type, options: options ?? []) }
        })
      observers.append(
        center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
          let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init)
          MainActor.assumeIsolated {
            // headphones unplugged or the car disconnected: stop rather than blast the speaker
            if reason == .oldDeviceUnavailable { self?.pause() }
          }
        })
      observers.append(
        center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: .main) { [weak self] _ in
          MainActor.assumeIsolated { self?.mediaServicesWereReset() }
        })
    #endif
  }

  #if os(iOS)
    private func handleInterruption(_ type: AVAudioSession.InterruptionType?, options: AVAudioSession.InterruptionOptions) {
      switch type {
      case .began:
        resumeAfterInterruption = isPlaying
        onEvent?(.interruptionBegan)
        pause()
      case .ended:
        if options.contains(.shouldResume), resumeAfterInterruption { play() }
        resumeAfterInterruption = false
        onEvent?(.interruptionEnded)
      default:
        break
      }
    }

    /// Every audio object is dead after a reset, and the session is back at its default category.
    private func mediaServicesWereReset() {
      Self.log.error("media services were reset: rebuilding the engine")
      let resume = isPlaying
      if resume {
        currentTime = liveTime
        stopPlayer()
        setPlaying(false)
      }
      try? SystemAudioSession.configureForPlayback(mixWithOthers: !hasPlayed)
      host.rebuild()
      onEvent?(.mediaServicesReset)
      if resume { play() }
    }
  #endif
}
