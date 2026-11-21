import Foundation
import Testing
@testable import MarginVoiceCore

private func tempDir(_ name: String) -> URL {
  let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(name)-\(UUID().uuidString)")
  try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
  return url
}

struct BookPackageTests {
  let book = PackagedBook(
    addedAt: "2026-10-29T10:00:00.000Z", audioFile: "book.m4b", chapters: [ChapterMark(title: "Intro", start: 0, end: 12.5)],
    duration: 1234.5, id: "6F1C2B4A-0D1E-4F00-9A8B-1C2D3E4F5A6B", language: "en", position: 61, rate: 1.25,
    sourceFile: "source.pdf", textFile: "narration.txt", timingFile: "timing.json", title: "Cuddle-fish",
    updatedAt: "2026-10-29T10:05:00.123Z")
  let comment = PackagedComment(
    audioFile: "comment-1761732300000.m4a", chapter: "Intro", confidence: 0.8, createdAt: "2026-10-29T10:05:00.000Z", duration: 7.4,
    estimatedOffset: 120, id: "0B5F1D9E-3C4A-4B2A-8E7F-123456789ABC", kind: "comment", mediaTime: 12, refinedOffset: 118,
    reviewTag: "weakness", sentenceNudge: -1, transcript: "the baseline is never tuned", transcriptStatus: "done",
    updatedAt: "2026-10-29T10:06:00.000Z")

  @Test func packageRoundTripsEveryCommentField() throws {
    let dir = tempDir("pkg")
    let audio = Data((0..<5000).map { UInt8($0 % 251) })
    let zip = dir.appendingPathComponent("book.drafttalk")
    let entries = try BookPackage.entries(
      book: book, comments: [comment],
      files: [
        .text("narration.txt", "Hello. World. Hello. World. Hello. World."),
        ZipArchive.Entry(path: "book.m4b") { audio },
        ZipArchive.Entry(path: "comments/\(comment.audioFile!)") { audio },
      ])
    try ZipArchive.write(entries, to: zip)
    let out = dir.appendingPathComponent("out")
    let unpacked = try BookPackage.unzip(zip, into: out)
    #expect(unpacked.book == book)
    #expect(unpacked.comments == [comment])
    #expect(try String(contentsOf: out.appendingPathComponent("narration.txt"), encoding: .utf8) == "Hello. World. Hello. World. Hello. World.")
    #expect(try Data(contentsOf: out.appendingPathComponent("book.m4b")) == audio)
    #expect(FileManager.default.fileExists(atPath: out.appendingPathComponent("comments/\(comment.audioFile!)").path))
    // keys sorted, nulls left out: the JSON the Android app writes
    let json = String(decoding: try BookPackage.encode(comment), as: UTF8.self)
    #expect(json.hasPrefix(#"{"audioFile":"comment-"#))
    #expect(!json.contains("endEstimatedOffset"))
  }

  @Test func readsZipsWrittenByJava() throws {
    // `zip -X` writes deflated entries with data descriptors, like java.util.zip
    let dir = tempDir("java")
    try Data(try BookPackage.encode(book)).write(to: dir.appendingPathComponent("book.json"))
    try String(repeating: "narration ", count: 200).write(to: dir.appendingPathComponent("narration.txt"), atomically: true, encoding: .utf8)
    let zip = dir.appendingPathComponent("x.drafttalk")
    let p = Process()
    p.executableURL = URL(fileURLWithPath: "/usr/bin/zip")
    p.currentDirectoryURL = dir
    p.arguments = ["-q", "-X", "-fd", zip.path, "book.json", "narration.txt"]
    try p.run()
    p.waitUntilExit()
    let unpacked = try BookPackage.unzip(zip, into: dir.appendingPathComponent("out"))
    #expect(unpacked.book == book)
    #expect(try String(contentsOf: dir.appendingPathComponent("out/narration.txt"), encoding: .utf8).count == 2000)
  }

  @Test func hashIgnoresUpdatedAt() {
    var later = comment
    later.updatedAt = "2027-01-01T00:00:00.000Z"
    #expect(BookPackage.hash(comment) == BookPackage.hash(later))
    var edited = comment
    edited.transcript = "tuned"
    #expect(BookPackage.hash(comment) != BookPackage.hash(edited))
    #expect(BookPackage.date(BookPackage.date("2026-10-29T10:05:00.123Z")) == "2026-10-29T10:05:00.123Z")
    #expect(BookPackage.date("2026-10-29T10:05:00Z") > Date.distantPast)
  }

