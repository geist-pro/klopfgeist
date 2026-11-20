import Foundation
import MarginVoiceCore

/// One run of a recognizer result's attributed text, with the audio time the
/// recognizer gave it (seconds on the recognizer's own timeline).
public struct RecognizerRun: Sendable, Equatable {
  public var text: String
  public var start: Double?
  public var end: Double?

  public init(_ text: String, start: Double? = nil, end: Double? = nil) {
    self.text = text
    self.start = start
    self.end = end
  }

  var isTimed: Bool {
    guard let start, let end else { return false }
    return start.isFinite && end.isFinite && end >= start
  }
}

/// Turns a recognizer result into words on the microphone timeline.
///
/// `CommandDetector` reads the pause before a command from the gap between words,
/// so each word needs its own time: a result timed only as a whole ("so I think
/// continue", 0–2 s) puts "continue" right after "think" however long the speaker
/// paused. The recognizer times each run of its text; a run is usually one word.
public enum RecognizerWords {
  /// - Parameters:
  ///   - runs: the result's text runs, in order.
  ///   - text, start, end: the whole result (recognizer timeline), used when no run is timed.
  ///   - origin: microphone time of the recognizer's time zero.
  public static func hypothesis(
    runs: [RecognizerRun], text: String, start: Double, end: Double, origin: Double, isFinal: Bool
  ) -> Hypothesis {
    let spoken = runs.map { (words: $0.text.split(whereSeparator: \.isWhitespace).map(String.init), run: $0) }.filter { !$0.words.isEmpty }
    guard spoken.contains(where: { $0.run.isTimed }) else {
      // no per-run times (attribute missing): the words spread evenly over the result
      var h = Hypothesis(text: text, start: origin + start, end: origin + end, isFinal: isFinal)
      h.timesApproximate = !isFinal
      return h
    }
    var words: [SpokenWord] = []
    for (i, item) in spoken.enumerated() {
      var from: Double
      var to: Double
      if item.run.isTimed, let s = item.run.start, let e = item.run.end {
        (from, to) = (s, e)
      } else {
        // a run without a time between timed ones: it was said in the gap between them
        from = spoken[..<i].last(where: { $0.run.isTimed })?.run.end ?? start
        to = spoken[(i + 1)...].first(where: { $0.run.isTimed })?.run.start ?? end
        to = max(from, to)
      }
      // a run of several words: spread them evenly over it
      let step = (to - from) / Double(item.words.count)
      for (k, word) in item.words.enumerated() {
        words.append(SpokenWord(word, start: origin + from + Double(k) * step, end: origin + from + Double(k + 1) * step))
      }
    }
    // a volatile result is timed over the whole run since the last final one (field trace,
    // 2026-11-15: "Continue" from 0.3 to 8.09 s); only final results time each word
    return Hypothesis(words: words, isFinal: isFinal, timesApproximate: !isFinal)
  }
}
