import CryptoKit
import Foundation

/// A book as it travels between devices: a `.drafttalk` file (a zip) or a folder in
/// the sync store, both with the same layout:
///
/// ```
/// book.json               PackagedBook
/// book.m4b, narration.txt, timing.json, source.pdf
/// comments/<UUID>.json    PackagedComment, one file per comment
/// comments/<audio>.m4a    the comment's recording, under its own name
/// ```
/// UUIDs are uppercase, dates ISO 8601 UTC with milliseconds, text offsets code points,
/// keys sorted: the same JSON the Android app writes.
public enum BookPackage {
  public static let format = 1
  public static let fileExtension = "drafttalk"
  public static let bookFile = "book.json"
  public static let commentsDirectory = "comments"
  public static let sourcePdf = "source.pdf"

  public enum PackageError: Error, LocalizedError {
    case newerFormat(Int)
    case notABook
    case badFileName(String)

    public var errorDescription: String? {
      switch self {
      case .newerFormat(let f): "The book was written by a newer Klopfgeist (format \(f))."
      case .notABook: "Not a Klopfgeist book: \(BookPackage.bookFile) is missing."
      case .badFileName(let name): "The book names a file outside its folder (\(name))."
      }
    }
  }

  public static func commentPath(_ id: String) -> String { "\(commentsDirectory)/\(id.uppercased()).json" }

  static let encoder: JSONEncoder = {
    let e = JSONEncoder()
    e.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
    return e
  }()

  public static func encode(_ book: PackagedBook) throws -> Data { try encoder.encode(book) }
  public static func encode(_ comment: PackagedComment) throws -> Data { try encoder.encode(comment) }

  public static func decodeBook(_ data: Data) throws -> PackagedBook {
    let book = try JSONDecoder().decode(PackagedBook.self, from: data)
    if book.format > format { throw PackageError.newerFormat(book.format) }
    for name in [book.audioFile, book.textFile, book.timingFile, book.sourceFile].compactMap({ $0 }) { try checkFileName(name) }
    return book
  }

  public static func decodeComment(_ data: Data) throws -> PackagedComment {
    let comment = try JSONDecoder().decode(PackagedComment.self, from: data)
    if let name = comment.audioFile { try checkFileName(name) }
    return comment
  }

  /// The file names in a package or in the sync store become paths inside the book's folder: a shared
  /// `.drafttalk` or a server must not reach outside it ("../book.m4a", "../<other book>/…").
  public static func checkFileName(_ name: String) throws {
    if name.isEmpty || name == "." || name == ".." || name.contains("/") || name.contains("\\") || name.contains("\0") {
      throw PackageError.badFileName(name)
    }
  }

  nonisolated(unsafe) static let dateFormatter: ISO8601DateFormatter = {
    let f = ISO8601DateFormatter()
    f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return f
  }()
  nonisolated(unsafe) static let plainDateFormatter = ISO8601DateFormatter()

  public static func date(_ d: Date) -> String { dateFormatter.string(from: d) }
  public static func date(_ s: String) -> Date { dateFormatter.date(from: s) ?? plainDateFormatter.date(from: s) ?? .distantPast }

  /// SHA-256 of the content without `updatedAt`: equal hashes mean nothing changed.
  public static func hash(_ book: PackagedBook) -> String {
    var b = book
    b.updatedAt = ""
    return sha256((try? encode(b)) ?? Data())
  }

  public static func hash(_ comment: PackagedComment) -> String {
    var c = comment
    c.updatedAt = ""
    return sha256((try? encode(c)) ?? Data())
  }

  static func sha256(_ data: Data) -> String {
    SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
  }

  /// Book, comments and files of one book folder, ready to zip.
  public static func entries(book: PackagedBook, comments: [PackagedComment], files: [ZipArchive.Entry]) throws -> [ZipArchive.Entry] {
    let bookData = try encode(book)
    let commentData = try comments.map { (commentPath($0.id), try encode($0)) }
    return [ZipArchive.Entry(path: bookFile) { bookData }] + commentData.map { path, d in ZipArchive.Entry(path: path) { d } } + files
  }

  public struct Unpacked: Sendable {
    public let book: PackagedBook
    public let comments: [PackagedComment]
    public let directory: URL
  }

  /// Unpacks a `.drafttalk` file into `directory`.
  public static func unzip(_ url: URL, into directory: URL) throws -> Unpacked {
    try ZipArchive.unzip(url, into: directory)
    let bookURL = directory.appendingPathComponent(bookFile)
    guard FileManager.default.fileExists(atPath: bookURL.path) else { throw PackageError.notABook }
    let commentsURL = directory.appendingPathComponent(commentsDirectory)
    let files = (try? FileManager.default.contentsOfDirectory(at: commentsURL, includingPropertiesForKeys: nil)) ?? []
    let comments = try files.filter { $0.pathExtension == "json" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
      .map { try decodeComment(Data(contentsOf: $0)) }
    return Unpacked(book: try decodeBook(Data(contentsOf: bookURL)), comments: comments, directory: directory)
  }
}

public struct PackagedBook: Codable, Sendable, Equatable {
  public var addedAt: String
  public var audioFile: String
  public var chapters: [ChapterMark]
  public var duration: Double
  public var format: Int
  public var id: String
  public var language: String?
  public var lastOpened: String?
  public var position: Double
  public var rate: Double
  /// `source.pdf` when the book carries the paper.
  public var sourceFile: String?
  public var textFile: String?
  public var timingFile: String?
  public var title: String
  public var updatedAt: String
  public var voiceName: String?

