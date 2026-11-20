import Foundation
import Speech
import os

public enum SpeechSetupError: LocalizedError {
  case unsupportedLocale(String)
  case noAudioFormat

  public var errorDescription: String? {
    switch self {
    case .unsupportedLocale(let id): "On-device speech recognition is not available for \(id)."
    case .noAudioFormat: "Speech recognition could not use the microphone format."
    }
  }
}

/// A recognizer module plus its results, independent of which Speech class backs it.
public struct Recognizer: @unchecked Sendable {
  public struct Piece: Sendable {
    public let text: String
    /// The result's range on the recognizer's timeline, seconds.
    public let start: Double
    public let end: Double
    public let isFinal: Bool
    /// The runs of the result's text with their audio times (`.audioTimeRange`).
    public let runs: [RecognizerRun]
  }

  public let name: String
  public let module: any SpeechModule
  public let results: AsyncThrowingStream<Piece, any Error>
}

/// On-device speech models (SpeechAnalyzer). Prefers `SpeechTranscriber`; falls back
/// to `DictationTranscriber`, which covers more devices and locales.
public enum SpeechAssets {
  private static let log = Logger.marginVoice("speech")

  /// Recognizers to try in order. `live` asks for volatile (fast, partial) results.
  public static func candidates(for locale: Locale, live: Bool) async -> [Recognizer] {
    var result: [Recognizer] = []
    if SpeechTranscriber.isAvailable, let supported = await SpeechTranscriber.supportedLocale(equivalentTo: locale) {
      let t = SpeechTranscriber(
        locale: supported, transcriptionOptions: [], reportingOptions: live ? [.volatileResults, .fastResults] : [],
        attributeOptions: [.audioTimeRange])
      result.append(Recognizer(name: "SpeechTranscriber", module: t, results: stream(t.results)))
    }
    if let supported = await DictationTranscriber.supportedLocale(equivalentTo: locale) {
      let d = DictationTranscriber(
        locale: supported, contentHints: live ? [.shortForm, .farField] : [.farField], transcriptionOptions: [.punctuation],
        reportingOptions: live ? [.volatileResults, .frequentFinalization] : [], attributeOptions: [.audioTimeRange])
      result.append(Recognizer(name: "DictationTranscriber", module: d, results: stream(d.results)))
    }
    log.info("recognizers for \(locale.identifier): \(result.map(\.name).joined(separator: ", "))")
    return result
  }

  public static func installAssets(for module: any SpeechModule) async throws {
    if let request = try await AssetInventory.assetInstallationRequest(supporting: [module]) {
      try await request.downloadAndInstall()
    }
  }

  private static func stream<S: AsyncSequence & Sendable>(_ results: S) -> AsyncThrowingStream<Recognizer.Piece, any Error>
  where S.Element: SpeechModuleResult & Sendable {
    AsyncThrowingStream { continuation in
      let task = Task {
        do {
          for try await r in results {
            continuation.yield(piece(r))
          }
          continuation.finish()
        } catch {
          continuation.finish(throwing: error)
        }
      }
      continuation.onTermination = { _ in task.cancel() }
    }
  }

  private static func piece(_ r: some SpeechModuleResult) -> Recognizer.Piece {
    let attributed: AttributedString
    switch r {
    case let s as SpeechTranscriber.Result: attributed = s.text
    case let d as DictationTranscriber.Result: attributed = d.text
    default: attributed = AttributedString()
    }
    return Recognizer.Piece(
      text: String(attributed.characters).trimmingCharacters(in: .whitespacesAndNewlines), start: r.range.start.seconds,
      end: r.range.end.seconds, isFinal: r.isFinal, runs: runs(attributed))
  }

  /// Each run of the text with the audio time the recognizer gave it.
  private static func runs(_ text: AttributedString) -> [RecognizerRun] {
    text.runs.map { run in
      let range = run.audioTimeRange
      return RecognizerRun(String(text[run.range].characters), start: range?.start.seconds, end: range?.end.seconds)
    }
  }
}
