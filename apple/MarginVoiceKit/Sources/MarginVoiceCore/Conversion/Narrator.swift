import Foundation

/// Turns a `SourceDocument` into narration (port of doc2audio's `narrate.py`) and
/// plans the utterances to synthesize, with exact character ranges in the
/// narration text so every comment can be anchored precisely.
public enum Narrator {
  public struct Segment: Sendable, Equatable {
    public enum Kind: String, Sendable { case title, heading, paragraph, item, figure, table }
    public var text: String
    public var pauseAfter: Double
    /// Set on the first segment of a chapter (title, abstract, level ≤ 2 headings).
    public var chapter: String?
    public var kind: Kind
  }

  /// One synthesized unit (a sentence), located in the narration text.
  public struct Utterance: Sendable, Equatable {
    public let text: String
    /// `Character` offsets into the narration text (same as `NarrationText`).
    public let range: Range<Int>
    public let pauseAfter: Double
    public let chapter: String?
  }

  public struct Plan: Sendable {
    public let text: String
    public let utterances: [Utterance]
    public var wordCount: Int { text.split(whereSeparator: \.isWhitespace).count }
    /// Rough length at a normal speaking rate (150 words/min).
    public var estimatedMinutes: Double { Double(wordCount) / 150 }
    public var chapters: [String] { utterances.compactMap(\.chapter) }
  }

  static let pauseHeading = 0.8
  static let pauseParagraph = 0.5
  static let pauseItem = 0.3
  static let pauseSentence = 0.15

  // MARK: Phrases and abbreviations

  struct Phrases {
    let section: String, abstract: String, figure: String, figureNoCaption: String
    let table: String, tableSkip: String, tableNoCaption: String, equation: String
  }

  static func phrases(_ lang: String) -> Phrases {
    lang == "de"
      ? Phrases(
        section: "Abschnitt: %@.", abstract: "Zusammenfassung.", figure: "Abbildung %@: %@", figureNoCaption: "Abbildung %@.",
        table: "Tabelle %@: %@", tableSkip: "Tabelle übersprungen.", tableNoCaption: "Tabelle %@ übersprungen.",
        equation: "Gleichung %@.")
      : Phrases(
        section: "Section: %@.", abstract: "Abstract.", figure: "Figure %@: %@", figureNoCaption: "Figure %@.",
        table: "Table %@: %@", tableSkip: "Table skipped.", tableNoCaption: "Table %@ skipped.",
        equation: "Equation %@.")
  }