  @Test func damagedZipsAreErrorsNotCrashes() throws {
    let dir = tempDir("damaged")
    let good = dir.appendingPathComponent("good.zip")
    try ZipArchive.write([.text("book.json", "{}"), .text("narration.txt", String(repeating: "word ", count: 200))], to: good)
    let bytes = try Data(contentsOf: good)
    let eocd = bytes.count - 22
    func damaged(_ change: (inout Data) -> Void) throws -> URL {
      var d = bytes
      change(&d)
      let url = dir.appendingPathComponent(UUID().uuidString + ".zip")
      try d.write(to: url)
      return url
    }
    func put32(_ d: inout Data, _ at: Int, _ v: UInt32) { for k in 0..<4 { d[at + k] = UInt8((v >> (8 * UInt32(k))) & 0xFF) } }
    let cases = [
      try damaged { put32(&$0, eocd + 16, 0xFFFF_FFFF) },  // central directory offset past the end (also the ZIP64 marker)
      try damaged { put32(&$0, eocd + 16, UInt32($0.count - 30)) },  // central directory too close to the end
      try damaged { $0[eocd + 10] = 0xFF; $0[eocd + 11] = 0xFF },  // more entries than there are
      try damaged { $0.removeLast(30) },  // cut off
    ]
    for url in cases {
      #expect(throws: ZipArchive.ZipError.self) { try ZipArchive.unzip(url, into: dir.appendingPathComponent(UUID().uuidString)) }
    }
  }

  @Test func unzipRefusesPathsOutsideTheFolder() throws {
    let dir = tempDir("evil")
    let zip = dir.appendingPathComponent("evil.zip")
    try ZipArchive.write([.text("../evil.txt", "x")], to: zip)
    #expect(throws: ZipArchive.ZipError.self) { try ZipArchive.unzip(zip, into: dir.appendingPathComponent("out")) }
  }

  @Test func fileNamesThatLeaveTheBookFolderAreRefused() throws {
    let book = #"{"addedAt":"2026-10-29T10:00:00.000Z","audioFile":"../other/book.m4b","duration":1,"format":1,"id":"X","title":"t","updatedAt":"2026-10-29T10:00:00.000Z"}"#
    #expect(throws: BookPackage.PackageError.self) { try BookPackage.decodeBook(Data(book.utf8)) }
    let comment = #"{"audioFile":"../book.m4a","createdAt":"2026-10-29T10:00:00.000Z","id":"A","kind":"comment","mediaTime":1,"updatedAt":"2026-10-29T10:00:00.000Z"}"#
    #expect(throws: BookPackage.PackageError.self) { try BookPackage.decodeComment(Data(comment.utf8)) }
    for ok in ["book.m4b", "comment-1A2B.m4a", "My Paper (final).m4b"] { #expect(throws: Never.self) { try BookPackage.checkFileName(ok) } }
  }

  @Test func newerFormatIsRefused() throws {
    var b = book
    b.format = 2
    let data = try BookPackage.encode(b)
    #expect(throws: BookPackage.PackageError.self) { try BookPackage.decodeBook(data) }
  }

  @Test func offsetsSurviveEmojiAndUmlauts() {
    let s = Array("Schöne 👍🏽 Grüße. Next")
    let offset = s.firstIndex(of: "N")!
    let cp = OffsetUnits.toCodePoints(s, offset)
    #expect(cp == offset + 1)  // 👍🏽 is one Character, two code points
    #expect(OffsetUnits.fromCodePoints(s, cp) == offset)
    #expect(OffsetUnits.fromCodePoints(s, 10_000) == s.count)
    #expect(OffsetUnits.fromCodePoints(s, -3) == 0)
  }
}

struct SyncMergeTests {
  let t0 = Date(timeIntervalSince1970: 1_761_732_000)
  func item(_ v: String, _ at: Double = 0) -> SyncMerge.Item<String> { SyncMerge.Item(value: v, hash: "h-\(v)", updatedAt: t0 + at) }

  func describe(_ r: SyncMerge.Result<String>) -> [String] {
    r.actions.map {
      switch $0 {
      case .putLocal(let id, let v): "putLocal \(id) \(v)"
      case .deleteLocal(let id): "deleteLocal \(id)"
      case .putRemote(let id, let v): "putRemote \(id) \(v)"
      case .deleteRemote(let id): "deleteRemote \(id)"
      }
    }
  }

