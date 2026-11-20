import Foundation

/// The hands-free review loop as a pure state machine. The app feeds it events
/// (voice commands, headset buttons, silence) and executes the returned effects.
public struct ReviewMachine: Sendable, Equatable {
  public enum State: Sendable, Equatable {
    case paused
    case playing
    /// Recording a comment anchored at `anchor` (media seconds).
    case recording(anchor: Double)
  }

  public enum RemoteButton: String, Sendable, Equatable, Hashable, Codable {
    case playPause, play, pause, next, previous
  }

  public enum Event: Sendable, Equatable {
    case command(VoiceCommand)
    case remote(RemoteButton)
    /// Silence after speech while recording (end of comment).
    case silenceTimeout
    /// The comment ended with nobody asking to go on (nothing said, the time limit,
    /// a call, another app playing): it is finished, and the book stays paused.
    case commentAbandoned(save: Bool)
    /// Tap on the big on-screen comment button.
    case commentButton
  }

  public enum Earcon: Sendable, Equatable {
    case recordStart, recordSaved, recordDiscarded, bookmark
    /// The second "bookmark" that closes a passage.
    case bookmarkEnd
  }

  public enum Effect: Sendable, Equatable {
    case play
    case pause
    case seek(to: Double)
    /// Resume near this time, at a sentence start when one is close before it.
    case seekToSentence(near: Double)
    case beep(Earcon)
    case startRecording(anchor: Double)
    case finishRecording(save: Bool)
    case addBookmark(at: Double)
    /// Extends the open bookmark to a passage ending here.
    case closeBookmark(at: Double)
    case setRate(Double)
    case restartSentence
    case announce(String)
  }

  public struct Settings: Sendable, Equatable {
    /// Subtracted from the command time: people say "stop" a moment after the passage.
    public var reactionOffset: Double = 1.5
    /// After a comment, resume this far before the moment "stop" was said.
    public var resumeRewind: Double = 2
    public var backStep: Double = 15
    public var rateStep: Double = 0.1
    public var rateRange: ClosedRange<Double> = 0.7...2.5
    /// Saying "bookmark" again within this many seconds turns the first bookmark into a passage.
    public var bookmarkWindow: Double = 120
    /// Headsets and cars send some button presses twice (or as "toggle" plus "play"):
    /// a second transport button within this time is the same press.
    public var remoteDebounce: Double = 0.3
    public init() {}
  }

  public private(set) var state: State = .paused
  public var settings: Settings
  /// Where the book was when the comment started; playback resumes just before it.
  private var stoppedAt: Double = 0
  /// Time of the last "back" command, to double the step when repeated quickly.
  private var lastBack: (at: Double, wall: Double)?
  /// The last bookmark while it can still be extended to a passage.
  public private(set) var openBookmark: (time: Double, wall: Double)?
  /// The last headset button, to drop its echo.
  private var lastRemote: (button: RemoteButton, wall: Double)?
  /// "Pause" arrived during the comment (an earbud taken out): the "play" that
  /// follows is the earbud going back in, not the end of the comment.
  private var pausedByRemoteWhileRecording = false

  /// Whether a second "bookmark" would end a passage now.
  public func isBookmarkOpen(now: Double) -> Bool {
    openBookmark.map { now - $0.wall <= settings.bookmarkWindow } ?? false
  }

  public init(settings: Settings = Settings()) {
    self.settings = settings
  }

  /// Forgets everything about the previous book (open bookmark, repeated "back").
  public mutating func reset() {
    state = .paused
    stoppedAt = 0
    lastBack = nil
    openBookmark = nil
    lastRemote = nil
    pausedByRemoteWhileRecording = false
  }

  public mutating func setPlaying(_ playing: Bool) {
    if case .recording = state { return }
    state = playing ? .playing : .paused
  }

