import AVFoundation
import Foundation
import Observation

/// Plays a comment's recording from a list row ("Play voice note"), one at a time.
@MainActor
@Observable
final class VoiceNotePlayer {
  /// The comment whose recording is playing.
  private(set) var playing: UUID?
  @ObservationIgnored private var player: AVAudioPlayer?
  @ObservationIgnored private var ending: Task<Void, Never>?

  /// Starts the recording at `url`, or stops it when it is the one playing.
  func toggle(_ id: UUID, url: URL?) {
    if playing == id {
      stop()
      return
    }
    stop()
    guard let url, let player = try? AVAudioPlayer(contentsOf: url) else { return }
    self.player = player
    player.play()
    playing = id
    let duration = player.duration
    ending = Task { [weak self] in
      try? await Task.sleep(for: .seconds(duration + 0.2))
      guard !Task.isCancelled, let self, self.playing == id else { return }
      self.playing = nil
      self.player = nil
    }
  }

  func stop() {
    ending?.cancel()
    player?.stop()
    player = nil
    playing = nil
  }
}
