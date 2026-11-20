import Foundation
import MarginVoiceApple
import MarginVoiceCore
import MarginVoiceSession
import Observation
import QuartzCore
import SwiftData
import os
#if canImport(UIKit)
  import UIKit
#else
  import AppKit
#endif

/// The review session as the app uses it: owns the audio adapters and the
/// platform-free `ReviewSession`, opens books from the library on it, keeps its
/// clock ticking, hands it the settings, and exposes what the screens show.
///
/// It decides nothing about commands or audio; that is the session and the core.
@MainActor
@Observable
final class SessionHost {
  typealias ListenState = ReviewSession.ListenState

  /// A short confirmation shown in the status pill ("Comment saved").
  struct Flash: Equatable {
    let label: String
    let symbol: String
    let isBookmark: Bool
    let date: Date
  }

  struct LogEntry: Identifiable {
    let id: Int
    let date: Date
    let text: String
    let accepted: Bool
  }

  private static let log = Logger.drafttalk("review")

  /// The session itself (state machine, command log, trace).
  let review: ReviewSession
  @ObservationIgnored private let player: EnginePlayer
  @ObservationIgnored let processor: CommentProcessor
  @ObservationIgnored private let store: BookStore
  @ObservationIgnored private let settings: AppSettings
  @ObservationIgnored private var context: ModelContext?
  @ObservationIgnored private var ticker: Task<Void, Never>?
  /// A book is being handed to the session (its own `book` is not ours yet).
  @ObservationIgnored private var opening = 0
  @ObservationIgnored private var lastFlash: ReviewSession.Flash?
  @ObservationIgnored private var reportedSaveFailure = false
  @ObservationIgnored private var awakeActivity: NSObjectProtocol?
  /// The trace as it grows, in Library/Caches/Traces: fetched from the device after a
  /// failure (see `TraceFile`) when nobody exported it in time.
  @ObservationIgnored private let traceFile = TraceFile()
  /// How many of the session's trace events are in the file already.
  @ObservationIgnored private var traceWritten = 0
  @ObservationIgnored private var ticksSinceTraceWrite = 0

  private(set) var book: Book?
  private(set) var content: BookContent?
  /// The book that plays when this one ends (when continuing is on).
  private(set) var upNext: Book?
  private(set) var flash: Flash?
  // The player is not observable: these follow it ten times a second, and at once after every control.
  private(set) var isPlaying = false
  private(set) var currentTime: Double = 0
  private(set) var duration: Double = 0
  private(set) var rate: Double = 1

