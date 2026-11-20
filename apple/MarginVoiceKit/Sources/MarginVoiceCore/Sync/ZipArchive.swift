import Compression
import Foundation

/// The zip files of `BookPackage`: media stored as is, text deflated, no zip64 (a
/// book is far below 4 GB). Reads stored and deflated entries, which is what both
/// DraftTalk apps (and most zip tools) write.
public enum ZipArchive {
  public enum ZipError: Error, LocalizedError {
    case malformed(String)
    case unsupported(String)
    case badPath(String)

    public var errorDescription: String? {
      switch self {
      case .malformed(let s): "Not a valid zip file: \(s)"
      case .unsupported(let s): "Unsupported zip entry: \(s)"
      case .badPath(let s): "Bad path in package: \(s)"
      }
    }
  }

  public struct Entry: Sendable {
    public let path: String
    public let data: @Sendable () throws -> Data

    public init(path: String, data: @escaping @Sendable () throws -> Data) {
      self.path = path
      self.data = data
    }

    public static func file(_ path: String, _ url: URL) -> Entry {
      Entry(path: path) { try Data(contentsOf: url, options: .mappedIfSafe) }
    }

    public static func text(_ path: String, _ s: String) -> Entry {
      Entry(path: path) { Data(s.utf8) }
    }
  }

  static let storedExtensions: Set<String> = ["m4a", "m4b", "mp3", "aac", "wav", "pdf"]

  // MARK: Writing

  public static func write(_ entries: [Entry], to url: URL) throws {
    FileManager.default.createFile(atPath: url.path, contents: nil)
    let out = try FileHandle(forWritingTo: url)
    defer { try? out.close() }
    var central = Data()
    var offset: UInt32 = 0
    for entry in entries {
      let raw = try entry.data()
      let crc = CRC32.checksum(raw)
      let stored = storedExtensions.contains((entry.path as NSString).pathExtension.lowercased())
      let body = stored ? raw : (deflate(raw) ?? raw)
      let method: UInt16 = !stored && body.count < raw.count ? 8 : 0
      let name = Data(entry.path.utf8)
      var local = Data()
      local.le32(0x0403_4b50)
      local.le16(20); local.le16(0x0800); local.le16(method); local.le16(0); local.le16(0x21)
      local.le32(crc); local.le32(UInt32(body.count)); local.le32(UInt32(raw.count))
      local.le16(UInt16(name.count)); local.le16(0)
      local.append(name)
      try out.write(contentsOf: local)
      try out.write(contentsOf: body)

      central.le32(0x0201_4b50)
      central.le16(20); central.le16(20); central.le16(0x0800); central.le16(method); central.le16(0); central.le16(0x21)
      central.le32(crc); central.le32(UInt32(body.count)); central.le32(UInt32(raw.count))
      central.le16(UInt16(name.count)); central.le16(0); central.le16(0); central.le16(0); central.le16(0)
      central.le32(0); central.le32(offset)
      central.append(name)
      offset += UInt32(local.count + body.count)
    }
    var end = Data()
    end.le32(0x0605_4b50)
    end.le16(0); end.le16(0); end.le16(UInt16(entries.count)); end.le16(UInt16(entries.count))
    end.le32(UInt32(central.count)); end.le32(offset); end.le16(0)
    try out.write(contentsOf: central)
    try out.write(contentsOf: end)
  }

  // MARK: Reading