  @Test func newerCommentWins() {
    let r = SyncMerge.merge(base: ["c": "h-a"], local: ["c": item("b", 10)], remote: ["c": item("c", 20)])
    #expect(describe(r) == ["putLocal c c"])
    #expect(r.base["c"] == "h-c")
    let l = SyncMerge.merge(base: ["c": "h-a"], local: ["c": item("b", 30)], remote: ["c": item("c", 20)])
    #expect(describe(l) == ["putRemote c b"])
  }

  @Test func oneSidedChangesWin() {
    #expect(describe(SyncMerge.merge(base: ["c": "h-a"], local: ["c": item("b")], remote: ["c": item("a", 99)])) == ["putRemote c b"])
    #expect(describe(SyncMerge.merge(base: ["c": "h-a"], local: ["c": item("a", 99)], remote: ["c": item("b")])) == ["putLocal c b"])
    let same = SyncMerge.merge(base: [:], local: ["c": item("a")], remote: ["c": item("a")])
    #expect(same.actions.isEmpty)
    #expect(same.base["c"] == "h-a")
  }

  @Test func deleteRemovesUnchangedCopy() {
    let remoteDeleted = SyncMerge.merge(base: ["c": "h-a"], local: ["c": item("a")], remote: [:])
    #expect(describe(remoteDeleted) == ["deleteLocal c"])
    #expect(remoteDeleted.base["c"] == nil)
    #expect(describe(SyncMerge.merge(base: ["c": "h-a"], local: [:], remote: ["c": item("a")])) == ["deleteRemote c"])
  }

  @Test func editSurvivesRemoteDelete() {
    #expect(describe(SyncMerge.merge(base: ["c": "h-a"], local: ["c": item("b")], remote: [:])) == ["putRemote c b"])
    #expect(describe(SyncMerge.merge(base: ["c": "h-a"], local: [:], remote: ["c": item("b")])) == ["putLocal c b"])
  }

  @Test func concurrentNewCommentsBothKept() {
    let r = SyncMerge.merge(base: [:], local: ["x": item("x")], remote: ["y": item("y")])
    #expect(describe(r) == ["putRemote x x", "putLocal y y"])
    #expect(Set(r.base.keys) == ["x", "y"])
  }

