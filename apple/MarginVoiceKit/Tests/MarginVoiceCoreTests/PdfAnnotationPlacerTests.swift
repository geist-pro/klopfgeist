import Foundation
import PDFKit
import Testing
@testable import MarginVoiceCore

struct PdfAnnotationPlacerTests {
  func icraPages() throws -> (PdfAnnotationPlacer.Pages, Int) {
    let url = try #require(Bundle.module.url(forResource: "icra", withExtension: "pdf", subdirectory: "Fixtures"))
    let pdf = try #require(PDFDocument(url: url))
    return (PdfAnnotationPlacer.Pages(lines: PDFLineReader.lines(of: pdf)), pdf.pageCount)
  }

  /// Sentences of the doc2audio narration (which rewrote the PDF's text) land on the
  /// right page, on boxes inside the page, mostly in reading order.
  @Test(.enabled(if: Fixture.hasPrivate)) func placesASentenceOnItsLines() throws {
    let (pages, pageCount) = try icraPages()
    let text = try Fixture.text("icra")
    // the body sentences of the first pages, long enough to align
    let sentences = text.sentences.prefix(60).map { text.substring($0.range) }
      .filter { !$0.hasPrefix("Section:") && TextSegmentation.tokens($0).count >= 8 }
    #expect(sentences.count >= 30)
    var placed: [PdfAnnotationPlacer.Placement] = []
    var unplaced: [String] = []
    for s in sentences {
      if let p = PdfAnnotationPlacer.place(s, in: pages, expectedPage: 0) { placed.append(p) } else { unplaced.append(s) }
    }
    print("placed \(placed.count) of \(sentences.count); unplaced:", unplaced.map { $0.prefix(60) })
    #expect(Double(placed.count) >= Double(sentences.count) * 0.8)
    for p in placed {
      #expect(p.page < pageCount && p.page <= 2)
      #expect(!p.rects.isEmpty && p.rects.count <= 8)
      for r in p.rects {
        #expect(r.left >= 0 && r.top >= 0 && r.left + r.width <= 1.001 && r.top + r.height <= 1.001 && r.width > 0)
      }
    }
    // reading order: the pages never go backwards by more than one
    for (a, b) in zip(placed, placed.dropFirst()) { #expect(b.page >= a.page - 1) }

    // the title sits on the first lines of page 1
    let title = try #require(PdfAnnotationPlacer.place(try Fixture.text("icra").string.split(separator: " ").prefix(3).joined(separator: " "), in: pages, minimumMatches: 2))
    #expect(title.page == 0 && title.rects.count == 1 && title.rects[0].top < 0.2)
  }

  @Test(.enabled(if: Fixture.hasPrivate)) func reportsUnplacedSentences() throws {
    let (pages, _) = try icraPages()
    #expect(PdfAnnotationPlacer.place("The quick brown fox jumps over the lazy dog near the river bank.", in: pages) == nil)
    #expect(PdfAnnotationPlacer.place("", in: pages) == nil)
    #expect(PdfAnnotationPlacer.place("prediction", in: pages) == nil)  // one word is not enough
  }

  @Test func partialLinesCoverOnlyTheSentence() {
    let lines = [
      PDFLine(page: 0, text: "One two three four five six seven eight.", fontSize: 10, isBold: false, top: 0.1, left: 0.1, width: 0.8, height: 0.02),
      PDFLine(page: 0, text: "Nine ten eleven twelve.", fontSize: 10, isBold: false, top: 0.13, left: 0.1, width: 0.5, height: 0.02),
    ]
    let pages = PdfAnnotationPlacer.Pages(lines: lines)
    let p = PdfAnnotationPlacer.place("five six seven eight nine ten", in: pages)!
    #expect(p.rects.count == 2)
    // starts at "five" (character 19 of 40 in the first line) and ends after "ten" on the second
    #expect(abs(p.rects[0].left - (0.1 + 0.8 * 19 / 40)) < 0.001)
    #expect(abs(p.rects[0].left + p.rects[0].width - 0.9) < 0.001)
    #expect(abs(p.rects[1].left - 0.1) < 0.001 && p.rects[1].width < 0.5 * 0.45)
  }
}
