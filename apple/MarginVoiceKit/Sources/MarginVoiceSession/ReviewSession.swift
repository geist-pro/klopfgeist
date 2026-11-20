import Foundation
import MarginVoiceCore
import Observation

/// The hands-free review loop for the open book: playback, voice commands, headset
/// buttons and comment recording.
///
/// Decisions are made by pure types in MarginVoiceCore (`ReviewMachine`,
/// `CommandDetector`, `AudioCoordinator`); this class feeds them what the ports
/// report and carries out what they ask for. It never sleeps or reads a clock of
/// its own: time is `now()`, and the periodic rules run in `tick()`, which the app
/// calls ten times a second and a test calls when it wants time to pass.
@MainActor
@Observable
public final class ReviewSession {
  public enum ListenState: Sendable, Equatable {
    case off
    case starting
    case listening
    case unavailable(String)
  }

  /// A short confirmation for the status pill ("Comment saved").
  public struct Flash: Sendable, Equatable {
    public enum Kind: Sendable, Equatable { case comment, bookmark, passage }
    public let kind: Kind
    public let at: Double
  }

  public struct LogEntry: Sendable, Equatable, Identifiable {
    public let id: Int
    /// Session clock.
    public let at: Double
    public let text: String
    public let accepted: Bool
  }

  /// A comment ended by silence after longer than this leaves the book paused.
  static let longestCommentThatPlaysOn: Double = 120
  static let nothingSaidLimit: Double = 20
  static let longestComment: Double = 300

  // MARK: Observable state

  public private(set) var machine = ReviewMachine()
  public private(set) var book: (any ReviewBook)?
  public private(set) var listenState = ListenState.off
  /// The microphone listening right now ("AirPods Pro, high quality").
  public private(set) var microphone = "Not listening"
  /// The microphone filters the book out of what it hears (built-in microphone on the speaker).
  public private(set) var echoCancellation = false
  public private(set) var micLevel: Float = 0
  /// Session clock time the current recording began.
  public private(set) var recordingStarted: Double?
  public private(set) var flash: Flash?
  public private(set) var log: [LogEntry] = []
  /// A bookmark was just set and saying "bookmark" again would end a passage there.
  public private(set) var isBookmarkOpen = false
  /// Voice commands are on, but the microphone was let go because nobody used the
  /// paused book for a while; Play or the status pill turns it back on.
  public private(set) var isListeningPaused = false
  public var errorMessage: String?

  public var settings: SessionSettings {
    didSet {
      guard settings != oldValue, book != nil else { return }
      if settings.commandLanguage != oldValue.commandLanguage { languageChanged = true }
      if settings.voiceCommandsEnabled, !oldValue.voiceCommandsEnabled { isListeningPaused = false }
      replan()
    }
  }
  /// The app is in the background (iOS): a paused book lets go of the microphone sooner.
  public var isInBackground = false {
    didSet {
      backgroundSince = isInBackground ? now() : nil
      // a microphone that could not start while the app was away is tried again at once
      if !isInBackground, oldValue { lastRepair = -.infinity }
    }
  }
  /// The book finished. Return the next book to open and play, or nil.
  @ObservationIgnored public var onBookFinished: (@MainActor () async -> (any ReviewBook)?)?

  public var isRecording: Bool {
    if case .recording = machine.state { true } else { false }
  }
  public var isPlaying: Bool { player.isPlaying }

  // MARK: Ports and pure parts

  @ObservationIgnored public let player: any BookPlayer
  @ObservationIgnored private let capture: any AudioCapture
  @ObservationIgnored private let remote: any RemoteControlling
  @ObservationIgnored private let now: @MainActor () -> Double

  @ObservationIgnored private var detector = CommandDetector()
  @ObservationIgnored private var coordinator = AudioCoordinator()
  @ObservationIgnored private var otherAudio = OtherAudioWatch()
  @ObservationIgnored public private(set) var trace = TraceRecorder()

  // MARK: Bookkeeping

