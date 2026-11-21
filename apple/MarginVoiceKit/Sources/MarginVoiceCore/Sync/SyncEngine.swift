import Foundation

/// The sync store: a folder somewhere (iCloud Drive, WebDAV) holding
/// `Books/<BOOK-UUID>/…` in the `BookPackage` layout. Paths use `/` and are
/// relative to the store's root.
public protocol SyncFolder: Sendable {
  /// The items in `path`, or nil when the folder does not exist.
  func list(_ path: String) async throws -> [SyncFolderItem]?
  func read(_ path: String) async throws -> Data
  /// Copies a remote file to `dest`.
  func download(_ path: String, to dest: URL) async throws
  /// Writes `path` from a local file, creating its folders; replaces a file that is there.
  func write(_ path: String, from file: URL) async throws
  func write(_ path: String, data: Data) async throws
  /// Deletes a file or a whole folder; a missing one is fine.
  func delete(_ path: String) async throws
}

/// A file or folder directly inside a listed folder; `tag` changes whenever its content does (an ETag, size + date).
public struct SyncFolderItem: Sendable, Equatable {
  public let name: String
  public let isFolder: Bool
  public let tag: String

  public init(name: String, isFolder: Bool, tag: String) {
    self.name = name
    self.isFolder = isFolder
    self.tag = tag
  }
}

/// This device's library as the sync engine sees it.
public protocol SyncLocal: Sendable {
  func books() async throws -> [PackagedBook]
  func comments(bookID: String) async throws -> [PackagedComment]
  /// The local file of a book-relative path (`book.m4b`, `comments/x.m4a`), if it is there.
  func file(bookID: String, path: String) async -> URL?
  /// Adds or updates a book; `fetch` copies one of its files from the store into a local file.
  func putBook(_ book: PackagedBook, fetch: @Sendable (String, URL) async throws -> Void) async throws
  func deleteBook(id: String) async throws
  func putComment(bookID: String, _ comment: PackagedComment, fetch: @Sendable (String, URL) async throws -> Void) async throws
  func deleteComment(bookID: String, id: String) async throws
}

/// What this device knew after its last sync with one store. Saved between syncs.
public struct SyncState: Codable, Sendable, Equatable {
  public struct Cached: Codable, Sendable, Equatable {
    public var tag: String
    public var json: String
  }

  public var books: [String: String] = [:]
  /// Book id → comment id → hash.
  public var comments: [String: [String: String]] = [:]
  /// Store path → tag and JSON as last read, so unchanged files are not read again.
  public var cache: [String: Cached] = [:]

  public init() {}
}