  /// - Parameters:
  ///   - time: current media time.
  ///   - rate: current playback rate.
  ///   - now: wall clock seconds (for repeated "back").
  public mutating func handle(_ event: Event, time: Double, rate: Double, now: Double = 0) -> [Effect] {
    if case .remote(let button) = event {
      if let last = lastRemote, now > last.wall, now - last.wall < settings.remoteDebounce, isEcho(button, of: last.button) {
        return []
      }
      lastRemote = (button, now)
      if case .recording = state {
        // AirPods send "pause" when one is taken out of the ear and "play" when it
        // goes back in. Neither is a decision about the comment.
        if button == .pause {
          pausedByRemoteWhileRecording = true
          return []
        }
        if button == .play, pausedByRemoteWhileRecording {
          pausedByRemoteWhileRecording = false
          return []
        }
      }
    }
    if case .recording = state {} else { pausedByRemoteWhileRecording = false }
    switch (state, event) {
    // start a comment
    case (.playing, .command(.stop)), (.playing, .remote(.next)), (.playing, .commentButton),
      (.paused, .remote(.next)), (.paused, .commentButton):
      let anchor = max(0, time - (state == .playing ? settings.reactionOffset : 0))
      state = .recording(anchor: anchor)
      stoppedAt = time
      openBookmark = nil
      return [.pause, .beep(.recordStart), .startRecording(anchor: anchor)]

    // finish a comment
    case (.recording, .command(.resume)), (.recording, .silenceTimeout),
      (.recording, .remote(.playPause)), (.recording, .remote(.play)), (.recording, .commentButton):
      state = .playing
      return [.finishRecording(save: true), .beep(.recordSaved), .seekToSentence(near: resumePoint), .play]

    case (.recording, .command(.cancel)), (.recording, .remote(.previous)):
      state = .playing
      return [.finishRecording(save: false), .beep(.recordDiscarded), .seekToSentence(near: resumePoint), .play]

    // "stop", then "mark this": a bookmark, not a comment
    case (.recording(let anchor), .command(.mark)):
      state = .playing
      openBookmark = (anchor, now)
      return [.finishRecording(save: false), .addBookmark(at: anchor), .beep(.bookmark), .seekToSentence(near: resumePoint), .play]

    case (.recording, .commentAbandoned(let save)):
      state = .paused
      return [.finishRecording(save: save), .beep(save ? .recordSaved : .recordDiscarded), .seekToSentence(near: resumePoint)]

    case (.recording, _):
      return []  // everything else is part of the comment

    // transport
    case (.paused, .command(.resume)), (.paused, .remote(.play)), (.paused, .remote(.playPause)):
      state = .playing
      return [.play]
    case (.playing, .remote(.pause)), (.playing, .remote(.playPause)):
      state = .paused
      return [.pause]
    case (_, .command(.back)), (_, .remote(.previous)):
      var step = settings.backStep
      if let last = lastBack, now - last.wall < 8 { step *= 2 }
      lastBack = (time, now)
      return [.seek(to: max(0, time - step))]
    case (_, .command(.again)):
      return [.restartSentence]
    case (_, .command(.mark)):
      let at = max(0, time - settings.reactionOffset)
      if let open = openBookmark, isBookmarkOpen(now: now), at > open.time {
        openBookmark = nil
        return [.closeBookmark(at: at), .beep(.bookmarkEnd)]
      }
      openBookmark = (at, now)
      return [.addBookmark(at: at), .beep(.bookmark)]
    case (_, .command(.faster)), (_, .command(.slower)):
      let sign: Double = event == .command(.faster) ? 1 : -1
      let raw = ((rate + sign * settings.rateStep) * 10).rounded() / 10
      let new = min(settings.rateRange.upperBound, max(settings.rateRange.lowerBound, raw))
      return new == rate ? [] : [.setRate(new), .announce(String(format: "%.1f", new))]
    default:
      return []
    }
  }

  private var resumePoint: Double { max(0, stoppedAt - settings.resumeRewind) }

  /// Whether `button` right after `previous` is the same press reported again.
  private func isEcho(_ button: RemoteButton, of previous: RemoteButton) -> Bool {
    let transport: Set<RemoteButton> = [.play, .pause, .playPause]
    return button == previous || (transport.contains(button) && transport.contains(previous))
  }

  public static func == (lhs: ReviewMachine, rhs: ReviewMachine) -> Bool {
    lhs.state == rhs.state && lhs.settings == rhs.settings
  }
}