  @ObservationIgnored private var plan = AudioPlan.inactive
  @ObservationIgnored private var status = CaptureStatus()
  @ObservationIgnored private var hasPlayed = false
  @ObservationIgnored private var permission = MicrophonePermission.undetermined
  @ObservationIgnored private var languageChanged = false
  /// The audio setup is being brought to the latest plan.
  @ObservationIgnored private var pump: Task<Void, Never>?
  @ObservationIgnored private var dirty = false
  @ObservationIgnored private var rebuildForced = false
  @ObservationIgnored private var lastRepair = -Double.infinity
  /// Recent (microphone time, book time, playing) samples to map an utterance to a book position.
  @ObservationIgnored private var clock: [(mic: Double, book: Double, playing: Bool)] = []
  @ObservationIgnored private var lastMicProgress: (mic: Double, at: Double) = (0, 0)
  @ObservationIgnored private var detectorMode = ""

  @ObservationIgnored private var recordingURL: URL?
  @ObservationIgnored private var recordingAnchor = 0.0
  @ObservationIgnored private var recordingTask: Task<Void, Never>?
  /// Microphone time the recording began: the end of "stop" heard after it is not the comment.
  @ObservationIgnored private var recordingMicStart = Double.infinity
  @ObservationIgnored private var heardSpeechWhileRecording = false
  /// Microphone time anything was last heard, by the energy detector or the recognizer.
  @ObservationIgnored private var lastSpeechEvidence = 0.0
  @ObservationIgnored private var otherAudioAtRecordingStart = false

  @ObservationIgnored private var lastPositionSave = -Double.infinity
  @ObservationIgnored private var lastOtherAudioLook = -Double.infinity
  /// When the book last stopped playing or was last used while paused; nil while it plays.
  @ObservationIgnored private var pausedSince: Double?
  @ObservationIgnored private var backgroundSince: Double?
  @ObservationIgnored private var reportedSaveFailure = false
  @ObservationIgnored private var nextLogID = 0

  public init(
    player: any BookPlayer, capture: any AudioCapture, remote: any RemoteControlling, settings: SessionSettings = SessionSettings(),
    now: @escaping @MainActor () -> Double
  ) {
    self.player = player
    self.capture = capture
    self.remote = remote
    self.settings = settings
    self.now = now
    player.onEvent = { [weak self] event in self?.playerEvent(event) }
    capture.onEvent = { [weak self] event in self?.captureEvent(event) }
  }

  // MARK: Opening and closing

  public func open(_ book: any ReviewBook) async {
    guard self.book !== book else { return }
    close(releasingAudio: false)
    do {
      try player.load(book.audioURL, position: book.position, rate: book.rate)
    } catch {
      errorMessage = "Could not open the audiobook: \(error.localizedDescription)"
      // the book before it is closed and nothing took its place: let go of the audio
      replan()
      return
    }
    self.book = book
    hasPlayed = false
    pausedSince = now()
    otherAudio.reset()
    permission = capture.microphonePermission
    remote.activate { [weak self] button in self?.remoteButton(button) }
    updateNowPlaying()
    if settings.voiceCommandsEnabled, permission == .undetermined {
      permission = await capture.requestMicrophonePermission() ? .granted : .denied
      guard self.book === book else { return }
    }
    _ = coordinator.routeChanged(capture.route, reason: .other, now: now() - coordinator.policy.settle)
    replan()
    await idle()
  }

  /// Closes the book. Switching to another book keeps the audio session.
  public func close(releasingAudio: Bool = true) {
    guard book != nil else { return }
    if isRecording { dispatch(.commentAbandoned(save: true)) }
    player.pause()
    persistPosition(force: true)
    machine.reset()
    player.unload()
    remote.deactivate()
    book = nil
    isBookmarkOpen = false
    isListeningPaused = false
    hasPlayed = false
    detector.reset()
    clock.removeAll()
    if releasingAudio { replan() }
  }

  /// Waits until the audio setup has caught up with the latest plan (and a
  /// recording that was starting has started).
  public func idle() async {
    while pump != nil || recordingTask != nil {
      await pump?.value
      await recordingTask?.value
    }
  }

  // MARK: Controls (UI)

  public func togglePlay() { dispatch(.remote(.playPause), debounced: false) }
  public func commentButton() { dispatch(.commentButton) }
  public func discardRecording() { dispatch(.command(.cancel)) }
  public func skipBack() { dispatch(.command(.back)) }
  public func repeatSentence() { dispatch(.command(.again)) }
  public func addBookmark() { dispatch(.command(.mark)) }

  public func seek(to time: Double) {
    touch()
    player.seek(to: time)
    persistPosition(force: true)
    updateNowPlaying()
  }

  public func setRate(_ rate: Double) {
    player.setRate(rate)
    book?.saveRate(rate)
    updateNowPlaying()
  }