  /// Unpacks into `directory`, refusing entries that would land outside it.
  public static func unzip(_ url: URL, into directory: URL) throws {
    let data = try Data(contentsOf: url, options: .mappedIfSafe)
    let root = directory.standardizedFileURL
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    guard data.count >= 22 else { throw ZipError.malformed("too short") }
    // the end of central directory record, searched from the end (a comment may follow it)
    var eocd = -1
    var i = data.count - 22
    while i >= max(0, data.count - 22 - 65_535) {
      if data.u32(i) == 0x0605_4b50 { eocd = i; break }
      i -= 1
    }
    guard eocd >= 0 else { throw ZipError.malformed("no central directory") }
    let count = Int(data.u16(eocd + 10))
    var p = Int(data.u32(eocd + 16))
    for _ in 0..<count {
      guard data.u32(p) == 0x0201_4b50 else { throw ZipError.malformed("central directory") }
      let method = data.u16(p + 10)
      let compressed = Int(data.u32(p + 20))
      let size = Int(data.u32(p + 24))
      let nameLength = Int(data.u16(p + 28))
      let extra = Int(data.u16(p + 30))
      let comment = Int(data.u16(p + 32))
      let localOffset = Int(data.u32(p + 42))
      let name = String(decoding: data[data.startIndex + p + 46..<data.startIndex + p + 46 + nameLength], as: UTF8.self)
      p += 46 + nameLength + extra + comment

      let dest = root.appendingPathComponent(name).standardizedFileURL
      guard dest.path.hasPrefix(root.path + "/") else { throw ZipError.badPath(name) }
      if name.hasSuffix("/") {
        try FileManager.default.createDirectory(at: dest, withIntermediateDirectories: true)
        continue
      }
      guard data.u32(localOffset) == 0x0403_4b50 else { throw ZipError.malformed("local header of \(name)") }
      let start = localOffset + 30 + Int(data.u16(localOffset + 26)) + Int(data.u16(localOffset + 28))
      guard start + compressed <= data.count else { throw ZipError.malformed("\(name) is cut off") }
      let body = data[data.startIndex + start..<data.startIndex + start + compressed]
      let contents: Data
      switch method {
      case 0: contents = Data(body)
      case 8:
        guard let inflated = inflate(Data(body), size: size) else { throw ZipError.malformed("\(name) does not inflate") }
        contents = inflated
      default: throw ZipError.unsupported("\(name) (method \(method))")
      }
      try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
      try contents.write(to: dest)
    }
  }

  // MARK: Deflate (raw, as zip wants it; Apple's COMPRESSION_ZLIB is raw DEFLATE)

  static func deflate(_ data: Data) -> Data? {
    if data.isEmpty { return nil }
    let capacity = data.count + data.count / 10 + 64
    var out = Data(count: capacity)
    let n = out.withUnsafeMutableBytes { dst in
      data.withUnsafeBytes { src in
        compression_encode_buffer(
          dst.bindMemory(to: UInt8.self).baseAddress!, capacity, src.bindMemory(to: UInt8.self).baseAddress!, data.count, nil,
          COMPRESSION_ZLIB)
      }
    }
    guard n > 0, n < data.count else { return nil }
    return out.prefix(n)
  }

  static func inflate(_ data: Data, size: Int) -> Data? {
    if size == 0 { return Data() }
    var out = Data(count: size)
    let n = out.withUnsafeMutableBytes { dst in
      data.withUnsafeBytes { src in
        compression_decode_buffer(
          dst.bindMemory(to: UInt8.self).baseAddress!, size, src.bindMemory(to: UInt8.self).baseAddress!, data.count, nil,
          COMPRESSION_ZLIB)
      }
    }
    return n == size ? out : nil
  }
}

enum CRC32 {
  static let table: [UInt32] = (0..<256).map { i in
    var c = UInt32(i)
    for _ in 0..<8 { c = c & 1 != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
    return c
  }

  static func checksum(_ data: Data) -> UInt32 {
    var crc: UInt32 = 0xFFFF_FFFF
    data.withUnsafeBytes { bytes in
      for b in bytes { crc = table[Int((crc ^ UInt32(b)) & 0xFF)] ^ (crc >> 8) }
    }
    return crc ^ 0xFFFF_FFFF
  }
}

extension Data {
  fileprivate mutating func le16(_ v: UInt16) { Swift.withUnsafeBytes(of: v.littleEndian) { append(contentsOf: $0) } }
  fileprivate mutating func le32(_ v: UInt32) { Swift.withUnsafeBytes(of: v.littleEndian) { append(contentsOf: $0) } }
  fileprivate func u16(_ i: Int) -> UInt16 {
    UInt16(self[startIndex + i]) | UInt16(self[startIndex + i + 1]) << 8
  }
  fileprivate func u32(_ i: Int) -> UInt32 {
    UInt32(u16(i)) | UInt32(u16(i + 2)) << 16
  }
}
