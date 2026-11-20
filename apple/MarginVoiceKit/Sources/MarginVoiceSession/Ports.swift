import Foundation
import MarginVoiceCore

// The review session talks to the platform only through these protocols. The app
// supplies AVFoundation / Speech / MediaPlayer adapters; the tests supply fakes and
// a clock they move by hand, so every rule of the session runs under `swift test`.

/// Plays the book and the short sounds around it.
@MainActor
public protocol BookPlayer: AnyObject {
  var isPlaying: Bool { get }
  /// Media time right now.
  var liveTime: Double { get }
  var duration: Double { get }
  var rate: Double { get }

  func load(_ url: URL, position: Double, rate: Double) throws
  func unload()
  /// Starts playing. Every change of `isPlaying` (this one too) is reported as `.playingChanged`.
  func play()
  func pause()
  func seek(to time: Double)
  func setRate(_ rate: Double)
  func playEarcon(_ earcon: ReviewMachine.Earcon)
  /// Says a short phrase ("Speed 1.4", "Up next: …").
  func announce(_ text: String, language: String)

  /// Where to resume near `time` so that playback starts after a pause in the narration
  /// (`QuietPoint` over the audio around it); nil when the audio has none there or can't be read.
  func quietPoint(near time: Double) -> Double?

  var onEvent: (@MainActor (PlayerEvent) -> Void)? { get set }
}

public extension BookPlayer {
  func quietPoint(near time: Double) -> Double? { nil }
}

public enum PlayerEvent: Sendable, Equatable {
  case playingChanged(Bool)
  /// Playback reached the end of the book.
  case finished
  /// A phone call or Siri took the audio.
  case interruptionBegan
  case interruptionEnded
  /// The system's audio services restarted: everything has to be set up again.
  case mediaServicesReset
}

public enum MicrophonePermission: Sendable, Equatable {
  case undetermined, granted, denied
}

/// What is known about the running microphone.
public struct CaptureStatus: Sendable, Equatable {
  /// "AirPods Pro, high quality"
  public var microphone: String
  /// Where the book is heard: "AirPods Pro", "Speaker".
  public var output: String
  /// Output plus input latency of the route, seconds.
  public var latency: Double
  /// Why commands can't be recognized although the microphone runs.
  public var recognizerError: String?

  public init(microphone: String = "", output: String = "", latency: Double = 0, recognizerError: String? = nil) {
    self.microphone = microphone
    self.output = output
    self.latency = latency
    self.recognizerError = recognizerError
  }
}

public struct MicrophoneSnapshot: Sendable, Equatable {
  /// 0…1, for the level meter.
  public var level: Float
  /// Seconds of microphone audio since capture (re)started.
  public var micTime: Double
  /// Seconds since the energy detector last heard speech.
  public var silence: Double
  public var isRunning: Bool

  public init(level: Float = 0, micTime: Double = 0, silence: Double = 0, isRunning: Bool = false) {
    self.level = level
    self.micTime = micTime
    self.silence = silence
    self.isRunning = isRunning
  }
}

public enum CaptureEvent: Sendable, Equatable {
  /// A recognizer result (words on the microphone timeline).
  case hypothesis(Hypothesis)
  /// The energy detector: speech started or ended at this microphone time.
  case speech(active: Bool, micTime: Double)
  /// Capture was rebuilt by the system (format or route change): microphone time is zero again.
  case restarted
  case routeChanged(AudioRoute, AudioCoordinator.RouteReason)
  /// The microphone or recognizer stopped working.
  case failed(String)
}

/// The audio session, the microphone, the command recognizer and the comment recorder.
@MainActor
public protocol AudioCapture: AnyObject {
  var route: AudioRoute { get }
  var microphonePermission: MicrophonePermission { get }
  func requestMicrophonePermission() async -> Bool

  /// Brings the audio system to `plan`. `change` says how much differs from what was
  /// applied last; the adapter rebuilds no more than that.
  func apply(_ plan: AudioPlan, change: AudioPlan.Change, language: VoiceCommand.Language) async throws -> CaptureStatus
  func snapshot() -> MicrophoneSnapshot

  func startRecording(to url: URL) throws
  /// Closes the file. `error` when the recording stopped early.
  func stopRecording() -> (duration: Double, error: String?)

  /// Another app is playing (as far as the system tells).
  func isOtherAudioPlaying() -> Bool

  var onEvent: (@MainActor (CaptureEvent) -> Void)? { get set }
}

public struct NowPlaying: Sendable, Equatable {
  public var title: String
  public var chapter: String?
  public var elapsed: Double
  public var duration: Double
  public var rate: Double
  public var isPlaying: Bool

  public init(title: String, chapter: String?, elapsed: Double, duration: Double, rate: Double, isPlaying: Bool) {
    self.title = title
    self.chapter = chapter
    self.elapsed = elapsed
    self.duration = duration
    self.rate = rate
    self.isPlaying = isPlaying
  }
}

/// Lock screen, headset and steering-wheel buttons.
@MainActor
public protocol RemoteControlling: AnyObject {
  func activate(_ handler: @escaping @MainActor (ReviewMachine.RemoteButton) -> Void)
  func deactivate()
  func update(_ info: NowPlaying)
}

/// What became of the second "bookmark".
public enum BookmarkClose: Sendable, Equatable {
  /// The bookmark is a passage now.
  case closed
  /// There is no bookmark to extend (it was deleted in the meantime).
  case missing
  /// The library could not be written.
  case notSaved
}

/// The open book, as far as the session needs it: where comments go.
@MainActor
public protocol ReviewBook: AnyObject {
  var title: String { get }
  var audioURL: URL { get }
  var position: Double { get }
  var rate: Double { get }
  func chapterTitle(at time: Double) -> String?

  /// What the book narrated in the `seconds` before `time`.
  func recentText(before time: Double, seconds: Double) -> String?
  /// Start time of the sentence playing at `time`.
  func sentenceStart(at time: Double) -> Double?

  /// Where the next comment's audio is written.
  func newRecordingURL() -> URL?
  func discardRecording(_ url: URL)
  /// Each returns false when the library could not be written.
  func addComment(at time: Double, audio: URL, duration: Double) -> Bool
  func addBookmark(at time: Double) -> Bool
  /// Extends the bookmark added last to a passage ending at `time`.
  func closeBookmark(at time: Double) -> BookmarkClose
  func savePosition(_ time: Double) -> Bool
  func saveRate(_ rate: Double)
}

/// The settings the session reads. A value: the app hands over a new one when the user changes something.
public struct SessionSettings: Sendable, Equatable {
  public var voiceCommandsEnabled = true
  public var commandLanguage = VoiceCommand.Language.english
  public var useHeadsetMicrophone = false
  public var reactionOffset = 1.5
  public var resumeRewind = 2.0
  public var endCommentOnSilence = false
  public var silenceTimeout = 6.0
  /// Minutes a paused book keeps listening; 0 = until it closes.
  public var stopListeningAfter = 5

  public init() {}
}
