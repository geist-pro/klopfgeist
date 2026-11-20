import AVFoundation
import MarginVoiceCore
import Speech

/// Streams microphone audio into an on-device `SpeechAnalyzer` and reports every
/// (volatile and final) result as words on the microphone timeline.
///
/// `feed` is called on the audio thread by `MicrophoneTap`.
final class CommandListener: @unchecked Sendable {
  private let lock = NSLock()
  private var continuation: AsyncStream<AnalyzerInput>.Continuation?
  private var converter: AVAudioConverter?
  private var analyzerFormat: AVAudioFormat?
  /// Microphone time of the analyzer's time zero.
  private var origin: Double?
  /// Seconds of audio handed to the analyzer so far (its own clock).
  private var fedSeconds = 0.0
  /// Counts restarts of the microphone timeline: a result computed before one is not
  /// on the timeline that is current when it is delivered.
  private var timelineEpoch = 0

  var epoch: Int { lock.withLock { timelineEpoch } }
  private var analyzer: SpeechAnalyzer?
  private var resultsTask: Task<Void, Never>?

  var isRunning: Bool { lock.withLock { continuation != nil } }

  /// Returns the name of the recognizer in use.
  @discardableResult
  func start(locale: Locale, inputFormat: AVAudioFormat, onResult: @escaping @Sendable (Hypothesis, Int) -> Void) async throws -> String {
    await stop()
    var lastError: any Error = SpeechSetupError.unsupportedLocale(locale.identifier)
    for recognizer in await SpeechAssets.candidates(for: locale, live: true) {
      do {
        try await start(recognizer, inputFormat: inputFormat, onResult: onResult)
        return recognizer.name
      } catch {
        lastError = error
        await stop()
      }
    }
    throw lastError
  }

  private func start(_ recognizer: Recognizer, inputFormat: AVAudioFormat, onResult: @escaping @Sendable (Hypothesis, Int) -> Void) async throws {
    let module = recognizer.module
    try await SpeechAssets.installAssets(for: module)
    guard let format = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [module], considering: inputFormat) else {
      throw SpeechSetupError.noAudioFormat
    }
    let analyzer = SpeechAnalyzer(modules: [module], options: .init(priority: .userInitiated, modelRetention: .whileInUse))
    try await analyzer.prepareToAnalyze(in: format)
    // the words it is most likely to hear: "stop" is short, and easily heard as something else
    let context = AnalysisContext()
    context.contextualStrings[.general] = VoiceCommand.Language.allCases.flatMap { VoiceCommand.phrases($0).values.flatMap { $0 } }
    try? await analyzer.setContext(context)
    let (stream, continuation) = AsyncStream.makeStream(of: AnalyzerInput.self, bufferingPolicy: .bufferingNewest(256))

    let results = recognizer.results
    let task = Task { [weak self] in
      do {
        for try await r in results where !r.text.isEmpty {
          guard let (origin, epoch) = self?.currentOrigin() else { continue }
          // one word per run of the result, each with its own time: the pause before
          // "stop" or "continue" is read from the gap between words
          onResult(RecognizerWords.hypothesis(runs: r.runs, text: r.text, start: r.start, end: r.end, origin: origin, isFinal: r.isFinal), epoch)
        }
      } catch {}
    }
    lock.withLock {
      self.converter = AVAudioConverter(from: inputFormat, to: format)
      self.analyzerFormat = format
      self.continuation = continuation
      self.origin = nil
      self.timelineEpoch += 1
      self.fedSeconds = 0
      self.analyzer = analyzer
      self.resultsTask = task
    }
    try await analyzer.start(inputSequence: stream)
  }

  func stop() async {
    let (analyzer, task) = lock.withLock { () -> (SpeechAnalyzer?, Task<Void, Never>?) in
      continuation?.finish()
      continuation = nil
      converter = nil
      let a = self.analyzer
      let t = resultsTask
      self.analyzer = nil
      resultsTask = nil
      return (a, t)
    }
    await analyzer?.cancelAndFinishNow()
    task?.cancel()
  }

  /// The tap was reinstalled: microphone time starts at zero again, while the
  /// analyzer's own clock runs on. Its time zero is placed anew with the next buffer.
  func microphoneTimeRestarted() {
    lock.withLock {
      origin = nil
      timelineEpoch += 1
    }
  }

  private func currentOrigin() -> (Double, Int)? {
    lock.withLock { origin.map { ($0, timelineEpoch) } }
  }

  func feed(_ buffer: AVAudioPCMBuffer, micTime: Double) {
    lock.withLock {
      guard let continuation, let format = analyzerFormat else { return }
      // a microphone that changed its format (AirPods connecting) without the recognizer restarting
      if converter?.inputFormat != buffer.format { converter = AVAudioConverter(from: buffer.format, to: format) }
      guard let converter else { return }
      if origin == nil { origin = micTime - fedSeconds }
      let ratio = format.sampleRate / buffer.format.sampleRate
      let capacity = AVAudioFrameCount((Double(buffer.frameLength) * ratio).rounded(.up)) + 32
      guard let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return }
      let feeder = BufferFeeder(buffer)
      var error: NSError?
      let status = converter.convert(to: out, error: &error) { _, outStatus in feeder.next(outStatus) }
      guard status != .error, out.frameLength > 0 else { return }
      fedSeconds += Double(out.frameLength) / format.sampleRate
      continuation.yield(AnalyzerInput(buffer: out))
    }
  }
}

/// Hands one input buffer to `AVAudioConverter` exactly once per callback.
final class BufferFeeder: @unchecked Sendable {
  private var buffer: AVAudioPCMBuffer?
  init(_ buffer: AVAudioPCMBuffer) { self.buffer = buffer }

  func next(_ status: UnsafeMutablePointer<AVAudioConverterInputStatus>) -> AVAudioBuffer? {
    guard let b = buffer else {
      status.pointee = .noDataNow
      return nil
    }
    buffer = nil
    status.pointee = .haveData
    return b
  }
}
