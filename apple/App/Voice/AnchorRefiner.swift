import AVFoundation
import MarginVoiceCore

/// Pins a comment to the exact place in the text: transcribes the book audio just
/// before the anchor and aligns those words with the narration text.
enum AnchorRefiner {
  struct Refinement: Sendable {
    let offset: Int
    let confidence: Double
  }

  static let contextSeconds = 20.0

  static func refine(bookAudio: URL, anchor: Double, content: BookContent, locale: Locale) async throws -> Refinement? {
    let start = max(0, anchor - contextSeconds)
    guard anchor - start > 2 else { return nil }
    let clip = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".caf")
    defer { try? FileManager.default.removeItem(at: clip) }
    try extract(from: bookAudio, start: start, end: anchor, to: clip)
    let transcript = try await FileTranscriber.transcribe(url: clip, locale: locale)

    let estimate = content.offset(at: anchor)
    // search ±90 s of narration around the estimate (chapter-based estimates drift)
    let span = content.offset(at: anchor + 90) - content.offset(at: max(0, anchor - 90))
    let window = max(1_500, span / 2 + 300)
    guard let r = ContextAligner.align(transcript: transcript, in: content.text, near: estimate, window: window, minimumMatches: 5)
    else { return nil }
    return Refinement(offset: r.offset, confidence: r.confidence)
  }

  static func extract(from url: URL, start: Double, end: Double, to destination: URL) throws {
    let input = try AVAudioFile(forReading: url)
    let format = input.processingFormat
    let first = AVAudioFramePosition(start * format.sampleRate)
    let count = AVAudioFrameCount(max(0, min(input.length - first, AVAudioFramePosition((end - start) * format.sampleRate))))
    guard count > 0, let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: count) else { return }
    input.framePosition = first
    try input.read(into: buffer, frameCount: count)
    let output = try AVAudioFile(
      forWriting: destination, settings: format.settings, commonFormat: format.commonFormat, interleaved: format.isInterleaved)
    try output.write(from: buffer)
  }
}
