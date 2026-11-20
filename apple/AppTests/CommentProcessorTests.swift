import MarginVoiceCore
import Foundation
import SwiftData
import Testing
@testable import MarginVoice

/// Transcription and anchoring after a comment, with stand-in speech engines.
@MainActor
struct CommentProcessorTests {
  let root = FileManager.default.temporaryDirectory.appendingPathComponent("drafttalk-tests-\(UUID().uuidString)", isDirectory: true)
  let store: BookStore
  let settings = AppSettings(defaults: UserDefaults(suiteName: "drafttalk-tests-\(UUID().uuidString)")!)
  let container: ModelContainer
  var context: ModelContext { container.mainContext }

  init() throws {
    store = BookStore(baseURL: root.appendingPathComponent("Books", isDirectory: true))
    container = try ModelContainer(
      for: Schema(versionedSchema: DraftTalkCurrentSchema.self), migrationPlan: DraftTalkMigrationPlan.self,
      configurations: ModelConfiguration(isStoredInMemoryOnly: true, cloudKitDatabase: .none))
  }

  /// A German book with narration and one recorded comment.
  func germanComment() async throws -> MarginVoice.Comment {
    let dir = root.appendingPathComponent("source", isDirectory: true)
    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    let audio = dir.appendingPathComponent("Skizze.m4b")
    let text = dir.appendingPathComponent("Skizze.narration.txt")
    try "audio".write(to: audio, atomically: true, encoding: .utf8)
    try "Die Ergebnisse zeigen, dass der WallBOT Roboter die Wand schneller reinigt als bisher. Das ist gut.\n"
      .write(to: text, atomically: true, encoding: .utf8)
    let book = try #require(try await store.importFiles([audio, text], into: context, defaultRate: 1).first)
    let comment = MarginVoice.Comment(kind: .comment, mediaTime: 0, estimatedOffset: 10, confidence: 0.3, chapter: nil, audioFile: "c.m4a")
    context.insert(comment)
    comment.book = book
    try context.save()
    return comment
  }

  @Test func transcribesInTheBookLanguageAndPinsTheComment() async throws {
    let comment = try await germanComment()
    let heard = Heard()
    let processor = CommentProcessor(
      store: store, settings: settings,
      engines: .init(
        transcribe: { _, locale, vocabulary in
          await heard.set(locale.identifier, vocabulary)
          return "Das ist zu lang. Weiter."
        },
        refine: { _, _, _, _ in .init(offset: 42, confidence: 0.9) }))

    await processor.process(comment)
    #expect(await heard.locale == "de-DE")
    #expect(await heard.vocabulary.contains("WallBOT"))
    #expect(comment.transcript == "Das ist zu lang.")
    #expect(comment.transcriptStatus == .done)
    #expect(comment.refinedOffset == 42 && comment.confidence == 0.9)
  }

  @Test func aSpokenTagSortsTheComment() async throws {
    let comment = try await germanComment()
    let processor = CommentProcessor(
      store: store, settings: settings,
      engines: .init(transcribe: { _, _, _ in "Schwäche: die Baseline wird nie optimiert. Weiter." }, refine: { _, _, _, _ in nil }))
    await processor.process(comment)
    #expect(comment.transcript == "Die Baseline wird nie optimiert.")
    #expect(comment.tag == .weakness)
  }

  @Test func aFixedCommentLanguageWins() async throws {
    let comment = try await germanComment()
    settings.commentLanguage = .english
    let heard = Heard()
    let processor = CommentProcessor(
      store: store, settings: settings,
      engines: .init(transcribe: { _, locale, _ in await heard.set(locale.identifier, []); return "Shorter." }, refine: { _, _, _, _ in nil }))
    await processor.process(comment)
    #expect(await heard.locale == "en-US")
  }

  @Test func failureCanBeRetried() async throws {
    let comment = try await germanComment()
    let failing = CommentProcessor(
      store: store, settings: settings,
      engines: .init(transcribe: { _, _, _ in throw CancellationError() }, refine: { _, _, _, _ in nil }))
    await failing.process(comment)
    #expect(comment.transcriptStatus == .failed)
  }

  @Test func aCommentDeletedMeanwhileIsLeftAlone() async throws {
    let comment = try await germanComment()
    let gate = Gate()
    let processor = CommentProcessor(
      store: store, settings: settings,
      engines: .init(transcribe: { _, _, _ in await gate.pass(); return "Too late." }, refine: { _, _, _, _ in .init(offset: 1, confidence: 1) }))
    let running = Task { await processor.process(comment) }
    await gate.waitUntilReached()
    context.delete(comment)
    try context.save()
    await gate.open()
    await running.value  // would crash writing to the deleted comment
  }

  @Test func runsEachCommentOnceAtATime() async throws {
    let comment = try await germanComment()
    let gate = Gate()
    let processor = CommentProcessor(
      store: store, settings: settings,
      engines: .init(transcribe: { _, _, _ in await gate.pass(); return "Once." }, refine: { _, _, _, _ in nil }))
    let first = Task { await processor.process(comment) }
    await gate.waitUntilReached()
    await processor.process(comment)  // returns at once: already running
    await gate.open()
    await first.value
    #expect(await gate.passes == 1)
  }

  @Test func transcribingAgainRedoesARunningComment() async throws {
    let comment = try await germanComment()
    let gate = Gate()
    let processor = CommentProcessor(
      store: store, settings: settings,
      engines: .init(transcribe: { _, _, _ in await gate.pass(); return "Again." }, refine: { _, _, _, _ in nil }))
    let first = Task { await processor.process(comment) }
    await gate.waitUntilReached()
    let again = Task { await processor.transcribeAgain(try #require(comment.book)) }
    await gate.open()
    try await again.value
    await first.value
    #expect(await gate.passes == 2)
    #expect(comment.transcriptStatus == .done)
  }
}

private actor Heard {
  var locale = ""
  var vocabulary: [String] = []
  func set(_ locale: String, _ vocabulary: [String]) {
    self.locale = locale
    self.vocabulary = vocabulary
  }
}

/// Holds a stand-in engine until the test lets it go on.
private actor Gate {
  private(set) var passes = 0
  private var isOpen = false
  private var waiting: [CheckedContinuation<Void, Never>] = []
  private var reached: [CheckedContinuation<Void, Never>] = []

  func pass() async {
    passes += 1
    reached.forEach { $0.resume() }
    reached = []
    guard !isOpen else { return }
    await withCheckedContinuation { waiting.append($0) }
  }

  func waitUntilReached() async {
    guard passes == 0 else { return }
    await withCheckedContinuation { reached.append($0) }
  }

  func open() {
    isOpen = true
    waiting.forEach { $0.resume() }
    waiting = []
  }
}
