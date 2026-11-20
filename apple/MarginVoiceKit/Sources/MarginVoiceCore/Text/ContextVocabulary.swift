import Foundation

/// Words from the book around a comment that a speech recognizer is unlikely to
/// know or to spell right: names, acronyms, product and project names, compounds.
/// Passed as contextual strings so "WallBOT" isn't heard as "Wall bot".
public enum ContextVocabulary {
  public static func terms(in text: String, limit: Int = 100) -> [String] {
    var counts: [String: Int] = [:]
    var order: [String] = []
    for token in text.split(whereSeparator: { !($0.isLetter || $0.isNumber || $0 == "-") }) {
      let word = String(token).trimmingCharacters(in: CharacterSet(charactersIn: "-"))
      guard isDistinctive(word) else { continue }
      if counts[word] == nil { order.append(word) }
      counts[word, default: 0] += 1
    }
    // most frequent first, then in reading order
    let rank = Dictionary(uniqueKeysWithValues: order.enumerated().map { ($1, $0) })
    return order.sorted { (counts[$0]!, rank[$1]!) > (counts[$1]!, rank[$0]!) }.prefix(limit).map { $0 }
  }

  static func isDistinctive(_ word: String) -> Bool {
    let letters = word.filter(\.isLetter)
    guard letters.count >= 2 else { return false }
    let uppercase = letters.filter(\.isUppercase).count
    if uppercase == letters.count { return true }  // acronyms: KI, HAFEN
    if word.contains(where: \.isNumber) { return true }  // Robots4CleanTech, H2
    if word.dropFirst().contains(where: \.isUppercase) { return true }  // WallBOT, RoboHand
    if word.contains("-") { return word.count >= 6 }  // Reinigungs-Roboter
    // names and German nouns, long compounds
    return (word.first?.isUppercase == true && word.count >= 8) || word.count >= 12
  }
}

extension ContextVocabulary {
  /// Terms from the text around `offset` (about a minute of narration either side)
  /// plus the title, which names the project more often than not.
  public static func terms(around offset: Int, in text: NarrationText, title: String, radius: Int = 1500, limit: Int = 100) -> [String] {
    let lower = max(0, min(text.count, offset - radius))
    let upper = max(lower, min(text.count, offset + radius))
    let titleTerms = terms(in: title)
    let nearby = terms(in: String(text.characters[lower..<upper]), limit: limit).filter { !titleTerms.contains($0) }
    return Array((titleTerms + nearby).prefix(limit))
  }
}
