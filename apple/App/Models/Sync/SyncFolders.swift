import MarginVoiceCore
import Foundation
import Security

/// The sync store on a WebDAV server (Nextcloud: `https://<host>/remote.php/dav/files/<user>/DraftTalk`).
/// Basic auth with an app password; the same folder the Android app syncs with.
struct WebDAVFolder: SyncFolder {
  struct DAVError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
  }

  let root: URL
  let authorization: String
  let session: URLSession

  init(root: URL, user: String, password: String, session: URLSession = .shared) {
    self.root = root
    authorization = "Basic " + Data("\(user):\(password)".utf8).base64EncodedString()
    self.session = session
  }

  /// A Nextcloud address (`cloud.example.org`) becomes its WebDAV folder; a full WebDAV URL is used as given.
  static func davURL(server: String, user: String) -> URL? {
    var s = server.trimmingCharacters(in: .whitespaces)
    while s.hasSuffix("/") { s.removeLast() }
    if !s.contains("://") { s = "https://" + s }
    if !s.contains("/remote.php/") {
      let u = user.trimmingCharacters(in: .whitespaces).addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? user
      s += "/remote.php/dav/files/\(u)/MarginVoice"
    }
    return URL(string: s)
  }

  private func url(_ path: String, folder: Bool = false) -> URL {
    var u = root
    for part in path.split(separator: "/") { u.appendPathComponent(String(part)) }
    return folder ? u.appendingPathComponent("", isDirectory: true) : u
  }

  private func request(_ method: String, _ url: URL, body: Data? = nil, headers: [String: String] = [:]) -> URLRequest {
    var r = URLRequest(url: url)
    r.httpMethod = method
    r.httpBody = body
    r.setValue(authorization, forHTTPHeaderField: "Authorization")
    for (k, v) in headers { r.setValue(v, forHTTPHeaderField: k) }
    return r
  }

  private func check(_ response: URLResponse, _ ok: Set<Int>, _ r: URLRequest) throws -> Int {
    let code = (response as? HTTPURLResponse)?.statusCode ?? 0
    if ok.contains(code) || (ok.isEmpty && (200..<300).contains(code)) { return code }
    let reason =
      switch code {
      case 401: "the server refused the user name or app password"
      case 403: "the server does not allow this"
      case 507: "the server is full"
      default: "HTTP \(code)"
      }
    throw DAVError(message: "Sync: \(reason) (\(r.httpMethod ?? "") \(r.url?.path ?? ""))")
  }

  func list(_ path: String) async throws -> [SyncFolderItem]? {
    let body = Data(
      #"<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getetag/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"#
        .utf8)
    let r = request("PROPFIND", url(path, folder: true), body: body, headers: ["Depth": "1", "Content-Type": "application/xml"])
    let (data, response) = try await session.data(for: r)
    if try check(response, [207, 404], r) == 404 { return nil }
    let parser = MultistatusParser()
    let xml = XMLParser(data: data)
    xml.shouldProcessNamespaces = true
    xml.delegate = parser
    guard xml.parse() else { throw DAVError(message: "Sync: the server's folder listing could not be read") }
    let selfPath = (url(path, folder: true).path as NSString).standardizingPath
    return parser.responses.compactMap { item in
      guard let href = item.href.removingPercentEncoding.map({ h -> String in
        if h.hasPrefix("http"), let u = URL(string: h) { return u.path }
        return h
      }) else { return nil }
      let trimmed = (href as NSString).standardizingPath
      if trimmed == selfPath { return nil }
      let tag = item.etag ?? [item.length, item.modified].compactMap(\.self).joined(separator: "/")
      return SyncFolderItem(name: (trimmed as NSString).lastPathComponent, isFolder: item.isCollection, tag: tag)
    }
  }

  func read(_ path: String) async throws -> Data {
    let r = request("GET", url(path))
    let (data, response) = try await session.data(for: r)
    _ = try check(response, [], r)
    return data
  }

  func download(_ path: String, to dest: URL) async throws {
    let r = request("GET", url(path))
    let (tmp, response) = try await session.download(for: r)
    _ = try check(response, [], r)
    try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
    try? FileManager.default.removeItem(at: dest)
    try FileManager.default.moveItem(at: tmp, to: dest)
  }

  func write(_ path: String, from file: URL) async throws {
    try await makeFolders(path)
    let r = request("PUT", url(path))
    let (_, response) = try await session.upload(for: r, fromFile: file)
    _ = try check(response, [200, 201, 204], r)
  }

  func write(_ path: String, data: Data) async throws {
    try await makeFolders(path)
    let r = request("PUT", url(path))
    let (_, response) = try await session.upload(for: r, from: data)
    _ = try check(response, [200, 201, 204], r)
  }

  func delete(_ path: String) async throws {
    let r = request("DELETE", url(path))
    let (_, response) = try await session.data(for: r)
    _ = try check(response, [200, 204, 404], r)
  }

  /// Creates the folders above `path` (405: it is there already).
  private func makeFolders(_ path: String) async throws {
    var current = ""
    for part in path.split(separator: "/").dropLast() {
      current = current.isEmpty ? String(part) : "\(current)/\(part)"
      let r = request("MKCOL", url(current, folder: true))
      let (_, response) = try await session.data(for: r)
      _ = try check(response, [201, 405], r)
    }
  }

  /// The `<d:response>` entries of a PROPFIND answer.
  private final class MultistatusParser: NSObject, XMLParserDelegate {
    struct Item {
      var href = ""
      var isCollection = false
      var etag: String?
      var length: String?
      var modified: String?
    }

    var responses: [Item] = []
    private var current: Item?
    private var text = ""

    func parser(_ p: XMLParser, didStartElement name: String, namespaceURI: String?, qualifiedName: String?, attributes: [String: String] = [:]) {
      text = ""
      if name == "response" { current = Item() }
      if name == "collection" { current?.isCollection = true }
    }

    func parser(_ p: XMLParser, foundCharacters string: String) { text += string }

    func parser(_ p: XMLParser, didEndElement name: String, namespaceURI: String?, qualifiedName: String?) {
      let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
      switch name {
      case "href": current?.href = value
      case "getetag": current?.etag = value.isEmpty ? nil : value
      case "getcontentlength": current?.length = value
      case "getlastmodified": current?.modified = value
      case "response": if let c = current { responses.append(c) }; current = nil
      default: break
      }
      text = ""
    }
  }
}

