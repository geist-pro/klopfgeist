import Foundation
import MarginVoiceCore
import Testing
@testable import MarginVoiceSession

/// The review loop end to end against fakes. Each test is a thing that happens to
/// someone using the app; several are the AirPods failures the rewrite is for.
@MainActor
struct ReviewSessionTests {
  // MARK: The basic loop

  // MARK: Where the book resumes

  private func commentAndContinue(_ rig: Rig) async {
    await rig.open()
    rig.session.togglePlay()
    await rig.pass(30)
    rig.say("stop")
    await rig.pass(1.5)
    rig.hear([("unclear", 0.6, 0.1)], final: true)
    await rig.pass(0.7)
    rig.say("continue")
    await rig.pass(0.8)
  }

  @Test func resumesAfterThePauseTheAudioHas() async {
    let rig = Rig(route: Rig.airPods)
    rig.player.quiet = 28.4
    await commentAndContinue(rig)
    // asked near the resume point (two seconds before "stop"), and the audio's pause is taken
    #expect(rig.player.quietAsked.count == 1)
    #expect(abs((rig.player.quietAsked.first ?? 0) - (30 - 0.35 - 2.0)) < 0.3, "asked \(rig.player.quietAsked)")
    #expect(rig.player.calls.last { $0.hasPrefix("seek") } == "seek 28.4")
    #expect(rig.player.isPlaying)
  }

  @Test func aSentenceStartJustBeforeTheResumePointIsWhereThePauseIsLookedFor() async {
    let rig = Rig(route: Rig.airPods)
    rig.book.sentenceStarts = 27.0
    rig.player.quiet = 26.8
    await commentAndContinue(rig)
    #expect(rig.player.quietAsked == [27.0])
    #expect(rig.player.calls.last { $0.hasPrefix("seek") } == "seek 26.8")
  }

  @Test func withoutAPauseInTheAudioTheEstimateStands() async {
    let rig = Rig(route: Rig.airPods)
    rig.book.sentenceStarts = 27.0
    await commentAndContinue(rig)
    #expect(rig.player.calls.last { $0.hasPrefix("seek") } == "seek 26.9")
  }

  @Test func stopCommentContinue() async {
    let rig = Rig(route: Rig.airPods)
    await rig.open()
    #expect(rig.session.listenState == .listening)
    rig.session.togglePlay()
    await rig.pass(30)
    #expect(rig.state == "playing")

    rig.say("stop")
    await rig.pass(0.5)
    #expect(rig.state == "recording")
    #expect(!rig.player.isPlaying)
    #expect(rig.capture.recording != nil)

    await rig.pass(1)
    rig.hear([("this", 0.9, 0.7), ("is", 0.7, 0.6), ("unclear", 0.6, 0.1)], final: true)
    await rig.pass(0.7)
    rig.say("continue")
    await rig.pass(0.8)

    #expect(rig.state == "playing")
    #expect(rig.player.isPlaying)
    #expect(rig.book.comments.count == 1)
    // anchored a reaction time before "stop" was said
    let anchor = try! #require(rig.book.comments.first?.time)
    #expect(abs(anchor - (30 - 0.35 - 1.5)) < 0.3, "anchor \(anchor)")
    #expect(rig.session.flash?.kind == .comment)
  }

  @Test func stopIsHeardWithoutTheEnergyDetector() async {
    // AirPods: no `.speech` event ever arrives. The old pipeline dropped the command silently.
    let rig = Rig(route: Rig.airPods)
    await rig.open()
    rig.session.togglePlay()
    await rig.pass(10)
    rig.say("stop")
    await rig.pass(0.5)
    #expect(rig.state == "recording")
  }

  @Test func continueSaidRightAfterTheCommentStillEndsIt() async {
    // The comment and "continue" are one stretch of speech with a half-second pause.
    let rig = Rig(route: Rig.airPods)
    await rig.open()
    rig.session.togglePlay()
    await rig.pass(10)
    rig.say("stop")
    await rig.pass(2)
    rig.hear([("too", 1.6, 1.4), ("vague", 1.4, 1.0), ("continue", 0.45, 0.05)])
    await rig.pass(0.7)
    #expect(rig.state == "playing")
    #expect(rig.book.comments.count == 1)
  }

