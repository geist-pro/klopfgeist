import AVFoundation
import MarginVoiceCore
import Foundation
import Observation
import SwiftData
import UniformTypeIdentifiers
import os

/// Imports books into the app container and loads their text lazily.
@MainActor
@Observable
final class BookStore {
  private static let log = Logger.drafttalk("store")
  enum ImportError: LocalizedError {
    case noAudio([String])

    var errorDescription: String? {
      switch self {
      case .noAudio(let names):
        "No audiobook found for \(names.joined(separator: ", ")). Select the .m4b together with its .txt file."
      }
    }
  }

  nonisolated static var importTypes: [UTType] {
    [UTType(filenameExtension: "m4b"), .mpeg4Audio, .audio, .plainText, .json, .draftTalkBook].compactMap { $0 }
  }

  let baseURL: URL
  private var contentCache: [UUID: BookContent] = [:]
  private var loading: [UUID: Task<BookContent?, Never>] = [:]

  /// - Parameter baseURL: where book folders live; `Application Support/Books` unless a test passes its own.
  init(baseURL: URL? = nil) {
    let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    self.baseURL = baseURL ?? support.appendingPathComponent("Books", isDirectory: true)
    do {
      try FileManager.default.createDirectory(at: self.baseURL, withIntermediateDirectories: true)
    } catch {
      Self.log.error("books folder: \(error.localizedDescription, privacy: .public)")
    }
  }

  func folderURL(_ book: Book) -> URL { baseURL.appendingPathComponent(book.folder, isDirectory: true) }
  func audioURL(_ book: Book) -> URL { folderURL(book).appendingPathComponent(book.audioFile) }
  func commentsURL(_ book: Book) -> URL { folderURL(book).appendingPathComponent("comments", isDirectory: true) }

  func audioURL(_ comment: Comment, in book: Book) -> URL? {
    comment.audioFile.map { commentsURL(book).appendingPathComponent($0) }
  }

  /// A new, empty book folder.
  func makeBookFolder() throws -> (name: String, url: URL) {
    let name = UUID().uuidString
    let url = baseURL.appendingPathComponent(name, isDirectory: true)
    try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return (name, url)
  }

  /// At launch: removes folders of conversions cut off by a crash or kill (no book
  /// refers to them, and they hold no recorded comments).
  func removeLeftovers(context: ModelContext) {
    let fm = FileManager.default
    if let books = try? context.fetch(FetchDescriptor<Book>()) {
      let known = Set(books.map(\.folder))
      let folders = (try? fm.contentsOfDirectory(at: baseURL, includingPropertiesForKeys: nil)) ?? []
      for folder in folders where UUID(uuidString: folder.lastPathComponent) != nil && !known.contains(folder.lastPathComponent) {
        let comments = (try? fm.contentsOfDirectory(atPath: folder.appendingPathComponent("comments").path)) ?? []
        guard comments.isEmpty else { continue }
        Self.log.notice("removing unused book folder \(folder.lastPathComponent, privacy: .public)")
        try? fm.removeItem(at: folder)
      }
    }
  }

  /// At launch: exports and documents copied for conversion from earlier runs.
  static func removeTemporaryFiles() {
    let fm = FileManager.default
    for item in (try? fm.contentsOfDirectory(at: fm.temporaryDirectory, includingPropertiesForKeys: nil)) ?? [] {
      try? fm.removeItem(at: item)
    }
  }

  // MARK: Import

  /// Groups the picked files by name stem (`x.m4b`, `x.txt` / `x.narration.txt`,
  /// `x.timing.json`) and imports one book per audio file.
  func importFiles(_ urls: [URL], into context: ModelContext, defaultRate: Double) async throws -> [Book] {
    let packages = urls.filter { $0.pathExtension.lowercased() == BookPackage.fileExtension }
    let unpacked = try packages.map { url in
      let ok = url.startAccessingSecurityScopedResource()
      defer { if ok { url.stopAccessingSecurityScopedResource() } }
      return try importPackage(url, into: context)
    }
    let files = urls.filter { $0.pathExtension.lowercased() != BookPackage.fileExtension }
    if files.isEmpty { return unpacked }
    return unpacked + (try await importAudiobooks(files, into: context, defaultRate: defaultRate))
  }

  private func importAudiobooks(_ urls: [URL], into context: ModelContext, defaultRate: Double) async throws -> [Book] {
    let (groups, orphans) = BookFiles.group(urls)
    var imported: [Book] = []
    do {
      for g in groups {
        imported.append(try await importBook(stem: g.stem, audio: g.audio, text: g.text, timing: g.timing, context: context, rate: defaultRate))
      }
      if imported.isEmpty, !orphans.isEmpty { throw ImportError.noAudio(orphans) }
      try context.save()
    } catch {
      // all or nothing: no half-imported books left behind
      for book in imported {
        try? FileManager.default.removeItem(at: folderURL(book))
        context.delete(book)
      }
      throw error
    }
    return imported
  }

