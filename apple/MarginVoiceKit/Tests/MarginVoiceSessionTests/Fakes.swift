import Foundation
import MarginVoiceCore
@testable import MarginVoiceSession

/// A clock the test moves by hand.
@MainActor
final class TestClock {
  var time = 1000.0
}

@MainActor
final class FakePlayer: BookPlayer {
  private(set) var isPlaying = false
  var liveTime = 0.0
  var duration = 3600.0
  private(set) var rate = 1.0
  var onEvent: (@MainActor (PlayerEvent) -> Void)?
  /// Everything the session asked for, in order.
  var calls: [String] = []
  var refusesToPlay = false

  var failsToLoad = false

  func load(_ url: URL, position: Double, rate: Double) throws {
    if failsToLoad { throw CocoaError(.fileReadCorruptFile) }
    liveTime = position
    self.rate = rate
    calls.append("load")
  }
  func unload() { calls.append("unload") }
  func play() {
    calls.append("play")
    guard !refusesToPlay, !isPlaying else {
      if refusesToPlay { onEvent?(.playingChanged(false)) }
      return
    }
    isPlaying = true
    onEvent?(.playingChanged(true))
  }
  func pause() {
    calls.append("pause")
    guard isPlaying else { return }
    isPlaying = false
    onEvent?(.playingChanged(false))
  }
  func seek(to time: Double) {
    liveTime = time
    calls.append("seek \(time)")
  }
  /// The pause in the audio the next resume finds, if any; the times asked for are kept.
  var quiet: Double?
  var quietAsked: [Double] = []
  func quietPoint(near time: Double) -> Double? {
    quietAsked.append(time)
    return quiet
  }
  func setRate(_ rate: Double) { self.rate = rate }
  func playEarcon(_ earcon: ReviewMachine.Earcon) { calls.append("beep \(earcon)") }
  func announce(_ text: String, language: String) { calls.append("say \(text)") }

  /// The book plays for a while.
  func advance(_ seconds: Double) {
    if isPlaying { liveTime += seconds * rate }
  }
}

@MainActor
final class FakeCapture: AudioCapture {
  var route = AudioRoute.builtIn
  var microphonePermission = MicrophonePermission.granted
  var onEvent: (@MainActor (CaptureEvent) -> Void)?
  var otherAudio = false

  /// Every plan applied, with how much had to be rebuilt.
  private(set) var applied: [(plan: AudioPlan, change: AudioPlan.Change)] = []
  var failNextApply: String?
  /// Every apply fails, as when the system refuses the microphone to an app in the background.
  var failsToApply: String?
  private(set) var applyAttempts = 0
  /// Runs once in the middle of the next apply (something happens while the microphone is being set up).
  var duringNextApply: (() -> Void)?
  var micTime = 0.0
  var silence = 0.0
  var isRunning = false
  private(set) var recording: URL?
  var recordedDuration = 4.0

  struct Failure: LocalizedError {
    let errorDescription: String?
  }

  func requestMicrophonePermission() async -> Bool {
    if microphonePermission == .undetermined { microphonePermission = .granted }
    return microphonePermission == .granted
  }

  func apply(_ plan: AudioPlan, change: AudioPlan.Change, language: VoiceCommand.Language) async throws -> CaptureStatus {
    applyAttempts += 1
    if let during = duringNextApply {
      duringNextApply = nil
      during()
      await Task.yield()
    }
    if let why = failsToApply { throw Failure(errorDescription: why) }
    if let why = failNextApply {
      failNextApply = nil
      throw Failure(errorDescription: why)
    }
    applied.append((plan, change))
    if change >= .capture { micTime = 0 }
    isRunning = plan.capture
    return CaptureStatus(microphone: route.input == .builtInMic ? "Built-in microphone" : "AirPods Pro, high quality", output: "test", latency: 0)
  }

  func snapshot() -> MicrophoneSnapshot {
    MicrophoneSnapshot(level: 0, micTime: micTime, silence: silence, isRunning: isRunning)
  }