  @Test func tiesPickTheSameSideOnEveryDevice() {
    let a = SyncMerge.merge(base: ["c": "h-0"], local: ["c": item("a")], remote: ["c": item("b")])
    let b = SyncMerge.merge(base: ["c": "h-0"], local: ["c": item("b")], remote: ["c": item("a")])
    #expect(a.base["c"] == "h-b")
    #expect(b.base["c"] == "h-b")
  }
}

/// A store in memory: path → bytes, the tag is a write counter.
actor MemoryFolder: SyncFolder {
  var files: [String: (Data, Int)] = [:]
  /// Folders stay when their last file goes, as on WebDAV and in iCloud Drive.
  var folders = Set<String>()
  var writes = 0
  var reads = 0

  func list(_ path: String) -> [SyncFolderItem]? {
    let prefix = path + "/"
    let inside = files.filter { $0.key.hasPrefix(prefix) }.sorted { $0.key < $1.key }
    if inside.isEmpty { return folders.contains(path) ? [] : nil }
    var seen = Set<String>()
    var items: [SyncFolderItem] = []
    for (p, v) in inside {
      let rest = String(p.dropFirst(prefix.count))
      let name = rest.split(separator: "/").first.map(String.init) ?? rest
      guard seen.insert(name).inserted else { continue }
      items.append(SyncFolderItem(name: name, isFolder: rest.contains("/"), tag: rest.contains("/") ? "" : "\(v.1)"))
    }
    return items
  }

  func read(_ path: String) throws -> Data {
    reads += 1
    return files[path]!.0
  }

  func download(_ path: String, to dest: URL) throws {
    try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
    try files[path]!.0.write(to: dest)
  }

  func write(_ path: String, from file: URL) throws { try write(path, data: Data(contentsOf: file)) }

  func write(_ path: String, data: Data) throws {
    writes += 1
    files[path] = (data, writes)
    var parts = path.split(separator: "/").dropLast()
    while !parts.isEmpty {
      folders.insert(parts.joined(separator: "/"))
      parts = parts.dropLast()
    }
  }

  func delete(_ path: String) {
    files = files.filter { $0.key != path && !$0.key.hasPrefix(path + "/") }
    folders = folders.filter { $0 != path && !$0.hasPrefix(path + "/") }
  }

  /// Puts bytes at a path without going through the engine (a damaged or newer-format file).
  func put(_ path: String, _ text: String) { try? write(path, data: Data(text.utf8)) }

  func has(_ path: String) -> Bool { files[path] != nil }
  var isEmpty: Bool { files.isEmpty }
}

/// A device's library in memory, with its files in a temporary folder.
actor MemoryLocal: SyncLocal {
  var bookList: [String: PackagedBook] = [:]
  var commentList: [String: [String: PackagedComment]] = [:]
  let dir = tempDir("device")

  func add(_ book: PackagedBook, audio: String) throws {
    bookList[book.id] = book
    commentList[book.id] = [:]
    let f = dir.appendingPathComponent("\(book.id)/\(book.audioFile)")
    try FileManager.default.createDirectory(at: f.deletingLastPathComponent(), withIntermediateDirectories: true)
    try audio.write(to: f, atomically: true, encoding: .utf8)
  }

  func add(_ c: PackagedComment, to bookID: String, audio: String? = nil) throws {
    commentList[bookID, default: [:]][c.id] = c
    if let audio {
      let f = dir.appendingPathComponent("\(bookID)/comments/\(c.audioFile!)")
      try FileManager.default.createDirectory(at: f.deletingLastPathComponent(), withIntermediateDirectories: true)
      try audio.write(to: f, atomically: true, encoding: .utf8)
    }
  }

  func set(_ book: PackagedBook) { bookList[book.id] = book }
  func text(_ path: String) -> String? { try? String(contentsOf: dir.appendingPathComponent(path), encoding: .utf8) }

  func books() -> [PackagedBook] { Array(bookList.values) }
  func comments(bookID: String) -> [PackagedComment] { Array((commentList[bookID] ?? [:]).values) }
  func file(bookID: String, path: String) -> URL? {
    let f = dir.appendingPathComponent("\(bookID)/\(path)")
    return FileManager.default.fileExists(atPath: f.path) ? f : nil
  }

  func putBook(_ book: PackagedBook, fetch: @Sendable (String, URL) async throws -> Void) async throws {
    for name in [book.audioFile, book.textFile, book.timingFile, book.sourceFile].compactMap({ $0 }) {
      let f = dir.appendingPathComponent("\(book.id)/\(name)")
      if !FileManager.default.fileExists(atPath: f.path) { try await fetch(name, f) }
    }
    bookList[book.id] = book
    if commentList[book.id] == nil { commentList[book.id] = [:] }
  }

  func deleteBook(id: String) {
    bookList[id] = nil
    commentList[id] = nil
    try? FileManager.default.removeItem(at: dir.appendingPathComponent(id))
  }

  func putComment(bookID: String, _ comment: PackagedComment, fetch: @Sendable (String, URL) async throws -> Void) async throws {
    if let a = comment.audioFile {
      let f = dir.appendingPathComponent("\(bookID)/comments/\(a)")
      if !FileManager.default.fileExists(atPath: f.path) { try await fetch("comments/\(a)", f) }
    }
    commentList[bookID, default: [:]][comment.id] = comment
  }

  func deleteComment(bookID: String, id: String) { commentList[bookID]?[id] = nil }
}

struct SyncEngineTests {
  let bookID = "6F1C2B4A-0D1E-4F00-9A8B-1C2D3E4F5A6B"

  func book(_ updatedAt: String = "2026-10-29T10:00:00.000Z", position: Double = 0) -> PackagedBook {
    PackagedBook(
      addedAt: "2026-10-29T10:00:00.000Z", audioFile: "book.m4b", duration: 100, id: bookID, position: position,
      title: "Cuddle-fish", updatedAt: updatedAt)
  }

  func comment(_ id: String, _ transcript: String) -> PackagedComment {
    PackagedComment(
      audioFile: "comment-\(id).m4a", createdAt: "2026-10-29T10:01:00.000Z", id: id, kind: "comment", mediaTime: 12,
      transcript: transcript, transcriptStatus: "done", updatedAt: "2026-10-29T10:01:00.000Z")
  }