  @Test func continueInsideASentenceDoesNotEndTheComment() async {
    let rig = Rig(route: Rig.airPods)
    await rig.open()
    rig.session.togglePlay()
    await rig.pass(10)
    rig.say("stop")
    await rig.pass(2)
    rig.hear([("we", 0.9, 0.7), ("should", 0.7, 0.45), ("continue", 0.45, 0.05)])
    await rig.pass(1)
    #expect(rig.state == "recording")
    #expect(rig.logText.contains("not said on its own"))
  }

  @Test func cancelDiscardsTheComment() async {
    let rig = Rig(route: Rig.airPods)
    await rig.open()
    rig.session.togglePlay()
    await rig.pass(10)
    rig.say("stop")
    await rig.pass(2)
    rig.say("cancel")
    await rig.pass(0.8)
    #expect(rig.state == "playing")
    #expect(rig.book.comments.isEmpty)
    #expect(rig.book.discarded == 1)
  }

  // MARK: Headset buttons

  @Test func aDoubleReportedHeadsetPressStartsOneComment() async {
    let rig = Rig(route: Rig.airPods)
    await rig.open()
    rig.session.togglePlay()
    await rig.pass(10)
    rig.remote.press(.next)
    rig.clock.time += 0.05
    rig.remote.press(.next)
    await rig.pass(0.2)
    #expect(rig.state == "recording")
    await rig.pass(3)
    rig.remote.press(.playPause)
    await rig.pass(0.2)
    #expect(rig.state == "playing")
    #expect(rig.book.comments.count == 1)
  }

  @Test func takingAnAirPodOutDuringACommentKeepsRecording() async {
    let rig = Rig(route: Rig.airPods)
    await rig.open()
    rig.session.togglePlay()
    await rig.pass(10)
    rig.say("stop")
    await rig.pass(2)
    rig.remote.press(.pause)  // ear detection
    await rig.pass(3)
    rig.remote.press(.play)  // back in the ear
    await rig.pass(1)
    #expect(rig.state == "recording")
    #expect(rig.book.comments.isEmpty)
  }

  @Test func twoQuickTapsOnTheScreenAreTwoTaps() async {
    let rig = Rig()
    await rig.open()
    rig.session.togglePlay()
    rig.clock.time += 0.1
    rig.session.togglePlay()
    #expect(rig.state == "paused")
  }

  // MARK: The microphone is not rebuilt needlessly

  @Test func playingOnAirPodsNeverRebuildsTheMicrophone() async {
    let rig = Rig(route: Rig.airPods)
    await rig.open()
    #expect(rig.capture.captureRebuilds == 1)
    rig.session.togglePlay()
    await rig.pass(5)
    rig.session.togglePlay()
    await rig.pass(5)
    rig.session.togglePlay()
    await rig.pass(5)
    // the old app rebuilt it on the first Play, and a "stop" said in that second was lost
    #expect(rig.capture.captureRebuilds == 1)
    rig.say("stop")
    await rig.pass(0.5)
    #expect(rig.state == "recording")
    #expect(rig.capture.captureRebuilds == 1)
  }

  @Test func aBurstOfRouteChangesRebuildsOnce() async {
    let rig = Rig()
    await rig.open()
    rig.session.togglePlay()
    await rig.pass(2)
    let before = rig.capture.captureRebuilds
    // AirPods connect: the system reports the route four times in half a second
    let half = AudioRoute(outputs: [.bluetoothA2DP], input: .builtInMic, headsetMicrophoneAvailable: true)
    rig.capture.route = half
    rig.capture.onEvent?(.routeChanged(half, .deviceArrived))
    await rig.pass(0.2)
    for _ in 0..<3 {
      rig.capture.route = Rig.airPods
      rig.capture.onEvent?(.routeChanged(Rig.airPods, .other))
      await rig.pass(0.1)
    }
    await rig.pass(2)
    // once to let go of echo cancellation; nothing more for AirPods
    #expect(rig.capture.captureRebuilds == before + 1)
    #expect(rig.capture.applied.last?.plan.echoCancellation == false)
    #expect(rig.session.listenState == .listening)
  }

