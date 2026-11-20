#if canImport(AVFAudio)
  import AVFAudio
  import Foundation

  /// Renders a narration plan to an AAC `.m4a`, one sentence at a time, recording
  /// exact timing for every sentence. The voice is either one of the system's
  /// (`AVSpeechSynthesizer`) or a neural engine that turns a sentence into samples
  /// (Kokoro, Qwen3-TTS). Either way the narration text, and with it every
  /// comment's anchor, stays the same.
  public final class SpeechRenderer: @unchecked Sendable {
    /// A neural voice: one sentence in, mono samples out at `sampleRate`.
    public struct SampleVoice: Sendable {
      public let name: String
      public let sampleRate: Double
      public let synthesize: @Sendable (String) async throws -> [Float]

      public init(name: String, sampleRate: Double, synthesize: @escaping @Sendable (String) async throws -> [Float]) {
        self.name = name
        self.sampleRate = sampleRate
        self.synthesize = synthesize
      }
    }

    public struct Output: Sendable {
      public let audioURL: URL
      /// Sentence timing with code point offsets (same format doc2audio writes).
      public let timing: TimingSidecar
      public let chapters: [ChapterMark]
      public let duration: Double
    }

    public enum RenderError: LocalizedError {
      case noAudio
      public var errorDescription: String? { "The voice produced no audio." }
    }

    private let synthesizer = AVSpeechSynthesizer()
    private let voice: AVSpeechSynthesisVoice?
    private let sampleVoice: SampleVoice?
    private let rate: Float
    private let lock = NSLock()
    private var file: AVAudioFile?
    private var format: AVAudioFormat?
    private var frames: AVAudioFramePosition = 0
    /// The utterance being written; buffers of earlier ones are dropped.
    private var generation = 0

    /// - Parameter rate: `AVSpeechUtterance` rate; the default is the natural speaking rate.
    public init(voice: AVSpeechSynthesisVoice?, rate: Float = AVSpeechUtteranceDefaultSpeechRate) {
      self.voice = voice
      self.sampleVoice = nil
      self.rate = rate
    }

    public init(sampleVoice: SampleVoice) {
      self.voice = nil
      self.sampleVoice = sampleVoice
      self.rate = AVSpeechUtteranceDefaultSpeechRate
    }

    /// The voice's display name ("Ava (Premium)", "Heart").
    public var voiceName: String? { sampleVoice?.name ?? voice?.name }

    /// Best installed voice for a language: premium > enhanced > default, no novelty voices.
    public static func bestVoice(language: String) -> AVSpeechSynthesisVoice? {
      let preferredRegion = VoiceCommand.Language(code: language).regionIdentifier
      let candidates = AVSpeechSynthesisVoice.speechVoices().filter {
        $0.language.hasPrefix(language) && !$0.voiceTraits.contains(.isNoveltyVoice) && !$0.voiceTraits.contains(.isPersonalVoice)
      }
      return candidates.max { a, b in
        (a.quality.rawValue, a.language == preferredRegion ? 1 : 0) < (b.quality.rawValue, b.language == preferredRegion ? 1 : 0)
      }
    }

    public static func voices(language: String) -> [AVSpeechSynthesisVoice] {
      AVSpeechSynthesisVoice.speechVoices()
        .filter { $0.language.hasPrefix(language) && !$0.voiceTraits.contains(.isNoveltyVoice) }
        .sorted { ($0.quality.rawValue, $0.name) > ($1.quality.rawValue, $1.name) }
    }

    public func render(
      _ plan: Narrator.Plan, to url: URL, progress: @escaping @Sendable (Double) -> Void = { _ in }
    ) async throws -> Output {
      try? FileManager.default.removeItem(at: url)
      lock.withLock {
        file = nil
        format = nil
        frames = 0
      }
      // Character → code point offsets for the sidecar
      var scalarOffset: [Int] = [0]
      scalarOffset.reserveCapacity(plan.text.count + 1)
      for ch in plan.text { scalarOffset.append(scalarOffset.last! + ch.unicodeScalars.count) }

      var chunks: [TimingSidecar.Chunk] = []
      var chapterStarts: [(String, Double)] = []
      for (i, utterance) in plan.utterances.enumerated() {
        try Task.checkCancellation()
        let start = seconds()
        try await speak(utterance.text, url: url)
        let end = seconds()
        if end > start {
          chunks.append(
            .init(start: start, end: end, charStart: scalarOffset[utterance.range.lowerBound], charEnd: scalarOffset[utterance.range.upperBound]))
        }
        if let chapter = utterance.chapter { chapterStarts.append((chapter, start)) }
        try writeSilence(utterance.pauseAfter)
        progress(Double(i + 1) / Double(plan.utterances.count))
      }
      let duration = seconds()
      lock.withLock { file = nil }  // closes and finalizes the AAC file
      guard duration > 0 else { throw RenderError.noAudio }
      let chapters = chapterStarts.enumerated().map { i, c in
        ChapterMark(title: c.0, start: c.1, end: i + 1 < chapterStarts.count ? chapterStarts[i + 1].1 : duration)
      }
      return Output(audioURL: url, timing: TimingSidecar(chunks: chunks), chapters: chapters, duration: duration)
    }

    public func cancel() {
      synthesizer.stopSpeaking(at: .immediate)
    }

    private func seconds() -> Double {
      lock.withLock { format.map { Double(frames) / $0.sampleRate } ?? 0 }
    }

    private func speak(_ text: String, url: URL) async throws {
      if let sampleVoice { return try await speak(text, with: sampleVoice, url: url) }
      let u = AVSpeechUtterance(string: text)
      u.voice = voice
      u.rate = rate
      u.preUtteranceDelay = 0
      u.postUtteranceDelay = 0
      let state = ResumeOnce()
      // buffers of an utterance given up on (timeout) must not land in the next one
      let utterance = lock.withLock {
        generation += 1
        return generation
      }
      try await withTaskCancellationHandler {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, any Error>) in
          state.set(cont)
          // a voice occasionally never signals the end; don't hang the whole book on it
          DispatchQueue.global().asyncAfter(deadline: .now() + 30) { state.resume() }
          synthesizer.write(u) { [weak self] buffer in
            guard let self, let pcm = buffer as? AVAudioPCMBuffer else { return }
            if pcm.frameLength == 0 {
              state.resume()
              return
            }
            do {
              try self.append(pcm, url: url, utterance: utterance)
            } catch {
              state.resume(throwing: error)
            }
          }
        }
      } onCancel: {
        state.resume(throwing: CancellationError())
        cancel()
      }
    }

    private func speak(_ text: String, with voice: SampleVoice, url: URL) async throws {
      let utterance = lock.withLock {
        generation += 1
        return generation
      }
      let samples = try await voice.synthesize(text)
      try Task.checkCancellation()
      guard !samples.isEmpty,
        let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: voice.sampleRate, channels: 1, interleaved: false),
        let pcm = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count))
      else { return }
      pcm.frameLength = AVAudioFrameCount(samples.count)
      samples.withUnsafeBufferPointer { pcm.floatChannelData![0].update(from: $0.baseAddress!, count: samples.count) }
      try append(pcm, url: url, utterance: utterance)
    }

    private func append(_ pcm: AVAudioPCMBuffer, url: URL, utterance: Int) throws {
      try lock.withLock {
        guard utterance == generation else { return }
        if file == nil {
          let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: pcm.format.sampleRate,
            AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 64_000,
          ]
          file = try AVAudioFile(forWriting: url, settings: settings, commonFormat: pcm.format.commonFormat, interleaved: pcm.format.isInterleaved)
          format = pcm.format
        }
        try file?.write(from: pcm)
        frames += AVAudioFramePosition(pcm.frameLength)
      }
    }

    private func writeSilence(_ seconds: Double) throws {
      try lock.withLock {
        guard let file, let format, seconds > 0 else { return }
        let n = AVAudioFrameCount(seconds * format.sampleRate)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: n) else { return }
        buffer.frameLength = n
        let bytes = Int(n) * Int(format.streamDescription.pointee.mBytesPerFrame)
        for b in 0..<Int(buffer.audioBufferList.pointee.mNumberBuffers) {
          let list = UnsafeMutableAudioBufferListPointer(buffer.mutableAudioBufferList)
          if let data = list[b].mData { memset(data, 0, bytes) }
        }
        try file.write(from: buffer)
        frames += AVAudioFramePosition(n)
      }
    }
  }

  /// Resumes a continuation exactly once (end-of-utterance, error or timeout).
  private final class ResumeOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var cont: CheckedContinuation<Void, any Error>?
    /// A resume that came before the continuation (cancelled right away).
    private var early: Result<Void, any Error>?
    private var done = false

    func set(_ c: CheckedContinuation<Void, any Error>) {
      let result = lock.withLock { () -> Result<Void, any Error>? in
        if early == nil { cont = c }
        return early
      }
      if let result { c.resume(with: result) }
    }

    func resume(throwing error: (any Error)? = nil) {
      let result: Result<Void, any Error> = error.map { .failure($0) } ?? .success(())
      let c = lock.withLock { () -> CheckedContinuation<Void, any Error>? in
        guard !done else { return nil }
        done = true
        if cont == nil { early = result }
        defer { cont = nil }
        return cont
      }
      c?.resume(with: result)
    }
  }
#endif
