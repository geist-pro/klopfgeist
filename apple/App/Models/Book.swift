import MarginVoiceCore
import Foundation
import SwiftData

extension DraftTalkSchemaV2 {
  /// An imported audiobook: the m4b, its narration text and optional timing sidecar,
  /// stored together in `Application Support/Books/<folder>/`.
  @Model
  final class Book {
    @Attribute(.unique) var id: UUID
    var title: String
    var folder: String
    var audioFile: String
    var textFile: String?
    var timingFile: String?
    /// BCP-47 language of the narration ("en", "de"), detected from the text.
    var language: String?
    /// Set for books created on the device: the system voice that narrated them.
    var voiceName: String?
    var duration: Double
    var chapters: [ChapterMark]
    var position: Double
    var rate: Double
    var addedAt: Date
    var lastOpened: Date?
    @Relationship(deleteRule: .cascade, inverse: \Comment.book) var comments: [Comment] = []

    init(
      title: String, folder: String, audioFile: String, textFile: String?, timingFile: String?, language: String?,
      duration: Double, chapters: [ChapterMark], rate: Double
    ) {
      id = UUID()
      self.title = title
      self.folder = folder
      self.audioFile = audioFile
      self.textFile = textFile
      self.timingFile = timingFile
      self.language = language
      self.duration = duration
      self.chapters = chapters
      position = 0
      self.rate = rate
      addedAt = Date()
    }

    var sortedComments: [Comment] { comments.sorted { $0.mediaTime < $1.mediaTime } }

    var progress: Double { duration > 0 ? min(1, position / duration) : 0 }

    /// Listened to the end (within the last few seconds).
    var isFinished: Bool { duration > 0 && position >= duration - 3 }

    func chapterIndex(at time: Double) -> Int? { chapters.index(at: time) }

    func chapterTitle(at time: Double) -> String? {
      chapterIndex(at: time).map { chapters[$0].title }
    }
  }
}