  /// Listens again after `isListeningPaused` (the status pill, or Play).
  public func resumeListening() {
    guard isListeningPaused else { return }
    isListeningPaused = false
    touch()
    replan()
  }

  /// Tries the microphone again now ("Try Again" on the status pill) instead of
  /// waiting for the next automatic attempt.
  public func retryListening() {
    if isListeningPaused { return resumeListening() }
    guard book != nil, case .unavailable = listenState, pump == nil else { return }
    // the answer may have changed in the system's settings
    permission = capture.microphonePermission
    guard permission == .granted else { return }
    lastRepair = now()
    listenState = .starting
    forceRebuild()
  }

  /// Lets go of the microphone but keeps voice commands on.
  public func pauseListening(_ why: String) {
    guard book != nil, settings.voiceCommandsEnabled, !isListeningPaused else { return }
    isListeningPaused = true
    note("Stopped listening: \(why)", accepted: true)
    replan()
  }

  // MARK: Audio setup

  private var needs: AudioNeeds {
    var n = AudioNeeds()
    n.bookOpen = book != nil
    n.hasPlayed = hasPlayed
    n.voiceCommands = settings.voiceCommandsEnabled
    n.listeningPaused = isListeningPaused
    n.recording = isRecording
    n.microphonePermission = permission == .granted
    n.useHeadsetMicrophone = settings.useHeadsetMicrophone
    return n
  }

  /// Something the plan depends on changed.
  private func replan() {
    schedule(coordinator.needsChanged(needs, now: now()))
  }

  private func schedule(_ action: AudioCoordinator.Action?) {
    guard action != nil || languageChanged else { return }
    startPump()
  }

  private func startPump() {
    dirty = true
    guard pump == nil else { return }
    pump = Task { [weak self] in
      while let self, self.dirty {
        self.dirty = false
        await self.applyLatest()
      }
      self?.pump = nil
    }
  }

  /// One pass: whatever the coordinator wants now, applied. Requests that arrive
  /// during the pass are folded into the next one, never dropped and never run in parallel.
  private func applyLatest() async {
    var action = coordinator.needsChanged(needs, now: now())
    if action == nil, languageChanged {
      // same plan, another language: only the recognizer restarts
      action = .init(plan: coordinator.applied, change: .recognizer)
    }
    if rebuildForced {
      // what is running is broken or dead: rebuild it even though the plan is the same
      rebuildForced = false
      let plan = action?.plan ?? AudioPlan.make(needs: needs, route: capture.route, echoCancellationAllowed: coordinator.echoCancellationAllowed)
      action = .init(plan: plan, change: .capture)
    }
    guard let action else { return }
    languageChanged = false
    let wanted = action.plan
    if wanted.recognize, listenState != .listening { listenState = .starting }
    trace.record(.plan(wanted, change: "\(action.change)"), at: now())
    do {
      let status = try await capture.apply(wanted, change: action.change, language: settings.commandLanguage)
      coordinator.didApply(wanted, now: now())
      plan = wanted
      self.status = status
      if action.change >= .capture { microphoneRestarted() }
      echoCancellation = wanted.capture && wanted.echoCancellation
      if wanted.capture {
        microphone = status.microphone
        if action.change >= .capture {
          note("Microphone: \(status.microphone), book on \(status.output)\(wanted.echoCancellation ? ", echo cancellation on" : "")", accepted: true)
        }
      } else {
        microphone = "Not listening"
      }
      if let problem = status.recognizerError, wanted.recognize {
        listenState = .unavailable(problem)
        note("Speech recognition failed: \(problem)", accepted: false)
      } else if !wanted.capture, permission == .denied, settings.voiceCommandsEnabled, !isListeningPaused, book != nil {
        listenState = .unavailable("Microphone access is off. Turn it on in Settings › Klopfgeist.")
      } else {
        listenState = wanted.recognize ? .listening : .off
      }
    } catch {
      // nothing was applied: the coordinator still holds the old plan and asks again
      listenState = wanted.capture ? .unavailable(error.localizedDescription) : .off
      note("Microphone failed: \(error.localizedDescription)", accepted: false)
      lastRepair = now()
    }
  }

  private func microphoneRestarted() {
    detector.reset()
    clock.removeAll()
    lastSpeechEvidence = 0
    lastMicProgress = (0, now())
    if recordingURL != nil { recordingMicStart = 0 }
    trace.record(.microphoneRestarted, at: now())
  }

