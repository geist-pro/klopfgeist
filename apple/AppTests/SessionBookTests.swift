import Foundation
import MarginVoiceCore
import MarginVoiceSession
import SwiftData
import Testing
@testable import MarginVoice

/// Comments, bookmarks and passages written through the `ReviewBook` port, as the
/// review session does, against an in-memory library.
@MainActor
struct SessionBookTests {
  let root = FileManager.default.temporaryDirectory.appendingPathComponent("marginvoice-tests-\(UUID().uuidString)", isDirectory: true)
  let store: BookStore
  let settings = AppSettings(defaults: UserDefaults(suiteName: "marginvoice-tests-\(UUID().uuidString)")!)
  let container: ModelContainer
  var context: ModelContext { container.mainContext }

  static let narration =
    "The results show that the robot cleans the wall faster than before. That is good news for everyone. "
    + "A third sentence follows here, and it is a little longer than the others. The end."

  init() throws {
    store = BookStore(baseURL: root.appendingPathComponent("Books", isDirectory: true))
    container = try ModelContainer(
      for: Schema(versionedSchema: DraftTalkCurrentSchema.self), migrationPlan: DraftTalkMigrationPlan.self,
      configurations: ModelConfiguration(isStoredInMemoryOnly: true, cloudKitDatabase: .none))
  }

  /// A book with narration; the processor's speech engines are stand-ins.
  func makeBook(transcript: String = "Needs a citation. Continue.") async throws -> (SessionBook, any ReviewBook) {
    let dir = root.appendingPathComponent("source", isDirectory: true)
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    let audio = dir.appendingPathComponent("Paper.m4b")
    let text = dir.appendingPathComponent("Paper.narration.txt")
    try "audio".write(to: audio, atomically: true, encoding: .utf8)
    try Self.narration.write(to: text, atomically: true, encoding: .utf8)
    let book = try #require(try await store.importFiles([audio, text], into: context, defaultRate: 1.2).first)
    book.duration = 100
    book.chapters = [ChapterMark(title: "Introduction", start: 0, end: 50), ChapterMark(title: "Results", start: 50, end: 100)]
    let processor = CommentProcessor(
      store: store, settings: settings, engines: .init(transcribe: { _, _, _ in transcript }, refine: { _, _, _, _ in nil }))
    box.processor = processor
    processor.save = { [context] what in context.saveLogged(what) }
    let content = BookContent(text: NarrationText(Self.narration), chapters: book.chapters, duration: 100, sidecar: nil)
    let sessionBook = SessionBook(book: book, content: content, context: context, store: store, processor: processor)
    return (sessionBook, sessionBook)
  }

  /// The processor of the book under test.
  final class Box { var processor: CommentProcessor? }
  let box = Box()

  /// Post-processing runs in tasks of its own: wait for them, so none outlives the test's library.
  private func processed() async {
    for _ in 0..<5 { await Task.yield() }
    for _ in 0..<300 where box.processor?.isIdle == false { try? await Task.sleep(for: .milliseconds(10)) }
    for _ in 0..<5 { await Task.yield() }
  }

  private func settle(_ condition: () -> Bool) async {
    for _ in 0..<200 where !condition() { try? await Task.sleep(for: .milliseconds(10)) }
  }

  @Test func describesTheBookToTheSession() async throws {
    let (wrapped, book) = try await makeBook()
    #expect(book.title == wrapped.book.title)
    #expect(book.audioURL == store.audioURL(wrapped.book))
    #expect(book.rate == 1.2)
    #expect(book.position == 0)
    #expect(book.chapterTitle(at: 10) == "Introduction")
    #expect(book.chapterTitle(at: 60) == "Results")
    #expect(book.savePosition(42))
    #expect(wrapped.book.position == 42)
    #expect(book.position == 42)
    book.saveRate(1.5)
    #expect(wrapped.book.rate == 1.5)
  }

  @Test func narrationAroundATime() async throws {
    let (wrapped, book) = try await makeBook()
    let recent = try #require(book.recentText(before: 50, seconds: 5))
    #expect(!recent.isEmpty)
    #expect(Self.narration.contains(recent))
    let start = try #require(book.sentenceStart(at: 50))
    #expect(start <= 50)
    // without the text (still loading) there is nothing to say
    wrapped.content = nil
    #expect(book.recentText(before: 50, seconds: 5) == nil)
    #expect(book.sentenceStart(at: 50) == nil)
  }

