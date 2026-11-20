import MarginVoiceCore
import Foundation
import SwiftData
import os

extension Logger {
  static func drafttalk(_ category: String) -> Logger { Logger(subsystem: "de.kaikunze.drafttalk", category: category) }
}

/// The store as shipped in 0.1.0. Frozen: these copies carry only the stored
/// properties and are never edited. A change to `Book` or `Comment` goes into a new
/// schema version with a migration stage, so existing comments survive the update.
enum DraftTalkSchemaV1: VersionedSchema {
  static let versionIdentifier = Schema.Version(1, 0, 0)
  static var models: [any PersistentModel.Type] { [Book.self, Comment.self] }

  @Model
  final class Book {
    @Attribute(.unique) var id: UUID
    var title: String
    var folder: String
    var audioFile: String
    var textFile: String?
    var timingFile: String?
    var language: String?
    var voiceName: String?
    var duration: Double
    var chapters: [ChapterMark]
    var position: Double
    var rate: Double
    var addedAt: Date
    var lastOpened: Date?
    @Relationship(deleteRule: .cascade, inverse: \Comment.book) var comments: [Comment] = []

    init(title: String, folder: String, audioFile: String, duration: Double, rate: Double) {
      id = UUID()
      self.title = title
      self.folder = folder
      self.audioFile = audioFile
      self.duration = duration
      chapters = []
      position = 0
      self.rate = rate
      addedAt = Date()
    }
  }

  @Model
  final class Comment {
    @Attribute(.unique) var id: UUID
    var kindRaw: String
    var mediaTime: Double
    var estimatedOffset: Int?
    var endMediaTime: Double?
    var endEstimatedOffset: Int?
    var refinedOffset: Int?
    var sentenceNudge: Int
    var confidence: Double
    var chapter: String?
    var transcript: String
    var transcriptStatusRaw: String
    var audioFile: String?
    var duration: Double
    var createdAt: Date
    var book: Book?

    init(kindRaw: String, mediaTime: Double, transcript: String) {
      id = UUID()
      self.kindRaw = kindRaw
      self.mediaTime = mediaTime
      sentenceNudge = 0
      confidence = 0
      self.transcript = transcript
      transcriptStatusRaw = "done"
      duration = 0
      createdAt = Date()
    }
  }
}

/// 0.2.0: `Comment.reviewTag`. The live models live in `Book.swift` and `Comment.swift`.
enum DraftTalkSchemaV2: VersionedSchema {
  static let versionIdentifier = Schema.Version(2, 0, 0)
  static var models: [any PersistentModel.Type] { [Book.self, Comment.self] }
}

/// What the app means by `Book` and `Comment`: always the newest version.
typealias DraftTalkCurrentSchema = DraftTalkSchemaV2
typealias Book = DraftTalkSchemaV2.Book
typealias Comment = DraftTalkSchemaV2.Comment

enum DraftTalkMigrationPlan: SchemaMigrationPlan {
  static var schemas: [any VersionedSchema.Type] { [DraftTalkSchemaV1.self, DraftTalkSchemaV2.self] }
  static var stages: [MigrationStage] { [v1ToV2] }

  /// Adds the optional `reviewTag` column; existing rows get nil.
  static let v1ToV2 = MigrationStage.lightweight(fromVersion: DraftTalkSchemaV1.self, toVersion: DraftTalkSchemaV2.self)
}

extension ModelContext {
  private static let log = Logger.drafttalk("store")

  /// Saves and logs a failure instead of dropping it. Returns whether it saved.
  @discardableResult
  func saveLogged(_ what: String) -> Bool {
    do {
      try save()
      return true
    } catch {
      Self.log.error("save failed (\(what, privacy: .public)): \(error.localizedDescription, privacy: .public)")
      return false
    }
  }
}