  @Test func aRouteChangeDuringACommentWaitsForTheComment() async {
    let rig = Rig()
    await rig.open()
    rig.session.togglePlay()
    await rig.pass(2)
    rig.say("stop")
    await rig.pass(1)
    let during = rig.capture.captureRebuilds
    rig.capture.route = Rig.airPods
    rig.capture.onEvent?(.routeChanged(Rig.airPods, .deviceArrived))
    await rig.pass(3)
    #expect(rig.capture.captureRebuilds == during)
    #expect(rig.capture.recording != nil)
    rig.say("continue")
    await rig.pass(1)
    #expect(rig.book.comments.count == 1)
    #expect(rig.capture.captureRebuilds == during + 1)
  }

  // MARK: The book on a loudspeaker

  @Test func theBooksOwnStopIsIgnored() async {
    let rig = Rig()
    rig.book.bookText = "the robot must stop early"
    await rig.open()
    rig.session.togglePlay()
    await rig.pass(10)
    rig.say("stop")
    await rig.pass(1)
    #expect(rig.state == "playing")
    #expect(rig.logText.contains("the book just said it"))
  }

  @Test func echoCancellationStartsWithTheFirstPlayOnTheSpeaker() async {
    let rig = Rig()
    await rig.open()
    #expect(rig.capture.applied.last?.plan.echoCancellation == false)
    #expect(rig.capture.applied.last?.plan.mixWithOthers == true)
    rig.session.togglePlay()
    await rig.pass(0.5)
    #expect(rig.capture.applied.last?.plan.echoCancellation == true)
    #expect(rig.capture.applied.last?.plan.mixWithOthers == false)
  }

  @Test func theScreensSeeWhetherEchoCancellationIsOn() async {
    let rig = Rig()
    await rig.open()
    #expect(rig.session.echoCancellation == false)
    rig.session.togglePlay()
    await rig.pass(0.5)
    #expect(rig.session.echoCancellation == true)
  }

  // MARK: Comments that end by themselves

  @Test func nothingSaidDiscardsAndLeavesTheBookPaused() async {
    let rig = Rig(route: Rig.airPods)
    await rig.open()
    rig.session.togglePlay()
    await rig.pass(10)
    rig.say("stop")
    await rig.pass(22)
    #expect(rig.state == "paused")
    #expect(rig.book.comments.isEmpty)
    #expect(!rig.player.isPlaying)
  }

  @Test func silenceEndsACommentOnlyAfterSpeechTheRecognizerHeard() async {
    var settings = SessionSettings()
    settings.endCommentOnSilence = true
    settings.silenceTimeout = 4
    let rig = Rig(route: Rig.airPods, settings: settings)
    await rig.open()
    rig.session.togglePlay()
    await rig.pass(10)
    rig.say("stop")
    await rig.pass(1)
    // the energy detector hears nothing on this headset: its silence only grows
    rig.capture.silence = 60
    for _ in 0..<5 {
      // a long sentence, word by word, three seconds in all
      rig.hear([("and", 0.5, 0.3), ("so", 0.3, 0.05)])
      await rig.pass(0.6)
    }
    #expect(rig.state == "recording", "speech the recognizer hears is not silence")
    await rig.pass(4.5)
    #expect(rig.state == "playing")
    #expect(rig.book.comments.count == 1)
  }

  @Test func anotherAppStartingEndsTheCommentAndTheBookStaysPaused() async {
    let rig = Rig(route: Rig.airPods)
    await rig.open()
    rig.session.togglePlay()
    await rig.pass(10)
    rig.say("stop")
    await rig.pass(2)
    rig.hear([("note", 0.5, 0.1)], final: true)
    await rig.pass(2)
    rig.capture.otherAudio = true
    await rig.pass(4)
    #expect(rig.state == "paused")
    #expect(rig.book.comments.count == 1)
  }

  // MARK: Listening only while the book is in use

  @Test func aPausedBookStopsListeningAndPlayListensAgain() async {
    var settings = SessionSettings()
    settings.stopListeningAfter = 1
    let rig = Rig(route: Rig.airPods, settings: settings)
    await rig.open()
    await rig.pass(61)
    #expect(rig.session.isListeningPaused)
    #expect(rig.capture.applied.last?.plan.capture == false)
    rig.session.togglePlay()
    await rig.pass(0.5)
    #expect(!rig.session.isListeningPaused)
    #expect(rig.session.listenState == .listening)
  }