  @Test func aCommentIsStoredLikeTheArchivedAppDid() async throws {
    let (wrapped, book) = try await makeBook()
    let url = try #require(book.newRecordingURL())
    #expect(url.deletingLastPathComponent() == store.commentsURL(wrapped.book))
    #expect(url.lastPathComponent.hasPrefix("comment-") && url.pathExtension == "m4a")
    try "sound".write(to: url, atomically: true, encoding: .utf8)

    #expect(book.addComment(at: 60, audio: url, duration: 4.5))
    let comment = try #require(wrapped.book.comments.first)
    #expect(wrapped.book.comments.count == 1)
    #expect(comment.kind == .comment)
    #expect(comment.mediaTime == 60)
    #expect(comment.duration == 4.5)
    #expect(comment.audioFile == url.lastPathComponent)
    #expect(comment.chapter == "Results")
    // offset from the time map; a chapter-based map is approximate
    #expect(comment.estimatedOffset == wrapped.content?.offset(at: 60))
    #expect(comment.confidence == 0.3)
    #expect(store.audioURL(comment, in: wrapped.book) == url)

    // the processor transcribes it and strips the closing command
    await settle { comment.transcriptStatus == .done }
    #expect(comment.transcript == "Needs a citation.")
    #expect(try context.fetch(FetchDescriptor<MarginVoice.Comment>()).count == 1)
    await processed()
  }

  @Test func aDiscardedRecordingIsRemoved() async throws {
    let (wrapped, book) = try await makeBook()
    let url = try #require(book.newRecordingURL())
    try "sound".write(to: url, atomically: true, encoding: .utf8)
    book.discardRecording(url)
    #expect(!FileManager.default.fileExists(atPath: url.path))
    #expect(wrapped.book.comments.isEmpty)
  }

  @Test func aBookmarkAndThenAPassage() async throws {
    let (wrapped, book) = try await makeBook()
    #expect(book.addBookmark(at: 20))
    let bookmark = try #require(wrapped.book.comments.first)
    #expect(bookmark.kind == .bookmark)
    #expect(bookmark.mediaTime == 20)
    #expect(bookmark.audioFile == nil)
    #expect(bookmark.transcriptStatus == .none)
    #expect(!bookmark.isPassage)
    #expect(bookmark.chapter == "Introduction")

    #expect(book.closeBookmark(at: 35) == .closed)
    #expect(wrapped.book.comments.count == 1)
    #expect(bookmark.isPassage)
    #expect(bookmark.endMediaTime == 35)
    #expect(bookmark.endEstimatedOffset == wrapped.content?.offset(at: 35))

    // nothing is open any more: a stray close changes nothing
    #expect(book.closeBookmark(at: 80) == .missing)
    #expect(bookmark.endMediaTime == 35)
    await processed()
  }

  @Test func closingExtendsTheBookmarkAddedLast() async throws {
    let (wrapped, book) = try await makeBook()
    #expect(book.addBookmark(at: 10))
    #expect(book.addBookmark(at: 40))
    #expect(book.closeBookmark(at: 55) == .closed)
    let sorted = wrapped.book.sortedComments
    #expect(sorted.map(\.mediaTime) == [10, 40])
    #expect(sorted[0].endMediaTime == nil)
    #expect(sorted[1].endMediaTime == 55)
    await processed()
  }

  @Test func aDeletedBookmarkIsNotExtended() async throws {
    let (wrapped, book) = try await makeBook()
    #expect(book.addBookmark(at: 10))
    let bookmark = try #require(wrapped.book.comments.first)
    context.delete(bookmark)
    try context.save()
    #expect(book.closeBookmark(at: 30) == .missing)
    #expect(try context.fetch(FetchDescriptor<MarginVoice.Comment>()).isEmpty)
    await processed()
  }

  @Test func withoutTextACommentHasNoOffset() async throws {
    let (wrapped, book) = try await makeBook()
    wrapped.content = nil
    #expect(book.addBookmark(at: 12))
    let bookmark = try #require(wrapped.book.comments.first)
    #expect(bookmark.estimatedOffset == nil)
    #expect(bookmark.confidence == 0)
    await processed()
  }

  @Test func settingsReachTheSession() {
    settings.voiceCommandsEnabled = false
    settings.commandLanguage = .german
    settings.useHeadsetMicrophone = true
    settings.reactionOffset = 2.5
    settings.resumeRewind = 4
    settings.endCommentOnSilence = true
    settings.silenceTimeout = 9
    settings.stopListeningAfter = 15
    let s = SessionHost.sessionSettings(settings)
    #expect(!s.voiceCommandsEnabled)
    #expect(s.commandLanguage == .german)
    #expect(s.useHeadsetMicrophone)
    #expect(s.reactionOffset == 2.5)
    #expect(s.resumeRewind == 4)
    #expect(s.endCommentOnSilence)
    #expect(s.silenceTimeout == 9)
    #expect(s.stopListeningAfter == 15)
  }
}
