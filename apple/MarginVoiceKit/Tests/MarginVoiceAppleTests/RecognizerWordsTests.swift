import MarginVoiceCore
import Testing

@testable import MarginVoiceApple

@Suite struct RecognizerWordsTests {
  private func words(_ h: Hypothesis) -> [String] { h.words.map(\.text) }
  private func close(_ a: [Double], _ b: [Double]) -> Bool { a.count == b.count && zip(a, b).allSatisfy { abs($0 - $1) < 1e-9 } }

  @Test func oneWordPerRunOnTheMicrophoneTimeline() {
    let runs = [RecognizerRun("so", start: 0.2, end: 0.4), RecognizerRun(" ", start: nil, end: nil), RecognizerRun("stop", start: 1.5, end: 1.9)]
    let h = RecognizerWords.hypothesis(runs: runs, text: "so stop", start: 0.2, end: 1.9, origin: 10, isFinal: false)
    #expect(words(h) == ["so", "stop"])
    #expect(close(h.words.flatMap { [$0.start, $0.end] }, [10.2, 10.4, 11.5, 11.9]))
    #expect(!h.isFinal)
  }

  @Test func thePauseBeforeACommandSurvives() {
    // "I think" … 1 s pause … "continue": timed as a whole the pause would vanish
    let runs = [
      RecognizerRun("I ", start: 0, end: 0.2), RecognizerRun("think ", start: 0.2, end: 0.6), RecognizerRun("continue", start: 1.6, end: 2.1),
    ]
    let h = RecognizerWords.hypothesis(runs: runs, text: "I think continue", start: 0, end: 2.1, origin: 0, isFinal: true)
    #expect(h.words.count == 3)
    #expect(abs(h.words[2].start - h.words[1].end - 1.0) < 1e-9)
    #expect(h.isFinal)
  }

  @Test func aRunOfSeveralWordsIsSplitEvenly() {
    let h = RecognizerWords.hypothesis(
      runs: [RecognizerRun("go on now", start: 3, end: 4.5)], text: "go on now", start: 3, end: 4.5, origin: 1, isFinal: false)
    #expect(words(h) == ["go", "on", "now"])
    #expect(h.words.map(\.start) == [4, 4.5, 5])
    #expect(h.words.map(\.end) == [4.5, 5, 5.5])
  }

  @Test func withoutRunTimesTheResultRangeIsUsed() {
    let h = RecognizerWords.hypothesis(runs: [RecognizerRun("mark this")], text: "mark this", start: 2, end: 3, origin: 5, isFinal: true)
    #expect(h == Hypothesis(text: "mark this", start: 7, end: 8, isFinal: true))
  }

  @Test func invalidTimesCountAsMissing() {
    let h = RecognizerWords.hypothesis(
      runs: [RecognizerRun("stop", start: .nan, end: .nan)], text: "stop", start: 1, end: 1.4, origin: 0, isFinal: false)
    #expect(h.words == [SpokenWord("stop", start: 1, end: 1.4)])
  }

  @Test func anUntimedRunFillsTheGapBetweenItsNeighbours() {
    let runs = [RecognizerRun("well", start: 0, end: 0.3), RecognizerRun("uh"), RecognizerRun("stop", start: 1.0, end: 1.4)]
    let h = RecognizerWords.hypothesis(runs: runs, text: "well uh stop", start: 0, end: 1.4, origin: 0, isFinal: false)
    #expect(h.words[1] == SpokenWord("uh", start: 0.3, end: 1.0))
    // first and last untimed runs lean on the result's own range
    let edge = RecognizerWords.hypothesis(
      runs: [RecognizerRun("oh"), RecognizerRun("stop", start: 1.0, end: 1.4), RecognizerRun("now")], text: "oh stop now", start: 0.5, end: 2,
      origin: 0, isFinal: false)
    #expect(edge.words.first == SpokenWord("oh", start: 0.5, end: 1.0))
    #expect(edge.words.last == SpokenWord("now", start: 1.4, end: 2))
  }

  @Test func whitespaceAndEmptyRunsCarryNoWords() {
    let h = RecognizerWords.hypothesis(
      runs: [RecognizerRun("  ", start: 0, end: 1), RecognizerRun("", start: 1, end: 2)], text: "", start: 0, end: 2, origin: 0, isFinal: false)
    #expect(h.words.isEmpty)
  }

  /// The detector fires on a trailing command from these words, which it could not from a whole-result time.
  @Test func detectorFiresOnATrailingCommandAfterSpeech() {
    var detector = CommandDetector()
    let runs = [
      RecognizerRun("that ", start: 0, end: 0.3), RecognizerRun("is ", start: 0.3, end: 0.5), RecognizerRun("unclear ", start: 0.5, end: 1.0),
      RecognizerRun("continue", start: 1.8, end: 2.3),
    ]
    let h = RecognizerWords.hypothesis(runs: runs, text: "that is unclear continue", start: 0, end: 2.3, origin: 0, isFinal: false)
    let context = CommandDetector.Context(policy: .recording, allowed: CommandDetector.Context.recordingCommands)
    var outcomes = detector.hypothesis(h, now: 2.4, context: context)
    var t = 2.4
    while t < 4 {
      t += 0.1
      outcomes += detector.tick(now: t, context: context)
    }
    guard case .fire(let c)? = outcomes.first else {
      Issue.record("expected a command, got \(outcomes)")
      return
    }
    #expect(c.command == .resume)
  }
}