public actor SyncEngine {
  public struct Report: Sendable {
    public let state: SyncState
    public let uploaded: Int
    public let downloaded: Int
    public let deleted: Int
  }

  public static let books = "Books"

  let folder: any SyncFolder
  let local: any SyncLocal
  let log: @Sendable (String) -> Void
  var cache: [String: SyncState.Cached] = [:]
  var uploaded = 0
  var downloaded = 0
  var deleted = 0

  public init(folder: any SyncFolder, local: any SyncLocal, log: @escaping @Sendable (String) -> Void = { _ in }) {
    self.folder = folder
    self.local = local
    self.log = log
  }

  public func sync(_ state: SyncState) async throws -> Report {
    cache = [:]
    uploaded = 0
    downloaded = 0
    deleted = 0

    let (remoteBooks, unreadable) = try await remoteBooks(state)
    let localBooks = Dictionary(try await local.books().map { ($0.id.uppercased(), $0) }, uniquingKeysWith: { a, _ in a })
    let merged = SyncMerge.merge(
      base: state.books, local: localBooks.mapValues(Self.item), remote: remoteBooks.mapValues(Self.item),
      unreadable: unreadable)
    var removed = Set<String>()
    for action in merged.actions {
      switch action {
      case .putRemote(_, let book): try await upload(book)
      case .putLocal(let id, let book):
        let folder = folder
        let base = Self.bookPath(id)
        try await local.putBook(book) { p, d in try await folder.download("\(base)/\(p)", to: d) }
        downloaded += 1
      case .deleteRemote(let id):
        try await folder.delete(Self.bookPath(id))
        removed.insert(id)
        deleted += 1
      case .deleteLocal(let id):
        try await local.deleteBook(id: id)
        removed.insert(id)
        deleted += 1
      }
    }

    var comments: [String: [String: String]] = [:]
    for id in merged.base.keys where !removed.contains(id) {
      comments[id] = try await syncComments(bookID: id, base: state.comments[id] ?? [:], state: state)
    }
    var next = SyncState()
    next.books = merged.base
    next.comments = comments
    next.cache = cache
    return Report(state: next, uploaded: uploaded, downloaded: downloaded, deleted: deleted)
  }

  static func item(_ b: PackagedBook) -> SyncMerge.Item<PackagedBook> {
    SyncMerge.Item(value: b, hash: BookPackage.hash(b), updatedAt: BookPackage.date(b.updatedAt))
  }

  static func item(_ c: PackagedComment) -> SyncMerge.Item<PackagedComment> {
    SyncMerge.Item(value: c, hash: BookPackage.hash(c), updatedAt: BookPackage.date(c.updatedAt))
  }

  static func bookPath(_ id: String) -> String { "\(books)/\(id.uppercased())" }

  /// Reads a JSON file of the store, or takes it from the cache when its tag is unchanged.
  func json(_ path: String, tag: String, state: SyncState) async throws -> Data {
    if let known = state.cache[path], known.tag == tag {
      cache[path] = known
      return Data(known.json.utf8)
    }
    let data = try await folder.read(path)
    cache[path] = SyncState.Cached(tag: tag, json: String(decoding: data, as: UTF8.self))
    return data
  }

  /// The books in the store, and the ids of those that are there but cannot be read: their absence from the
  /// first is no deletion. A missing `Books` folder (moved on the server, an iCloud container still filling)
  /// makes every book known from the last sync unreadable rather than deleted.
  func remoteBooks(_ state: SyncState) async throws -> ([String: PackagedBook], Set<String>) {
    var result: [String: PackagedBook] = [:]
    var unreadable = Set<String>()
    guard let dirs = try await folder.list(Self.books) else {
      if !state.books.isEmpty { log("the Books folder is missing in the store: nothing is deleted") }
      return (result, Set(state.books.keys))
    }
    for dir in dirs where dir.isFolder {
      // a book without book.json is still being uploaded
      guard let meta = try await folder.list("\(Self.books)/\(dir.name)")?.first(where: { $0.name == BookPackage.bookFile }) else {
        unreadable.insert(dir.name.uppercased())
        continue
      }
      do {
        let book = try BookPackage.decodeBook(try await json("\(Self.books)/\(dir.name)/\(meta.name)", tag: meta.tag, state: state))
        result[book.id.uppercased()] = book
      } catch {
        unreadable.insert(dir.name.uppercased())
        log("skipping \(dir.name): \(error.localizedDescription)")
      }
    }
    return (result, unreadable)
  }

  /// Files first and `book.json` last, so other devices never see half a book.
  func upload(_ book: PackagedBook) async throws {
    let base = Self.bookPath(book.id)
    let there = Set((try await folder.list(base) ?? []).map(\.name))
    for name in [book.audioFile, book.textFile, book.timingFile, book.sourceFile].compactMap({ $0 }) where !there.contains(name) {
      guard let file = await local.file(bookID: book.id, path: name) else { continue }
      try await folder.write("\(base)/\(name)", from: file)
    }
    try await folder.write("\(base)/\(BookPackage.bookFile)", data: try BookPackage.encode(book))
    uploaded += 1
  }

  func syncComments(bookID: String, base: [String: String], state: SyncState) async throws -> [String: String] {
    let dir = "\(Self.bookPath(bookID))/\(BookPackage.commentsDirectory)"
    let found = try await folder.list(dir)
    let listing = found ?? []
    var names = Set(listing.map(\.name))
    var remote: [String: PackagedComment] = [:]
    // no comments folder although comments were synced before: unreadable, not deleted
    var unreadable = found == nil ? Set(base.keys) : []
    for f in listing where !f.isFolder && f.name.hasSuffix(".json") {
      do {
        let c = try BookPackage.decodeComment(try await json("\(dir)/\(f.name)", tag: f.tag, state: state))
        remote[c.id.uppercased()] = c
      } catch {
        unreadable.insert(String(f.name.dropLast(5)).uppercased())
        log("skipping comment \(f.name): \(error.localizedDescription)")
      }
    }
    let localComments = Dictionary(
      try await local.comments(bookID: bookID).map { ($0.id.uppercased(), $0) }, uniquingKeysWith: { a, _ in a })
    let merged = SyncMerge.merge(
      base: base, local: localComments.mapValues(Self.item), remote: remote.mapValues(Self.item), unreadable: unreadable)
    for action in merged.actions {
      switch action {
      case .putRemote(_, let c):
        if let audio = c.audioFile, !names.contains(audio),
          let f = await local.file(bookID: bookID, path: "\(BookPackage.commentsDirectory)/\(audio)")
        {
          try await folder.write("\(dir)/\(audio)", from: f)
          names.insert(audio)
        }
        try await folder.write("\(dir)/\(c.id.uppercased()).json", data: try BookPackage.encode(c))
        uploaded += 1
      case .putLocal(_, let c):
        let folder = folder
        let base = Self.bookPath(bookID)
        try await local.putComment(bookID: bookID, c) { p, d in try await folder.download("\(base)/\(p)", to: d) }
        downloaded += 1
      case .deleteRemote(let id):
        try await folder.delete("\(dir)/\(id).json")
        if let audio = remote[id]?.audioFile { try await folder.delete("\(dir)/\(audio)") }
        deleted += 1
      case .deleteLocal(let id):
        try await local.deleteComment(bookID: bookID, id: id)
        deleted += 1
      }
    }
    return merged.base
  }
}