/// The sync store in iCloud Drive: the app's ubiquity container, shown as "Klopfgeist"
/// in Files and Finder. Coordinated reads download a file that is only in the cloud.
struct ICloudFolder: SyncFolder {
  static let containerID = "iCloud.pro.geist.klopfgeist"

  /// Whether this build carries the iCloud entitlement (`MV_SYNC=icloud` in project.yml). Without it the
  /// container can never be opened, so the switch is not offered.
  static let isBuiltIn = Bundle.main.object(forInfoDictionaryKey: "MVSync") as? String == "icloud"

  struct Unavailable: LocalizedError {
    var errorDescription: String? { "iCloud Drive is off for Klopfgeist (Settings › your name › iCloud)." }
  }

  let root: URL

  /// Looks the container up (slow on first use, so off the main thread).
  static func make() async throws -> ICloudFolder {
    let url = await Task.detached { FileManager.default.url(forUbiquityContainerIdentifier: containerID) }.value
    guard let url else { throw Unavailable() }
    let docs = url.appendingPathComponent("Documents", isDirectory: true)
    try FileManager.default.createDirectory(at: docs, withIntermediateDirectories: true)
    return ICloudFolder(root: docs)
  }

  private func url(_ path: String) -> URL {
    path.split(separator: "/").reduce(root) { $0.appendingPathComponent(String($1)) }
  }

  private func coordinated<T: Sendable>(_ work: @escaping @Sendable () throws -> T) async throws -> T {
    try await Task.detached { try work() }.value
  }