  func startRecording(to url: URL) throws { recording = url }
  func stopRecording() -> (duration: Double, error: String?) {
    recording = nil
    return (recordedDuration, nil)
  }
  func isOtherAudioPlaying() -> Bool { otherAudio }

  /// How often the microphone itself was rebuilt.
  var captureRebuilds: Int { applied.filter { $0.change == .capture }.count }
}

@MainActor
final class FakeRemote: RemoteControlling {
  var handler: (@MainActor (ReviewMachine.RemoteButton) -> Void)?
  var nowPlaying: NowPlaying?
  func activate(_ handler: @escaping @MainActor (ReviewMachine.RemoteButton) -> Void) { self.handler = handler }
  func deactivate() { handler = nil }
  func update(_ info: NowPlaying) { nowPlaying = info }
  func press(_ button: ReviewMachine.RemoteButton) { handler?(button) }
}

@MainActor
final class FakeBook: ReviewBook {
  var title = "Test Paper"
  var audioURL = URL(fileURLWithPath: "/tmp/test.m4b")
  var position = 0.0
  var rate = 1.0
  var comments: [(time: Double, duration: Double)] = []
  var bookmarks: [(time: Double, end: Double?)] = []
  var discarded = 0
  var savedPosition: Double?
  var bookText = ""
  var canSave = true

  func chapterTitle(at time: Double) -> String? { nil }
  func recentText(before time: Double, seconds: Double) -> String? { bookText }
  var sentenceStarts: Double?
  func sentenceStart(at time: Double) -> Double? { sentenceStarts }
  func newRecordingURL() -> URL? { URL(fileURLWithPath: "/tmp/comment-\(comments.count + discarded).m4a") }
  func discardRecording(_ url: URL) { discarded += 1 }
  func addComment(at time: Double, audio: URL, duration: Double) -> Bool {
    comments.append((time, duration))
    return canSave
  }
  func addBookmark(at time: Double) -> Bool {
    bookmarks.append((time, nil))
    return canSave
  }
  func closeBookmark(at time: Double) -> BookmarkClose {
    guard !bookmarks.isEmpty else { return .missing }
    bookmarks[bookmarks.count - 1].end = time
    return canSave ? .closed : .notSaved
  }
  func savePosition(_ time: Double) -> Bool {
    savedPosition = time
    return canSave
  }
  func saveRate(_ rate: Double) { self.rate = rate }
}

/// A session wired to fakes, with helpers that read like what happens to the user.
@MainActor
final class Rig {
  let clock = TestClock()
  let player = FakePlayer()
  let capture = FakeCapture()
  let remote = FakeRemote()
  let book = FakeBook()
  let session: ReviewSession

  static let airPods = AudioRoute(outputs: [.bluetoothA2DP], input: .bluetoothLE, headsetMicrophoneAvailable: true)

  init(route: AudioRoute = .builtIn, settings: SessionSettings = SessionSettings()) {
    capture.route = route
    let clock = clock
    session = ReviewSession(player: player, capture: capture, remote: remote, settings: settings, now: { clock.time })
  }

  func open() async {
    await session.open(book)
  }

  /// Time passes: the clock, the microphone and (while playing) the book move on,
  /// and the session ticks ten times a second.
  func pass(_ seconds: Double) async {
    var left = seconds
    while left > 1e-9 {
      let step = min(0.1, left)
      left -= step
      clock.time += step
      if capture.isRunning { capture.micTime += step }
      player.advance(step)
      session.tick()
      await session.idle()
    }
  }

  /// The recognizer reports words, each (text, seconds ago it started, seconds ago it ended).
  func hear(_ words: [(String, Double, Double)], final: Bool = false) {
    let mic = capture.micTime
    capture.onEvent?(.hypothesis(Hypothesis(words: words.map { SpokenWord($0.0, start: mic - $0.1, end: mic - $0.2) }, isFinal: final)))
  }

  /// Someone says one word that ended just now, heard only by the recognizer.
  func say(_ word: String, lasting: Double = 0.3) {
    hear([(word, lasting + 0.05, 0.05)])
  }

  var state: String { ReviewSession.describe(session.machine.state) }
  var logText: String { session.log.reversed().map(\.text).joined(separator: "\n") }
}
