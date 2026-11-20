import AVFoundation
import MarginVoiceApple
import Speech

/// Transcribes a recorded audio file on device.
enum FileTranscriber {
  /// - Parameter vocabulary: words the speaker is likely to use (names, terms from the book).
  static func transcribe(url: URL, locale: Locale, vocabulary: [String] = []) async throws -> String {
    var lastError: any Error = SpeechSetupError.unsupportedLocale(locale.identifier)
    for recognizer in await SpeechAssets.candidates(for: locale, live: false) {
      do {
        return try await transcribe(url: url, with: recognizer, vocabulary: vocabulary)
      } catch {
        lastError = error
      }
    }
    throw lastError
  }

  private static func transcribe(url: URL, with recognizer: Recognizer, vocabulary: [String]) async throws -> String {
    try await SpeechAssets.installAssets(for: recognizer.module)
    let results = recognizer.results
    let collector = Task { () throws -> String in
      var parts: [String] = []
      for try await r in results where r.isFinal && !r.text.isEmpty {
        parts.append(r.text)
      }
      return parts.joined(separator: " ")
    }
    let file = try AVAudioFile(forReading: url)
    let context = AnalysisContext()
    if !vocabulary.isEmpty { context.contextualStrings[.general] = vocabulary }
    let analyzer: SpeechAnalyzer
    do {
      analyzer = try await SpeechAnalyzer(
        inputAudioFile: file, modules: [recognizer.module], analysisContext: context, finishAfterFile: true)
    } catch {
      collector.cancel()
      throw error
    }
    let text = try await collector.value
    withExtendedLifetime(analyzer) {}
    return text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
  }
}