  /// Brings the microphone back when it failed to start or stopped delivering audio.
  private func watchMicrophone(_ snap: MicrophoneSnapshot) {
    guard book != nil, plan.capture || needs.voiceCommands && !needs.listeningPaused, pump == nil else { return }
    let t = now()
    if case .unavailable = listenState {
      // a failed start (another app held the microphone) is tried again now and then
      // the system does not start a microphone for an app in the background: wait for it to return
      guard permission == .granted, !isInBackground, t - lastRepair > 15 else { return }
      lastRepair = t
      note("Microphone unavailable: trying again", accepted: false)
      forceRebuild()
      return
    }
    guard plan.capture else { return }
    if snap.micTime != lastMicProgress.mic { lastMicProgress = (snap.micTime, t) }
    let stalled = snap.isRunning && t - lastMicProgress.at > 3
    guard !snap.isRunning || stalled, t - lastRepair > 5 else { return }
    lastRepair = t
    note(stalled ? "Microphone stalled: restarting" : "Microphone missing: restarting", accepted: false)
    forceRebuild()
  }

  /// Rebuilds the microphone although the plan has not changed. Goes through the
  /// same pump as every other change, so a request made while one is being applied
  /// runs right after it.
  private func forceRebuild() {
    rebuildForced = true
    startPump()
  }

  // MARK: Events from the ports

  private func playerEvent(_ event: PlayerEvent) {
    switch event {
    case .playingChanged(let playing):
      pausedSince = playing ? nil : now()
      if playing {
        if !hasPlayed {
          hasPlayed = true
          replan()
        }
        if isListeningPaused { resumeListening() }
      } else {
        otherAudio.ownSound(now: now())
      }
      machine.setPlaying(playing)
      persistPosition(force: !playing)
      updateNowPlaying()
    case .finished:
      Task { await bookFinished() }
    case .interruptionBegan:
      // a phone call while recording: keep what was said
      guard isRecording else { return }
      note("Audio interrupted: comment saved", accepted: true)
      dispatch(.commentAbandoned(save: true))
    case .interruptionEnded:
      guard book != nil, plan.capture || needs.voiceCommands && !needs.listeningPaused else { return }
      forceRebuild()
    case .mediaServicesReset:
      guard book != nil else { return }
      forceRebuild()
    }
  }

  private func captureEvent(_ event: CaptureEvent) {
    switch event {
    case .hypothesis(let h):
      let mic = capture.snapshot().micTime
      trace.record(.hypothesis(h, mic: mic), at: now())
      if let end = h.words.map(\.end).max() {
        lastSpeechEvidence = max(lastSpeechEvidence, end)
        // the recognizer hears speech the energy detector can miss (a quiet headset microphone)
        if isRecording, end > recordingMicStart { heardSpeechWhileRecording = true }
      }
      handle(detector.hypothesis(h, now: mic, context: detectorContext()))
    case .speech(let active, let micTime):
      trace.record(.speech(active: active, mic: micTime), at: now())
      detector.speech(active: active, at: micTime)
      lastSpeechEvidence = max(lastSpeechEvidence, micTime)
      if active {
        // a sample right at speech start, so a "stop" just after a restart has one too
        sampleClock(mic: micTime)
        if isRecording, micTime >= recordingMicStart { heardSpeechWhileRecording = true }
      }
    case .restarted:
      microphoneRestarted()
    case .routeChanged(let route, let reason):
      guard book != nil else { return }
      trace.record(.route(route, reason: reason.rawValue), at: now())
      if reason != .other { note("Audio device \(reason == .deviceArrived ? "connected" : "disconnected")", accepted: true) }
      schedule(coordinator.routeChanged(route, reason: reason, now: now()))
    case .failed(let why):
      note("Microphone failed: \(why)", accepted: false)
      lastRepair = now() - 10
      listenState = .unavailable(why)
    }
  }

  private func remoteButton(_ button: ReviewMachine.RemoteButton) {
    trace.record(.remote(button), at: now())
    dispatch(.remote(button))
  }

  // MARK: Commands