  final class Device: @unchecked Sendable {
    let local = MemoryLocal()
    let folder: MemoryFolder
    var state = SyncState()
    init(_ folder: MemoryFolder) { self.folder = folder }
    @discardableResult func sync() async throws -> SyncEngine.Report {
      let r = try await SyncEngine(folder: folder, local: local).sync(state)
      state = r.state
      return r
    }
  }

  @Test func commentTravelsBothWaysAndDeletesFollow() async throws {
    let store = MemoryFolder()
    let pixel = Device(store)
    let phone = Device(store)
    try await pixel.local.add(book(), audio: "AUDIO")
    try await pixel.local.add(comment("A", "the baseline is never tuned"), to: bookID, audio: "VOICE")
    try await pixel.sync()
    #expect(await store.has("Books/\(bookID)/book.json"))
    #expect(await store.has("Books/\(bookID)/comments/comment-A.m4a"))

    try await phone.sync()
    #expect(await phone.local.text("\(bookID)/book.m4b") == "AUDIO")
    #expect(await phone.local.text("\(bookID)/comments/comment-A.m4a") == "VOICE")
    #expect(await phone.local.comments(bookID: bookID).first?.transcript == "the baseline is never tuned")

    await phone.local.deleteComment(bookID: bookID, id: "A")
    try await phone.local.add(comment("B", "question: why fish"), to: bookID, audio: "V2")
    try await phone.sync()
    try await pixel.sync()
    #expect(await pixel.local.comments(bookID: bookID).map(\.id) == ["B"])
    #expect(await !store.has("Books/\(bookID)/comments/comment-A.m4a"))

    // nothing changed: nothing is read again
    let reads = await store.reads
    let quiet = try await pixel.sync()
    #expect(quiet.uploaded + quiet.downloaded + quiet.deleted == 0)
    #expect(await store.reads == reads)
  }

  @Test func positionTakesLatest() async throws {
    let store = MemoryFolder()
    let a = Device(store)
    let b = Device(store)
    try await a.local.add(book(), audio: "AUDIO")
    try await a.sync()
    try await b.sync()
    await a.local.set(book("2026-10-29T11:00:00.000Z", position: 50))
    await b.local.set(book("2026-10-29T12:00:00.000Z", position: 80))
    try await a.sync()
    try await b.sync()
    try await a.sync()
    #expect(await a.local.books().first?.position == 80)
    #expect(await b.local.books().first?.position == 80)
  }

  @Test func anUnreadableBookInTheStoreIsNotADeletion() async throws {
    let store = MemoryFolder()
    let a = Device(store)
    let b = Device(store)
    try await a.local.add(book(), audio: "AUDIO")
    try await a.local.add(comment("A", "keep me"), to: bookID, audio: "VOICE")
    try await a.sync()
    try await b.sync()
    // a newer app version (or a damaged upload) leaves a book.json this version cannot read
    await store.put("Books/\(bookID)/book.json", #"{"format": 99}"#)
    let report = try await b.sync()
    #expect(report.deleted == 0)
    #expect(await b.local.books().count == 1)
    #expect(await b.local.text("\(bookID)/comments/comment-A.m4a") == "VOICE")
  }

  @Test func aMissingBooksFolderDeletesNothing() async throws {
    let store = MemoryFolder()
    let a = Device(store)
    try await a.local.add(book(), audio: "AUDIO")
    try await a.sync()
    // the folder was moved on the server, or iCloud has not filled the container yet
    await store.delete("Books")
    let report = try await a.sync()
    #expect(report.deleted == 0)
    #expect(await a.local.books().count == 1)
  }

  @Test func anUnreadableCommentIsNotADeletion() async throws {
    let store = MemoryFolder()
    let a = Device(store)
    let b = Device(store)
    try await a.local.add(book(), audio: "AUDIO")
    try await a.local.add(comment("A", "keep me"), to: bookID, audio: "VOICE")
    try await a.sync()
    try await b.sync()
    await store.put("Books/\(bookID)/comments/A.json", "not json")
    try await b.sync()
    #expect(await b.local.comments(bookID: bookID).map(\.id) == ["A"])
  }

  @Test func deletedBookLeavesTheStoreAndOtherDevices() async throws {
    let store = MemoryFolder()
    let a = Device(store)
    let b = Device(store)
    try await a.local.add(book(), audio: "AUDIO")
    try await a.sync()
    try await b.sync()
    await b.local.deleteBook(id: bookID)
    try await b.sync()
    try await a.sync()
    #expect(await a.local.books().isEmpty)
    #expect(await store.isEmpty)
  }
}
