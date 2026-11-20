import Foundation

/// Finds the lines of a PDF that a comment's sentence sits on, so the comment can be
/// written back into the PDF as a highlight. The narration differs from the PDF's
/// text layer (ligatures, hyphenation, abbreviations read out, numbers), so each
/// sentence is aligned against every page with the local alignment that refines
/// comment anchors, not searched for as a string. Equations and captions that the
/// narration rewrote may not match and are reported as unplaced.
public enum PdfAnnotationPlacer {
  /// A box on a page, as fractions of the page size, top-left origin like `PDFLine`.
  public struct Rect: Equatable, Sendable {
    public let page: Int
    public let left: Double
    public let top: Double
    public let width: Double
    public let height: Double
  }

  public struct Placement: Equatable, Sendable {
    public let page: Int
    /// One box per line the sentence covers, in reading order.
    public let rects: [Rect]
    /// Fraction of the sentence's words found on the page.
    public let confidence: Double
  }

  /// The pages' text layers as alignment targets, built once per document.
  public struct Pages: Sendable {
    struct Page: Sendable {
      let index: Int
      let text: NarrationText
      let lines: [PDFLine]
      /// Character offset of each line's start in `text`.
      let lineStarts: [Int]
    }

    let pages: [Page]
    public var count: Int { pages.count }

    public init(lines: [PDFLine]) {
      var result: [Page] = []
      for index in Set(lines.map(\.page)).sorted() {
        let pageLines = lines.filter { $0.page == index }
        var starts: [Int] = []
        var joined = ""
        var offset = 0
        for line in pageLines {
          starts.append(offset)
          joined += line.text + "\n"
          offset += line.text.count + 1
        }
        result.append(Page(index: index, text: NarrationText(joined), lines: pageLines, lineStarts: starts))
      }
      pages = result
    }
  }

  /// Where `sentence` sits in the document: the page where most of its words are
  /// found, ties going to the page nearest `expectedPage`.
  /// - Parameters:
  ///   - minimumMatches: fewer matched words on the best page means unplaced.
  ///   - minimumConfidence: a smaller share of matched words means unplaced.
  public static func place(
    _ sentence: String, in pages: Pages, expectedPage: Int? = nil, minimumMatches: Int = 4, minimumConfidence: Double = 0.5
  ) -> Placement? {
    // the aligner accepts min(minimumMatches, query) matches; here a short query is simply unplaceable
    guard TextSegmentation.tokens(sentence).count >= minimumMatches else { return nil }
    var best: (page: Pages.Page, result: ContextAligner.Result)?
    for page in pages.pages {
      guard
        let result = ContextAligner.align(
          transcript: sentence, in: page.text, near: 0, window: page.text.count + 1, minimumMatches: minimumMatches),
        result.confidence >= minimumConfidence
      else { continue }
      if let current = best {
        let closer = abs(page.index - (expectedPage ?? page.index)) < abs(current.page.index - (expectedPage ?? current.page.index))
        guard result.confidence > current.result.confidence || (result.confidence == current.result.confidence && closer) else { continue }
      }
      best = (page, result)
    }
    guard let (page, result) = best else { return nil }
    let words = page.text.words[result.wordRange]
    guard let first = words.first, let last = words.last else { return nil }
    return Placement(page: page.index, rects: rects(from: first.range.lowerBound, to: last.range.upperBound, on: page), confidence: result.confidence)
  }

  /// Boxes covering characters `from..<to` of the page text: a full line for every
  /// line in between, a partial one (by character share) for the first and last.
  static func rects(from: Int, to: Int, on page: Pages.Page) -> [Rect] {
    var result: [Rect] = []
    for (i, line) in page.lines.enumerated() {
      let start = page.lineStarts[i]
      let end = start + line.text.count
      guard end > from, start < to, line.text.count > 0 else { continue }
      let f0 = Double(max(from, start) - start) / Double(line.text.count)
      let f1 = Double(min(to, end) - start) / Double(line.text.count)
      result.append(Rect(page: line.page, left: line.left + line.width * f0, top: line.top, width: line.width * (f1 - f0), height: line.height))
    }
    return result
  }
}
