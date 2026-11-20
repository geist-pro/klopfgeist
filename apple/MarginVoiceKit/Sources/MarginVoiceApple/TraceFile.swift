import Foundation
import MarginVoiceCore
import os

/// The session trace on disk, so a failure in the field can be fetched from the
/// device afterwards without anyone exporting it first:
///
///     xcrun devicectl device copy from --device <id> --domain-type appDataContainer \
///       --domain-identifier de.kaikunze.marginvoice --source Library/Caches/Traces --destination <dir>
///
/// Same lines as Settings › Diagnostics exports (`TraceRecorder.jsonLine`), appended
/// as they come. Bounded: when `current.jsonl` would grow past `maximumBytes` it
/// becomes `previous.jsonl` (replacing the one before) and a new file starts.
/// Writes happen on a queue of their own, never on the caller's thread.
public final class TraceFile: @unchecked Sendable {
  private static let log = Logger.marginVoice("trace")

  public let directory: URL
  public let maximumBytes: Int
  public var current: URL { directory.appendingPathComponent("current.jsonl") }
  public var previous: URL { directory.appendingPathComponent("previous.jsonl") }

  private let queue = DispatchQueue(label: "de.kaikunze.marginvoice.trace-file", qos: .utility)
  /// Size of `current.jsonl`; only touched on `queue`.
  private var size: Int?
  private var reportedFailure = false

  /// `Library/Caches/Traces` in the app's container (iOS); on the Mac below the bundle's own cache folder.
  public static var defaultDirectory: URL {
    let caches = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
    #if os(macOS)
      return caches.appendingPathComponent(Bundle.main.bundleIdentifier ?? "de.kaikunze.marginvoice", isDirectory: true)
        .appendingPathComponent("Traces", isDirectory: true)
    #else
      return caches.appendingPathComponent("Traces", isDirectory: true)
    #endif
  }

  public init(directory: URL = TraceFile.defaultDirectory, maximumBytes: Int = 2_000_000) {
    self.directory = directory
    self.maximumBytes = maximumBytes
  }

  /// Appends the events, encoded off the caller's thread.
  public func append(_ events: some Collection<TraceEvent>) {
    guard !events.isEmpty else { return }
    let batch = Array(events)
    queue.async { [self] in write(batch) }
  }

  /// Waits until everything appended so far is on disk.
  public func flush() {
    queue.sync {}
  }

  private func write(_ events: [TraceEvent]) {
    var text = ""
    for event in events {
      guard let line = TraceRecorder.jsonLine(event) else { continue }
      text += line
      text += "\n"
    }
    let data = Data(text.utf8)
    guard !data.isEmpty else { return }
    do {
      let fm = FileManager.default
      if size == nil {
        try fm.createDirectory(at: directory, withIntermediateDirectories: true)
        size = (try? fm.attributesOfItem(atPath: current.path)[.size] as? Int) ?? 0
      }
      if let s = size, s > 0, s + data.count > maximumBytes {
        try? fm.removeItem(at: previous)
        try fm.moveItem(at: current, to: previous)
        size = 0
      }
      if !fm.fileExists(atPath: current.path) {
        try data.write(to: current)
      } else {
        let handle = try FileHandle(forWritingTo: current)
        defer { try? handle.close() }
        try handle.seekToEnd()
        try handle.write(contentsOf: data)
      }
      size = (size ?? 0) + data.count
    } catch {
      // the trace is a diagnostic: losing it must not disturb the session
      size = nil
      if !reportedFailure {
        reportedFailure = true
        Self.log.error("could not write the trace: \(error.localizedDescription)")
      }
    }
  }
}
