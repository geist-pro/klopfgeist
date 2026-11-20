import MarginVoiceCore
import Foundation
import PDFKit
#if canImport(UIKit)
  import UIKit
  private typealias PlatformColor = UIColor
#else
  import AppKit
  private typealias PlatformColor = NSColor
#endif

/// Writes a book's comments into its source PDF: a highlight on the sentence each
/// comment was made on (coloured by tag, bookmarks yellow) with a note in the margin
/// carrying the tag, time and transcript. Comments whose sentence is not found in
/// the PDF are listed in one note on the first page.
enum PDFAnnotator {
  struct Summary: Equatable {
    var placed = 0
    var unplaced = 0
  }

  enum Failure: LocalizedError {
    case unreadable, noTextLayer, writeFailed

    var errorDescription: String? {
      switch self {
      case .unreadable: "The PDF could not be opened."
      case .noTextLayer: "This PDF has no text layer, so the comments cannot be placed."
      case .writeFailed: "The annotated PDF could not be written."
      }
    }
  }

  static func annotate(
    source: URL, to output: URL, comments: [CommentRecord], text: NarrationText, language: VoiceCommand.Language
  ) throws -> Summary {
    guard let document = PDFDocument(url: source) else { throw Failure.unreadable }
    let lines = PDFLineReader.lines(of: document)
    guard !lines.isEmpty else { throw Failure.noTextLayer }
    let pages = PdfAnnotationPlacer.Pages(lines: lines)
    var summary = Summary()
    var missing: [String] = []

    for c in comments {
      let sentence = c.quotedText(in: text)
      let expected = text.count > 0 ? Int(Double(c.textOffset) / Double(text.count) * Double(document.pageCount)) : nil
      guard !sentence.isEmpty, let placement = PdfAnnotationPlacer.place(sentence, in: pages, expectedPage: expected),
        let page = document.page(at: placement.page)
      else {
        summary.unplaced += 1
        missing.append(label(c, language))
        continue
      }
      let bounds = page.bounds(for: .mediaBox)
      let color = tint(c)
      var first: CGRect?
      for r in placement.rects {
        let rect = CGRect(
          x: bounds.minX + r.left * bounds.width, y: bounds.minY + (1 - r.top - r.height) * bounds.height,
          width: r.width * bounds.width, height: r.height * bounds.height)
        let highlight = PDFAnnotation(bounds: rect, forType: .highlight, withProperties: nil)
        highlight.color = color
        // PDFKit takes the quad relative to the bounds origin (upper-left, upper-right,
        // lower-left, lower-right) and writes page-space QuadPoints for other viewers
        highlight.quadrilateralPoints = [
          CGPoint(x: 0, y: rect.height), CGPoint(x: rect.width, y: rect.height),
          CGPoint(x: 0, y: 0), CGPoint(x: rect.width, y: 0),
        ].map(value)
        highlight.contents = label(c, language)
        highlight.userName = "Klopfgeist"
        page.addAnnotation(highlight)
        if first == nil { first = rect }
      }
      if let first {
        // the note sits in the margin left of the first line
        let side = min(18.0, first.height * 1.4)
        let x = max(bounds.minX + 2, first.minX - side - 4)
        let note = PDFAnnotation(bounds: CGRect(x: x, y: first.maxY - side, width: side, height: side), forType: .text, withProperties: nil)
        note.iconType = .note
        note.color = color
        note.contents = label(c, language)
        note.userName = "Klopfgeist"
        page.addAnnotation(note)
      }
      summary.placed += 1
    }

    if !missing.isEmpty, let page = document.page(at: 0) {
      let bounds = page.bounds(for: .mediaBox)
      let note = PDFAnnotation(bounds: CGRect(x: bounds.minX + 8, y: bounds.maxY - 28, width: 20, height: 20), forType: .text, withProperties: nil)
      note.iconType = .comment
      note.color = PlatformColor(red: 0.66, green: 0.60, blue: 0.52, alpha: 1)
      note.contents = (language == .german ? "Nicht im PDF gefunden:\n" : "Not found in the PDF:\n") + missing.map { "• \($0)" }.joined(separator: "\n")
      note.userName = "Klopfgeist"
      page.addAnnotation(note)
    }

    guard document.write(to: output) else { throw Failure.writeFailed }
    return summary
  }

  /// "[Weakness] 12:34 · the transcript", "Bookmark 1:00–1:20".
  static func label(_ c: CommentRecord, _ language: VoiceCommand.Language) -> String {
    let time = c.endMediaTime.map { "\(TimeFormat.clock(c.mediaTime))–\(TimeFormat.clock($0))" } ?? TimeFormat.clock(c.mediaTime)
    if c.kind == .bookmark { return "\(language == .german ? "Lesezeichen" : "Bookmark") \(time)" }
    let t = c.transcript.trimmingCharacters(in: .whitespacesAndNewlines)
    let body = t.isEmpty ? (language == .german ? "(nicht transkribiert)" : "(not transcribed)") : t
    let tag = c.tag.map { "[\($0.displayName(language))] " } ?? ""
    return "\(tag)\(time) · \(body)"
  }

  /// Gruvbox-ish highlight colours by tag; bookmarks yellow, plain notes orange.
  private static func tint(_ c: CommentRecord) -> PlatformColor {
    let (r, g, b): (CGFloat, CGFloat, CGFloat)
    switch (c.kind, c.tag) {
    case (.bookmark, _): (r, g, b) = (0.98, 0.74, 0.18)
    case (_, .strength): (r, g, b) = (0.72, 0.73, 0.15)
    case (_, .weakness): (r, g, b) = (0.98, 0.29, 0.20)
    case (_, .question): (r, g, b) = (0.51, 0.65, 0.60)
    case (_, .minor): (r, g, b) = (0.66, 0.60, 0.52)
    case (_, .summary), (_, nil): (r, g, b) = (1.0, 0.50, 0.10)
    }
    return PlatformColor(red: r, green: g, blue: b, alpha: 1)
  }

  private static func value(_ point: CGPoint) -> NSValue {
    #if canImport(UIKit)
      NSValue(cgPoint: point)
    #else
      NSValue(point: point)
    #endif
  }
}
