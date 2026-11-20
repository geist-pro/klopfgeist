import Foundation

/// Watches the app's Documents folder (shown as "DraftTalk" in the Files app and
/// in Finder's file sharing) and reports changes once the files stop growing, so
/// files dragged in while the app is open are picked up right away.
@MainActor
final class InboxWatcher {
  private var source: DispatchSourceFileSystemObject?
  private var settle: Task<Void, Never>?
  private var onChange: (@MainActor () -> Void)?

  func start(_ folder: URL, onChange: @escaping @MainActor () -> Void) {
    guard source == nil else { return }
    let fd = open(folder.path, O_EVTONLY)
    guard fd >= 0 else { return }
    self.onChange = onChange
    let source = DispatchSource.makeFileSystemObjectSource(fileDescriptor: fd, eventMask: .write, queue: .main)
    source.setEventHandler { [weak self] in
      MainActor.assumeIsolated { self?.changed(folder) }
    }
    source.setCancelHandler { close(fd) }
    source.resume()
    self.source = source
  }

  func stop() {
    settle?.cancel()
    source?.cancel()
    source = nil
  }

  private func changed(_ folder: URL) {
    settle?.cancel()
    settle = Task { [weak self] in
      // a copy in progress keeps growing; wait until two reads agree
      var last: [String: Int] = [:]
      while !Task.isCancelled {
        try? await Task.sleep(for: .seconds(1))
        let sizes = Self.sizes(in: folder)
        if sizes == last { break }
        last = sizes
      }
      guard !Task.isCancelled else { return }
      self?.onChange?()
    }
  }

  private static func sizes(in folder: URL) -> [String: Int] {
    let files = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.fileSizeKey], options: .skipsHiddenFiles)) ?? []
    var sizes: [String: Int] = [:]
    for url in files { sizes[url.lastPathComponent] = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? -1 }
    return sizes
  }
}
