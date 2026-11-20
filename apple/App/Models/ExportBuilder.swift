import MarginVoiceCore
import Foundation

/// Writes the selected export files into a fresh temporary folder for sharing.
@MainActor
enum ExportBuilder {
  struct Options: Equatable {
    var annotatedText = true
    var commentList = true
    var csv = false
    var json = false
    var audio = false
    /// The comments sorted by tag into a review draft (`<name>-review.md`).
    var reviewDraft = false
    var reviewLayout = ReviewDraftExport.Layout.generic
    /// Reword each note with the on-device model (Apple Intelligence); the spoken words stay underneath.
    var polish = false
    /// The source PDF with a highlight and note per comment (`<name>-annotated.pdf`).
    var annotatedPDF = false

    var nothingChosen: Bool { !annotatedText && !commentList && !csv && !json && !audio && !reviewDraft && !annotatedPDF }
  }

  /// The PDF a book was converted from, kept as `source.pdf` in its folder (or picked once at export).
  static func sourcePDF(of book: Book, store: BookStore) -> URL? {
    let url = store.folderURL(book).appendingPathComponent("source.pdf")
    return FileManager.default.fileExists(atPath: url.path) ? url : nil
  }

  /// - Parameters:
  ///   - language: the language of the review draft's headings (the command language).
  ///   - polished: reworded transcripts by comment id, from `ReviewPolisher`.
  ///   - summary: a drafted summary paragraph for the review draft.
  ///   - sourcePDF: the PDF to annotate; without one (or without narration text) no annotated PDF is written.
  static func build(
    book: Book, content: BookContent?, store: BookStore, options: Options, language: VoiceCommand.Language = .english,
    polished: [UUID: String] = [:], summary: String? = nil, sourcePDF: URL? = nil
  ) throws -> [URL] {
    let fm = FileManager.default
    let base = fm.temporaryDirectory.appendingPathComponent("export-\(UUID().uuidString)", isDirectory: true)
    try fm.createDirectory(at: base, withIntermediateDirectories: true)
    let name = BookFiles.safeName(book.title)
    let comments = book.sortedComments
    var urls: [URL] = []

    // audio files are numbered in time order and referenced from the exports
    var audioNames: [UUID: String] = [:]
    if options.audio {
      let folder = base.appendingPathComponent("\(name)-audio", isDirectory: true)
      try fm.createDirectory(at: folder, withIntermediateDirectories: true)
      for (i, c) in comments.enumerated() {
        guard let src = store.audioURL(c, in: book), fm.fileExists(atPath: src.path) else { continue }
        let file = String(format: "comment-%02d-%@.m4a", i + 1, TimeFormat.clock(c.mediaTime).replacingOccurrences(of: ":", with: "-"))
        try fm.copyItem(at: src, to: folder.appendingPathComponent(file))
        audioNames[c.id] = "\(name)-audio/\(file)"
      }
      urls.append(folder)
    }

    let records = comments.map { $0.record(text: content?.text, audioName: audioNames[$0.id]) }
    func write(_ string: String, _ file: String) throws {
      let url = base.appendingPathComponent(file)
      try string.write(to: url, atomically: true, encoding: .utf8)
      urls.append(url)
    }
    if options.annotatedText, let content {
      try write(
        AnnotatedTextExport.markdown(text: content.text, comments: records, title: book.title, headingOffsets: content.headingOffsets),
        "\(name)-annotated.md")
    }
    if options.commentList { try write(CommentExport.markdown(title: book.title, comments: records, text: content?.text), "\(name)-comments.md") }
    if options.csv { try write(CommentExport.csv(comments: records, text: content?.text), "\(name)-comments.csv") }
    if options.json {
      let url = base.appendingPathComponent("\(name)-comments.json")
      try CommentExport.json(title: book.title, comments: records).write(to: url)
      urls.append(url)
    }
    if options.reviewDraft {
      try write(
        ReviewDraftExport.markdown(
          title: book.title, comments: records, text: content?.text, layout: options.reviewLayout, language: language,
          polished: polished, summary: summary),
        "\(name)-review.md")
    }
    if options.annotatedPDF, let sourcePDF, let content {
      let url = base.appendingPathComponent("\(name)-annotated.pdf")
      _ = try PDFAnnotator.annotate(source: sourcePDF, to: url, comments: records, text: content.text, language: language)
      urls.append(url)
    }
    return urls
  }
}
