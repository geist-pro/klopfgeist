import MarginVoiceCore
import Foundation
import SwiftData
import os

/// The SwiftData library as the sync engine sees it.
///
/// The model has no change dates, so this keeps them beside it: per book and comment
/// the hash of its packaged form and when that hash last changed here
/// (`sync-stamps.json`). What arrives from the store takes the store's date.
@MainActor
final class SwiftDataSyncLocal: SyncLocal {
  nonisolated private static let log = Logger.drafttalk("sync")

  struct Stamp: Codable {
    var hash: String
    var updatedAt: Date
  }

  let context: ModelContext
  let store: BookStore
  /// Called before a book that was deleted elsewhere goes (closes it if it is open).
  var willDelete: (Book) -> Void = { _ in }
  private let stampsURL: URL
  private var stamps: [String: Stamp]
  private var texts: [UUID: [Character]?] = [:]

  init(context: ModelContext, store: BookStore, stampsURL: URL) {
    self.context = context
    self.store = store
    self.stampsURL = stampsURL
    stamps = (try? JSONDecoder().decode([String: Stamp].self, from: Data(contentsOf: stampsURL))) ?? [:]
  }

  private func saveStamps() {
    do { try JSONEncoder().encode(stamps).write(to: stampsURL, options: .atomic) } catch {
      Self.log.error("stamps: \(error.localizedDescription, privacy: .public)")
    }
  }

  /// When the item with this content last changed here: now, if the hash is new.
  private func stamp(_ id: String, _ hash: String, fallback: Date) -> Date {
    if let s = stamps[id], s.hash == hash { return s.updatedAt }
    let date = stamps[id] == nil ? fallback : Date()
    stamps[id] = Stamp(hash: hash, updatedAt: date)
    return date
  }

  private func text(_ book: Book) -> [Character]? {
    if let t = texts[book.id] { return t }
    let t = store.narrationCharacters(book)
    texts[book.id] = t
    return t
  }

  private func book(_ id: String) -> Book? {
    guard let uuid = UUID(uuidString: id) else { return nil }
    return try? context.fetch(FetchDescriptor<Book>(predicate: #Predicate { $0.id == uuid })).first
  }

  private func packaged(_ b: Book) -> PackagedBook {
    var p = b.packaged(hasSource: store.hasSource(b), updatedAt: .distantPast)
    p.updatedAt = BookPackage.date(stamp(p.id, BookPackage.hash(p), fallback: b.lastOpened ?? b.addedAt))
    return p
  }

  private func packaged(_ c: Comment, text: [Character]?) -> PackagedComment {
    var p = c.packaged(text: text, updatedAt: .distantPast)
    p.updatedAt = BookPackage.date(stamp(p.id, BookPackage.hash(p), fallback: c.createdAt))
    return p
  }

  /// Records the current state of everything, so a change shows up with the time it was made.
  func stampAll() {
    for b in (try? context.fetch(FetchDescriptor<Book>())) ?? [] {
      _ = packaged(b)
      let t = text(b)
      for c in b.comments { _ = packaged(c, text: t) }
    }
    saveStamps()
  }

  private func adopt(_ b: Book, updatedAt: String) {
    let p = b.packaged(hasSource: store.hasSource(b), updatedAt: .distantPast)
    stamps[p.id] = Stamp(hash: BookPackage.hash(p), updatedAt: BookPackage.date(updatedAt))
  }

  private func adopt(_ c: Comment, text: [Character]?, updatedAt: String) {
    let p = c.packaged(text: text, updatedAt: .distantPast)
    stamps[p.id] = Stamp(hash: BookPackage.hash(p), updatedAt: BookPackage.date(updatedAt))
  }

  // MARK: SyncLocal

  func books() async throws -> [PackagedBook] {
    let result = try context.fetch(FetchDescriptor<Book>()).map(packaged)
    saveStamps()
    return result
  }

  func comments(bookID: String) async throws -> [PackagedComment] {
    guard let b = book(bookID) else { return [] }
    let t = text(b)
    let result = b.comments.map { packaged($0, text: t) }
    saveStamps()
    return result
  }

  func file(bookID: String, path: String) async -> URL? {
    guard let b = book(bookID) else { return nil }
    let url = store.folderURL(b).appendingPathComponent(path)
    return FileManager.default.fileExists(atPath: url.path) ? url : nil
  }

  func putBook(_ p: PackagedBook, fetch: @Sendable (String, URL) async throws -> Void) async throws {
    let names = [p.audioFile, p.textFile, p.timingFile, p.sourceFile].compactMap(\.self)
    if let existing = book(p.id) {
      for name in names {
        let url = store.folderURL(existing).appendingPathComponent(name)
        if !FileManager.default.fileExists(atPath: url.path) { try await fetch(name, url) }
      }
      existing.update(from: p)
      try context.save()
      adopt(existing, updatedAt: p.updatedAt)
      saveStamps()
      return
    }
    let (folder, dir) = try store.makeBookFolder()
    do {
      for name in names { try await fetch(name, dir.appendingPathComponent(name)) }
      let b = Book.make(p, folder: folder)
      context.insert(b)
      try context.save()
      adopt(b, updatedAt: p.updatedAt)
      saveStamps()
    } catch {
      context.rollback()
      try? FileManager.default.removeItem(at: dir)
      throw error
    }
  }

  func deleteBook(id: String) async throws {
    guard let b = book(id) else { return }
    willDelete(b)
    texts[b.id] = nil
    store.delete(b, context: context)
    stamps[id] = nil
    saveStamps()
  }

  func putComment(bookID: String, _ p: PackagedComment, fetch: @Sendable (String, URL) async throws -> Void) async throws {
    guard let b = book(bookID) else { return }
    if let name = p.audioFile {
      let url = store.commentsURL(b).appendingPathComponent(name)
      if !FileManager.default.fileExists(atPath: url.path) {
        try FileManager.default.createDirectory(at: store.commentsURL(b), withIntermediateDirectories: true)
        try await fetch("\(BookPackage.commentsDirectory)/\(name)", url)
      }
    }
    let t = text(b)
    let comment: Comment
    if let mine = b.comments.first(where: { $0.id.uuidString == p.id.uppercased() }) {
      mine.update(from: p, text: t)
      comment = mine
    } else {
      comment = Comment.make(p, text: t)
      context.insert(comment)
      comment.book = b
    }
    try context.save()
    adopt(comment, text: t, updatedAt: p.updatedAt)
    saveStamps()
  }

  func deleteComment(bookID: String, id: String) async throws {
    guard let b = book(bookID), let c = b.comments.first(where: { $0.id.uuidString == id.uppercased() }) else { return }
    store.delete(c, context: context)
    stamps[id] = nil
    saveStamps()
  }
}