  public init(
    addedAt: String, audioFile: String, chapters: [ChapterMark] = [], duration: Double, format: Int = BookPackage.format, id: String,
    language: String? = nil, lastOpened: String? = nil, position: Double = 0, rate: Double = 1, sourceFile: String? = nil,
    textFile: String? = nil, timingFile: String? = nil, title: String, updatedAt: String, voiceName: String? = nil
  ) {
    self.addedAt = addedAt
    self.audioFile = audioFile
    self.chapters = chapters
    self.duration = duration
    self.format = format
    self.id = id
    self.language = language
    self.lastOpened = lastOpened
    self.position = position
    self.rate = rate
    self.sourceFile = sourceFile
    self.textFile = textFile
    self.timingFile = timingFile
    self.title = title
    self.updatedAt = updatedAt
    self.voiceName = voiceName
  }

  public init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    addedAt = try c.decode(String.self, forKey: .addedAt)
    audioFile = try c.decode(String.self, forKey: .audioFile)
    chapters = try c.decodeIfPresent([ChapterMark].self, forKey: .chapters) ?? []
    duration = try c.decode(Double.self, forKey: .duration)
    format = try c.decodeIfPresent(Int.self, forKey: .format) ?? BookPackage.format
    id = try c.decode(String.self, forKey: .id)
    language = try c.decodeIfPresent(String.self, forKey: .language)
    lastOpened = try c.decodeIfPresent(String.self, forKey: .lastOpened)
    position = try c.decodeIfPresent(Double.self, forKey: .position) ?? 0
    rate = try c.decodeIfPresent(Double.self, forKey: .rate) ?? 1
    sourceFile = try c.decodeIfPresent(String.self, forKey: .sourceFile)
    textFile = try c.decodeIfPresent(String.self, forKey: .textFile)
    timingFile = try c.decodeIfPresent(String.self, forKey: .timingFile)
    title = try c.decode(String.self, forKey: .title)
    updatedAt = try c.decode(String.self, forKey: .updatedAt)
    voiceName = try c.decodeIfPresent(String.self, forKey: .voiceName)
  }
}

public struct PackagedComment: Codable, Sendable, Equatable {
  public var audioFile: String?
  public var chapter: String?
  public var confidence: Double
  public var createdAt: String
  public var duration: Double
  public var endEstimatedOffset: Int?
  public var endMediaTime: Double?
  public var estimatedOffset: Int?
  public var id: String
  /// "comment" or "bookmark".
  public var kind: String
  public var mediaTime: Double
  public var refinedOffset: Int?
  public var reviewTag: String?
  public var sentenceNudge: Int
  public var transcript: String
  /// "none", "pending", "done", "failed".
  public var transcriptStatus: String
  public var updatedAt: String

  public init(
    audioFile: String? = nil, chapter: String? = nil, confidence: Double = 0, createdAt: String, duration: Double = 0,
    endEstimatedOffset: Int? = nil, endMediaTime: Double? = nil, estimatedOffset: Int? = nil, id: String, kind: String,
    mediaTime: Double, refinedOffset: Int? = nil, reviewTag: String? = nil, sentenceNudge: Int = 0, transcript: String = "",
    transcriptStatus: String = "none", updatedAt: String
  ) {
    self.audioFile = audioFile
    self.chapter = chapter
    self.confidence = confidence
    self.createdAt = createdAt
    self.duration = duration
    self.endEstimatedOffset = endEstimatedOffset
    self.endMediaTime = endMediaTime
    self.estimatedOffset = estimatedOffset
    self.id = id
    self.kind = kind
    self.mediaTime = mediaTime
    self.refinedOffset = refinedOffset
    self.reviewTag = reviewTag
    self.sentenceNudge = sentenceNudge
    self.transcript = transcript
    self.transcriptStatus = transcriptStatus
    self.updatedAt = updatedAt
  }

  public init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    audioFile = try c.decodeIfPresent(String.self, forKey: .audioFile)
    chapter = try c.decodeIfPresent(String.self, forKey: .chapter)
    confidence = try c.decodeIfPresent(Double.self, forKey: .confidence) ?? 0
    createdAt = try c.decode(String.self, forKey: .createdAt)
    duration = try c.decodeIfPresent(Double.self, forKey: .duration) ?? 0
    endEstimatedOffset = try c.decodeIfPresent(Int.self, forKey: .endEstimatedOffset)
    endMediaTime = try c.decodeIfPresent(Double.self, forKey: .endMediaTime)
    estimatedOffset = try c.decodeIfPresent(Int.self, forKey: .estimatedOffset)
    id = try c.decode(String.self, forKey: .id)
    kind = try c.decode(String.self, forKey: .kind)
    mediaTime = try c.decode(Double.self, forKey: .mediaTime)
    refinedOffset = try c.decodeIfPresent(Int.self, forKey: .refinedOffset)
    reviewTag = try c.decodeIfPresent(String.self, forKey: .reviewTag)
    sentenceNudge = try c.decodeIfPresent(Int.self, forKey: .sentenceNudge) ?? 0
    transcript = try c.decodeIfPresent(String.self, forKey: .transcript) ?? ""
    transcriptStatus = try c.decodeIfPresent(String.self, forKey: .transcriptStatus) ?? "none"
    updatedAt = try c.decode(String.self, forKey: .updatedAt)
  }
}
