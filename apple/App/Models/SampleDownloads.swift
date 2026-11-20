import MarginVoiceCore
import Foundation
import Observation

/// Downloads the sample papers into the inbox, which imports them like any `.drafttalk`
/// file. Shared, so closing the sheet keeps a download going.
@MainActor
@Observable
final class SampleDownloads {
  static let shared = SampleDownloads()

  /// Progress 0…1 per sample file while it downloads.
  private(set) var progress: [String: Double] = [:]
  /// The last failure per sample file.
  private(set) var errors: [String: String] = [:]
  @ObservationIgnored private var tasks: [String: URLSessionDownloadTask] = [:]
  @ObservationIgnored private var observations: [String: NSKeyValueObservation] = [:]

  func download(_ sample: SampleBooks.Sample) {
    guard progress[sample.file] == nil else { return }
    errors[sample.file] = nil
    progress[sample.file] = 0
    let file = sample.file
    let target = BookStore.inboxURL.appendingPathComponent(file)
    let task = URLSession.shared.downloadTask(with: sample.url) { location, response, error in
      // the temporary file is gone once this handler returns: move it right here
      var failure: String?
      if let error {
        failure = (error as? URLError)?.code == .cancelled ? nil : error.localizedDescription
      } else if let status = (response as? HTTPURLResponse)?.statusCode, status != 200 {
        failure = "The download failed (HTTP \(status))."
      } else if let location {
        do {
          try? FileManager.default.removeItem(at: target)
          try FileManager.default.moveItem(at: location, to: target)
        } catch {
          failure = error.localizedDescription
        }
      }
      Task { @MainActor in self.finish(file, failure: failure) }
    }
    observations[file] = task.progress.observe(\.fractionCompleted) { p, _ in
      let fraction = p.fractionCompleted
      Task { @MainActor in if self.progress[file] != nil { self.progress[file] = fraction } }
    }
    tasks[file] = task
    task.resume()
  }

  func cancel(_ sample: SampleBooks.Sample) {
    tasks[sample.file]?.cancel()
  }

  /// The inbox watcher imports the moved file.
  private func finish(_ file: String, failure: String?) {
    progress[file] = nil
    errors[file] = failure
    tasks[file] = nil
    observations[file] = nil
  }
}
