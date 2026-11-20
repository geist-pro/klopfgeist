import MarginVoiceCore
import Foundation
import Observation
import SwiftData
import os

/// Keeps books and comments in step with the other devices: through iCloud Drive
/// between Apple devices, and through a WebDAV folder (Nextcloud) that the Android
/// app reaches too. With both on, this device carries books between the two.
/// Runs at launch, when the app goes to the background and half a minute after
/// the last change; one sync at a time.
@MainActor
@Observable
final class SyncCenter {
  nonisolated private static let log = Logger.drafttalk("sync")
  private let defaults: UserDefaults

  var iCloudEnabled: Bool { didSet { defaults.set(iCloudEnabled, forKey: "sync.icloud") } }
  var webDAVEnabled: Bool { didSet { defaults.set(webDAVEnabled, forKey: "sync.webdav") } }
  private(set) var server: String
  private(set) var user: String
  var hasPassword: Bool { SyncKeychain.password(for: account) != nil }

  private(set) var isSyncing = false
  private(set) var lastSync: Date?
  private(set) var error: String?
  private(set) var lastReport: String?

  @ObservationIgnored private var local: SwiftDataSyncLocal?
  @ObservationIgnored private var pending: Task<Void, Never>?
  @ObservationIgnored private var saves: NSObjectProtocol?
  private var account: String { "\(user)@\(server)" }

  init(defaults: UserDefaults = .standard) {
    self.defaults = defaults
    iCloudEnabled = ICloudFolder.isBuiltIn && defaults.bool(forKey: "sync.icloud")
    webDAVEnabled = defaults.bool(forKey: "sync.webdav")
    server = defaults.string(forKey: "sync.server") ?? ""
    user = defaults.string(forKey: "sync.user") ?? ""
    lastSync = defaults.object(forKey: "sync.last") as? Date
  }

  private static var support: URL { FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0] }

  /// Connects to the library; call once the model container exists.
  func attach(context: ModelContext, store: BookStore, session: SessionHost) {
    let local = SwiftDataSyncLocal(context: context, store: store, stampsURL: Self.support.appendingPathComponent("sync-stamps.json"))
    local.willDelete = { [weak session] book in
      if session?.book?.id == book.id { session?.close() }
    }
    self.local = local
    saves = NotificationCenter.default.addObserver(forName: ModelContext.didSave, object: context, queue: .main) { [weak self] _ in
      MainActor.assumeIsolated { self?.libraryChanged() }
    }
    syncSoon(after: .seconds(2))
  }

  /// A library change: note when it happened, sync once things are quiet (playback saves the position often).
  func libraryChanged() {
    guard !isSyncing, iCloudEnabled || webDAVEnabled else { return }
    local?.stampAll()
    syncSoon(after: .seconds(30))
  }

  func syncSoon(after delay: Duration = .zero) {
    guard iCloudEnabled || webDAVEnabled else { return }
    pending?.cancel()
    pending = Task {
      try? await Task.sleep(for: delay)
      guard !Task.isCancelled else { return }
      await sync()
    }
  }

  /// Sets the WebDAV account; an empty password keeps the stored one. Turns WebDAV on and syncs.
  func configureWebDAV(server: String, user: String, password: String) {
    let server = server.trimmingCharacters(in: .whitespaces)
    let user = user.trimmingCharacters(in: .whitespaces)
    if server != self.server || user != self.user {
      // another account or folder: what this device knew about the old one doesn't apply
      try? FileManager.default.removeItem(at: stateURL("webdav"))
    }
    self.server = server
    self.user = user
    defaults.set(server, forKey: "sync.server")
    defaults.set(user, forKey: "sync.user")
    if !password.isEmpty { SyncKeychain.setPassword(password, for: account) }
    webDAVEnabled = true
    syncSoon()
  }

  private func stateURL(_ name: String) -> URL { Self.support.appendingPathComponent("sync-state-\(name).json") }

  func sync() async {
    guard let local, !isSyncing else { return }
    isSyncing = true
    defer { isSyncing = false }
    var errors: [String] = []
    var summary: [String] = []
    local.stampAll()
    if iCloudEnabled {
      do {
        summary.append("iCloud: " + (try await run(folder: try await ICloudFolder.make(), name: "icloud", local: local)))
      } catch {
        errors.append(error.localizedDescription)
      }
    }
    if webDAVEnabled {
      do {
        guard let url = WebDAVFolder.davURL(server: server, user: user), let password = SyncKeychain.password(for: account) else {
          throw WebDAVFolder.DAVError(message: "Enter the server, user name and app password.")
        }
        summary.append("Nextcloud: " + (try await run(folder: WebDAVFolder(root: url, user: user, password: password), name: "webdav", local: local)))
      } catch {
        errors.append(error.localizedDescription)
      }
    }
    error = errors.isEmpty ? nil : errors.joined(separator: "\n")
    // the status line turns into the error in place
    if !errors.isEmpty { Announce.say(StatusAnnouncement.syncFailed) }
    if errors.isEmpty {
      lastSync = Date()
      defaults.set(lastSync, forKey: "sync.last")
    }
    lastReport = summary.joined(separator: "; ")
  }

  private func run(folder: any SyncFolder, name: String, local: SwiftDataSyncLocal) async throws -> String {
    let url = stateURL(name)
    let state = (try? JSONDecoder().decode(SyncState.self, from: Data(contentsOf: url))) ?? SyncState()
    let report = try await SyncEngine(folder: folder, local: local) { Self.log.notice("\($0, privacy: .public)") }.sync(state)
    try JSONEncoder().encode(report.state).write(to: url, options: .atomic)
    Self.log.notice("\(name, privacy: .public): \(report.uploaded) up, \(report.downloaded) down, \(report.deleted) deleted")
    return "\(report.uploaded) up, \(report.downloaded) down, \(report.deleted) deleted"
  }
}
