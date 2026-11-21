import AVFAudio
import MarginVoiceCore
import Foundation
import Observation
import os

#if canImport(KokoroTTS)
  @preconcurrency import KokoroTTS
#endif
#if os(macOS) && canImport(Qwen3TTS)
  @preconcurrency import Qwen3TTS
#endif

/// A neural voice for converting books: the same engines doc2audio uses, run in the
/// app (speech-swift: Kokoro on Core ML, Qwen3-TTS on MLX). Models are downloaded
/// once on first use; after that the voice works offline.
///
/// Stored in settings next to system voice identifiers as `"kokoro:af_heart"`,
/// `"qwen3:de_designed_female"`.
struct NeuralVoice: Hashable, Identifiable, Sendable {
  enum Engine: String, Sendable {
    /// Kokoro-82M (Apache-2.0): fast, Core ML, iPhone and Mac.
    case kokoro
    /// Qwen3-TTS 1.7B (Apache-2.0), cloned from a designed reference voice: the
    /// most natural German, Mac only (MLX, about 3 GB).
    case qwen3
  }

  let engine: Engine
  /// Kokoro preset or Qwen3 reference clip name.
  let preset: String
  let name: String
  /// "American English", "British English", "Deutsch".
  let accent: String
  let language: String
  /// Hugging Face repository with the weights.
  let modelID: String

  var id: String { "\(engine.rawValue):\(preset)" }
  var engineName: String { engine == .kokoro ? "Kokoro" : "Qwen3" }
  /// What the first use downloads (shared by every voice of the same model).
  var downloadMB: Int { NeuralVoiceCatalog.downloadMB[modelID] ?? 0 }
}

enum NeuralVoiceCatalog {
  static let sampleRate: Double = 24000

  /// The engines are compiled in only with the speech-swift package.
  static var isAvailable: Bool {
    #if canImport(KokoroTTS)
      true
    #else
      false
    #endif
  }

  static let kokoroModel = "aufklarer/Kokoro-82M-CoreML"
  static let kokoroMartinModel = "aufklarer/Kokoro-82M-German-Martin-CoreML"
  static let kokoroVictoriaModel = "aufklarer/Kokoro-82M-German-Victoria-CoreML"
  /// The 8-bit Base model doc2audio uses (mlx-community's conversion there).
  static let qwen3Model = "aufklarer/Qwen3-TTS-12Hz-1.7B-Base-MLX-8bit"
  static let qwen3Tokenizer = "Qwen/Qwen3-TTS-Tokenizer-12Hz"

  static let downloadMB: [String: Int] = [
    kokoroModel: 335, kokoroMartinModel: 328, kokoroVictoriaModel: 328, qwen3Model: 2422 + 682,
  ]

  /// Kokoro's best-graded English voices (af_heart is doc2audio's default), then the
  /// German Kokoro fine-tunes, then the designed Qwen3 voices doc2audio reads German with.
  static let all: [NeuralVoice] = {
    var voices: [NeuralVoice] = [
      ("af_heart", "Heart", "American English"), ("af_bella", "Bella", "American English"),
      ("af_nicole", "Nicole", "American English"), ("am_michael", "Michael", "American English"),
      ("am_fenrir", "Fenrir", "American English"), ("bf_emma", "Emma", "British English"),
      ("bm_george", "George", "British English"), ("bm_fable", "Fable", "British English"),
    ].map { NeuralVoice(engine: .kokoro, preset: $0.0, name: $0.1, accent: $0.2, language: "en", modelID: kokoroModel) }
    voices.append(NeuralVoice(engine: .kokoro, preset: "dm_martin", name: "Martin", accent: "Deutsch", language: "de", modelID: kokoroMartinModel))
    voices.append(NeuralVoice(engine: .kokoro, preset: "df_victoria", name: "Victoria", accent: "Deutsch", language: "de", modelID: kokoroVictoriaModel))
    #if os(macOS)
      voices.append(NeuralVoice(engine: .qwen3, preset: "de_designed_female", name: "Hörbuchsprecherin", accent: "Deutsch", language: "de", modelID: qwen3Model))
      voices.append(NeuralVoice(engine: .qwen3, preset: "de_designed_male", name: "Hörbuchsprecher", accent: "Deutsch", language: "de", modelID: qwen3Model))
    #endif
    return voices
  }()

  static func voices(language: String) -> [NeuralVoice] {
    guard isAvailable else { return [] }
    return all.filter { $0.language == language }
  }

  static func voice(id: String?) -> NeuralVoice? {
    guard isAvailable, let id else { return nil }
    return all.first { $0.id == id }
  }

  /// The voice a new book in this language gets when none was picked before:
  /// Heart for English (doc2audio's default), the designed Qwen3 voice for German
  /// on the Mac when its model is already here, else Kokoro's Victoria.
  static func defaultVoice(language: String, ready: (String) -> Bool) -> NeuralVoice? {
    let candidates = voices(language: language)
    return candidates.first { $0.engine == .qwen3 && ready($0.modelID) } ?? candidates.first
  }
}