  private func detectorContext() -> CommandDetector.Context {
    let mode: String
    let context: CommandDetector.Context
    if isRecording {
      mode = "recording"
      context = .init(policy: .recording, allowed: CommandDetector.Context.recordingCommands)
    } else if plan.bookAudible, player.isPlaying {
      mode = "listeningOverSpeaker"
      context = .init(policy: .listeningOverSpeaker, recentBookText: book?.recentText(before: player.liveTime + 0.5, seconds: 5))
    } else {
      mode = "listening"
      context = .init(policy: .listening)
    }
    if mode != detectorMode {
      detectorMode = mode
      trace.record(.mode(mode), at: now())
    }
    return context
  }

  private func handle(_ outcomes: [CommandDetector.Outcome]) {
    for outcome in outcomes {
      trace.record(.outcome(TraceReplay.describe(outcome)), at: now())
      switch outcome {
      case .rejected(let text, let why):
        note("Ignored “\(text)” (\(Self.describe(why)))", accepted: false)
      case .fire(let c):
        // the other app's speech may be what was heard, and starting the book would cut it off
        if otherAudio.isPlaying, !player.isPlaying {
          if isRecording, c.command == .resume || c.command == .cancel {
            note("“\(c.text)” ends the comment; another app is playing, so the book stays paused", accepted: true)
            dispatch(.commentAbandoned(save: c.command == .resume))
          } else {
            note("Ignored “\(c.text)” (another app is playing)", accepted: false)
          }
          continue
        }
        let bookTime = bookTime(atMic: c.start)
        note("“\(c.text)” → \(c.command.rawValue) at \(Self.clockText(bookTime))", accepted: true)
        dispatch(.command(c.command), time: c.command == .stop || c.command == .mark ? bookTime : nil)
      }
    }
  }

  static func describe(_ why: CommandDetector.Rejection) -> String {
    switch why {
    case .notIsolated: "not said on its own"
    case .speechContinued: "more was said"
    case .bookEcho: "the book just said it"
    case .duplicate: "already done"
    case .tooLong: "drawn out"
    }
  }

  private func sampleClock(mic: Double) {
    // what the listener heard when this microphone audio was spoken
    let heard = player.isPlaying ? max(0, player.liveTime - status.latency * player.rate) : player.liveTime
    clock.append((mic, heard, player.isPlaying))
    if clock.count > 200 { clock.removeFirst(clock.count - 200) }
  }

  /// Book position at a moment on the microphone timeline.
  private func bookTime(atMic mic: Double) -> Double {
    guard let sample = clock.last(where: { $0.mic <= mic + 0.01 }) ?? clock.first else { return player.liveTime }
    return sample.playing ? sample.book + (mic - sample.mic) * player.rate : sample.book
  }

  // MARK: State machine

  private func dispatch(_ event: ReviewMachine.Event, time: Double? = nil, debounced: Bool = true) {
    guard book != nil else { return }
    touch()
    machine.settings.reactionOffset = settings.reactionOffset
    machine.settings.resumeRewind = settings.resumeRewind
    // the on-screen button is not a headset: two quick taps are two taps
    machine.settings.remoteDebounce = debounced ? ReviewMachine.Settings().remoteDebounce : 0
    let effects = machine.handle(event, time: time ?? player.liveTime, rate: player.rate, now: now())
    trace.record(.machine(event: "\(event)", state: Self.describe(machine.state), effects: effects.map { "\($0)" }), at: now())
    for effect in effects { perform(effect) }
  }

  static func describe(_ state: ReviewMachine.State) -> String {
    switch state {
    case .paused: "paused"
    case .playing: "playing"
    case .recording: "recording"
    }
  }