  func list(_ path: String) async throws -> [SyncFolderItem]? {
    let dir = url(path)
    return try await coordinated {
      let keys: [URLResourceKey] = [.isDirectoryKey, .contentModificationDateKey, .fileSizeKey, .ubiquitousItemDownloadingStatusKey]
      guard FileManager.default.fileExists(atPath: dir.path) else { return nil }
      let urls = try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: keys)
      return urls.compactMap { u -> SyncFolderItem? in
        var name = u.lastPathComponent
        // a file not downloaded yet can show as ".name.icloud"
        if name.hasPrefix("."), name.hasSuffix(".icloud") { name = String(name.dropFirst().dropLast(7)) } else if name.hasPrefix(".") { return nil }
        let v = try? u.resourceValues(forKeys: Set(keys))
        let tag = "\(v?.contentModificationDate?.timeIntervalSince1970 ?? 0)-\(v?.fileSize ?? 0)"
        return SyncFolderItem(name: name, isFolder: v?.isDirectory ?? false, tag: tag)
      }
    }
  }

  func read(_ path: String) async throws -> Data {
    let u = url(path)
    return try await coordinated {
      var error: NSError?
      var result: Result<Data, Error> = .failure(CocoaError(.fileReadUnknown))
      NSFileCoordinator().coordinate(readingItemAt: u, options: [], error: &error) { r in result = Result { try Data(contentsOf: r) } }
      if let error { throw error }
      return try result.get()
    }
  }

  func download(_ path: String, to dest: URL) async throws {
    let u = url(path)
    try await coordinated {
      var error: NSError?
      var failure: Error?
      NSFileCoordinator().coordinate(readingItemAt: u, options: [], error: &error) { r in
        do {
          try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
          try? FileManager.default.removeItem(at: dest)
          try FileManager.default.copyItem(at: r, to: dest)
        } catch { failure = error }
      }
      if let e = error ?? failure { throw e }
    }
  }

  func write(_ path: String, from file: URL) async throws {
    let u = url(path)
    try await coordinated {
      try FileManager.default.createDirectory(at: u.deletingLastPathComponent(), withIntermediateDirectories: true)
      var error: NSError?
      var failure: Error?
      NSFileCoordinator().coordinate(writingItemAt: u, options: .forReplacing, error: &error) { w in
        do {
          try? FileManager.default.removeItem(at: w)
          try FileManager.default.copyItem(at: file, to: w)
        } catch { failure = error }
      }
      if let e = error ?? failure { throw e }
    }
  }

  func write(_ path: String, data: Data) async throws {
    let u = url(path)
    try await coordinated {
      try FileManager.default.createDirectory(at: u.deletingLastPathComponent(), withIntermediateDirectories: true)
      var error: NSError?
      var failure: Error?
      NSFileCoordinator().coordinate(writingItemAt: u, options: .forReplacing, error: &error) { w in
        do { try data.write(to: w, options: .atomic) } catch { failure = error }
      }
      if let e = error ?? failure { throw e }
    }
  }

  func delete(_ path: String) async throws {
    let u = url(path)
    try await coordinated {
      guard FileManager.default.fileExists(atPath: u.path) else { return }
      var error: NSError?
      var failure: Error?
      NSFileCoordinator().coordinate(writingItemAt: u, options: .forDeleting, error: &error) { w in
        do { try FileManager.default.removeItem(at: w) } catch { failure = error }
      }
      if let e = error ?? failure { throw e }
    }
  }
}

/// The WebDAV app password, in the keychain.
enum SyncKeychain {
  static let service = "de.kaikunze.drafttalk.sync"

  static func password(for account: String) -> String? {
    let q: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account,
      kSecReturnData as String: true,
    ]
    var out: CFTypeRef?
    guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
    return String(decoding: data, as: UTF8.self)
  }

  static func setPassword(_ password: String, for account: String) {
    let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    SecItemDelete(q as CFDictionary)
    var add = q
    add[kSecValueData as String] = Data(password.utf8)
    add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
    SecItemAdd(add as CFDictionary, nil)
  }
}
