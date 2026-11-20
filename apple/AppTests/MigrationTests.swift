import Foundation
import SwiftData
import Testing
@testable import MarginVoice

/// An existing 0.1.0 store opens with the current schema and keeps its comments.
@MainActor
struct MigrationTests {
  @Test func v1StoreOpensAndKeepsComments() throws {
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("drafttalk-migration-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    let url = dir.appendingPathComponent("library.store")

    // 1. a store exactly as 0.1.0 wrote it: V1 schema, no stages
    let commentID: UUID = try {
      let schema = Schema(versionedSchema: DraftTalkSchemaV1.self)
      let v1 = try ModelContainer(for: schema, configurations: ModelConfiguration(schema: schema, url: url, cloudKitDatabase: .none))
      let book = DraftTalkSchemaV1.Book(title: "Old Draft", folder: "b1", audioFile: "a.m4b", duration: 10, rate: 1)
      let comment = DraftTalkSchemaV1.Comment(kindRaw: "comment", mediaTime: 3, transcript: "The baseline is missing.")
      v1.mainContext.insert(book)
      v1.mainContext.insert(comment)
      comment.book = book
      try v1.mainContext.save()
      return comment.id
    }()

    // 2. opened the way the app does now
    let schema = Schema(versionedSchema: DraftTalkCurrentSchema.self)
    let v2 = try ModelContainer(for: schema, migrationPlan: DraftTalkMigrationPlan.self, configurations: ModelConfiguration(schema: schema, url: url, cloudKitDatabase: .none))
    let comments = try v2.mainContext.fetch(FetchDescriptor<DraftTalkSchemaV2.Comment>())
    let comment = try #require(comments.first)
    #expect(comments.count == 1)
    #expect(comment.id == commentID)
    #expect(comment.transcript == "The baseline is missing.")
    #expect(comment.reviewTag == nil && comment.tag == nil)
    #expect(comment.book?.title == "Old Draft")
    #expect(try v2.mainContext.fetch(FetchDescriptor<DraftTalkSchemaV2.Book>()).first?.comments.count == 1)
  }
}
