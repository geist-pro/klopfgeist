import MarginVoiceCore
import Foundation
import SwiftData

extension DraftTalkSchemaV2 {
  /// A spoken comment or a bookmark at a position in a book.
  @Model
  final class Comment {
    enum TranscriptStatus: String, Codable {
      case none, pending, done, failed
    }

    @Attribute(.unique) var id: UUID
    var kindRaw: String
    /// Book media time of the anchor, seconds.
    var mediaTime: Double
    /// Text offset estimated from the time map.
    var estimatedOffset: Int?
    /// Bookmarked passages: where the passage ends (media seconds, estimated text offset).
    var endMediaTime: Double?
    var endEstimatedOffset: Int?
    /// Text offset after aligning a transcript of the book audio, if that worked.
    var refinedOffset: Int?
    /// Manual correction in sentences (from the nudge buttons).
    var sentenceNudge: Int
    var confidence: Double
    var chapter: String?
    var transcript: String
    var transcriptStatusRaw: String
    /// File name inside the book folder's `comments/` directory.
    var audioFile: String?
    var duration: Double
    var createdAt: Date
    /// The review category spoken as the first word ("weakness: …"): a `ReviewTag` raw
    /// value. nil for plain notes and for every comment made before 0.2.0.
    var reviewTag: String?
    var book: Book?

    init(kind: CommentRecord.Kind, mediaTime: Double, estimatedOffset: Int?, confidence: Double, chapter: String?, audioFile: String?, duration: Double = 0) {
      id = UUID()
      kindRaw = kind.rawValue
      self.mediaTime = mediaTime
      self.estimatedOffset = estimatedOffset
      sentenceNudge = 0
      self.confidence = confidence
      self.chapter = chapter
      transcript = ""
      transcriptStatusRaw = (kind == .comment ? TranscriptStatus.pending : .none).rawValue
      self.audioFile = audioFile
      self.duration = duration
      createdAt = Date()
    }

    var kind: CommentRecord.Kind { CommentRecord.Kind(rawValue: kindRaw) ?? .comment }

    var isPassage: Bool { endMediaTime != nil }

    var transcriptStatus: TranscriptStatus {
      get { TranscriptStatus(rawValue: transcriptStatusRaw) ?? .none }
      set { transcriptStatusRaw = newValue.rawValue }
    }

    var tag: ReviewTag? {
      get { reviewTag.flatMap(ReviewTag.init(rawValue:)) }
      set { reviewTag = newValue?.rawValue }
    }

    /// Best known text offset including manual nudges.
    func textOffset(in text: NarrationText?) -> Int? {
      (refinedOffset ?? estimatedOffset).map { CommentRecord.nudgedOffset($0, by: sentenceNudge, in: text) }
    }

    /// End of a bookmarked passage, moved by the same amount as the start (refined and nudged).
    func endTextOffset(in text: NarrationText?) -> Int? {
      endEstimatedOffset.map { CommentRecord.passageEnd(estimatedEnd: $0, estimatedStart: estimatedOffset, refinedStart: textOffset(in: text)) }
    }

    func record(text: NarrationText?, audioName: String? = nil) -> CommentRecord {
      CommentRecord(
        id: id, kind: kind, mediaTime: mediaTime, textOffset: textOffset(in: text) ?? 0,
        confidence: sentenceNudge != 0 ? 1 : confidence, chapter: chapter, transcript: transcript,
        audioFile: audioName, createdAt: createdAt, endMediaTime: endMediaTime, endTextOffset: endTextOffset(in: text), tag: tag)
    }
  }
}
