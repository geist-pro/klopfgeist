import Foundation

/// Text cleaning ported from doc2audio's `cleanup.py`: typography, citations,
/// boilerplate, hyphenation and heading clean-up.
public enum TextCleanup {
  private static func regex(_ pattern: String, _ options: NSRegularExpression.Options = []) -> NSRegularExpression {
    try! NSRegularExpression(pattern: pattern, options: options)
  }

  static let citationBracket = regex(#"\s?\[(?:\s*\d+\s*(?:[\s,;–\-]+\s*\d+\s*)*)\]"#)
  static let citationKey = regex(#"\s?\[[A-Za-z][A-Za-z\-]+\d{2,4}[a-z]?(?:\s*,\s*[A-Za-z][A-Za-z\-]+\d{2,4}[a-z]?)*\]"#)
  static let citationParen = regex(
    #"\s?\(\s*(?:(?:e\.g\.,?|see|cf\.)\s*)?(?:[A-ZÄÖÜ][\w\-]+(?:\s+(?:and|&|und|et\s+al\.?))?\s*(?:[A-ZÄÖÜ][\w\-]+\s*)?,?\s*\d{4}[a-z]?\s*;?\s*)+\)"#)
  static let refPlaceholder = regex(#"\s?\[(?:REF|ref|citation needed|TODO)[^\]]*\]"#)
  static let url = regex(#"(?:https?://|www\.)\S+"#)
  static let doi = regex(#"\b(?:doi:|DOI:)\s*\S+"#, .caseInsensitive)
  static let email = regex(#"\b[\w.+-]+@\w[\w.-]*\.\w{2,}\b"#)
  static let affiliation = regex(
    #"\b(University|Universit[äa]t|Institute|Institut|Laboratory|Department|School|College|Hochschule|Fakult[äa]t|Faculty|Corporation|Inc\.|Ltd\.|GmbH|Research Center|Centre)\b"#,
    .caseInsensitive)
  static let personTitle = regex(#"\b(Prof\.|Dr\.|Ph\.?D\.?|M\.Sc\.|Dipl\.-Ing\.)"#)
  static let referencesHeader = regex(
    #"^\s*(?:[\dIVX]+\.?\s*)?(References|Bibliography|Acknowledg(?:e?)ments?|Literatur\w*|Referenzen|Quellen(?:verzeichnis)?|Danksagung)\s*$"#,
    .caseInsensitive)
  static let appendixHeader = regex(#"^\s*(?:[A-Z]\.?\s+)?Appendi(?:x|ces)\b"#, .caseInsensitive)
  static let figureCaption = regex(#"^\s*(?:Figure|Fig\.?|Abbildung|Abb\.)\s*(\d+(?:\.\d+)*[a-z]?)\s*[.:\-–]?\s*(.*)$"#, [.caseInsensitive, .dotMatchesLineSeparators])
  static let tableCaption = regex(#"^\s*(?:Table|Tabelle|Tab\.)\s*([\dIVX]+(?:\.\d+)*[a-z]?)\s*[.:\-–]?\s*(.*)$"#, [.caseInsensitive, .dotMatchesLineSeparators])
  static let sectionNumber = regex(#"^\s*(?:[\dIVXLC]+(?:\.\d+)*\.?|[A-Z]\.)\s+"#)

  static let boilerplatePrefixes = [
    "permission to make digital", "copyrights for components", "©", "(c) 20", "copyright 20",
    "acm reference format", "acm isbn", "acm/ims", "isbn ", "issn ", "manuscript submitted", "preprint", "arxiv:",
    "this work is licensed", "creative commons", "ccs concepts", "additional key words", "keywords", "author keywords",
    "index terms", "categories and subject descriptors", "general terms", "authors' address", "author's address",
    "authors' addresses", "corresponding author", "* corresponding", "∗ corresponding", "received ", "accepted ",
    "published online", "proceedings of", "in proceedings", "conference on", "ieee international", "acm ",
    "this is the author", "final version", "to appear in", "e-mail:", "email:",
  ]

  static func replace(_ re: NSRegularExpression, in s: String, with template: String = "") -> String {
    re.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: template)
  }

  static func matches(_ re: NSRegularExpression, _ s: String) -> Bool {
    re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
  }

  /// Unicode normalisation plus the typographic fixes TTS engines trip on.
  public static func normalize(_ input: String) -> String {
    var s = input.precomposedStringWithCompatibilityMapping  // NFKC (also expands ﬁ ﬂ ligatures)
    let map: [(String, String)] = [
      ("\u{00AD}", ""), ("\u{200B}", ""), ("\u{00A0}", " "), ("\u{2009}", " "), ("\u{202F}", " "),
      ("’", "'"), ("‘", "'"), ("“", "\""), ("”", "\""), ("„", "\""),
    ]
    for (a, b) in map { s = s.replacingOccurrences(of: a, with: b) }
    s = s.replacingOccurrences(of: #"[ \t]+"#, with: " ", options: .regularExpression)
    return s.trimmingCharacters(in: .whitespacesAndNewlines)
  }

  public static func stripCitations(_ input: String) -> String {
    var s = input
    for re in [citationBracket, citationKey, citationParen, refPlaceholder, url, doi] { s = replace(re, in: s) }
    s = s.replacingOccurrences(of: #"\s+([.,;:!?])"#, with: "$1", options: .regularExpression)
    s = s.replacingOccurrences(of: #"([,;])\s*([,;])"#, with: "$1", options: .regularExpression)
    s = s.replacingOccurrences(of: #"\(\s*\)"#, with: "", options: .regularExpression)
    return s.replacingOccurrences(of: #"\s{2,}"#, with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
  }

  public static func isBoilerplate(_ line: String) -> Bool {
    let low = line.trimmingCharacters(in: .whitespaces).lowercased()
    return boilerplatePrefixes.contains { low.hasPrefix($0) }
  }

  /// Author / affiliation lines on a title page.
  public static func looksLikeAuthorLine(_ line: String) -> Bool {
    let s = line.trimmingCharacters(in: .whitespaces)
    guard !s.isEmpty else { return false }
    if matches(email, s) { return true }
    if matches(personTitle, s), s.count < 220 { return true }
    if matches(affiliation, s), s.count < 220, s.filter({ $0 == "." }).count <= 3 { return true }
    let words = s.split(separator: " ")
    if words.count > 1, words.count <= 12, !(s.hasSuffix(".") || s.hasSuffix("?") || s.hasSuffix("!")) {
      let caps = words.filter { $0.first?.isUppercase == true || ["and", "&", "und"].contains($0) }.count
      if Double(caps) / Double(words.count) > 0.8 { return true }
    }
    return false
  }

  /// Joins PDF lines: "collabora-" + "tive" → "collaborative".
  public static func joinLines(_ lines: [String]) -> String {
    var out = ""
    for raw in lines {
      let line = raw.trimmingCharacters(in: .whitespaces)
      guard !line.isEmpty else { continue }
      if out.hasSuffix("-"), let first = line.first, first.isLowercase, out.count > 1 {
        out.removeLast()
        out += line
      } else {
        out += out.isEmpty ? line : " " + line
      }
    }
    return out
  }

  public static func isReferencesHeading(_ s: String) -> Bool { matches(referencesHeader, s) }

  /// Sections that are left out with everything up to the next heading.
  public static func isSkippedSection(_ s: String) -> Bool {
    s.range(
      of: #"^(acknowledge?ments?|funding|danksagung|conflicts? of interest|competing interests|author contributions|data availability)\b"#,
      options: [.regularExpression, .caseInsensitive]) != nil
  }

  /// Title-page metadata that runs on for a few lines (ACM, Springer, Elsevier):
  /// keywords, CCS concepts, contact information, license and citation format.
  public static func startsFrontMatterBlock(_ s: String) -> Bool {
    s.range(
      of: #"^(CCS Concepts|Additional Key Words|Key ?words|Index Terms|Schlüsselwörter|ACM Reference Format|Authors?[’'] ?(Contact Information|addresses)|This work is licensed|Permission to make digital|Publication rights licensed|©|Corresponding author)"#,
      options: [.regularExpression, .caseInsensitive]) != nil
  }

  /// "SHILONG LIU, Institute of …", "CHAO ZHOU∗", ", Institute of …": author
  /// lines set in capitals (ACM journals) and their split-off affiliations.
  public static func looksLikeCapitalizedAuthorLine(_ s: String) -> Bool {
    if s.hasPrefix(",") { return true }
    let words = s.split(separator: " ").prefix(4)
    let caps = words.prefix { w in
      let letters = w.filter(\.isLetter)
      return letters.count >= 2 && letters == letters.uppercased()
    }
    guard caps.count >= 2 else { return false }
    return s.contains(",") || s.contains("∗") || s.contains("*") || caps.count == words.count
  }

  /// "2.3.1 Pain Relief Based on Visual Distraction. The exploration …":
  /// a numbered run-in heading at the start of a paragraph.
  public static func splitRunInHeading(_ s: String) -> (heading: String, level: Int, rest: String)? {
    let re = #"^(\d+(?:\.\d+){1,3})\s+(\p{Lu}[^.?!]{2,90}?)[.:]\s+(?=\p{Lu}|\()"#
    guard let r = s.range(of: re, options: .regularExpression) else { return nil }
    let head = String(s[r])
    guard let depth = numberingDepth(head) else { return nil }
    let title = head.replacingOccurrences(of: #"^[\d.]+\s+"#, with: "", options: .regularExpression)
      .trimmingCharacters(in: CharacterSet(charactersIn: ".: "))
    return (title, min(depth, 3), String(s[r.upperBound...]))
  }
  public static func isAppendixHeading(_ s: String) -> Bool { matches(appendixHeader, s) }

  /// ("3", "Caption text") for "Figure 3: Caption text".
  public static func figureCaption(_ s: String) -> (number: String, caption: String)? { caption(figureCaption, s) }
  public static func tableCaption(_ s: String) -> (number: String, caption: String)? { caption(tableCaption, s) }

  private static func caption(_ re: NSRegularExpression, _ s: String) -> (String, String)? {
    guard let m = re.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)),
      let n = Range(m.range(at: 1), in: s), let c = Range(m.range(at: 2), in: s)
    else { return nil }
    // "Figure 6c evaluates …", "Figure 5a, the …" are sentences about a figure, not its caption
    let between = s[n.upperBound..<c.lowerBound].trimmingCharacters(in: .whitespaces)
    if between.isEmpty, let first = s[c].first, first.isLowercase || first == "," { return nil }
    return (String(s[n]), String(s[c]).trimmingCharacters(in: .whitespaces))
  }

  /// "3.2 RESULTS." → "Results"
  public static func cleanHeading(_ input: String) -> String {
    // a second number appears when two overlapping heading lines were merged ("3.4 3.4.1 Experiment 2")
    var s = replace(sectionNumber, in: replace(sectionNumber, in: normalize(input)))
    while let last = s.last, " .:".contains(last) { s.removeLast() }
    if s.count > 3, s == s.uppercased(), s.contains(where: \.isLetter) { s = s.capitalized }
    return s
  }

  static let mathSymbols = Set("=+−∑∫∥⃦√∂∇≤≥≈±×·^_[]{}|∞∈∀∃⊂→←↦αβγδεζηθικλμνξπρστυφχψωΓΔΘΛΞΠΣΦΨΩ̂̃̄̇̈")

  /// Share of characters that are math symbols, Greek letters or combining accents.
  public static func mathRatio(_ s: String) -> Double {
    let chars = s.filter { !$0.isWhitespace }
    guard !chars.isEmpty else { return 0 }
    let math = chars.filter { c in c.unicodeScalars.contains { mathSymbols.contains(Character($0)) || (0x300...0x36F).contains($0.value) || (0xFE00...0xFE0F).contains($0.value) } }
    return Double(math.count) / Double(chars.count)
  }

  /// A display-equation line: math-heavy with few real words, or ending in "(n)".
  public static func isEquationLine(_ s: String) -> Bool {
    let words = s.split(separator: " ").filter { $0.count >= 4 && $0.allSatisfy(\.isLetter) }.count
    let numbered = s.range(of: #"\(\d{1,3}\)\s*$"#, options: .regularExpression) != nil
    let ratio = mathRatio(s)
    let shortNumbered = numbered && s.split(separator: " ").count <= 3  // "a. (6)", ". (14)"
    return (ratio > 0.12 && words <= 2) || (numbered && ratio > 0.05 && words <= 4) || shortNumbered
  }

  /// The "(12)" equation number at the end of a line.
  public static func equationNumber(_ s: String) -> String? {
    guard let r = s.range(of: #"\((\d{1,3})\)\s*$"#, options: .regularExpression) else { return nil }
    return s[r].filter(\.isNumber)
  }

  /// Text that can be a heading: mostly letters, no formulas, units or percentages.
  public static func looksLikeHeadingText(_ s: String) -> Bool {
    guard s.contains(where: \.isLetter), !s.contains(where: { "=+%/<>∑∫∥[]{}|".contains($0) }) else { return false }
    let chars = s.filter { !$0.isWhitespace }
    let letters = chars.filter { $0.isLetter }.count
    return Double(letters) / Double(max(1, chars.count)) >= 0.6 && mathRatio(s) < 0.05
  }

  /// Section numbering depth: "2" → 1, "2.1" → 2, "2.1.3" → 3; nil if unnumbered.
  public static func numberingDepth(_ s: String) -> Int? {
    let t = s.trimmingCharacters(in: .whitespaces)
    guard let m = try? NSRegularExpression(pattern: #"^(\d+(?:\.\d+)*)\.?\s+\S"#).firstMatch(in: t, range: NSRange(t.startIndex..., in: t)),
      let r = Range(m.range(at: 1), in: t)
    else {
      if t.range(of: #"^[IVX]+\.\s+\S"#, options: .regularExpression) != nil { return 1 }  // "IV. RESULTS"
      if t.range(of: #"^[A-H]\.\s+[A-Z]"#, options: .regularExpression) != nil { return 2 }  // "B. Related Work"
      return nil
    }
    let parts = t[r].split(separator: ".").compactMap { Int($0) }
    guard let first = parts.first, first <= 20, parts.allSatisfy({ $0 <= 30 }) else { return nil }  // "200 Hz" is not a section
    return parts.count
  }
}
