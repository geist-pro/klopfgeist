#if canImport(PDFKit)
  import Foundation
  import PDFKit
  #if canImport(UIKit)
    import UIKit
    private typealias PlatformFont = UIFont
  #else
    import AppKit
    private typealias PlatformFont = NSFont
  #endif

  /// Reads the text layer of a PDF as lines with font size, weight and position.
  /// Returns no lines for scanned PDFs (no text layer); use OCR for those.
  public enum PDFLineReader {
    public static func lines(of document: PDFDocument, progress: ((Int, Int) -> Void)? = nil) -> [PDFLine] {
      var result: [PDFLine] = []
      for p in 0..<document.pageCount {
        progress?(p, document.pageCount)
        guard let page = document.page(at: p), let attributed = page.attributedString else { continue }
        let bounds = page.bounds(for: .mediaBox)
        let ns = attributed.string as NSString
        var location = 0
        while location < ns.length {
          var lineRange = ns.lineRange(for: NSRange(location: location, length: 0))
          location = NSMaxRange(lineRange)
          // trim the line break
          while lineRange.length > 0, let scalar = UnicodeScalar(ns.character(at: NSMaxRange(lineRange) - 1)),
            CharacterSet.newlines.contains(scalar)
          {
            lineRange.length -= 1
          }
          guard lineRange.length > 0 else { continue }
          let text = ns.substring(with: lineRange)
          guard text.contains(where: { !$0.isWhitespace }) else { continue }
          let (size, bold) = typography(attributed, range: lineRange)
          var rect = CGRect.zero
          if let selection = page.selection(for: lineRange) { rect = selection.bounds(for: page) }
          guard rect.width > 0, bounds.height > 0 else { continue }
          result.append(
            PDFLine(
              page: p, text: text, fontSize: size, isBold: bold,
              top: 1 - (rect.maxY - bounds.minY) / bounds.height, left: (rect.minX - bounds.minX) / bounds.width,
              width: rect.width / bounds.width, height: rect.height / bounds.height))
        }
      }
      return result
    }

    /// Character-weighted dominant font size and boldness of a line.
    private static func typography(_ s: NSAttributedString, range: NSRange) -> (Double, Bool) {
      var sizes: [Double: Int] = [:]
      var boldChars = 0
      s.enumerateAttribute(.font, in: range) { value, r, _ in
        guard let font = value as? PlatformFont else { return }
        let n = r.length
        sizes[(Double(font.pointSize) * 2).rounded() / 2, default: 0] += n
        let name = font.fontName.lowercased()
        #if canImport(UIKit)
          let traitBold = font.fontDescriptor.symbolicTraits.contains(.traitBold)
        #else
          let traitBold = font.fontDescriptor.symbolicTraits.contains(.bold)
        #endif
        if traitBold || name.contains("bold") || name.contains("semibold") || name.contains("black") || name.contains("heavy")
          || name.hasSuffix(".b") || name.contains("cmbx")
        {
          boldChars += n
        }
      }
      let size = sizes.max { $0.value < $1.value }?.key ?? 10
      return (size, boldChars * 2 > range.length)
    }
  }
#endif