  static let abbreviations: [String: [(String, String)]] = [
    "en": [
      (#"\be\.\s?g\.,?"#, "for example,"), (#"\bi\.\s?e\.,?"#, "that is,"), (#"\bet al\."#, "and others"),
      (#"\bvs\."#, "versus"), (#"\bcf\."#, "compare"), (#"\bw\.r\.t\."#, "with respect to"),
      (#"\bapprox\."#, "approximately"), (#"\bFigs?\.\s*(?=\d)"#, "Figure "), (#"\bEqs?\.\s*(?=\(?\d)"#, "Equation "),
      (#"\bSec\.\s*(?=\d)"#, "Section "), (#"\bTab\.\s*(?=\d)"#, "Table "), (#"\bNo\.\s*(?=\d)"#, "number "),
      (#"\bResp\."#, "respectively"), (#"\betc\."#, "et cetera"), (#"\bPh\.D\."#, "PhD"),
      (#"\bHCI\b"#, "H C I"), (#"\bUI\b"#, "U I"), (#"\bUX\b"#, "U X"), (#"\bAI\b"#, "A I"), (#"\bVR\b"#, "V R"),
      (#"\bAR\b"#, "A R"), (#"\bEEG\b"#, "E E G"), (#"\bEMS\b"#, "E M S"), (#"\bIQR\b"#, "I Q R"), (#"\bLSTM\b"#, "L S T M"),
      (#"\bTCN\b"#, "T C N"), (#"\bHMD\b"#, "H M D"), (#"\bSD\b"#, "S D"), (#"\bMAP\b"#, "M A P"), (#"\bBIC\b"#, "B I C"),
    ],
    "de": [
      (#"\bz\.\s?B\."#, "zum Beispiel"), (#"\bbzw\."#, "beziehungsweise"), (#"\bd\.\s?h\."#, "das heißt"),
      (#"\bu\.\s?a\."#, "unter anderem"), (#"\bvgl\."#, "vergleiche"), (#"\bca\."#, "circa"),
      (#"\bAbb\.\s*(?=\d)"#, "Abbildung "), (#"\bTab\.\s*(?=\d)"#, "Tabelle "), (#"\bNr\."#, "Nummer"),
      (#"\bggf\."#, "gegebenenfalls"), (#"\bevtl\."#, "eventuell"), (#"\binkl\."#, "inklusive"),
      (#"\bKI\b"#, "K I"), (#"\bIT\b"#, "I T"), (#"\bMBSE\b"#, "M B S E"), (#"\bLLMs?\b"#, "L L M"),
      (#"\bet al\."#, "und andere"), (#"\bvs\."#, "versus"), (#"\bsog\."#, "sogenannte"),
    ],
  ]

  static func symbols(_ lang: String) -> [(String, String)] {
    lang == "de"
      ? [("≤", " kleiner gleich "), ("≥", " größer gleich "), ("≠", " ungleich "), ("≈", " ungefähr "), ("±", " plus minus "), ("→", " zu "), ("×", " mal ")]
      : [("≤", " less than or equal to "), ("≥", " greater than or equal to "), ("≠", " not equal to "), ("≈", " approximately "), ("±", " plus or minus "), ("→", " to "), ("×", " times ")]
  }

  // MARK: Prose

  static let greek: [Character: String] = [
    "α": "alpha", "β": "beta", "γ": "gamma", "δ": "delta", "ε": "epsilon", "ζ": "zeta", "η": "eta", "θ": "theta",
    "κ": "kappa", "λ": "lambda", "μ": "mu", "ν": "nu", "ξ": "xi", "π": "pi", "ρ": "rho", "σ": "sigma", "τ": "tau",
    "φ": "phi", "χ": "chi", "ψ": "psi", "ω": "omega", "Γ": "Gamma", "Δ": "Delta", "Θ": "Theta", "Λ": "Lambda",
    "Σ": "Sigma", "Φ": "Phi", "Ψ": "Psi", "Ω": "Omega",
  ]

  /// Inline math left in prose: Greek letters by name, operators as words,
  /// accents and decorative brackets dropped.
  static func speakInlineMath(_ input: String, language lang: String) -> String {
    let de = lang == "de"
    // drop combining accents (hats, bars, dots from math typesetting) at the scalar level
    var scalars = String.UnicodeScalarView()
    for u in input.unicodeScalars where !((0x300...0x36F).contains(u.value) || (0xFE00...0xFE0F).contains(u.value)) {
      scalars.append(u)
    }
    var out = ""
    for ch in String(scalars) {
      if let name = greek[ch] {
        out += " \(name) "
      } else if ch.unicodeScalars.allSatisfy({ (0x300...0x36F).contains($0.value) || (0xFE00...0xFE0F).contains($0.value) }) || "ˆ˜¯˙¨⃦︂︁".contains(ch) {
        continue
      } else {
        switch ch {
        case "=": out += de ? " gleich " : " equals "
        case "∈": out += de ? " in " : " in "
        case "−": out += de ? " minus " : " minus "
        case "∝": out += de ? " proportional zu " : " proportional to "
        case "∥": out += de ? " Norm " : " norm "
        case "∑": out += de ? " Summe " : " sum "
        case "∫": out += de ? " Integral " : " integral "
        case "√": out += de ? " Wurzel " : " square root of "
        case "∞": out += de ? " unendlich " : " infinity "
        case "⊤": out += de ? " transponiert " : " transpose "
        default: out.append(ch)
        }
      }
    }
    return out
  }

  public static func prepareProse(_ input: String, language lang: String) -> String {
    var t = input
    for (pattern, replacement) in abbreviations[lang] ?? abbreviations["en"]! {
      t = t.replacingOccurrences(of: pattern, with: replacement, options: .regularExpression)
    }
    // "p < .001" → "p less than 0.001" (TTS reads ".001" badly and "<" not at all)
    let lt = lang == "de" ? " kleiner als " : " less than ", gt = lang == "de" ? " größer als " : " greater than "
    t = t.replacingOccurrences(of: #"\b(p|r|t|F|d|n|N|R2|R²|β)\s*([<>=])\s*\.(\d)"#, with: "$1 $2 0.$3", options: .regularExpression)
    t = t.replacingOccurrences(of: #"\b(p|r|t|F|d|n|N|R2|R²|β)\s*<\s*"#, with: "$1" + lt, options: .regularExpression)
    t = t.replacingOccurrences(of: #"\b(p|r|t|F|d|n|N|R2|R²|β)\s*>\s*"#, with: "$1" + gt, options: .regularExpression)
    for (sym, word) in symbols(lang) { t = t.replacingOccurrences(of: sym, with: word) }
    t = speakInlineMath(t, language: lang)
    if lang == "en" {
      // "16/20" → "16 out of 20"
      t = t.replacingOccurrences(of: #"(?<![\d/.])\b(\d{1,2})/(\d{1,3})\b(?![\d/%.])"#, with: "$1 out of $2", options: .regularExpression)
    }
    t = t.replacingOccurrences(of: #"\^\{([^}]*)\}"#, with: lang == "de" ? " hoch $1 " : " to the $1 ", options: .regularExpression)
    t = t.replacingOccurrences(of: #"_\{([^}]*)\}"#, with: lang == "de" ? " Index $1 " : " sub $1 ", options: .regularExpression)
    t = t.replacingOccurrences(of: #"\b(\d+)-(?=[A-Za-z]{2,})"#, with: "$1 ", options: .regularExpression)
    t = t.replacingOccurrences(of: #"\s+([.,;:!?])"#, with: "$1", options: .regularExpression)
    t = t.replacingOccurrences(of: #"\s{2,}"#, with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
    if let last = t.last, !".!?:;\"'".contains(last) { t += "." }
    return t
  }

  static func ensurePeriod(_ s: String) -> String {
    let t = s.trimmingCharacters(in: .whitespaces)
    guard let last = t.last, !".!?".contains(last) else { return t }
    return t + "."
  }

  // MARK: Segments

  public static func segments(for doc: SourceDocument, language lang: String) -> [Segment] {
    let p = phrases(lang)
    var segs: [Segment] = []
    if let title = doc.title?.trimmingCharacters(in: .whitespaces), !title.isEmpty {
      segs.append(Segment(text: ensurePeriod(title), pauseAfter: pauseHeading, chapter: title, kind: .title))
    }
    for block in doc.blocks {
      switch block {
      case .heading(let raw, let level):
        let title = raw.trimmingCharacters(in: .whitespaces)
        guard !title.isEmpty else { continue }
        let spoken: String
        if ["abstract", "zusammenfassung", "summary"].contains(title.lowercased()) {
          spoken = p.abstract
        } else if level <= 1 {
          spoken = String(format: p.section, title)
        } else {
          spoken = ensurePeriod(title)
        }
        segs.append(Segment(text: spoken, pauseAfter: pauseHeading, chapter: level <= 2 ? title : nil, kind: .heading))
      case .paragraph(let t):
        let text = prepareProse(t, language: lang)
        if !text.isEmpty { segs.append(Segment(text: text, pauseAfter: pauseParagraph, kind: .paragraph)) }
      case .listItem(let t):
        let text = prepareProse(t, language: lang)
        if !text.isEmpty { segs.append(Segment(text: text, pauseAfter: pauseItem, kind: .item)) }
      case .figure(let number, let caption):
        let cap = prepareProse(caption, language: lang)
        guard !cap.isEmpty else { continue }  // images without caption are not worth announcing
        segs.append(Segment(text: String(format: p.figure, number ?? "", cap), pauseAfter: pauseParagraph, kind: .figure))
      case .table(let number, let caption):
        let cap = prepareProse(caption, language: lang)
        let text: String
        if !cap.isEmpty {
          text = String(format: p.table, number ?? "", cap) + " " + p.tableSkip
        } else if let number {
          text = String(format: p.tableNoCaption, number)
        } else {
          continue
        }
        segs.append(Segment(text: text, pauseAfter: pauseParagraph, kind: .table))
      case .equation(let number):
        guard let number else { continue }  // unnumbered display math is skipped
        segs.append(Segment(text: String(format: p.equation, number), pauseAfter: pauseItem, kind: .item))
      }
    }
    return segs
  }

  /// The narration text in doc2audio's format: one segment per line, blank lines
  /// between, extra blank line before headings.
  public static func narrationText(_ segments: [Segment]) -> String {
    var lines: [String] = []
    for s in segments {
      if s.kind == .title || s.kind == .heading {
        lines += ["", s.text, ""]
      } else {
        lines += [s.text, ""]
      }
    }
    let joined = lines.joined(separator: "\n").replacingOccurrences(of: #"\n{3,}"#, with: "\n\n", options: .regularExpression)
    return joined.trimmingCharacters(in: .whitespacesAndNewlines) + "\n"
  }

  // MARK: Plan

  public static func plan(_ doc: SourceDocument, language: String) -> Plan {
    let segs = segments(for: doc, language: language)
    let text = narrationText(segs)
    let chars = Array(text)
    var utterances: [Utterance] = []
    var cursor = 0
    for seg in segs {
      let needle = Array(seg.text)
      guard let start = find(needle, in: chars, from: cursor) else { continue }
      let range = start..<(start + needle.count)
      cursor = range.upperBound
      let sentences = TextSegmentation.sentenceRanges(in: chars, range: range)
      for (i, r) in sentences.enumerated() {
        let last = i == sentences.count - 1
        utterances.append(
          Utterance(
            text: String(chars[r]), range: r, pauseAfter: last ? seg.pauseAfter : pauseSentence,
            chapter: i == 0 ? seg.chapter : nil))
      }
    }
    return Plan(text: text, utterances: utterances)
  }

  static func find(_ needle: [Character], in hay: [Character], from: Int) -> Int? {
    guard !needle.isEmpty, hay.count >= needle.count else { return nil }
    var i = from
    while i <= hay.count - needle.count {
      if hay[i] == needle[0], Array(hay[i..<(i + needle.count)]) == needle { return i }
      i += 1
    }
    return nil
  }
}
