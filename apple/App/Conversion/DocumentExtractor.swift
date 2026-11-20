import MarginVoiceCore
import Foundation
import PDFKit
import UniformTypeIdentifiers
import Vision
#if canImport(UIKit)
  import UIKit
#else
  import AppKit
#endif

/// Reads a document's structure on device: the PDF text layer via PDFKit (with
/// doc2audio's layout heuristics), Vision OCR for scanned PDFs, and plain
/// text, Markdown and RTF.
enum DocumentExtractor {
  enum ExtractError: LocalizedError {
    case unreadable
    case unsupported(String)
    case empty

    var errorDescription: String? {
      switch self {
      case .unreadable: "The document could not be opened."
      case .unsupported(let ext): "\(ext.uppercased()) files can't be converted on the device yet. Export the document as PDF and try again."
      case .empty: "No readable text was found in the document."
      }
    }
  }

  struct Result: Sendable {
    let document: SourceDocument
    let language: String
    let pageCount: Int
    let usedOCR: Bool
  }

  static let supportedTypes: [UTType] = [.pdf, .plainText, .rtf, .rtfd, UTType(filenameExtension: "md")].compactMap { $0 }

  static func canConvert(_ url: URL) -> Bool { BookFiles.isDocument(url) }

  static func extract(_ url: URL, progress: @escaping @Sendable (Double) -> Void) async throws -> Result {
    let stem = url.deletingPathExtension().lastPathComponent.replacingOccurrences(of: "_", with: " ")
    let doc: SourceDocument
    var pages = 0
    var usedOCR = false
    switch url.pathExtension.lowercased() {
    case "pdf":
      guard let pdf = PDFDocument(url: url) else { throw ExtractError.unreadable }
      pages = pdf.pageCount
      let metaTitle = (pdf.documentAttributes?[PDFDocumentAttribute.titleAttribute] as? String).flatMap { $0.count > 8 ? $0 : nil }
      let lines = PDFLineReader.lines(of: pdf) { page, total in progress(Double(page) / Double(max(1, total)) * 0.9) }
      let textChars = lines.reduce(0) { $0 + $1.text.count }
      if textChars > 200 * max(1, pages) / 4 {
        doc = PDFLayoutAnalyzer.document(from: lines, pageCount: pages, fallbackTitle: metaTitle ?? stem)
      } else {
        usedOCR = true
        doc = try await ocr(pdf, fallbackTitle: metaTitle ?? stem, progress: progress)
      }
    case "txt", "md", "markdown":
      guard let s = (try? String(contentsOf: url, encoding: .utf8)) ?? (try? String(contentsOf: url, encoding: .isoLatin1)) else {
        throw ExtractError.unreadable
      }
      doc = SourceDocument.fromPlainText(s, fallbackTitle: stem, markdown: url.pathExtension.lowercased() != "txt")
    case "rtf", "rtfd":
      let attributed = try NSAttributedString(url: url, options: [:], documentAttributes: nil)
      // RTF paragraphs are single line breaks
      let text = attributed.string.replacingOccurrences(of: "\n", with: "\n\n")
      doc = SourceDocument.fromPlainText(text, fallbackTitle: stem)
    default:
      throw ExtractError.unsupported(url.pathExtension)
    }
    guard doc.blocks.count > 0 else { throw ExtractError.empty }
    progress(1)
    return Result(document: doc, language: VoiceCommand.Language.detect(doc.plainText).rawValue, pageCount: pages, usedOCR: usedOCR)
  }

  // MARK: OCR (scanned PDFs)

  private static func ocr(_ pdf: PDFDocument, fallbackTitle: String, progress: @escaping @Sendable (Double) -> Void) async throws -> SourceDocument {
    var blocks: [SourceDocument.Block] = []
    var title: String?
    for i in 0..<pdf.pageCount {
      guard let page = pdf.page(at: i) else { continue }
      let box = page.bounds(for: .mediaBox)
      let scale = 2400 / max(box.width, box.height)
      let image = page.thumbnail(of: CGSize(width: box.width * scale, height: box.height * scale), for: .mediaBox)
      #if canImport(UIKit)
        guard let cg = image.cgImage else { continue }
      #else
        guard let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else { continue }
      #endif
      let observations = try await RecognizeDocumentsRequest().perform(on: cg)
      for obs in observations {
        let container = obs.document
        if i == 0, title == nil, let t = container.title?.transcript, !t.isEmpty { title = TextCleanup.normalize(t) }
        for paragraph in container.paragraphs {
          let text = TextCleanup.normalize(paragraph.transcript.replacingOccurrences(of: "\n", with: " "))
          guard !text.isEmpty, text != title else { continue }
          // running headers and page numbers
          if let top = paragraph.lines.first?.boundingBox.cgRect.maxY, top > 0.94 || top < 0.05, text.count < 80 { continue }
          if TextCleanup.isReferencesHeading(text) { return SourceDocument(title: title ?? fallbackTitle, blocks: blocks) }
          let words = text.split(separator: " ").count
          if let depth = TextCleanup.numberingDepth(text), words <= 12, !text.hasSuffix(".") {
            blocks.append(.heading(TextCleanup.cleanHeading(text), level: min(depth, 3)))
          } else if words <= 6, !text.hasSuffix("."), TextCleanup.looksLikeHeadingText(text), text.first?.isUppercase == true {
            blocks.append(.heading(TextCleanup.cleanHeading(text), level: 2))
          } else if let f = TextCleanup.figureCaption(text) {
            blocks.append(.figure(number: f.number, caption: f.caption))
          } else if let t = TextCleanup.tableCaption(text) {
            blocks.append(.table(number: t.number, caption: t.caption))
          } else if !TextCleanup.isBoilerplate(text) {
            blocks.append(.paragraph(TextCleanup.stripCitations(text)))
          }
        }
        for list in container.lists {
          for item in list.items { blocks.append(.listItem(TextCleanup.normalize(item.itemString))) }
        }
      }
      progress(Double(i + 1) / Double(pdf.pageCount) * 0.9)
    }
    return SourceDocument(title: title ?? fallbackTitle, blocks: blocks)
  }
}
