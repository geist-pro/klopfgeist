import MarginVoiceCore
import Foundation
import SwiftData
import Testing
@testable import MarginVoice

/// BookStore, the models and exports against an in-memory store and a temporary books folder.
@MainActor
struct LibraryTests {
  let root = FileManager.default.temporaryDirectory.appendingPathComponent("drafttalk-tests-\(UUID().uuidString)", isDirectory: true)
  let store: BookStore
  let container: ModelContainer
  var context: ModelContext { container.mainContext }

  init() throws {
    store = BookStore(baseURL: root.appendingPathComponent("Books", isDirectory: true))
    container = try ModelContainer(
      for: Schema(versionedSchema: DraftTalkCurrentSchema.self), migrationPlan: DraftTalkMigrationPlan.self,
      configurations: ModelConfiguration(isStoredInMemoryOnly: true, cloudKitDatabase: .none))
  }

  /// Source files to import; the audio is a placeholder (duration 0), which import accepts.
  func source(_ name: String, _ contents: String = "audio") throws -> URL {
    let dir = root.appendingPathComponent("source", isDirectory: true)
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    let url = dir.appendingPathComponent(name)
    try contents.write(to: url, atomically: true, encoding: .utf8)
    return url
  }

  func bookFolders() throws -> [String] {
    try FileManager.default.contentsOfDirectory(atPath: store.baseURL.path)
  }

  func books() throws -> [Book] { try context.fetch(FetchDescriptor<Book>()) }

  @Test func importsAudioWithNarrationAndTiming() async throws {
    let files = [
      try source("Projekt_Skizze.m4b"),
      try source("Projekt_Skizze.narration.txt", "Die Ergebnisse zeigen, dass der Roboter die Wand schneller reinigt als bisher. Das ist gut."),
      try source("Projekt_Skizze.timing.json", "{}"),
    ]
    let imported = try await store.importFiles(files, into: context, defaultRate: 1.2)
    #expect(imported.count == 1)
    let book = try #require(try books().first)
    #expect(book.title == "Projekt Skizze")
    #expect(book.language == "de")
    #expect(book.rate == 1.2)
    #expect(book.textFile == "narration.txt" && book.timingFile == "timing.json")
    #expect(FileManager.default.fileExists(atPath: store.audioURL(book).path))
  }

  @Test func textWithoutAudioIsRejected() async throws {
    await #expect(throws: BookStore.ImportError.self) {
      _ = try await store.importFiles([try source("notes.txt", "Hello.")], into: context, defaultRate: 1)
    }
    #expect(try books().isEmpty)
    #expect(try bookFolders().isEmpty)
  }

  @Test func failedImportLeavesNothingBehind() async throws {
    let good = try source("a.m4b")
    let missing = root.appendingPathComponent("source/b.m4b")
    await #expect(throws: (any Error).self) {
      _ = try await store.importFiles([good, missing], into: context, defaultRate: 1)
    }
    #expect(try books().isEmpty)
    #expect(try bookFolders().isEmpty)
  }

  @Test func deletingABookRemovesItsFolder() async throws {
    let book = try #require(try await store.importFiles([try source("a.m4b")], into: context, defaultRate: 1).first)
    let folder = store.folderURL(book)
    store.delete(book, context: context)
    #expect(try books().isEmpty)
    #expect(!FileManager.default.fileExists(atPath: folder.path))
  }

  @Test func deletingACommentRemovesItsRecording() async throws {
    let book = try #require(try await store.importFiles([try source("a.m4b")], into: context, defaultRate: 1).first)
    try FileManager.default.createDirectory(at: store.commentsURL(book), withIntermediateDirectories: true)
    let audio = store.commentsURL(book).appendingPathComponent("c.m4a")
    try "x".write(to: audio, atomically: true, encoding: .utf8)
    let comment = Comment(kind: .comment, mediaTime: 3, estimatedOffset: 0, confidence: 0.8, chapter: nil, audioFile: "c.m4a")
    context.insert(comment)
    comment.book = book
    try context.save()

    store.delete(comment, context: context)
    #expect(book.comments.isEmpty)
    #expect(!FileManager.default.fileExists(atPath: audio.path))
    #expect(FileManager.default.fileExists(atPath: store.audioURL(book).path))
  }

  @Test func leftoversAreRemovedButCommentsKept() async throws {
    let book = try #require(try await store.importFiles([try source("a.m4b")], into: context, defaultRate: 1).first)
    let (_, crashed) = try store.makeBookFolder()
    let (_, withComments) = try store.makeBookFolder()
    try FileManager.default.createDirectory(at: withComments.appendingPathComponent("comments"), withIntermediateDirectories: true)
    try "x".write(to: withComments.appendingPathComponent("comments/c.m4a"), atomically: true, encoding: .utf8)

    store.removeLeftovers(context: context)
    #expect(Set(try bookFolders()) == [book.folder, withComments.lastPathComponent])
    #expect(!FileManager.default.fileExists(atPath: crashed.path))
  }

  @Test func commentRecordUsesRefinedAndNudgedOffsets() {
    let text = NarrationText("One a. Two b. Three c.\n")
    let comment = Comment(kind: .bookmark, mediaTime: 1, estimatedOffset: 2, confidence: 0.3, chapter: nil, audioFile: nil)
    comment.endEstimatedOffset = 12
    comment.refinedOffset = text.sentences[0].range.upperBound
    let refined = comment.record(text: text)
    #expect(refined.textOffset == text.sentences[0].range.upperBound)
    #expect(refined.endTextOffset == 12 + text.sentences[0].range.upperBound - 2)

    comment.sentenceNudge = 1
    let nudged = comment.record(text: text)
    #expect(nudged.textOffset == text.sentences[1].range.upperBound)
    #expect(nudged.confidence == 1)
    // the passage end moves with the nudged start
    #expect(nudged.endTextOffset == 12 + text.sentences[1].range.upperBound - 2)
  }

  @Test func exportWritesSelectedFiles() async throws {
    let book = try #require(try await store.importFiles([try source("My Draft.m4b")], into: context, defaultRate: 1).first)
    let comment = Comment(kind: .comment, mediaTime: 65, estimatedOffset: nil, confidence: 0, chapter: nil, audioFile: nil)
    comment.transcript = "Shorter, please."
    context.insert(comment)
    comment.book = book

    var options = ExportBuilder.Options()
    options.csv = true
    let urls = try ExportBuilder.build(book: book, content: nil, store: store, options: options)
    // no narration text, so no annotated text
    #expect(Set(urls.map(\.lastPathComponent)) == ["My-Draft-comments.md", "My-Draft-comments.csv"])
    let list = try String(contentsOf: try #require(urls.first { $0.pathExtension == "md" }), encoding: .utf8)
    #expect(list.contains("Shorter, please."))

    // the review draft, with a reworded note: the prose first, the spoken words underneath
    comment.tag = .weakness
    var draftOptions = ExportBuilder.Options(annotatedText: false, commentList: false)
    draftOptions.reviewDraft = true
    let draft = try ExportBuilder.build(
      book: book, content: nil, store: store, options: draftOptions, language: .english, polished: [comment.id: "The text should be shorter."])
    #expect(draft.map(\.lastPathComponent) == ["My-Draft-review.md"])
    let text = try String(contentsOf: try #require(draft.first), encoding: .utf8)
    #expect(text.contains("## Weaknesses\n\n- The text should be shorter. (1:05)\n  > said: Shorter, please.\n"))
  }
}
