import MarginVoiceCore
import Foundation
import SwiftData
import Testing
@testable import MarginVoice

/// `.drafttalk` files and sync between two libraries, each with its own in-memory store and books folder.
@MainActor
struct SyncTests {
  @MainActor
  final class Library {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent("drafttalk-sync-\(UUID().uuidString)", isDirectory: true)
    let store: BookStore
    let container: ModelContainer
    var context: ModelContext { container.mainContext }
    lazy var local = SwiftDataSyncLocal(context: context, store: store, stampsURL: root.appendingPathComponent("stamps.json"))
    var state = SyncState()

    init() throws {
      store = BookStore(baseURL: root.appendingPathComponent("Books", isDirectory: true))
      container = try ModelContainer(
        for: Schema(versionedSchema: DraftTalkCurrentSchema.self), migrationPlan: DraftTalkMigrationPlan.self,
        configurations: ModelConfiguration(isStoredInMemoryOnly: true, cloudKitDatabase: .none))
    }

    func source(_ name: String, _ contents: String = "audio") throws -> URL {
      let dir = root.appendingPathComponent("source", isDirectory: true)
      try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
      let url = dir.appendingPathComponent(name)
      try contents.write(to: url, atomically: true, encoding: .utf8)
      return url
    }

    func books() throws -> [Book] { try context.fetch(FetchDescriptor<Book>()) }

    func sync(_ folder: any SyncFolder) async throws {
      state = try await SyncEngine(folder: folder, local: local).sync(state).state
    }
  }

  let text = "Schöne 👍🏽 Grüße. The baseline is never tuned."

  func bookWithComment(_ lib: Library) async throws -> Book {
    let book = try #require(
      try await lib.store.importFiles([try lib.source("Fish.m4b"), try lib.source("Fish.narration.txt", text)], into: lib.context, defaultRate: 1)
        .first)
    let offset = Array(text).firstIndex(of: "b")!
    let comment = Comment(kind: .comment, mediaTime: 3, estimatedOffset: offset, confidence: 0.7, chapter: nil, audioFile: "comment-1.m4a", duration: 2)
    comment.transcript = "weakness: never tuned"
    comment.reviewTag = "weakness"
    lib.context.insert(comment)
    comment.book = book
    try FileManager.default.createDirectory(at: lib.store.commentsURL(book), withIntermediateDirectories: true)
    try "voice".write(to: lib.store.commentsURL(book).appendingPathComponent("comment-1.m4a"), atomically: true, encoding: .utf8)
    try lib.context.save()
    return book
  }

  @Test func packageCarriesBookCommentsAndRecordings() async throws {
    let a = try Library()
    let book = try await bookWithComment(a)
    let file = a.root.appendingPathComponent("Fish.drafttalk")
    try a.store.exportPackage(book, to: file)

    let b = try Library()
    let imported = try #require(try await b.store.importFiles([file], into: b.context, defaultRate: 1).first)
    #expect(imported.id == book.id)
    let c = try #require(imported.comments.first)
    #expect(c.id == book.comments.first?.id)
    #expect(c.estimatedOffset == Array(text).firstIndex(of: "b"))  // via code points and back
    #expect(c.reviewTag == "weakness")
    #expect(try String(contentsOf: b.store.commentsURL(imported).appendingPathComponent("comment-1.m4a"), encoding: .utf8) == "voice")
    #expect(!FileManager.default.fileExists(atPath: b.store.folderURL(imported).appendingPathComponent("book.json").path))

    // the same file again adds nothing
    _ = try await b.store.importFiles([file], into: b.context, defaultRate: 1)
    #expect(try b.books().count == 1)
    #expect(try b.books().first?.comments.count == 1)
  }

  @Test func commentsTravelThroughAFolderBothWays() async throws {
    let cloud = ICloudFolder(root: FileManager.default.temporaryDirectory.appendingPathComponent("cloud-\(UUID().uuidString)", isDirectory: true))
    let mac = try Library()
    let phone = try Library()
    let book = try await bookWithComment(mac)
    try await mac.sync(cloud)
    try await phone.sync(cloud)

    let copy = try #require(try phone.books().first)
    #expect(copy.id == book.id)
    #expect(copy.comments.first?.transcript == "weakness: never tuned")
    #expect(copy.comments.first?.estimatedOffset == book.comments.first?.estimatedOffset)
    #expect(FileManager.default.fileExists(atPath: phone.store.audioURL(copy).path))

    // edited on the phone, deleted there and added: the Mac follows
    copy.comments.first?.transcript = "weakness: the baseline is never tuned"
    try phone.context.save()
    try await phone.sync(cloud)
    try await mac.sync(cloud)
    #expect(book.comments.first?.transcript == "weakness: the baseline is never tuned")

    phone.store.delete(try #require(copy.comments.first), context: phone.context)
    try await phone.sync(cloud)
    try await mac.sync(cloud)
    #expect(try mac.books().first?.comments.isEmpty == true)
  }
}