  private func perform(_ effect: ReviewMachine.Effect) {
    switch effect {
    case .play: player.play()
    case .pause: player.pause()
    case .seek(let t): seek(to: t)
    case .seekToSentence(let t):
      // Start a sentence cleanly when one begins just before the resume point. Sentence times are
      // estimates, so the audio has the last word: resume after the pause nearest to that point.
      let start = book?.sentenceStart(at: t) ?? t
      let atSentence = t - start <= 1.5 && t >= start
      let quiet = player.quietPoint(near: atSentence ? start : t)
      seek(to: max(0, quiet ?? (atSentence ? start - 0.1 : t)))
    case .beep(let e):
      otherAudio.ownSound(now: now())
      player.playEarcon(e)
    case .startRecording(let anchor):
      // the machine leaves `.recording` before `.finishRecording` runs, so keep the anchor here
      recordingAnchor = anchor
      beginRecording()
    case .finishRecording(let save): endRecording(save: save)
    case .addBookmark(let t):
      guard let book else { break }
      saved(book.addBookmark(at: t), "bookmark")
      flash = Flash(kind: .bookmark, at: now())
      note("Bookmark at \(Self.clockText(t))", accepted: true)
    case .closeBookmark(let t):
      guard let book else { break }
      switch book.closeBookmark(at: t) {
      case .closed:
        flash = Flash(kind: .passage, at: now())
        note("Bookmarked passage up to \(Self.clockText(t))", accepted: true)
      case .missing:
        // deleted in the meantime: nothing happened, so nothing is confirmed
        note("No bookmark to extend", accepted: false)
      case .notSaved:
        saved(false, "passage end")
      }
    case .setRate(let r): setRate(r)
    case .restartSentence:
      let t = player.liveTime
      seek(to: max(0, (book?.sentenceStart(at: t) ?? t - 5) - 0.2))
    case .announce(let s):
      let german = settings.commandLanguage == .german
      player.announce(german ? "Tempo \(s)" : "Speed \(s)", language: german ? "de-DE" : "en-US")
    }
  }

  // MARK: Recording

  private func beginRecording() {
    guard let book else { return }
    recordingStarted = now()
    heardSpeechWhileRecording = false
    otherAudioAtRecordingStart = otherAudio.isPlaying
    // commands spoken before the comment are not its end
    detector.forget(before: capture.snapshot().micTime)
    _ = detectorContext()
    recordingTask?.cancel()
    recordingTask = Task { [weak self] in
      defer { self?.recordingTask = nil }
      guard let self else { return }
      if self.permission == .undetermined {
        self.permission = await self.capture.requestMicrophonePermission() ? .granted : .denied
      }
      self.replan()
      await self.pump?.value
      // "continue" or "cancel" while the microphone was starting: nothing to record
      guard self.isRecording, self.recordingURL == nil, self.book === book, !Task.isCancelled else { return }
      guard self.plan.capture, let url = book.newRecordingURL() else {
        self.errorMessage = "Recording needs microphone access."
        self.dispatch(.commentAbandoned(save: false))
        return
      }
      do {
        try self.capture.startRecording(to: url)
        self.recordingURL = url
        self.recordingMicStart = self.capture.snapshot().micTime
        self.lastSpeechEvidence = max(self.lastSpeechEvidence, self.recordingMicStart)
      } catch {
        self.errorMessage = "Could not record: \(error.localizedDescription)"
        self.dispatch(.commentAbandoned(save: false))
      }
    }
  }

  private func endRecording(save: Bool) {
    recordingStarted = nil
    recordingMicStart = .infinity
    _ = detectorContext()
    defer { replan() }
    guard let url = recordingURL, let book else { return }
    recordingURL = nil
    let (duration, error) = capture.stopRecording()
    if let error {
      note("Recording stopped early: \(error)", accepted: false)
      errorMessage = "Only the first \(Int(duration)) s of the comment could be recorded: \(error)"
    }
    guard save, duration > 0.3 else {
      book.discardRecording(url)
      return
    }
    saved(book.addComment(at: recordingAnchor, audio: url, duration: duration), "comment")
    flash = Flash(kind: .comment, at: now())
    note("Saved comment at \(Self.clockText(recordingAnchor))", accepted: true)
  }

  // MARK: Time

  /// The periodic rules. The app calls this ten times a second.
  public func tick() {
    guard book != nil else { return }
    let t = now()
    let snap = capture.snapshot()
    micLevel = snap.level
    if snap.isRunning { sampleClock(mic: snap.micTime) }
    watchMicrophone(snap)
    schedule(coordinator.tick(route: capture.route, now: t))
    if plan.recognize || isRecording {
      handle(detector.tick(now: snap.micTime, context: detectorContext()))
    }
    if let started = recordingStarted, isRecording, recordingURL != nil { checkRecording(elapsed: t - started, snap: snap) }
    if player.isPlaying { persistPosition() }
    lookForOtherAudio(t)
    checkIdleListening(t)
    let open = machine.isBookmarkOpen(now: t)
    if open != isBookmarkOpen { isBookmarkOpen = open }
  }

