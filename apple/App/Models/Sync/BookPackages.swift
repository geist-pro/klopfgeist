import MarginVoiceCore
import Foundation
import SwiftData
import UniformTypeIdentifiers

// The app's model ↔ the package format (`BookPackage`). Offsets are Characters here
// and code points in packages, so the narration text comes along for the conversion.

extension UTType {
  /// A `.drafttalk` book: a zip with the audio, text, comments and recordings.
  static let draftTalkBook = UTType(exportedAs: "de.kaikunze.drafttalk.book", conformingTo: .zip)
}

extension Book {
  func packaged(hasSource: Bool, updatedAt: Date) -> PackagedBook {
    PackagedBook(
      addedAt: BookPackage.date(addedAt), audioFile: audioFile, chapters: chapters, duration: duration, id: id.uuidString,
      language: language, lastOpened: lastOpened.map(BookPackage.date), position: position, rate: rate,
      sourceFile: hasSource ? BookPackage.sourcePdf : nil, textFile: textFile, timingFile: timingFile, title: title,
      updatedAt: BookPackage.date(updatedAt), voiceName: voiceName)
  }

  /// Takes the changeable fields of a newer copy of the same book.
  func update(from b: PackagedBook) {
    title = b.title
    duration = b.duration
    position = b.position
    rate = b.rate
    lastOpened = b.lastOpened.map(BookPackage.date)
  }

  static func make(_ b: PackagedBook, folder: String) -> Book {
    let book = Book(
      title: b.title, folder: folder, audioFile: b.audioFile, textFile: b.textFile, timingFile: b.timingFile, language: b.language,
      duration: b.duration, chapters: b.chapters, rate: b.rate)
    if let id = UUID(uuidString: b.id) { book.id = id }
    book.voiceName = b.voiceName
    book.position = b.position
    book.addedAt = BookPackage.date(b.addedAt)
    book.lastOpened = b.lastOpened.map(BookPackage.date)
    return book
  }
}

extension Comment {
  func packaged(text: [Character]?, updatedAt: Date) -> PackagedComment {
    func cp(_ o: Int?) -> Int? { o.map { v in text.map { OffsetUnits.toCodePoints($0, v) } ?? v } }
    return PackagedComment(
      audioFile: audioFile, chapter: chapter, confidence: confidence, createdAt: BookPackage.date(createdAt), duration: duration,
      endEstimatedOffset: cp(endEstimatedOffset), endMediaTime: endMediaTime, estimatedOffset: cp(estimatedOffset),
      id: id.uuidString, kind: kindRaw, mediaTime: mediaTime, refinedOffset: cp(refinedOffset), reviewTag: reviewTag,
      sentenceNudge: sentenceNudge, transcript: transcript, transcriptStatus: transcriptStatusRaw, updatedAt: BookPackage.date(updatedAt))
  }

  /// Takes every field of `c` but the identity (new comments get theirs from `make`).
  func update(from c: PackagedComment, text: [Character]?) {
    func chars(_ o: Int?) -> Int? { o.map { v in text.map { OffsetUnits.fromCodePoints($0, v) } ?? v } }
    confidence = c.confidence
    transcript = c.transcript
    transcriptStatusRaw = c.transcriptStatus
    refinedOffset = chars(c.refinedOffset)
    sentenceNudge = c.sentenceNudge
    endMediaTime = c.endMediaTime
    endEstimatedOffset = chars(c.endEstimatedOffset)
    reviewTag = c.reviewTag
  }

  static func make(_ c: PackagedComment, text: [Character]?) -> Comment {
    func chars(_ o: Int?) -> Int? { o.map { v in text.map { OffsetUnits.fromCodePoints($0, v) } ?? v } }
    let comment = Comment(
      kind: CommentRecord.Kind(rawValue: c.kind) ?? .comment, mediaTime: c.mediaTime, estimatedOffset: chars(c.estimatedOffset),
      confidence: c.confidence, chapter: c.chapter, audioFile: c.audioFile, duration: c.duration)
    if let id = UUID(uuidString: c.id) { comment.id = id }
    comment.createdAt = BookPackage.date(c.createdAt)
    comment.update(from: c, text: text)
    return comment
  }
}