/// Which models are on this device, and their downloads.
@MainActor
@Observable
final class NeuralVoiceModels {
  enum State: Equatable {
    case notDownloaded
    case downloading(Double)
    case ready
    case failed(String)
  }

  private static let readyKey = "neuralModelsReady"
  private(set) var states: [String: State] = [:]
  @ObservationIgnored private let defaults: UserDefaults

  init(defaults: UserDefaults = .standard) {
    self.defaults = defaults
    for id in defaults.stringArray(forKey: Self.readyKey) ?? [] { states[id] = .ready }
  }

  func state(_ modelID: String) -> State { states[modelID] ?? .notDownloaded }
  func isReady(_ modelID: String) -> Bool { state(modelID) == .ready }

  /// Loads the voice (downloading its model the first time) and returns it ready
  /// to render. Progress covers download and loading.
  func prepare(_ voice: NeuralVoice) async throws -> SpeechRenderer.SampleVoice {
    if !isReady(voice.modelID) { states[voice.modelID] = .downloading(0) }
    let modelID = voice.modelID
    do {
      try await NeuralSpeechHost.shared.load(voice) { [weak self] fraction in
        Task { @MainActor in
          guard let self, case .downloading = self.state(modelID) else { return }
          self.states[modelID] = .downloading(fraction)
        }
      }
    } catch {
      states[modelID] = error is CancellationError ? .notDownloaded : .failed(error.localizedDescription)
      throw error
    }
    markReady(modelID)
    return SpeechRenderer.SampleVoice(name: voice.name, sampleRate: NeuralVoiceCatalog.sampleRate) { text in
      try await NeuralSpeechHost.shared.synthesize(text, voice: voice)
    }
  }

  private func markReady(_ modelID: String) {
    states[modelID] = .ready
    var ready = Set(defaults.stringArray(forKey: Self.readyKey) ?? [])
    ready.insert(modelID)
    defaults.set(Array(ready).sorted(), forKey: Self.readyKey)
  }
}