  init(store: BookStore, settings: AppSettings) {
    self.store = store
    self.settings = settings
    // player and microphone share one engine: echo cancellation needs both on it
    let engine = AudioEngineHost()
    let player = EnginePlayer(host: engine)
    self.player = player
    review = ReviewSession(
      player: player, capture: AppleAudioCapture(host: engine), remote: SystemRemoteControls(),
      settings: Self.sessionSettings(settings), now: { CACurrentMediaTime() })
    processor = CommentProcessor(store: store, settings: settings)
    processor.save = { [weak self] what in self?.save(what) }
    processor.note = { [weak self] text, accepted in self?.review.note(text, accepted: accepted) }
    player.onEarcon = { earcon in Self.haptic(for: earcon) }
    review.onBookFinished = { [weak self] in await self?.nextBook() }
    trackSettings()
    #if os(iOS)
      NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
        MainActor.assumeIsolated {
          self?.review.isInBackground = true
          self?.writeTrace()
        }
      }
      NotificationCenter.default.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
        MainActor.assumeIsolated { self?.review.isInBackground = false }
      }
    #endif
  }

  // MARK: What the screens read

  var machine: ReviewMachine { review.machine }

  var listenState: ListenState {
    #if DEBUG
      // screenshots in the simulator, which has no speech recognition: DRAFTTALK_LISTENING=1 shows "Listening"
      if ProcessInfo.processInfo.environment["DRAFTTALK_LISTENING"] == "1" { return .listening }
    #endif
    return review.listenState
  }
  /// The microphone listening right now ("AirPods Pro, high quality").
  var microphone: String { review.microphone }
  /// The microphone filters the book's own sound out (built-in microphone on the speaker).
  var echoCancellation: Bool { review.echoCancellation }
  var micLevel: Float { review.micLevel }
  var isRecording: Bool { review.isRecording }
  /// A bookmark was just set and saying "bookmark" again would end a passage there.
  var isBookmarkOpen: Bool { review.isBookmarkOpen }
  /// Voice commands are on, but the microphone was let go because nobody used the
  /// paused book for a while; Play or the status pill turns it back on.
  var isListeningPaused: Bool { review.isListeningPaused }
  /// When the comment being recorded began (the session keeps it on its own clock).
  var recordingStarted: Date? { review.recordingStarted.map(date(atSessionTime:)) }

  var errorMessage: String? {
    get { review.errorMessage }
    set { review.errorMessage = newValue }
  }

  /// The command log, newest first.
  var events: [LogEntry] {
    review.log.map { LogEntry(id: $0.id, date: date(atSessionTime: $0.at), text: $0.text, accepted: $0.accepted) }
  }

  /// The flight recorder as JSON lines, for Settings › Diagnostics.
  func traceJSONLines() -> String { review.trace.jsonLines() }

  /// Appends what the session recorded since the last call to the trace file
  /// (encoded and written off the main thread).
  private func writeTrace() {
    ticksSinceTraceWrite = 0
    let trace = review.trace
    guard trace.recorded != traceWritten else { return }
    traceFile.append(trace.events(after: traceWritten))
    traceWritten = trace.recorded
  }

  /// The session's clock is monotonic seconds; screens show wall-clock times.
  private func date(atSessionTime t: Double) -> Date {
    Date().addingTimeInterval(t - CACurrentMediaTime())
  }

  // MARK: Settings

  static func sessionSettings(_ settings: AppSettings) -> SessionSettings {
    var s = SessionSettings()
    s.voiceCommandsEnabled = settings.voiceCommandsEnabled
    s.commandLanguage = settings.commandLanguage
    s.useHeadsetMicrophone = settings.useHeadsetMicrophone
    s.reactionOffset = settings.reactionOffset
    s.resumeRewind = settings.resumeRewind
    s.endCommentOnSilence = settings.endCommentOnSilence
    s.silenceTimeout = settings.silenceTimeout
    s.stopListeningAfter = settings.stopListeningAfter
    return s
  }

  /// Hands the session a new settings value whenever one it reads changes.
  private func trackSettings() {
    withObservationTracking {
      applySettings()
    } onChange: { [weak self] in
      Task { @MainActor in self?.trackSettings() }
    }
  }

  private func applySettings() {
    let new = Self.sessionSettings(settings)
    if review.settings != new { review.settings = new }
    _ = settings.continueWithNextBook
    updateIdleTimer()
  }

  // MARK: Opening books

  func open(_ book: Book, context: ModelContext) async {
    self.context = context
    guard self.book?.id != book.id else { return }
    let sessionBook = prepare(book, context: context)
    opening += 1
    await review.open(sessionBook)
    opening -= 1
    sync()
  }

  /// Makes `book` the open book of the screens and wraps it for the session.
  private func prepare(_ book: Book, context: ModelContext) -> SessionBook {
    book.lastOpened = Date()
    // a finished book starts over
    if book.isFinished { book.position = 0 }
    let sessionBook = SessionBook(book: book, context: context, store: store, processor: processor)
    self.book = book
    content = nil
    refreshUpNext()
    startTicker()
    Task {
      let content = await store.content(for: book)
      // another book was opened while this one's text loaded
      guard self.book === book else { return }
      sessionBook.content = content
      self.content = content
      processor.resumeCutOff(in: book)
    }
    return sessionBook
  }

  /// Closes the book. Switching to another book keeps the audio session.
  func close() {
    review.close()
    book = nil
    content = nil
    upNext = nil
    sync()
    writeTrace()
  }

  // MARK: Continuing with the next book

  /// The next unfinished book further down the library (newest first), wrapping around.
  func refreshUpNext() {
    guard let book, let context, settings.continueWithNextBook else {
      upNext = nil
      return
    }
    let all = (try? context.fetch(FetchDescriptor<Book>(sortBy: [SortDescriptor(\.addedAt, order: .reverse)]))) ?? []
    guard let i = all.firstIndex(where: { $0.id == book.id }) else { return }
    upNext = (all[(i + 1)...] + all[..<i]).first { !$0.isFinished }
  }

  /// The book ended: the one the session opens and plays next, if any.
  private func nextBook() async -> (any ReviewBook)? {
    refreshUpNext()
    guard let finished = book, let next = upNext, let context else { return nil }
    // tell the driver what comes next, then keep going
    let language = VoiceCommand.Language(code: next.language).regionIdentifier
    player.announce(settings.commandLanguage == .german ? "Als Nächstes: \(next.title)" : "Up next: \(next.title)", language: language)
    for _ in 0..<40 where player.isAnnouncing { try? await Task.sleep(for: .milliseconds(200)) }
    // closed, or started over by hand, while the title was spoken
    guard book === finished, !player.isPlaying else { return nil }
    return prepare(next, context: context)
  }

  /// Marks a book finished (or unplayed). The open book moves its playhead, or
  /// playback would write its old position back.
  func setFinished(_ finished: Bool, _ book: Book, context: ModelContext) {
    if book === self.book {
      if finished { review.player.pause() }
      seek(to: finished ? player.duration : 0)
    } else {
      book.position = finished ? book.duration : 0
      context.saveLogged("mark finished")
    }
    refreshUpNext()
  }

  // MARK: Controls (UI)

  func togglePlay() { act { review.togglePlay() } }
  func commentButton() { act { review.commentButton() } }
  func discardRecording() { act { review.discardRecording() } }
  func skipBack() { act { review.skipBack() } }
  func repeatSentence() { act { review.repeatSentence() } }
  func addBookmark() { act { review.addBookmark() } }
  func seek(to time: Double) { act { review.seek(to: time) } }
  func setRate(_ rate: Double) { act { review.setRate(rate) } }

  private func act(_ action: () -> Void) {
    action()
    sync()
  }

  // MARK: Listening

  func setVoiceCommands(_ enabled: Bool) async {
    settings.voiceCommandsEnabled = enabled
    applySettings()
    await review.idle()
  }

  /// Listens again after listening was paused, or tries a microphone that failed once more.
  func startListening() async {
    review.retryListening()
    await review.idle()
  }

  /// Lets go of the microphone but keeps voice commands on: Play, or a tap on the
  /// status pill, listens again.
  func pauseListening(_ why: String) { review.pauseListening(why) }

  /// The microphone choice or the command language changed in Settings.
  func restartListening() async {
    applySettings()
    await review.idle()
  }

  // MARK: Comments

  /// Transcribes the comment and pins it to the text. Safe to call again (retry).
  func postProcess(_ comment: Comment) async { await processor.process(comment) }

  /// Transcribes every recorded comment of the book again, replacing the transcripts.
  func transcribeAgain(_ book: Book) async { await processor.transcribeAgain(book) }

  // MARK: Clock

  /// The session owns no timers: its periodic rules run when it is ticked, ten
  /// times a second while a book is open.
  private func startTicker() {
    guard ticker == nil else { return }
    ticker = Task { @MainActor [weak self] in
      while !Task.isCancelled {
        try? await Task.sleep(for: .milliseconds(100))
        guard let self else { return }
        self.review.tick()
        self.sync()
        // about once a second, so a missed command is on disk moments later
        self.ticksSinceTraceWrite += 1
        if self.ticksSinceTraceWrite >= 10 { self.writeTrace() }
        if self.book == nil, self.opening == 0 { break }
      }
      self?.writeTrace()
      self?.ticker = nil
    }
  }

  /// Brings what the screens observe in line with the player and the session.
  private func sync() {
    // the session gave the book up (it could not be opened)
    if review.book == nil, book != nil, opening == 0 {
      book = nil
      content = nil
      upNext = nil
    }
    let playing = player.isPlaying
    if playing != isPlaying {
      isPlaying = playing
      updateIdleTimer()
    }
    let time = player.liveTime
    if time != currentTime { currentTime = time }
    if player.duration != duration { duration = player.duration }
    if player.rate != rate { rate = player.rate }
    if review.flash != lastFlash {
      lastFlash = review.flash
      flash = review.flash.map { f in
        switch f.kind {
        case .comment: Flash(label: "Comment saved", symbol: "checkmark.circle.fill", isBookmark: false, date: date(atSessionTime: f.at))
        case .bookmark: Flash(label: "Bookmarked", symbol: "bookmark.fill", isBookmark: true, date: date(atSessionTime: f.at))
        case .passage: Flash(label: "Passage bookmarked", symbol: "bookmark.fill", isBookmark: true, date: date(atSessionTime: f.at))
        }
      }
    }
  }

  private func updateIdleTimer() {
    let awake = settings.keepScreenAwake && player.isPlaying
    #if os(iOS)
      UIApplication.shared.isIdleTimerDisabled = awake
    #else
      if awake, awakeActivity == nil {
        awakeActivity = ProcessInfo.processInfo.beginActivity(options: [.idleDisplaySleepDisabled, .userInitiated], reason: "Playing a book")
      } else if !awake, let activity = awakeActivity {
        ProcessInfo.processInfo.endActivity(activity)
        awakeActivity = nil
      }
    #endif
  }

  // MARK: Feedback

  private static func haptic(for earcon: ReviewMachine.Earcon) {
    #if os(iOS)
      let generator = UINotificationFeedbackGenerator()
      switch earcon {
      case .recordStart: UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
      case .recordSaved: generator.notificationOccurred(.success)
      case .recordDiscarded: generator.notificationOccurred(.warning)
      case .bookmark: UIImpactFeedbackGenerator(style: .light).impactOccurred()
      case .bookmarkEnd: UIImpactFeedbackGenerator(style: .medium).impactOccurred()
      }
    #endif
  }

  /// Saves (after a transcript arrived), and tells the user once when the library
  /// can't be written: comments made after that would otherwise be lost silently.
  private func save(_ what: String) {
    guard let context, !context.saveLogged(what) else { return }
    review.note("Could not save the \(what)", accepted: false)
    if !reportedSaveFailure {
      reportedSaveFailure = true
      errorMessage = "Klopfgeist could not save your \(what). Free some storage, then reopen the book."
    }
  }
}