extension BookStore {
  /// The narration as `NarrationText` indexes it (CRLF as LF), for converting offsets.
  nonisolated static func narrationCharacters(_ url: URL) -> [Character]? {
    (try? String(contentsOf: url, encoding: .utf8)).map { Array($0.replacingOccurrences(of: "\r\n", with: "\n")) }
  }

  func narrationCharacters(_ book: Book) -> [Character]? {
    book.textFile.flatMap { Self.narrationCharacters(folderURL(book).appendingPathComponent($0)) }
  }

  func hasSource(_ book: Book) -> Bool {
    FileManager.default.fileExists(atPath: folderURL(book).appendingPathComponent(BookPackage.sourcePdf).path)
  }

  /// The book with its comments and recordings as a `.drafttalk` file.
  func exportPackage(_ book: Book, to url: URL) throws {
    let text = narrationCharacters(book)
    let folder = folderURL(book)
    let names = [book.audioFile, book.textFile, book.timingFile, hasSource(book) ? BookPackage.sourcePdf : nil].compactMap(\.self)
    let recordings = book.comments.compactMap(\.audioFile).filter {
      FileManager.default.fileExists(atPath: commentsURL(book).appendingPathComponent($0).path)
    }
    let files =
      names.map { ZipArchive.Entry.file($0, folder.appendingPathComponent($0)) }
      + recordings.map { ZipArchive.Entry.file("\(BookPackage.commentsDirectory)/\($0)", commentsURL(book).appendingPathComponent($0)) }
    let entries = try BookPackage.entries(
      book: book.packaged(hasSource: hasSource(book), updatedAt: Date()),
      comments: book.comments.map { $0.packaged(text: text, updatedAt: $0.createdAt) }, files: files)
    try ZipArchive.write(entries, to: url)
  }

  /// Imports a `.drafttalk` file. A book that is already in the library gets the
  /// comments it lacks; those it has stay as they are here.
  func importPackage(_ url: URL, into context: ModelContext) throws -> Book {
    let (folder, dir) = try makeBookFolder()
    let unpacked: BookPackage.Unpacked
    do {
      unpacked = try BookPackage.unzip(url, into: dir)
    } catch {
      try? FileManager.default.removeItem(at: dir)
      throw error
    }
    let fm = FileManager.default
    let id = UUID(uuidString: unpacked.book.id) ?? UUID()
    if let existing = try context.fetch(FetchDescriptor<Book>(predicate: #Predicate { $0.id == id })).first {
      let text = narrationCharacters(existing)
      let known = Set(existing.comments.map(\.id))
      for c in unpacked.comments where !known.contains(UUID(uuidString: c.id) ?? UUID()) {
        if let name = c.audioFile {
          let from = dir.appendingPathComponent("\(BookPackage.commentsDirectory)/\(name)")
          let to = commentsURL(existing).appendingPathComponent(name)
          try? fm.createDirectory(at: commentsURL(existing), withIntermediateDirectories: true)
          if !fm.fileExists(atPath: to.path) { try? fm.moveItem(at: from, to: to) }
        }
        let comment = Comment.make(c, text: text)
        context.insert(comment)
        comment.book = existing
      }
      try context.save()
      try? fm.removeItem(at: dir)
      return existing
    }
    try? fm.removeItem(at: dir.appendingPathComponent(BookPackage.bookFile))
    for f in (try? fm.contentsOfDirectory(at: dir.appendingPathComponent(BookPackage.commentsDirectory), includingPropertiesForKeys: nil)) ?? []
    where f.pathExtension == "json" {
      try? fm.removeItem(at: f)
    }
    let text = unpacked.book.textFile.flatMap { Self.narrationCharacters(dir.appendingPathComponent($0)) }
    let book = Book.make(unpacked.book, folder: folder)
    context.insert(book)
    for c in unpacked.comments {
      let comment = Comment.make(c, text: text)
      context.insert(comment)
      comment.book = book
    }
    do {
      try context.save()
    } catch {
      context.rollback()
      try? fm.removeItem(at: dir)
      throw error
    }
    return book
  }
}
