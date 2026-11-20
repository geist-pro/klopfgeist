import Testing
@testable import MarginVoiceCore

struct VoiceCommandTests {
  @Test func parsesShortCommands() {
    #expect(VoiceCommand.parse("Stop.", languages: [.english]) == .stop)
    #expect(VoiceCommand.parse("continue please", languages: [.english]) == .resume)
    #expect(VoiceCommand.parse("Zurück!", languages: [.german]) == .back)
    #expect(VoiceCommand.parse("Weiter", languages: [.english]) == nil)
    #expect(VoiceCommand.parse("stop the motion before", languages: [.english]) == nil)
  }

  @Test func bookEchoNeedsEveryWordToHaveBeenNarrated() {
    #expect(CommandDetector.isBookEcho(utterance: "stop", recentBookText: "the robot must stop early"))
    #expect(!CommandDetector.isBookEcho(utterance: "stop", recentBookText: "endpoint prediction"))
    #expect(!CommandDetector.isBookEcho(utterance: "go back", recentBookText: "we go on"))
  }
}

struct ReviewMachineTests {
  @Test func stopRecordContinue() {
    var m = ReviewMachine()
    m.setPlaying(true)
    #expect(m.handle(.command(.stop), time: 100, rate: 1.3) == [.pause, .beep(.recordStart), .startRecording(anchor: 98.5)])
    #expect(m.state == .recording(anchor: 98.5))
    #expect(m.handle(.command(.faster), time: 100, rate: 1.3).isEmpty)
    #expect(m.handle(.command(.resume), time: 100, rate: 1.3) == [.finishRecording(save: true), .beep(.recordSaved), .seekToSentence(near: 98), .play])
    #expect(m.state == .playing)
  }

  @Test func cancelDiscards() {
    var m = ReviewMachine()
    m.setPlaying(true)
    _ = m.handle(.remote(.next), time: 10, rate: 1)
    #expect(m.handle(.command(.cancel), time: 10, rate: 1).first == .finishRecording(save: false))
  }

  @Test func markWhileRecordingBecomesBookmark() {
    var m = ReviewMachine()
    m.setPlaying(true)
    _ = m.handle(.command(.stop), time: 10, rate: 1)
    let effects = m.handle(.command(.mark), time: 12, rate: 1)
    #expect(effects.first == .finishRecording(save: false))
    #expect(effects.dropFirst().first == .addBookmark(at: 10 - ReviewMachine.Settings().reactionOffset))
    #expect(effects.last == .play)
  }

  @Test func silenceEndsComment() {
    var m = ReviewMachine()
    m.setPlaying(true)
    _ = m.handle(.command(.stop), time: 10, rate: 1)
    #expect(m.handle(.silenceTimeout, time: 10, rate: 1).first == .finishRecording(save: true))
  }

  @Test func abandonedCommentLeavesTheBookPaused() {
    for save in [true, false] {
      var m = ReviewMachine()
      m.setPlaying(true)
      _ = m.handle(.command(.stop), time: 100, rate: 1)
      let effects = m.handle(.commentAbandoned(save: save), time: 100, rate: 1)
      #expect(effects == [.finishRecording(save: save), .beep(save ? .recordSaved : .recordDiscarded), .seekToSentence(near: 98)])
      #expect(m.state == .paused)
    }
  }

  @Test func abandoningWithoutACommentDoesNothing() {
    var m = ReviewMachine()
    m.setPlaying(true)
    #expect(m.handle(.commentAbandoned(save: true), time: 100, rate: 1).isEmpty)
    #expect(m.state == .playing)
  }

  @Test func repeatedBackDoublesStep() {
    var m = ReviewMachine()
    m.setPlaying(true)
    #expect(m.handle(.command(.back), time: 100, rate: 1, now: 0) == [.seek(to: 85)])
    #expect(m.handle(.command(.back), time: 85, rate: 1, now: 3) == [.seek(to: 55)])
    #expect(m.handle(.command(.back), time: 55, rate: 1, now: 30) == [.seek(to: 40)])
  }

  @Test func rateClamps() {
    var m = ReviewMachine()
    m.setPlaying(true)
    #expect(m.handle(.command(.faster), time: 0, rate: 1.3) == [.setRate(1.4), .announce("1.4")])
    #expect(m.handle(.command(.faster), time: 0, rate: 2.5).isEmpty)
  }

  @Test func secondBookmarkEndsPassage() {
    var m = ReviewMachine()
    m.setPlaying(true)
    #expect(m.handle(.command(.mark), time: 100, rate: 1, now: 0) == [.addBookmark(at: 98.5), .beep(.bookmark)])
    #expect(m.isBookmarkOpen(now: 30))
    #expect(m.handle(.command(.mark), time: 140, rate: 1, now: 40) == [.closeBookmark(at: 138.5), .beep(.bookmarkEnd)])
    // after the window a new bookmark starts instead
    _ = m.handle(.command(.mark), time: 200, rate: 1, now: 100)
    #expect(m.handle(.command(.mark), time: 500, rate: 1, now: 400) == [.addBookmark(at: 498.5), .beep(.bookmark)])
    // recording a comment closes the window
    _ = m.handle(.command(.stop), time: 510, rate: 1, now: 410)
    #expect(!m.isBookmarkOpen(now: 411))
  }

  @Test func resetForgetsTheOpenBookmark() {
    var m = ReviewMachine()
    m.setPlaying(true)
    _ = m.handle(.command(.mark), time: 100, rate: 1, now: 0)
    m.reset()
    #expect(m.state == .paused && !m.isBookmarkOpen(now: 1))
    // the next book's first bookmark is a new one, not the end of a passage
    #expect(m.handle(.command(.mark), time: 300, rate: 1, now: 10) == [.addBookmark(at: 298.5), .beep(.bookmark)])
  }
}

struct TrailingCommandTests {
  @Test func stripsClosingCommand() {
    #expect(VoiceCommand.strippingTrailingCommand("This needs a citation. Continue.", languages: [.english]) == "This needs a citation.")
    #expect(VoiceCommand.strippingTrailingCommand("Hier fehlt ein Beispiel, weiter", languages: [.german]) == "Hier fehlt ein Beispiel")
    #expect(VoiceCommand.strippingTrailingCommand("We should continue the analysis", languages: [.english]) == "We should continue the analysis")
  }
}

struct OtherAudioWatchTests {
  /// Each look in order, with what it should answer.
  private func check(_ w: inout OtherAudioWatch, _ looks: [(active: Bool, now: Double, playing: Bool)]) {
    for look in looks {
      let playing = w.update(active: look.active, now: look.now)
      #expect(playing == look.playing, "at \(look.now)")
    }
  }

  @Test func countsOnlyAudioThatLasts() {
    var w = OtherAudioWatch()
    check(&w, [(true, 10, false), (true, 11.9, false), (true, 12, true), (false, 13, false), (true, 14, false)])
  }

  @Test func ignoresOurOwnSounds() {
    var w = OtherAudioWatch()
    w.ownSound(now: 10)
    check(&w, [(true, 11, false), (true, 11.4, false), (true, 11.5, false), (true, 13.4, false), (true, 13.5, true)])
  }

  @Test func aShortSoundDoesNotCount() {
    var w = OtherAudioWatch()
    check(&w, [(true, 10, false), (false, 11, false), (true, 12.5, false)])
  }
}
