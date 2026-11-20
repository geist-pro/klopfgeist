import Foundation

/// The review category a comment opens with: "weakness: the baseline is never tuned".
/// Spoken in English or German, stripped from the transcript, and used to sort the
/// comments into a review draft.
public enum ReviewTag: String, CaseIterable, Sendable, Codable {
  case summary, strength, weakness, question, minor

  /// Accepted opening words per language, normalized like commands (`TextSegmentation.normalize`).
  public static func phrases(_ language: VoiceCommand.Language) -> [ReviewTag: [String]] {
    switch language {
    case .english:
      return [
        .summary: ["summary"],
        .strength: ["strength", "strengths"],
        .weakness: ["weakness", "weaknesses"],
        .question: ["question", "questions"],
        .minor: ["minor", "minor issue", "minor issues", "nitpick", "nit", "typo"],
      ]
    case .german:
      return [
        .summary: ["zusammenfassung"],
        .strength: ["starke", "starken"],
        .weakness: ["schwache", "schwachen"],
        .question: ["frage", "fragen"],
        .minor: ["kleinigkeit", "kleinigkeiten", "minor", "detail"],
      ]
    }
  }

  public func displayName(_ language: VoiceCommand.Language) -> String {
    switch (self, language) {
    case (.summary, .english): "Summary"
    case (.strength, .english): "Strength"
    case (.weakness, .english): "Weakness"
    case (.question, .english): "Question"
    case (.minor, .english): "Minor"
    case (.summary, .german): "Zusammenfassung"
    case (.strength, .german): "Stärke"
    case (.weakness, .german): "Schwäche"
    case (.question, .german): "Frage"
    case (.minor, .german): "Kleinigkeit"
    }
  }

  /// SF Symbol / icon name used by the apps.
  public var symbol: String {
    switch self {
    case .summary: "text.alignleft"
    case .strength: "hand.thumbsup"
    case .weakness: "exclamationmark.triangle"
    case .question: "questionmark.circle"
    case .minor: "pencil.line"
    }
  }

  private static let leadIns: Set<String> = ["a", "an", "oh", "hey"]
  /// Words that make "a weakness …" part of a sentence rather than a tag.
  private static let continuations: Set<String> = ["of", "is", "was", "that", "here", "in", "for"]

  /// The tag said at the start of a transcript, and the transcript without it.
  /// "Weakness, the baseline is missing" → (.weakness, "The baseline is missing").
  /// Fillers before the tag ("um, weakness…") are dropped too, and so is a lead-in the
  /// recognizer hears in the beep ("a weakness …"), unless the sentence goes on with the
  /// tag word in it ("a weakness of the method is…"). No tag: the text comes back as is.
  public static func extract(_ transcript: String) -> (tag: ReviewTag?, text: String) {
    let words = transcript.split(whereSeparator: \.isWhitespace).map(String.init)
    func token(_ index: Int) -> String { index < words.count ? TextSegmentation.tokens(words[index]).first ?? "" : "" }
    var i = 0
    var ledIn = false
    while i < words.count, VoiceCommand.fillers.contains(token(i)) || leadIns.contains(token(i)) {
      ledIn = ledIn || leadIns.contains(token(i))
      i += 1
    }
    for n in [2, 1] where i + n <= words.count {
      let phrase = TextSegmentation.tokens(words[i..<(i + n)].joined(separator: " ")).joined(separator: " ")
      guard !phrase.isEmpty else { continue }
      for language in VoiceCommand.Language.allCases {
        for (tag, list) in phrases(language) where list.contains(phrase) {
          if ledIn, continuations.contains(token(i + n)) { return (nil, transcript) }
          var rest = words[(i + n)...].joined(separator: " ")
          while let first = rest.first, ":,.;-–—".contains(first) { rest.removeFirst() }
          rest = rest.trimmingCharacters(in: .whitespaces)
          if let first = rest.first { rest = first.uppercased() + rest.dropFirst() }
          return (tag, rest)
        }
      }
    }
    return (nil, transcript)
  }
}