  @Test func voiceCommandsOffStillRecordsByButton() async {
    var settings = SessionSettings()
    settings.voiceCommandsEnabled = false
    let rig = Rig(settings: settings)
    await rig.open()
    #expect(rig.capture.applied.last?.plan.capture == false)
    rig.session.togglePlay()
    await rig.pass(5)
    rig.session.commentButton()
    await rig.pass(3)
    #expect(rig.capture.recording != nil)
    rig.session.commentButton()
    await rig.pass(0.5)
    #expect(rig.book.comments.count == 1)
    // the microphone was only on for the comment
    #expect(rig.capture.applied.last?.plan.capture == false)
  }

  @Test func withoutPermissionTheBookStillPlays() async {
    let rig = Rig()
    rig.capture.microphonePermission = .denied
    await rig.open()
    #expect(rig.capture.applied.last?.plan.session == .playback)
    if case .unavailable = rig.session.listenState {} else { Issue.record("expected the missing permission to be shown") }
    rig.session.togglePlay()
    await rig.pass(1)
    #expect(rig.player.isPlaying)
  }

  // MARK: Repair

  @Test func aMicrophoneThatFailsToStartIsTriedAgain() async {
    let rig = Rig(route: Rig.airPods)
    rig.capture.failNextApply = "The microphone is in use."
    await rig.open()
    if case .unavailable = rig.session.listenState {} else { Issue.record("expected unavailable") }
    await rig.pass(16)
    #expect(rig.session.listenState == .listening)
  }

  @Test func aStalledMicrophoneIsRestarted() async {
    let rig = Rig(route: Rig.airPods)
    await rig.open()
    rig.session.togglePlay()
    await rig.pass(5)
    let before = rig.capture.captureRebuilds
    // the microphone stops delivering audio: the clock moves, microphone time does not
    for _ in 0..<60 {
      rig.clock.time += 0.1
      rig.session.tick()
      await rig.session.idle()
    }
    #expect(rig.capture.captureRebuilds == before + 1)
    #expect(rig.logText.contains("Microphone stalled"))
  }

  @Test func aResetWhileTheMicrophoneIsBeingSetUpStillRebuildsIt() async {
    // The system's audio services restart during the first setup: what was just
    // built is dead, although the plan has not changed.
    let rig = Rig(route: Rig.airPods)
    let player = rig.player
    rig.capture.duringNextApply = { player.onEvent?(.mediaServicesReset) }
    await rig.open()
    #expect(rig.capture.captureRebuilds == 2)
    #expect(rig.session.listenState == .listening)
  }

  @Test func inTheBackgroundAFailedMicrophoneWaitsForTheApp() async {
    // Android refuses to start the microphone from the background; retrying every
    // 15 s only fills the log. It is tried again when the app comes back.
    let rig = Rig(route: Rig.airPods)
    rig.capture.failsToApply = "The microphone can't start in the background."
    await rig.open()
    rig.session.isInBackground = true
    let attempts = rig.capture.applyAttempts
    await rig.pass(50)
    #expect(rig.capture.applyAttempts == attempts)
    rig.capture.failsToApply = nil
    rig.session.isInBackground = false
    await rig.pass(0.5)
    #expect(rig.session.listenState == .listening)
  }

  @Test func aBookThatFailsToOpenReleasesTheAudioOfTheOneBefore() async {
    let rig = Rig(route: Rig.airPods)
    await rig.open()
    #expect(rig.capture.applied.last?.plan.capture == true)
    rig.player.failsToLoad = true
    await rig.session.open(FakeBook())
    await rig.session.idle()
    #expect(rig.session.book == nil)
    #expect(rig.session.errorMessage != nil)
    // no book is open: the microphone must not stay on
    #expect(rig.capture.applied.last?.plan.session == .inactive)
  }

  // MARK: Bookkeeping

