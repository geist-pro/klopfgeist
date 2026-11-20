import Foundation

/// The narration text written by doc2audio next to the audiobook, parsed into
/// paragraphs, sentences, words and chapter headings with character offsets.
///
/// All offsets are indices into `characters` (Swift `Character`s), which keeps
/// slicing and insertion trivial for exports.
public struct NarrationText: Sendable {
  public struct Paragraph: Sendable, Equatable {
    public let range: Range<Int>
    public let text: String
  }

  public struct Sentence: Sendable, Equatable {
    public let range: Range<Int>
    public let paragraphIndex: Int
  }

  public struct Word: Sendable, Equatable {
    public let range: Range<Int>
    public let normalized: String
  }

  /// A heading as it appears in the text: "Section: Introduction." / "Abstract." / "Results."
  public struct Heading: Sendable, Equatable {
    public let paragraphIndex: Int
    public let title: String  // without "Section: " prefix and trailing period
    public let offset: Int
  }

  public let characters: [Character]
  public let paragraphs: [Paragraph]
  public let sentences: [Sentence]
  public let words: [Word]

  public var count: Int { characters.count }
  public var string: String { String(characters) }

  public init(_ text: String) {
    let normalizedNewlines = text.replacingOccurrences(of: "\r\n", with: "\n")
    let chars = Array(normalizedNewlines)
    characters = chars
    let paras = NarrationText.splitParagraphs(chars)
    paragraphs = paras
    var sents: [Sentence] = []
    for (pi, p) in paras.enumerated() {
      for r in TextSegmentation.sentenceRanges(in: chars, range: p.range) {
        sents.append(Sentence(range: r, paragraphIndex: pi))
      }
    }
    sentences = sents
    words = TextSegmentation.words(in: chars, range: 0..<chars.count)
  }

  public func substring(_ range: Range<Int>) -> String {
    let lo = max(0, min(range.lowerBound, characters.count))
    let hi = max(lo, min(range.upperBound, characters.count))
    return String(characters[lo..<hi])
  }

  /// Sentence containing `offset` (or the closest one before it).
  public func sentenceIndex(containing offset: Int) -> Int? {
    guard !sentences.isEmpty else { return nil }
    var lo = 0, hi = sentences.count - 1
    if offset < sentences[0].range.lowerBound { return 0 }
    while lo < hi {
      let mid = (lo + hi + 1) / 2
      if sentences[mid].range.lowerBound <= offset { lo = mid } else { hi = mid - 1 }
    }
    return lo
  }

  /// Index of the first word whose range starts at or after `offset`.
  public func wordIndex(atOrAfter offset: Int) -> Int {
    var lo = 0, hi = words.count
    while lo < hi {
      let mid = (lo + hi) / 2
      if words[mid].range.lowerBound < offset { lo = mid + 1 } else { hi = mid }
    }
    return lo
  }

  /// Candidate headings: short paragraphs that look like doc2audio heading lines.
  public var headings: [Heading] {
    var result: [Heading] = []
    for (i, p) in paragraphs.enumerated() {
      let t = p.text.trimmingCharacters(in: .whitespacesAndNewlines)
      guard t.count <= 160, !t.contains("\n") else { continue }
      var title = t
      var explicit = false
      for prefix in ["Section: ", "Abschnitt: "] where title.hasPrefix(prefix) {
        title = String(title.dropFirst(prefix.count))
        explicit = true
      }
      if title.hasSuffix(".") { title.removeLast() }
      // Implicit headings (level-2 headings, "Abstract.") are a single short sentence.
      if !explicit {
        let sentenceCount = sentences.filter { $0.paragraphIndex == i }.count
        guard sentenceCount == 1, title.split(separator: " ").count <= 14 else { continue }
      }
      result.append(Heading(paragraphIndex: i, title: title, offset: p.range.lowerBound))
    }
    return result
  }

