import Foundation

/// A document's readable structure, independent of the file format it came from
/// (mirrors doc2audio's `ir.py`).
public struct SourceDocument: Sendable, Equatable {
  public enum Block: Sendable, Equatable {
    case heading(String, level: Int)
    case paragraph(String)
    case listItem(String)
    case figure(number: String?, caption: String)
    case table(number: String?, caption: String)
    /// A display equation; announced by number ("Equation 12.").
    case equation(number: String?)
  }

  public var title: String?
  public var blocks: [Block]

  public init(title: String?, blocks: [Block]) {
    self.title = title
    self.blocks = blocks
  }

  /// All readable text, for language detection and statistics.
  public var plainText: String {
    var parts: [String] = title.map { [$0] } ?? []
    for b in blocks {
      switch b {
      case .heading(let t, _), .paragraph(let t), .listItem(let t): parts.append(t)
      case .figure(_, let c), .table(_, let c): parts.append(c)
      case .equation: break
      }
    }
    return parts.joined(separator: "\n")
  }

  public var headings: [String] {
    blocks.compactMap { if case .heading(let t, let level) = $0, level <= 2 { t } else { nil } }
  }

  /// Plain text / Markdown: paragraphs end at blank lines, `# ` lines are headings,
  /// `- ` and `* ` lines are list items. In Markdown, headings and list items also
  /// end the paragraph above; in plain text they must start a block, so a wrapped
  /// line that happens to begin with "- " stays part of its paragraph.
  public static func fromPlainText(_ text: String, fallbackTitle: String?, markdown: Bool = false) -> SourceDocument {
    var blocks: [Block] = []
    var title: String?
    var paragraph: [String] = []
    /// The line above was a list item or its continuation (no blank line since).
    var inListItem = false
    func endParagraph() {
      guard !paragraph.isEmpty else { return }
      blocks.append(.paragraph(TextCleanup.joinLines(paragraph)))
      paragraph = []
    }
    // macOS often stores umlauts decomposed ("u" + combining diaeresis)
    for raw in text.precomposedStringWithCanonicalMapping.components(separatedBy: .newlines) {
      let line = raw.trimmingCharacters(in: .whitespaces)
      let startsBlock = markdown || paragraph.isEmpty
      let level = line.prefix { $0 == "#" }.count
      if line.isEmpty {
        endParagraph()
        inListItem = false
      } else if startsBlock, (1...6).contains(level), line.count == level || line.dropFirst(level).first == " " {
        endParagraph()
        inListItem = false
        let heading = line.dropFirst(level).trimmingCharacters(in: .whitespaces)
        if level == 1, title == nil, blocks.isEmpty { title = heading } else { blocks.append(.heading(heading, level: max(1, level - 1))) }
      } else if startsBlock, line.hasPrefix("- ") || line.hasPrefix("* ") {
        endParagraph()
        inListItem = true
        blocks.append(.listItem(String(line.dropFirst(2)).trimmingCharacters(in: .whitespaces)))
      } else if inListItem, raw.first?.isWhitespace == true, case .listItem(let item) = blocks.last {
        // an indented line continues the list item above
        blocks[blocks.count - 1] = .listItem(item + " " + line)
      } else {
        inListItem = false
        paragraph.append(line)
      }
    }
    endParagraph()
    return SourceDocument(title: title ?? fallbackTitle, blocks: blocks)
  }
}
