import Foundation

/// One text line of a PDF page with its typography and position.
public struct PDFLine: Sendable, Equatable {
  public var page: Int
  public var text: String
  public var fontSize: Double
  public var isBold: Bool
  /// Top of the line, 0 = top of page, 1 = bottom.
  public var top: Double
  /// Left edge and width, as fractions of the page width.
  public var left: Double
  public var width: Double
  /// Line height as a fraction of the page height.
  public var height: Double

  public init(page: Int, text: String, fontSize: Double, isBold: Bool, top: Double, left: Double, width: Double, height: Double) {
    self.page = page
    self.text = text
    self.fontSize = fontSize
    self.isBold = isBold
    self.top = top
    self.left = left
    self.width = width
    self.height = height
  }
}

/// Rebuilds document structure from PDF lines (heuristics ported from doc2audio's
/// `extract_pdf.py`): body font, headings by size/weight/numbering, running
/// headers and footers, title page authors, captions, references cut-off.
public enum PDFLayoutAnalyzer {
  public struct Options: Sendable {
    public var includeAppendix = false
    public init() {}
  }

  public static func document(from allLines: [PDFLine], pageCount: Int, fallbackTitle: String?, options: Options = Options()) -> SourceDocument {
    var lines = allLines.map { l -> PDFLine in
      var l = l
      l.text = TextCleanup.normalize(l.text)
      return l
    }.filter { !$0.text.isEmpty }
    guard !lines.isEmpty else { return SourceDocument(title: fallbackTitle, blocks: []) }

    let body = bodySize(lines)
    let repeated = repeatingLines(lines, pageCount: pageCount)
    lines.removeAll { l in
      repeated.contains(repeatKey(l.text)) && (l.top < 0.12 || l.top > 0.88)
        || l.text.range(of: #"^\d{1,4}$"#, options: .regularExpression) != nil  // page and line numbers
        || TextCleanup.isBoilerplate(l.text) && !TextCleanup.startsFrontMatterBlock(l.text)  // blocks are skipped as a whole below
    }
    let typicalWidth = median(lines.filter { abs($0.fontSize - body) < 0.6 }.map(\.width)) ?? 0.4
    let margins = columnMargins(lines, body: body)

    // title: the largest text on the first page, above the body size
    var title: String?
    var index = 0
    let firstPage = lines.filter { $0.page == lines[0].page }
    if let maxSize = firstPage.map(\.fontSize).max(), maxSize >= body * 1.3 {
      var parts: [String] = []
      while index < lines.count, lines[index].page == lines[0].page, lines[index].fontSize < maxSize - 0.5, parts.isEmpty, index < 6 {
        index += 1  // skip small lines above the title (venue, preprint notes)
      }
      while index < lines.count, abs(lines[index].fontSize - maxSize) < 0.6 {
        parts.append(lines[index].text)
        index += 1
      }
      if !parts.isEmpty { title = TextCleanup.joinLines(parts) } else { index = 0 }
    }

    var blocks: [SourceDocument.Block] = []
    var paragraph: [PDFLine] = []
    var caption: (kind: Character, number: String, lines: [String])?
    var seenHeading = false
    /// Inside keywords / CCS concepts / contact information on the title page.
    var inFrontMatter = false
    /// Inside acknowledgments or funding, until the next heading.
    var inSkippedSection = false
    /// Below a table caption: cells until prose resumes at the column edge.
    var inTableBody = false
    var tableRows = 0
    let titlePage = lines[0].page

    func flushParagraph() {
      guard !paragraph.isEmpty else { return }
      let text = TextCleanup.stripCitations(TextCleanup.joinLines(paragraph.map(\.text)))
      paragraph.removeAll()
      guard !text.isEmpty else { return }
      if !seenHeading, TextCleanup.looksLikeAuthorLine(text), text.count < 300 { return }
      // a short fragment without a sentence is a label inside a figure
      let words = text.split(separator: " ")
      if words.count <= 3, !text.contains(where: { ".!?:".contains($0) }) { return }
      if words.allSatisfy({ $0.count <= 2 }) { return }  // "a b c d" panel letters
      // ACM and Springer abstracts have no heading: a long first paragraph on the title page
      if !seenHeading, blocks.isEmpty, text.count > 400 { blocks.append(.heading("Abstract", level: 2)) }
      if let run = TextCleanup.splitRunInHeading(text) {
        blocks.append(.heading(run.heading, level: run.level))
        blocks.append(.paragraph(run.rest))
        return
      }
      if text.hasPrefix("•") || text.hasPrefix("◦") || text.hasPrefix("– ") {
        blocks.append(.listItem(String(text.drop { "•◦– ".contains($0) })))
      } else {
        blocks.append(.paragraph(text))
      }
    }
    func flushCaption() {
      guard let c = caption else { return }
      inTableBody = c.kind == "T"
      tableRows = 0
      let text = TextCleanup.stripCitations(TextCleanup.joinLines(c.lines))
      blocks.append(c.kind == "F" ? .figure(number: c.number, caption: text) : .table(number: c.number, caption: text))
      caption = nil
    }

    var previous: PDFLine?
    while index < lines.count {
      let line = lines[index]
      index += 1
      defer { previous = line }

      // title page: authors set in capitals, keywords, contact information, license
      if !seenHeading, line.page == titlePage, TextCleanup.looksLikeCapitalizedAuthorLine(line.text) { continue }
      if TextCleanup.startsFrontMatterBlock(line.text) {
        flushParagraph()
        inFrontMatter = true
        continue
      }
      if inFrontMatter {
        // the block runs on in the same type until a gap or a new page
        if let prev = previous, prev.page == line.page, abs(prev.fontSize - line.fontSize) < 0.5,
          line.top - prev.top < prev.height * 2
        {
          continue
        }
        inFrontMatter = false
      }
      // headings
      if let level = headingLevel(line, body: body, next: index < lines.count ? lines[index] : nil) {
        let text = TextCleanup.cleanHeading(line.text)
        // "Anonymous Authors" or an author name set larger than the body
        if !seenHeading, line.page == titlePage, TextCleanup.numberingDepth(line.text) == nil, TextCleanup.looksLikeAuthorLine(text) {
          continue
        }
        if TextCleanup.isReferencesHeading(text) || (!options.includeAppendix && TextCleanup.isAppendixHeading(text)) {
          break
        }
        flushParagraph()
        flushCaption()
        inFrontMatter = false
        inTableBody = false
        inSkippedSection = TextCleanup.isSkippedSection(text)
        if inSkippedSection { continue }
        // multi-line headings: continue while the next line has the same style
        var parts = [text]
        while index < lines.count, abs(lines[index].fontSize - line.fontSize) < 0.3, lines[index].isBold == line.isBold,
          lines[index].page == line.page, lines[index].top - line.top < line.height * 3.2, parts.count < 3,
          headingLevel(lines[index], body: body, next: nil) != nil, TextCleanup.numberingDepth(lines[index].text) == nil
        {
          parts.append(lines[index].text)
          index += 1
        }
        blocks.append(.heading(TextCleanup.joinLines(parts), level: level))
        seenHeading = true
        continue
      }
      if inSkippedSection { continue }
      // IEEE style: "Abstract—Text..." in one line
      if line.text.range(of: #"^(Abstract|Zusammenfassung)\s*[—–\-.:]\s*"#, options: .regularExpression) != nil {
        flushParagraph()
        flushCaption()
        blocks.append(.heading("Abstract", level: 2))
        seenHeading = true
        var l = line
        l.text = l.text.replacingOccurrences(of: #"^(Abstract|Zusammenfassung)\s*[—–\-.:]\s*"#, with: "", options: .regularExpression)
        paragraph.append(l)
        continue
      }
      // captions run until a paragraph break
      if let f = TextCleanup.figureCaption(line.text) {
        flushParagraph()
        flushCaption()
        caption = ("F", f.number, [f.caption])
        continue
      }
      if let t = TextCleanup.tableCaption(line.text) {
        flushParagraph()
        flushCaption()
        caption = ("T", t.number, [t.caption])
        continue
      }
      if caption != nil {
        // table captions sit in small caps below "TABLE I"; figure captions run on in caption size
        let limit = caption!.kind == "T" ? 3 : 8
        if let prev = previous, line.fontSize >= body * 0.6, caption!.lines.count < limit,
          !(caption!.kind == "T" && caption!.lines.count > 1 && line.fontSize >= body * 0.95),
          !isBreak(prev, line, body: body, typicalWidth: typicalWidth) || caption!.lines.allSatisfy(\.isEmpty)
        {
          caption!.lines.append(line.text)
          continue
        }
        flushCaption()
      }
      // stray accents and punctuation split off by math typesetting
      if line.text.filter({ $0.isLetter || $0.isNumber }).count < 2 { continue }
      // display equations (indented or numbered): consecutive math lines become one "Equation n."
      if TextCleanup.isEquationLine(line.text), line.width < typicalWidth * 0.85 || TextCleanup.equationNumber(line.text) != nil {
        var number = TextCleanup.equationNumber(line.text)
        while index < lines.count, lines[index].page == line.page,
          TextCleanup.isEquationLine(lines[index].text) || lines[index].text.filter({ $0.isLetter || $0.isNumber }).count < 2
        {
          number = number ?? TextCleanup.equationNumber(lines[index].text)
          index += 1
        }
        // unnumbered display math is skipped without breaking the sentence around it
        if let number {
          flushParagraph()
          blocks.append(.equation(number: number))
        }
        continue
      }
      if inTableBody {
        // the table ends at a wider gap, after which prose resumes at the column edge
        let gap = previous.map { $0.page == line.page ? line.top - $0.top : 1 } ?? 1
        let jump = gap > (previous?.height ?? 0) * 2 || gap < 0  // a wider gap, or the next column
        let prose = tableRows > 0 && jump && abs(line.fontSize - body) < 0.6
          && margins.contains(where: { abs($0 - line.left) < 0.004 })
        if !prose, previous?.page == line.page {
          tableRows += 1
          continue
        }
        inTableBody = false
      }
      if line.fontSize < body * 0.88 { continue }  // figure labels, footnotes and other small print
      // labels inside figures and table cells sit off the text column's left edge
      // (continuation lines of a list item or paragraph are indented too, but follow it closely)
      let continues = paragraph.last.map { p in
        p.page == line.page && line.top - p.top > 0 && line.top - p.top < p.height * 1.6 && line.left >= p.left - 0.007
      } ?? false
      let bullet = line.text.first.map { "•◦▪–".contains($0) } ?? false
      // ("τ depends only on the": a sentence fragment between inline math stays)
      let prosy = line.text.range(of: #"\b\p{Ll}{2,}\s+\p{Ll}{2,}\b"#, options: .regularExpression) != nil
      if !continues, !bullet, !prosy, line.width < typicalWidth * 0.6, !margins.isEmpty, !margins.contains(where: { abs($0 - line.left) < 0.007 }) {
        continue
      }
      if let prev = paragraph.last, isBreak(prev, line, body: body, typicalWidth: typicalWidth) { flushParagraph() }
      paragraph.append(line)
    }
    flushParagraph()
    flushCaption()
    return SourceDocument(title: title ?? fallbackTitle, blocks: blocks)
  }

  /// Heading level, or nil for body text.
  static func headingLevel(_ l: PDFLine, body: Double, next: PDFLine?) -> Int? {
    let words = l.text.split(separator: " ").count
    guard words <= 16, l.text.count < 140, TextCleanup.looksLikeHeadingText(l.text) else { return nil }
    // "a b c" panel letters inside a figure are not headings
    guard l.text.split(separator: " ").contains(where: { $0.filter(\.isLetter).count >= 3 }) else { return nil }
    if TextCleanup.figureCaption(l.text) != nil || TextCleanup.tableCaption(l.text) != nil { return nil }
    let endsLikeSentence = l.text.hasSuffix(".") && words > 6 || l.text.hasSuffix(",") || l.text.hasSuffix(";")
    if endsLikeSentence { return nil }
    let depth = TextCleanup.numberingDepth(l.text)
    // IEEE/ACM small caps: "I. INTRODUCTION" set smaller than the body
    let letters = l.text.filter(\.isLetter)
    if let depth, letters.count >= 3, letters == letters.uppercased(), l.fontSize >= body * 0.7 { return min(depth, 3) }
    // ACM sets 11 pt section headings over a 10 pt body
    let larger = l.fontSize >= body * 1.08
    if larger {
      if let depth { return min(depth, 3) }
      return l.fontSize >= body * 1.35 ? 1 : 2
    }
    // same size: needs bold, or numbering plus title-like capitalization
    if l.isBold, words <= 12 {
      if let depth { return min(depth, 3) }
      let standalone = next.map { $0.page != l.page || $0.top - l.top > l.height * 1.2 || $0.isBold != l.isBold } ?? true
      return standalone || l.text.uppercased() == l.text ? 2 : nil
    }
    // numbered line on its own: "A. Problem and notation", "2.1 Data"
    let standalone = next.map { $0.page != l.page || $0.top - l.top > l.height * 1.15 || $0.left > l.left + 0.01 } ?? true
    if let depth, words <= 10, abs(l.fontSize - body) < 0.6, standalone || depth >= 1,
      l.text.drop(while: { !$0.isLetter }).first?.isUppercase == true, !l.text.hasSuffix(".")
    {
      return min(depth, 3)
    }
    return nil
  }

  /// Paragraph break between two consecutive lines.
  static func isBreak(_ a: PDFLine, _ b: PDFLine, body: Double, typicalWidth: Double) -> Bool {
    if abs(a.fontSize - b.fontSize) > 1.0 { return true }
    let endsSentence = a.text.last.map { ".!?:".contains($0) } ?? false
    if a.page != b.page {
      return endsSentence && a.width < typicalWidth * 0.8
    }
    let gap = b.top - a.top
    if gap > a.height * 2.0 || gap < -0.05 && endsSentence && a.width < typicalWidth * 0.8 { return true }  // spacing or column jump
    if endsSentence && a.width < typicalWidth * 0.8 { return true }  // short last line
    if abs(b.left - a.left) > 0.015, b.left > a.left, gap > 0, gap < a.height * 2, endsSentence { return true }  // indented first line
    return false
  }

  /// Left edges of the text column(s), including first-line indents: x positions
  /// shared by many body-size lines.
  static func columnMargins(_ lines: [PDFLine], body: Double) -> [Double] {
    let bodyLines = lines.filter { abs($0.fontSize - body) < 0.6 }
    var buckets: [Int: [Double]] = [:]
    for l in bodyLines { buckets[Int((l.left * 200).rounded()), default: []].append(l.left) }
    let minimum = max(5, bodyLines.count / 40)
    return buckets.values.filter { $0.count >= minimum }.compactMap { median($0) }.sorted()
  }

  static func bodySize(_ lines: [PDFLine]) -> Double {
    var weights: [Double: Int] = [:]
    for l in lines { weights[(l.fontSize * 2).rounded() / 2, default: 0] += l.text.count }
    return weights.max { $0.value < $1.value }?.key ?? 10
  }

  static func repeatKey(_ s: String) -> String {
    s.lowercased().replacingOccurrences(of: #"\d+"#, with: "#", options: .regularExpression)
  }

  static func repeatingLines(_ lines: [PDFLine], pageCount: Int) -> Set<String> {
    var pages: [String: Set<Int>] = [:]
    for l in lines where l.top < 0.12 || l.top > 0.88 { pages[repeatKey(l.text), default: []].insert(l.page) }
    let threshold = max(2, pageCount / 3)
    return Set(pages.filter { $0.value.count >= threshold }.map(\.key))
  }

  static func median(_ xs: [Double]) -> Double? {
    guard !xs.isEmpty else { return nil }
    let s = xs.sorted()
    return s[s.count / 2]
  }
}
