import Foundation

/// A comment or bookmark as seen by the exporters (decoupled from SwiftData).
public struct CommentRecord: Codable, Sendable, Equatable, Identifiable {
  public enum Kind: String, Codable, Sendable {
    case comment, bookmark
  }

  public var id: UUID
  public var kind: Kind
  /// Book media time of the anchor, seconds.
  public var mediaTime: Double
  /// Character offset in the narration text where the comment belongs.
  public var textOffset: Int
  /// Bookmarks of a passage: where the passage ends (media seconds and text offset).
  public var endMediaTime: Double?
  public var endTextOffset: Int?
  /// 0...1; below `AnnotatedTextExport.lowConfidence` the anchor is only estimated.
  public var confidence: Double
  public var chapter: String?
  public var transcript: String
  /// File name of the recorded comment audio, relative to the export folder.
  public var audioFile: String?
  public var createdAt: Date
  /// The review category the comment was spoken with ("weakness: …"), if any.
  public var tag: ReviewTag?

  public init(
    id: UUID = UUID(), kind: Kind = .comment, mediaTime: Double, textOffset: Int, confidence: Double,
    chapter: String? = nil, transcript: String, audioFile: String? = nil, createdAt: Date = Date(),
    endMediaTime: Double? = nil, endTextOffset: Int? = nil, tag: ReviewTag? = nil
  ) {
    self.endMediaTime = endMediaTime
    self.endTextOffset = endTextOffset
    self.tag = tag
    self.id = id
    self.kind = kind
    self.mediaTime = mediaTime
    self.textOffset = textOffset
    self.confidence = confidence
    self.chapter = chapter
    self.transcript = transcript
    self.audioFile = audioFile
    self.createdAt = createdAt
  }
}

extension CommentRecord {
  /// Sentences a bookmark covers: the one just heard, or the whole passage.
  public func sentenceRange(in text: NarrationText) -> ClosedRange<Int> {
    let first = AnnotatedTextExport.attachSentence(for: textOffset, in: text)
    let last = endTextOffset.map { AnnotatedTextExport.attachSentence(for: $0, in: text) } ?? first
    return first...max(first, last)
  }

  /// The text a comment refers to: the sentence just heard, or a bookmarked passage.
  public func quotedText(in text: NarrationText) -> String {
    guard !text.sentences.isEmpty else { return "" }
    let range = kind == .bookmark ? sentenceRange(in: text) : sentenceRange(in: text).lowerBound...sentenceRange(in: text).lowerBound
    let start = text.sentences[range.lowerBound].range.lowerBound
    let end = text.sentences[min(range.upperBound, text.sentences.count - 1)].range.upperBound
    return text.substring(start..<end).replacingOccurrences(of: "\n\n", with: " ")
  }
}

public enum TimeFormat {
  /// "4:05", "12:34", "1:02:03".
  public static func clock(_ seconds: Double) -> String {
    guard seconds.isFinite else { return "0:00" }
    let t = max(0, Int(seconds.rounded(.down)))
    let h = t / 3600, m = (t % 3600) / 60, s = t % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
  }
}