/// Owns the loaded models (one at a time: Qwen3 alone takes ~3 GB) and runs them
/// off the main actor, one sentence at a time.
actor NeuralSpeechHost {
  static let shared = NeuralSpeechHost()
  private static let log = Logger.drafttalk("neural-voice")

  enum HostError: LocalizedError {
    case unavailable
    case missingReference(String)
    case voiceFailed
    var errorDescription: String? {
      switch self {
      case .unavailable: "Neural voices are not part of this build."
      case .missingReference(let name): "The reference recording for the voice “\(name)” is missing from the app."
      case .voiceFailed: "The voice could not speak many words of this document. Try another voice, or a system voice."
      }
    }
  }

  private var loadedModelID: String?
  #if canImport(KokoroTTS)
    private var kokoro: KokoroTTSModel?
  #endif
  #if os(macOS) && canImport(Qwen3TTS)
    private var qwen3: (model: Qwen3TTSModel, encoder: SpeechTokenizerEncoder)?
    private var references: [String: (audio: [Float], sampleRate: Int, text: String)] = [:]
  #endif

  /// The model being loaded right now: a second caller waits for it instead of
  /// loading another copy (Qwen3 is about 3 GB) across the actor's suspension.
  private var loading: (modelID: String, task: Task<Void, Error>)?

  func load(_ voice: NeuralVoice, progress: @escaping @Sendable (Double) -> Void) async throws {
    while true {
      if loadedModelID == voice.modelID { return }
      guard let pending = loading else { break }
      if pending.modelID == voice.modelID { return try await pending.task.value }
      // another model is on its way in: let it land, then replace it
      _ = try? await pending.task.value
    }
    let task = Task { try await performLoad(voice, progress: progress) }
    loading = (voice.modelID, task)
    defer { loading = nil }
    try await task.value
  }

  private func performLoad(_ voice: NeuralVoice, progress: @escaping @Sendable (Double) -> Void) async throws {
    unload()
    switch voice.engine {
    case .kokoro:
      #if canImport(KokoroTTS)
        kokoro = try await KokoroTTSModel.fromPretrained(modelId: voice.modelID, voice: voice.preset) { fraction, _ in
          progress(fraction)
        }
      #else
        throw HostError.unavailable
      #endif
    case .qwen3:
      #if os(macOS) && canImport(Qwen3TTS)
        qwen3 = try await Qwen3TTSModel.fromPretrainedWithEncoder(
          modelId: voice.modelID, tokenizerModelId: NeuralVoiceCatalog.qwen3Tokenizer
        ) { fraction, _ in progress(fraction) }
      #else
        throw HostError.unavailable
      #endif
    }
    loadedModelID = voice.modelID
    #if os(macOS) && canImport(KokoroTTS)
      skippedWords = 0
    #endif
    Self.log.info("loaded \(voice.modelID, privacy: .public)")
  }

  #if os(macOS) && canImport(KokoroTTS)
    /// Words skipped because the voice could not speak them, since the model was loaded.
    private var skippedWords = 0

    /// Kokoro, with CoreML's Objective-C exceptions caught: on one, the text is spoken in halves, down to
    /// single words, and a word that still fails becomes a short pause. Without this the whole app
    /// crashed in the middle of a long conversion (and the half-made book was gone).
    private func kokoroGuarded(_ kokoro: KokoroTTSModel, _ text: String, voice: NeuralVoice) throws -> [Float] {
      var audio: [Float] = []
      var failure: Error?
      let exception = MVCatchException {
        do { audio = try kokoro.synthesize(text: text, voice: voice.preset, language: voice.language) } catch { failure = error }
      }
      if let failure { throw failure }
      guard let exception else { return audio }
      let words = text.split(whereSeparator: \.isWhitespace)
      Self.log.error("voice failed (\(exception.name.rawValue, privacy: .public): \(exception.reason ?? "", privacy: .public)) on \(words.count) words")
      if words.count <= 1 {
        skippedWords += 1
        // more than a few: the model is broken, not one odd word, and the book would be full of holes
        if skippedWords > 25 { throw HostError.voiceFailed }
        return [Float](repeating: 0, count: Int(NeuralVoiceCatalog.sampleRate * 0.25))
      }
      let half = words.count / 2
      let gap = [Float](repeating: 0, count: Int(NeuralVoiceCatalog.sampleRate * 0.05))
      return try kokoroGuarded(kokoro, words[..<half].joined(separator: " "), voice: voice) + gap
        + kokoroGuarded(kokoro, words[half...].joined(separator: " "), voice: voice)
    }
  #endif

  /// Frees the model's memory (after a conversion).
  func unload() {
    #if canImport(KokoroTTS)
      kokoro = nil
    #endif
    #if os(macOS) && canImport(Qwen3TTS)
      qwen3 = nil
    #endif
    loadedModelID = nil
  }

  func synthesize(_ text: String, voice: NeuralVoice) async throws -> [Float] {
    try Task.checkCancellation()
    // two conversions with different models take turns loading theirs
    if loadedModelID != voice.modelID { try await load(voice) { _ in } }
    switch voice.engine {
    case .kokoro:
      #if canImport(KokoroTTS)
        guard let kokoro else { throw HostError.unavailable }
        #if os(macOS)
          return try kokoroGuarded(kokoro, text, voice: voice)
        #else
          return try kokoro.synthesize(text: text, voice: voice.preset, language: voice.language)
        #endif
      #else
        throw HostError.unavailable
      #endif
    case .qwen3:
      #if os(macOS) && canImport(Qwen3TTS)
        guard let qwen3 else { throw HostError.unavailable }
        let ref = try reference(voice.preset)
        let language = voice.language == "de" ? "german" : "english"
        // Qwen3 drifts on very long input; doc2audio sends it at most ~400 characters
        var audio: [Float] = []
        for piece in Self.pieces(of: text, maxLength: 300) {
          try Task.checkCancellation()
          if !audio.isEmpty { audio += [Float](repeating: 0, count: Int(NeuralVoiceCatalog.sampleRate * 0.08)) }
          audio += qwen3.model.synthesizeWithVoiceCloneICL(
            text: piece, referenceAudio: ref.audio, referenceSampleRate: ref.sampleRate, referenceText: ref.text,
            language: language, codecEncoder: qwen3.encoder)
        }
        return audio
      #else
        throw HostError.unavailable
      #endif
    }
  }

  #if os(macOS) && canImport(Qwen3TTS)
    /// The designed voice's reference clip and its exact transcript (from doc2audio).
    private func reference(_ name: String) throws -> (audio: [Float], sampleRate: Int, text: String) {
      if let cached = references[name] { return cached }
      guard let wav = Bundle.main.url(forResource: name, withExtension: "wav"),
        let txt = Bundle.main.url(forResource: name, withExtension: "txt")
      else { throw HostError.missingReference(name) }
      let file = try AVAudioFile(forReading: wav)
      guard let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length)) else {
        throw HostError.missingReference(name)
      }
      try file.read(into: buffer)
      let samples = Array(UnsafeBufferPointer(start: buffer.floatChannelData![0], count: Int(buffer.frameLength)))
      let text = try String(contentsOf: txt, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
      let ref = (samples, Int(file.processingFormat.sampleRate), text)
      references[name] = ref
      return ref
    }
  #endif

  /// Splits text longer than `maxLength` at clause ends (then spaces), keeping every character.
  static func pieces(of text: String, maxLength: Int) -> [String] {
    var rest = Substring(text)
    var out: [String] = []
    while rest.count > maxLength {
      let window = rest.prefix(maxLength)
      // a clause end in the second half of the window, else the last space
      let half = window.index(window.startIndex, offsetBy: maxLength / 2)
      let clause = window[half...].lastIndex(where: { ";:,".contains($0) }).map { rest.index(after: $0) }
      let cut = clause ?? window.lastIndex(of: " ") ?? window.endIndex
      let piece = rest[..<cut].trimmingCharacters(in: .whitespaces)
      if !piece.isEmpty { out.append(piece) }
      rest = rest[cut...]
    }
    let last = rest.trimmingCharacters(in: .whitespaces)
    if !last.isEmpty { out.append(last) }
    return out
  }
}
