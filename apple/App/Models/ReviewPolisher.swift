import MarginVoiceCore
import Foundation
import FoundationModels

/// Rewrites spoken review notes into review prose with the on-device model. The
/// spoken words are never replaced: the review draft shows the prose with the
/// transcript underneath, and a note the model fails on keeps its transcript.
struct ReviewPolisher {
  /// One note → one or two sentences of review prose, in the note's language.
  var polish: @Sendable (_ note: String, _ language: VoiceCommand.Language) async throws -> String
  /// The summary notes and the paper's title → one summary paragraph.
  var summarize: @Sendable (_ notes: [String], _ title: String, _ language: VoiceCommand.Language) async throws -> String

  struct Result {
    var polished: [UUID: String] = [:]
    var summary: String?
  }

  /// Apple Intelligence is on and the model is downloaded on this device.
  static var isAvailable: Bool { SystemLanguageModel.default.availability == .available }

  static let live = ReviewPolisher(
    polish: { note, language in
      let session = LanguageModelSession(instructions: instructions(language))
      let lead = language == .german ? "Schreibe diese Gutachter-Notiz um:" : "Rewrite this reviewer note:"
      return try await session.respond(to: "\(lead)\n\n\(note)").content.trimmingCharacters(in: .whitespacesAndNewlines)
    },
    summarize: { notes, title, language in
      let session = LanguageModelSession(instructions: instructions(language))
      let lead = language == .german
        ? "Fasse diese Notizen zum Beitrag „\(title)“ zu einem Absatz zusammen, der den Beitrag des Papiers beschreibt:"
        : "Combine these notes about the paper “\(title)” into one paragraph describing the paper's contribution:"
      return try await session.respond(to: "\(lead)\n\n" + notes.map { "- \($0)" }.joined(separator: "\n")).content
        .trimmingCharacters(in: .whitespacesAndNewlines)
    })

  static func instructions(_ language: VoiceCommand.Language) -> String {
    switch language {
    case .english:
      """
      You tighten a reviewer's spoken note about a scientific paper into one or two sentences of review prose.
      Keep the meaning and every fact; add nothing that is not in the note. Keep technical terms as they are.
      Write about the paper and the authors in the third person, in the language of the note.
      Output only the rewritten text, without quotes or a preamble.
      """
    case .german:
      """
      Du formst die gesprochene Notiz eines Gutachters zu einem wissenschaftlichen Beitrag in ein bis zwei Sätze Gutachtenprosa um.
      Behalte Sinn und alle Fakten; füge nichts hinzu, was nicht in der Notiz steht. Fachbegriffe bleiben unverändert.
      Schreibe über den Beitrag und die Autoren in der dritten Person, in der Sprache der Notiz.
      Gib nur den umformulierten Text aus, ohne Anführungszeichen oder Einleitung.
      """
    }
  }

  /// Polishes every transcribed comment and drafts the summary from the summary notes.
  /// A note the model fails on is left out of the result (the draft shows its transcript);
  /// cancellation returns what is done so far. `progress` gets (done, total).
  @MainActor
  func run(_ comments: [CommentRecord], title: String, language: VoiceCommand.Language, progress: (Int, Int) -> Void) async -> Result {
    let notes = comments.filter { $0.kind == .comment && !$0.transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    let summaries = notes.filter { $0.tag == .summary }
    let total = notes.count + (summaries.isEmpty ? 0 : 1)
    var result = Result()
    var done = 0
    progress(done, total)
    for note in notes {
      if Task.isCancelled { return result }
      if let text = try? await polish(note.transcript, language), !text.isEmpty { result.polished[note.id] = text }
      done += 1
      progress(done, total)
    }
    if !summaries.isEmpty, !Task.isCancelled {
      result.summary = try? await summarize(summaries.map(\.transcript), title, language)
      progress(total, total)
    }
    return result
  }
}