  private func checkRecording(elapsed: Double, snap: MicrophoneSnapshot) {
    // quiet since anything was last heard, by the energy detector or the recognizer:
    // on a headset the energy detector alone can sit through a whole sentence
    let silence = min(snap.silence, max(0, snap.micTime - lastSpeechEvidence))
    if otherAudio.isPlaying, !otherAudioAtRecordingStart {
      // what it records from here on is the other app
      note("Another app started playing: comment \(heardSpeechWhileRecording ? "saved" : "discarded"), the book stays paused", accepted: true)
      dispatch(.commentAbandoned(save: heardSpeechWhileRecording))
    } else if settings.endCommentOnSilence, heardSpeechWhileRecording, silence >= settings.silenceTimeout, elapsed > 1 {
      note("Comment ended after \(Int(settings.silenceTimeout)) s of silence", accepted: true)
      // after a long comment the listener may have moved on: only a short one plays on by itself
      let playOn = elapsed <= Self.longestCommentThatPlaysOn && !otherAudio.isPlaying
      dispatch(playOn ? .silenceTimeout : .commentAbandoned(save: true))
    } else if !heardSpeechWhileRecording, elapsed > Self.nothingSaidLimit {
      note("Nothing said, comment discarded, the book stays paused", accepted: false)
      dispatch(.commentAbandoned(save: false))
    } else if elapsed > Self.longestComment {
      note("Comment ended after 5 minutes, the book stays paused", accepted: true)
      dispatch(.commentAbandoned(save: true))
    }
  }

  private func lookForOtherAudio(_ t: Double) {
    if player.isPlaying {
      otherAudio.reset()
    } else if t - lastOtherAudioLook >= 0.5 {
      lastOtherAudioLook = t
      otherAudio.update(active: capture.isOtherAudioPlaying(), now: t)
    }
  }

  /// A paused book stops listening after the time set in Settings, and after a
  /// minute at most once the app is in the background: the microphone (and its
  /// indicator) should not stay on for a book nobody is using.
  private func checkIdleListening(_ t: Double) {
    guard settings.stopListeningAfter > 0, !isListeningPaused, !isRecording, !player.isPlaying, var since = pausedSince,
      listenState == .listening || listenState == .starting
    else { return }
    if otherAudio.isPlaying {
      pauseListening("another app is playing")
      return
    }
    var limit = Double(settings.stopListeningAfter) * 60
    if let backgroundSince {
      limit = min(limit, 60)
      since = max(since, backgroundSince)
    }
    guard t - since >= limit else { return }
    pauseListening(backgroundSince != nil ? "paused in the background" : "paused for \(settings.stopListeningAfter) min")
  }

  /// The paused book was used: the idle countdown starts over.
  private func touch() {
    if pausedSince != nil { pausedSince = now() }
  }

  private func bookFinished() async {
    guard let book, !isRecording else { return }
    saved(book.savePosition(player.duration), "position")
    note("Finished “\(book.title)”", accepted: true)
    guard let next = await onBookFinished?() else {
      // nothing more to hear: nothing to say either
      pauseListening("the book ended")
      return
    }
    await open(next)
    guard self.book === next, !player.isPlaying else { return }
    dispatch(.remote(.play), debounced: false)
  }

  // MARK: Persistence and feedback

  private func persistPosition(force: Bool = false) {
    guard let book, force || now() - lastPositionSave > 5 else { return }
    lastPositionSave = now()
    saved(book.savePosition(player.liveTime), "position")
    updateNowPlaying()
  }

  private func updateNowPlaying() {
    guard let book else { return }
    let t = player.liveTime
    remote.update(
      NowPlaying(title: book.title, chapter: book.chapterTitle(at: t), elapsed: t, duration: player.duration, rate: player.rate, isPlaying: player.isPlaying))
  }

  /// Tells the user once when the library can't be written: comments made after
  /// that would otherwise be lost silently.
  private func saved(_ ok: Bool, _ what: String) {
    guard !ok else { return }
    note("Could not save the \(what)", accepted: false)
    if !reportedSaveFailure {
      reportedSaveFailure = true
      errorMessage = "Klopfgeist could not save your \(what). Free some storage, then reopen the book."
    }
  }

  /// Adds a line to the command log (the app reports its own steps here too, like
  /// "Transcribed 3 comments again").
  public func note(_ text: String, accepted: Bool) {
    nextLogID += 1
    log.insert(LogEntry(id: nextLogID, at: now(), text: text, accepted: accepted), at: 0)
    if log.count > 100 { log.removeLast() }
    trace.record(.note(text), at: now())
  }

  static func clockText(_ seconds: Double) -> String {
    let s = Int(max(0, seconds))
    return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
  }
}
