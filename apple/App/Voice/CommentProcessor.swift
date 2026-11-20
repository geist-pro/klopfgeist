import MarginVoiceCore
import Foundation
import SwiftData
import os

/// After a comment is saved: transcribes its recording in the comment language,
/// primed with the terms of the passage, and pins it to the exact place in the
/// text. Runs each comment once at a time, survives the comment being deleted
/// meanwhile, and picks up transcriptions an app kill cut off.
@MainActor
final class CommentProcessor {
  /// The speech work, replaceable in tests.
  struct Engines: Sendable {
    var transcribe: @Sendable (_ url: URL, _ locale: Locale, _ vocabulary: [String]) async throws -> String = {
      try await FileTranscriber.transcribe(url: $0, locale: $1, vocabulary: $2)
    }
    var refine: @Sendable (_ bookAudio: URL, _ anchor: Double, _ content: BookContent, _ locale: Locale) async throws -> AnchorRefiner.Refinement? = {
      try await AnchorRefiner.refine(bookAudio: $0, anchor: $1, content: $2, locale: $3)
    }
  }

  private static let log = Logger.drafttalk("comments")

  /// Saves after each step ("transcript"); the session reports failures.
  var save: (String) -> Void = { _ in }
  var note: (String, Bool) -> Void = { _, _ in }

  private let store: BookStore
  private let settings: AppSettings
  private let engines: Engines
  /// Comments being transcribed and anchored right now.
  private var running: [UUID: Task<Void, Never>] = [:]
  /// Running comments to transcribe once more when they finish (the language changed).
  private var rerun: Set<UUID> = []

  init(store: BookStore, settings: AppSettings, engines: Engines = Engines()) {
    self.store = store
    self.settings = settings
    self.engines = engines
  }

  /// Nothing is being transcribed or anchored.
  var isIdle: Bool { running.isEmpty }

  /// Transcribes the comment and pins it to the text. Safe to call again (retry).
  func process(_ comment: Comment) async {
    let id = comment.id
    guard comment.book != nil, running[id] == nil else { return }
    let task = Task {
      repeat {
        rerun.remove(id)
        await run(comment)
      } while rerun.contains(id) && Self.exists(comment)
      running[id] = nil
    }
    running[id] = task
    await task.value
  }

  private func run(_ comment: Comment) async {
    guard let book = comment.book else { return }
    let content = await store.content(for: book)
    // deleted (or its book) while the text loaded
    guard Self.exists(comment) else { return }
    if comment.kind == .comment, let url = store.audioURL(comment, in: book) {
      comment.transcriptStatus = .pending
      let language = settings.commentLanguage.resolved(bookLanguage: book.language)
      // the passage commented on primes the recognizer with its names and terms
      let vocabulary = content.map { c in
        ContextVocabulary.terms(around: comment.textOffset(in: c.text) ?? c.offset(at: comment.mediaTime), in: c.text, title: book.title)
      } ?? ContextVocabulary.terms(in: book.title)
      do {
        let raw = try await engines.transcribe(url, language.locale, vocabulary)
        // deleted while it was being transcribed
        guard Self.exists(comment) else { return }
        let stripped = VoiceCommand.strippingTrailingCommand(raw, languages: Set(VoiceCommand.Language.allCases))
        // "weakness: …" sorts the comment into the review draft; the tag word leaves the transcript
        let (tag, text) = ReviewTag.extract(stripped)
        comment.transcript = text
        if let tag { comment.tag = tag }
        comment.transcriptStatus = .done
      } catch {
        guard Self.exists(comment) else { return }
        comment.transcriptStatus = .failed
        Self.log.error("transcribe: \(error.localizedDescription)")
      }
      save("transcript")
    }
    guard comment.refinedOffset == nil, let content else { return }
    let locale = VoiceCommand.Language(code: book.language).locale
    do {
      if let r = try await engines.refine(store.audioURL(book), comment.mediaTime, content, locale), r.confidence >= 0.4,
        Self.exists(comment)
      {
        comment.refinedOffset = r.offset
        comment.confidence = max(comment.confidence, r.confidence)
        save("refined anchor")
      }
    } catch {
      Self.log.error("refine: \(error.localizedDescription)")
    }
  }

  /// Comments still marked as being transcribed when the app last quit.
  func resumeCutOff(in book: Book) {
    for comment in book.comments where comment.transcriptStatus == .pending && running[comment.id] == nil {
      Task { await process(comment) }
    }
  }

  /// Transcribes every recorded comment of a book again (after changing the
  /// comment language). Replaces the transcripts, including edited ones.
  func transcribeAgain(_ book: Book) async {
    let comments = book.sortedComments.filter { $0.kind == .comment && $0.audioFile != nil }
    for comment in comments {
      comment.transcriptStatus = .pending
      comment.tag = nil
    }
    for comment in comments {
      // one already running started in the old language: run it once more after
      if let task = running[comment.id] {
        rerun.insert(comment.id)
        await task.value
      } else {
        await process(comment)
      }
    }
    note("Transcribed \(comments.count) comments again", true)
  }

  static func exists(_ model: some PersistentModel) -> Bool {
    model.modelContext != nil && !model.isDeleted
  }
}
