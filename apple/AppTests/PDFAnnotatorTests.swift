import MarginVoiceCore
import Foundation
import PDFKit
import Testing
import UIKit
@testable import MarginVoice

struct PDFAnnotatorTests {
  /// A one-page PDF with a text layer, three lines of body text.
  static let lines = [
    "The first line introduces the method and its goal.",
    "The second line reports that the baseline was never tuned.",
    "The third line closes with a remark about future work.",
  ]

  func makePDF() throws -> URL {
    let page = CGRect(x: 0, y: 0, width: 612, height: 792)
    let data = UIGraphicsPDFRenderer(bounds: page).pdfData { ctx in
      ctx.beginPage()
      for (i, line) in Self.lines.enumerated() {
        NSAttributedString(string: line, attributes: [.font: UIFont.systemFont(ofSize: 12)]).draw(at: CGPoint(x: 72, y: 100 + 20 * CGFloat(i)))
      }
    }
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("annotate-\(UUID().uuidString).pdf")
    try data.write(to: url)
    return url
  }

  @Test func exportWritesAnnotatedPDF() throws {
    let source = try makePDF()
    let text = NarrationText(Self.lines.joined(separator: " ") + "\n")
    // a weakness on the second sentence, a bookmark over the first, and one comment whose sentence is not in the PDF
    let second = text.sentences[1]
    let weakness = CommentRecord(mediaTime: 65, textOffset: second.range.upperBound, confidence: 1, transcript: "Tune the baseline.", tag: .weakness)
    let bookmark = CommentRecord(kind: .bookmark, mediaTime: 10, textOffset: 5, confidence: 1, transcript: "", endMediaTime: 20, endTextOffset: text.sentences[0].range.upperBound - 1)
    let elsewhere = CommentRecord(mediaTime: 90, textOffset: 0, confidence: 1, transcript: "Lost.")
    let lost = NarrationText("A sentence that the PDF does not contain at all anywhere.\n")

    let output = source.deletingLastPathComponent().appendingPathComponent("out-\(UUID().uuidString).pdf")
    let summary = try PDFAnnotator.annotate(source: source, to: output, comments: [weakness, bookmark], text: text, language: .english)
    #expect(summary == .init(placed: 2, unplaced: 0))
    let lostSummary = try PDFAnnotator.annotate(source: source, to: output, comments: [elsewhere], text: lost, language: .english)
    #expect(lostSummary == .init(placed: 0, unplaced: 1))

    _ = try PDFAnnotator.annotate(source: source, to: output, comments: [weakness, bookmark], text: text, language: .english)
    let doc = try #require(PDFDocument(url: output))
    let annotations = try #require(doc.page(at: 0)).annotations
    let highlights = annotations.filter { $0.type == "Highlight" }
    let notes = annotations.filter { $0.type == "Text" }
    #expect(highlights.count == 2 && notes.count == 2)
    let weak = try #require(highlights.first { $0.contents?.contains("[Weakness] 1:05 · Tune the baseline.") == true })
    // the second line was drawn 20 pt below the first, at y = 120 from the top: its box lies around 792 - 120 - 12
    #expect(weak.bounds.midY > 792 - 140 && weak.bounds.midY < 792 - 110)
    #expect(weak.bounds.minX > 60 && weak.bounds.maxX < 500)

    // the file carries QuadPoints in page space (what other viewers read), matching the bounds
    let raw = try String(decoding: Data(contentsOf: output), as: UTF8.self)
    let quads = raw.ranges(of: "/QuadPoints")
    print("QuadPoints entries in file:", quads.count)
    if let first = quads.first {
      let tail = raw[first.upperBound...].prefix(200)
      let numbers = tail.split(whereSeparator: { !"0123456789.-".contains($0) }).compactMap { Double($0) }.prefix(8)
      print("first QuadPoints:", Array(numbers), "bounds:", highlights.map(\.bounds))
      #expect(numbers.count == 8)
      #expect(numbers.allSatisfy { $0 >= 0 && $0 <= 792 })
      // the quad is the bounds of one of the highlights: x from minX to maxX, y from minY to maxY
      let quad = Array(numbers)
      let match = highlights.first { abs($0.bounds.minX - quad[0]) < 0.5 && abs($0.bounds.maxY - quad[1]) < 0.5 }
      #expect(match != nil)
      if let match {
        #expect(abs(match.bounds.maxX - quad[2]) < 0.5 && abs(match.bounds.minY - quad[5]) < 0.5)
      }
    }
  }
}