  static func splitParagraphs(_ chars: [Character]) -> [Paragraph] {
    var result: [Paragraph] = []
    var i = 0
    let n = chars.count
    while i < n {
      // skip blank lines / whitespace
      while i < n, chars[i].isWhitespace { i += 1 }
      guard i < n else { break }
      let start = i
      var end = i
      while i < n {
        if chars[i] == "\n" {
          // paragraph ends at a blank line
          var j = i + 1
          while j < n, chars[j] == " " || chars[j] == "\t" { j += 1 }
          if j >= n || chars[j] == "\n" { break }
        }
        i += 1
        if !chars[i - 1].isWhitespace { end = i }
      }
      if end > start {
        result.append(Paragraph(range: start..<end, text: String(chars[start..<end])))
      }
    }
    return result
  }
}

/// Sentence and word segmentation, mirroring doc2audio's `chunking.split_sentences`.
public enum TextSegmentation {
  static let abbreviations: Set<String> = [
    "e.g", "i.e", "cf", "vs", "etc", "fig", "figs", "eq", "eqs", "tab", "prof", "dr", "mr", "mrs", "ms",
    "st", "no", "approx", "al", "z.b", "bzw", "ca", "d.h", "u.a", "vgl", "abb", "nr", "resp", "ref", "sec", "ch",
  ]

  static func isSentenceStart(_ c: Character) -> Bool {
    c.isUppercase || c.isNumber || c == "\"" || c == "'" || c == "(" || c == "“"
  }

  public static func sentenceRanges(in chars: [Character], range: Range<Int>) -> [Range<Int>] {
    var result: [Range<Int>] = []
    var start = range.lowerBound
    var i = range.lowerBound
    let end = range.upperBound
    while i < end {
      let c = chars[i]
      if c == "." || c == "!" || c == "?" {
        var j = i + 1
        if j < end, "\"')]”".contains(chars[j]) { j += 1 }
        let boundaryEnd = j
        var k = j
        var sawSpace = false
        while k < end, chars[k].isWhitespace { k += 1; sawSpace = true }
        if sawSpace, k < end, isSentenceStart(chars[k]), !isAbbreviation(chars, start: start, dotIndex: i) {
          result.append(start..<boundaryEnd)
          start = k
          i = k
          continue
        }
      }
      i += 1
    }
    if start < end {
      // trim trailing whitespace
      var e = end
      while e > start, chars[e - 1].isWhitespace { e -= 1 }
      if e > start { result.append(start..<e) }
    }
    return result
  }

  static func isAbbreviation(_ chars: [Character], start: Int, dotIndex: Int) -> Bool {
    guard chars[dotIndex] == "." else { return false }
    // last "word" (letters and dots) before the dot, max 12 chars back
    var k = dotIndex - 1
    let lower = max(start, dotIndex - 12)
    while k >= lower, chars[k].isLetter || chars[k].isNumber || chars[k] == "." || chars[k] == "_" { k -= 1 }
    let raw = String(chars[(k + 1)..<dotIndex]).trimmingCharacters(in: CharacterSet(charactersIn: "."))
    if raw.isEmpty { return false }
    if abbreviations.contains(raw.lowercased()) { return true }
    if raw.count == 1, let ch = raw.first, ch.isLetter, ch.isUppercase { return true }  // initials
    return false
  }

  public static func words(in chars: [Character], range: Range<Int>) -> [NarrationText.Word] {
    var result: [NarrationText.Word] = []
    var i = range.lowerBound
    while i < range.upperBound {
      if chars[i].isLetter || chars[i].isNumber {
        let s = i
        while i < range.upperBound, chars[i].isLetter || chars[i].isNumber || chars[i] == "'" || chars[i] == "’" { i += 1 }
        let token = normalize(String(chars[s..<i]))
        if !token.isEmpty { result.append(.init(range: s..<i, normalized: token)) }
      } else {
        i += 1
      }
    }
    return result
  }

  /// Lowercased, diacritic-folded, apostrophes removed.
  public static func normalize(_ s: String) -> String {
    s.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
      .replacingOccurrences(of: "'", with: "")
      .replacingOccurrences(of: "’", with: "")
  }

  /// Splits free text (e.g. a recognizer transcript) into normalized tokens.
  public static func tokens(_ text: String) -> [String] {
    words(in: Array(text), range: 0..<text.count).map(\.normalized)
  }
}