  // MARK: Inbox

  /// The app's Documents folder: "DraftTalk" in the Files app and in Finder's
  /// file sharing. Files put there are imported or offered for conversion.
  nonisolated static var inboxURL: URL {
    FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
  }

  nonisolated static func isInInbox(_ url: URL) -> Bool {
    url.deletingLastPathComponent().resolvingSymlinksInPath().path == inboxURL.resolvingSymlinksInPath().path
  }

  private static func inboxFiles() -> [URL] {
    let files = (try? FileManager.default.contentsOfDirectory(
      at: inboxURL, includingPropertiesForKeys: [.isRegularFileKey], options: .skipsHiddenFiles)) ?? []
    return files.filter { (try? $0.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true }
  }

  /// Imports books dropped into the app's Documents folder (Finder file sharing,
  /// Files app, or `devicectl device copy`). Imported files are removed from the inbox.
  func importInbox(into context: ModelContext, defaultRate: Double) async -> [Book] {
    var unpacked: [Book] = []
    for url in Self.inboxFiles() where url.pathExtension.lowercased() == BookPackage.fileExtension {
      do {
        unpacked.append(try importPackage(url, into: context))
        try FileManager.default.removeItem(at: url)
      } catch {
        Self.log.error("inbox package: \(error.localizedDescription, privacy: .public)")
      }
    }
    let ready = BookFiles.sortInbox(Self.inboxFiles()).audiobookFiles
    guard !ready.isEmpty else { return unpacked }
    do {
      let books = unpacked + (try await importAudiobooks(ready, into: context, defaultRate: defaultRate))
      let groups = BookFiles.group(ready).books
      // only what was imported: a second audio file stays
      for url in groups.flatMap({ [$0.audio, $0.text, $0.timing].compactMap(\.self) }) {
        do { try FileManager.default.removeItem(at: url) } catch { Self.log.error("inbox cleanup: \(error.localizedDescription, privacy: .public)") }
      }
      // the paper under the same name becomes the book's source.pdf (comments are written back into it)
      for (group, book) in zip(groups, books.dropFirst(unpacked.count)) {
        guard let pdf = ready.first(where: { $0.pathExtension.lowercased() == "pdf" && BookFiles.stem(of: $0) == group.stem }) else { continue }
        do {
          try FileManager.default.moveItem(at: pdf, to: folderURL(book).appendingPathComponent("source.pdf"))
        } catch {
          Self.log.error("inbox pdf: \(error.localizedDescription, privacy: .public)")
        }
      }
      return books
    } catch {
      Self.log.error("inbox import: \(error.localizedDescription, privacy: .public)")
      return unpacked
    }
  }

  var inboxHasAudiobooks: Bool {
    let files = Self.inboxFiles()
    return !BookFiles.sortInbox(files).audiobookFiles.isEmpty || files.contains { $0.pathExtension.lowercased() == BookPackage.fileExtension }
  }

  /// Documents in the inbox that aren't part of an audiobook: PDFs, Markdown,
  /// RTF and plain text to turn into new books.
  func inboxDocuments() -> [URL] {
    BookFiles.sortInbox(Self.inboxFiles()).documents
  }

  /// Copies files dragged onto the app (from a Mac via Universal Control, or from
  /// another app in Split View) into the inbox, where the inbox watcher picks them up.
  nonisolated static func receiveDrop(_ providers: [NSItemProvider]) -> Bool {
    let accepted = importTypes + DocumentExtractor.supportedTypes
    var any = false
    for provider in providers {
      guard let type = provider.registeredContentTypes.first(where: { t in accepted.contains { t.conforms(to: $0) } }) else { continue }
      any = true
      _ = provider.loadFileRepresentation(for: type, openInPlace: false) { url, _, _ in
        guard let url else { return }
        do {
          try copyIntoInbox(url)
        } catch {
          Logger.drafttalk("store").error("drop: \(error.localizedDescription, privacy: .public)")
        }
      }
    }
    return any
  }

  /// A file dropped again under the same name replaces the earlier copy, so
  /// `x.m4b`, `x.txt` and `x.timing.json` stay paired by name. The copy is made
  /// first, so a failed copy leaves the earlier file in place.
  private nonisolated static func copyIntoInbox(_ url: URL) throws {
    let fm = FileManager.default
    let destination = inboxURL.appendingPathComponent(url.lastPathComponent)
    let staging = fm.temporaryDirectory.appendingPathComponent(UUID().uuidString + "-" + url.lastPathComponent)
    try fm.copyItem(at: url, to: staging)
    if fm.fileExists(atPath: destination.path) {
      _ = try fm.replaceItemAt(destination, withItemAt: staging)
    } else {
      try fm.moveItem(at: staging, to: destination)
    }
  }

  private func importBook(stem: String, audio: URL, text: URL?, timing: URL?, context: ModelContext, rate: Double) async throws -> Book {
    let folderName = UUID().uuidString
    let folder = baseURL.appendingPathComponent(folderName, isDirectory: true)
    try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
    var finished = false
    defer { if !finished { try? FileManager.default.removeItem(at: folder) } }
    func copy(_ url: URL, as name: String) throws {
      let ok = url.startAccessingSecurityScopedResource()
      defer { if ok { url.stopAccessingSecurityScopedResource() } }
      try FileManager.default.copyItem(at: url, to: folder.appendingPathComponent(name))
    }
    let audioName = "book." + audio.pathExtension.lowercased()
    try copy(audio, as: audioName)
    if let text { try copy(text, as: "narration.txt") }
    if let timing { try copy(timing, as: "timing.json") }

    let asset = AVURLAsset(url: folder.appendingPathComponent(audioName))
    let duration = (try? await asset.load(.duration).seconds).flatMap { $0.isFinite ? $0 : nil } ?? 0
    let chapters = await Self.loadChapters(asset, duration: duration)
    var title = stem.replacingOccurrences(of: "_", with: " ")
    if let metaTitle = try? await Self.metadataTitle(asset), !metaTitle.isEmpty { title = metaTitle }
    var language: String?
    if text != nil, let s = try? String(contentsOf: folder.appendingPathComponent("narration.txt"), encoding: .utf8) {
      language = VoiceCommand.Language.detect(s).rawValue
    }
    let book = Book(
      title: title, folder: folderName, audioFile: audioName, textFile: text == nil ? nil : "narration.txt",
      timingFile: timing == nil ? nil : "timing.json", language: language, duration: duration, chapters: chapters, rate: rate)
    context.insert(book)
    finished = true
    return book
  }

  static func metadataTitle(_ asset: AVURLAsset) async throws -> String? {
    let items = try await asset.load(.commonMetadata)
    guard let item = AVMetadataItem.metadataItems(from: items, filteredByIdentifier: .commonIdentifierTitle).first else { return nil }
    return try await item.load(.stringValue)
  }

  static func loadChapters(_ asset: AVURLAsset, duration: Double) async -> [ChapterMark] {
    var groups = (try? await asset.loadChapterMetadataGroups(bestMatchingPreferredLanguages: Locale.preferredLanguages + ["en", "de", "und"])) ?? []
    if groups.isEmpty, let locales = try? await asset.load(.availableChapterLocales), let locale = locales.first {
      groups = (try? await asset.loadChapterMetadataGroups(withTitleLocale: locale, containingItemsWithCommonKeys: [.commonKeyTitle])) ?? []
    }
    var marks: [ChapterMark] = []
    for (i, g) in groups.enumerated() {
      let item = AVMetadataItem.metadataItems(from: g.items, filteredByIdentifier: .commonIdentifierTitle).first
      let title = (try? await item?.load(.stringValue)) ?? nil
      let start = g.timeRange.start.seconds
      let end = g.timeRange.end.seconds
      marks.append(ChapterMark(title: title ?? "Chapter \(i + 1)", start: start, end: end.isFinite ? end : duration))
    }
    return marks
  }

  // MARK: Content

  func cachedContent(for book: Book) -> BookContent? { contentCache[book.id] }

  /// Parses the narration text off the main actor; cached per book.
  func content(for book: Book) async -> BookContent? {
    if let c = contentCache[book.id] { return c }
    if let task = loading[book.id] { return await task.value }
    guard let textFile = book.textFile else { return nil }
    let folder = folderURL(book)
    let chapters = book.chapters, duration = book.duration, timingFile = book.timingFile
    let task = Task.detached(priority: .userInitiated) { () -> BookContent? in
      guard let s = try? String(contentsOf: folder.appendingPathComponent(textFile), encoding: .utf8) else { return nil }
      let sidecar = timingFile.flatMap { try? TimingSidecar.decode(Data(contentsOf: folder.appendingPathComponent($0))) }
      return BookContent(text: NarrationText(s), chapters: chapters, duration: duration, sidecar: sidecar)
    }
    loading[book.id] = task
    let content = await task.value
    loading[book.id] = nil
    contentCache[book.id] = content
    return content
  }

  /// Files go only after the deletion is saved, so a failed save never leaves
  /// a book or comment whose audio is gone.
  func delete(_ book: Book, context: ModelContext) {
    let folder = folderURL(book)
    let id = book.id
    context.delete(book)
    guard context.saveLogged("delete book") else {
      context.rollback()
      return
    }
    contentCache[id] = nil
    removeFile(folder)
  }

  func delete(_ comment: Comment, context: ModelContext) {
    let audio = comment.book.flatMap { audioURL(comment, in: $0) }
    context.delete(comment)
    guard context.saveLogged("delete comment") else {
      context.rollback()
      return
    }
    if let audio { removeFile(audio) }
  }

  private func removeFile(_ url: URL) {
    do { try FileManager.default.removeItem(at: url) } catch { Self.log.error("remove \(url.lastPathComponent, privacy: .public): \(error.localizedDescription, privacy: .public)") }
  }
}