  @Test func theTraceReplaysToTheSameDecisions() async {
    let rig = Rig(route: Rig.airPods)
    await rig.open()
    rig.session.togglePlay()
    await rig.pass(10)
    rig.say("stop")
    await rig.pass(2)
    rig.hear([("we", 0.9, 0.7), ("should", 0.7, 0.45), ("continue", 0.45, 0.05)])
    await rig.pass(2)
    rig.say("continue")
    await rig.pass(1)

    let recorded = rig.session.trace.events.compactMap { event -> String? in
      if case .outcome(let o) = event.kind { o } else { nil }
    }
    #expect(recorded == ["fire stop “stop”", "reject notIsolated “continue”", "fire resume “continue”"])
    // what a tester exports from Settings is enough to reproduce it
    let exported = TraceRecorder.parse(jsonLines: rig.session.trace.jsonLines())
    #expect(exported.count == rig.session.trace.events.count)
    #expect(TraceReplay.detectorOutcomes(exported) == recorded)
  }

  @Test func aPassageEndWithoutItsBookmarkIsNotConfirmed() async {
    let rig = Rig(route: Rig.airPods)
    await rig.open()
    rig.session.togglePlay()
    await rig.pass(10)
    rig.session.addBookmark()
    #expect(rig.session.flash?.kind == .bookmark)
    // the bookmark is deleted in the comments list before the passage is closed
    rig.book.bookmarks.removeAll()
    await rig.pass(20)
    rig.session.addBookmark()
    #expect(rig.session.flash?.kind == .bookmark, "no passage was made, so none is confirmed")
    #expect(rig.logText.contains("No bookmark to extend"))
    #expect(rig.session.errorMessage == nil)
  }

  @Test func aLibraryThatCannotBeWrittenIsReportedOnce() async {
    let rig = Rig(route: Rig.airPods)
    await rig.open()
    rig.book.canSave = false
    rig.session.addBookmark()
    #expect(rig.session.errorMessage?.contains("could not save") == true)
  }

  @Test func closingDuringACommentSavesIt() async {
    let rig = Rig(route: Rig.airPods)
    await rig.open()
    rig.session.togglePlay()
    await rig.pass(10)
    rig.say("stop")
    await rig.pass(3)
    rig.session.close()
    await rig.session.idle()
    #expect(rig.book.comments.count == 1)
    #expect(rig.capture.applied.last?.plan.session == .inactive)
  }

  // MARK: What the app adds for its screens

  @Test func tryAgainRestartsAMicrophoneThatFailed() async {
    let rig = Rig()
    rig.capture.failNextApply = "The microphone is in use."
    await rig.open()
    if case .unavailable = rig.session.listenState {} else { Issue.record("expected unavailable") }
    // no waiting for the automatic attempt
    rig.session.retryListening()
    await rig.session.idle()
    #expect(rig.session.listenState == .listening)
    #expect(rig.capture.isRunning)
  }

  @Test func tryAgainListensAgainAfterListeningWasPaused() async {
    let rig = Rig()
    await rig.open()
    rig.session.pauseListening("test")
    await rig.session.idle()
    #expect(rig.session.isListeningPaused)
    rig.session.retryListening()
    await rig.session.idle()
    #expect(!rig.session.isListeningPaused)
    #expect(rig.session.listenState == .listening)
  }

  @Test func tryAgainDoesNothingWithoutPermissionOrWhileListening() async {
    let rig = Rig()
    await rig.open()
    let before = rig.capture.captureRebuilds
    rig.session.retryListening()
    await rig.session.idle()
    #expect(rig.capture.captureRebuilds == before)

    let denied = Rig()
    denied.capture.microphonePermission = .denied
    await denied.open()
    let applied = denied.capture.captureRebuilds
    denied.session.retryListening()
    await denied.session.idle()
    #expect(denied.capture.captureRebuilds == applied)
    if case .unavailable = denied.session.listenState {} else { Issue.record("expected the missing permission to stay shown") }
  }

  @Test func theAppCanAddLinesToTheCommandLog() async {
    let rig = Rig()
    await rig.open()
    rig.session.note("Transcribed 3 comments again", accepted: true)
    #expect(rig.session.log.first?.text == "Transcribed 3 comments again")
    #expect(rig.session.trace.events.last?.kind == .note("Transcribed 3 comments again"))
  }
}
