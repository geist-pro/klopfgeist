import MarginVoiceCore
import MarginVoiceSession
import MediaPlayer

/// Lock screen, steering-wheel and AirPods controls.
///
/// Next track starts a comment (and previous track discards one while recording),
/// so a comment can be made with a single button press when voice is unreliable.
@MainActor
public final class SystemRemoteControls: RemoteControlling {
  private var targets: [(MPRemoteCommand, Any)] = []

  public init() {}

  public func activate(_ handler: @escaping @MainActor (ReviewMachine.RemoteButton) -> Void) {
    removeTargets()
    let center = MPRemoteCommandCenter.shared()
    let map: [(MPRemoteCommand, ReviewMachine.RemoteButton)] = [
      (center.playCommand, .play), (center.pauseCommand, .pause), (center.togglePlayPauseCommand, .playPause),
      (center.nextTrackCommand, .next), (center.previousTrackCommand, .previous),
    ]
    for (command, button) in map {
      command.isEnabled = true
      let token = command.addTarget { _ in
        MainActor.assumeIsolated { handler(button) }
        return .success
      }
      targets.append((command, token))
    }
    center.changePlaybackPositionCommand.isEnabled = false
  }

  /// The book closed: the buttons and the lock screen are no longer ours.
  public func deactivate() {
    removeTargets()
    let center = MPNowPlayingInfoCenter.default()
    center.nowPlayingInfo = nil
    #if os(macOS)
      center.playbackState = .stopped
    #endif
  }

  private func removeTargets() {
    for (command, token) in targets { command.removeTarget(token) }
    targets.removeAll()
  }

  public func update(_ now: NowPlaying) {
    var info: [String: Any] = [
      MPMediaItemPropertyTitle: now.chapter ?? now.title,
      MPMediaItemPropertyAlbumTitle: now.title,
      MPMediaItemPropertyPlaybackDuration: now.duration,
      MPNowPlayingInfoPropertyElapsedPlaybackTime: now.elapsed,
      MPNowPlayingInfoPropertyPlaybackRate: now.isPlaying ? now.rate : 0,
      MPNowPlayingInfoPropertyDefaultPlaybackRate: now.rate,
      MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
    ]
    if now.chapter != nil { info[MPMediaItemPropertyArtist] = now.title }
    let center = MPNowPlayingInfoCenter.default()
    center.nowPlayingInfo = info
    #if os(macOS)
      // the Mac sends media keys and AirPods taps only to an app that says it is playing
      center.playbackState = now.isPlaying ? .playing : .paused
    #endif
  }
}
