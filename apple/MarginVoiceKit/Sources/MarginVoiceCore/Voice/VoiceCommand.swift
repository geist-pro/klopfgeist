import Foundation

public enum VoiceCommand: String, CaseIterable, Sendable, Codable {
  case stop, resume, cancel, back, again, mark, faster, slower

  public enum Language: String, CaseIterable, Sendable, Codable {
    case english = "en", german = "de"
  }

  /// Accepted phrases per language, already normalized (`TextSegmentation.normalize`).
  public static func phrases(_ language: Language) -> [VoiceCommand: [String]] {
    switch language {
    case .english:
      return [
        .stop: ["stop", "note", "comment"],
        .resume: ["continue", "go on", "resume", "play"],
        .cancel: ["cancel", "discard", "delete that"],
        .back: ["back", "go back", "rewind"],
        .again: ["again", "repeat"],
        .mark: ["bookmark", "mark", "bookmark this", "bookmark that", "mark this"],
        .faster: ["faster"],
        .slower: ["slower"],
      ]
    case .german:
      return [
        .stop: ["stopp", "stop", "notiz", "kommentar"],
        .resume: ["weiter", "weiter machen", "weitermachen", "fortsetzen"],
        .cancel: ["abbrechen", "verwerfen", "loschen"],
        .back: ["zuruck", "zuruckspulen"],
        .again: ["nochmal", "noch mal", "wiederholen"],
        .mark: ["lesezeichen", "markierung", "markieren", "lesezeichen setzen", "merken"],
        .faster: ["schneller"],
        .slower: ["langsamer"],
      ]
    }
  }

  static let fillers: Set<String> = ["please", "bitte", "okay", "ok", "uh", "um", "ah", "ahm", "ah"]

  /// Parses a standalone utterance. Only short utterances that consist of a command
  /// phrase (plus fillers like "please") count; anything longer is speech, not a command.
  public static func parse(_ utterance: String, languages: Set<Language>) -> VoiceCommand? {
    let tokens = TextSegmentation.tokens(utterance).filter { !fillers.contains($0) }
    guard !tokens.isEmpty, tokens.count <= 2 else { return nil }
    let phrase = tokens.joined(separator: " ")
    for language in Language.allCases where languages.contains(language) {
      for (command, list) in phrases(language) where list.contains(phrase) {
        return command
      }
    }
    return nil
  }

  /// Removes a closing "continue" / "weiter" (or "cancel") that ended the recording
  /// and was captured at the end of the comment.
  public static func strippingTrailingCommand(_ text: String, languages: Set<Language>) -> String {
    var words = text.split(whereSeparator: \.isWhitespace).map(String.init)
    for _ in 0..<2 {
      var stripped = false
      for n in [2, 1] where words.count >= n {
        let tail = words.suffix(n).joined(separator: " ")
        if let c = parse(tail, languages: languages), c == .resume || c == .cancel {
          words.removeLast(n)
          stripped = true
          break
        }
      }
      if !stripped { break }
    }
    var result = words.joined(separator: " ")
    while let last = result.last, last == "," || last == ";" || last == "-" { result.removeLast() }
    return result.trimmingCharacters(in: .whitespaces)
  }
}
