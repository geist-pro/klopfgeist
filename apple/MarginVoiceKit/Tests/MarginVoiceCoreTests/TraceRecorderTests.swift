import Foundation
import Testing

@testable import MarginVoiceCore

/// The recorder hands out what is new since a caller last looked, so the app can
/// write the trace to a file as it grows (no export needed after a field failure).
struct TraceRecorderTests {
  @Test func newEventsAreThoseAfterTheLastLook() {
    var trace = TraceRecorder()
    trace.record(.note("a"), at: 1)
    trace.record(.note("b"), at: 2)
    var seen = 0
    #expect(trace.events(after: seen).map(\.t) == [1, 2])
    seen = trace.recorded
    #expect(trace.events(after: seen).isEmpty)
    trace.record(.note("c"), at: 3)
    #expect(trace.events(after: seen).map(\.t) == [3])
  }

  @Test func eventsDroppedFromTheRingAreNotHandedOut() {
    var trace = TraceRecorder(capacity: 3)
    for i in 0..<5 { trace.record(.note("\(i)"), at: Double(i)) }
    #expect(trace.recorded == 5)
    // the caller looked after the first event; only the three still kept remain
    #expect(trace.events(after: 1).map(\.t) == [2, 3, 4])
    #expect(trace.events(after: 4).map(\.t) == [4])
  }

  @Test func clearingDoesNotRepeatOrLoseNewEvents() {
    var trace = TraceRecorder()
    trace.record(.note("a"), at: 1)
    let seen = trace.recorded
    trace.clear()
    #expect(trace.events(after: seen).isEmpty)
    trace.record(.note("b"), at: 2)
    #expect(trace.events(after: seen).map(\.t) == [2])
  }

  @Test func aLineIsWhatTheExportWrites() {
    var trace = TraceRecorder()
    trace.record(.mode("listening"), at: 1)
    trace.record(.hypothesis(Hypothesis(words: [SpokenWord("stop", start: 1, end: 1.3)], isFinal: false), mic: 1.4), at: 1.5)
    #expect(trace.events.compactMap(TraceRecorder.jsonLine).joined(separator: "\n") == trace.jsonLines())
    #expect(TraceRecorder.parse(jsonLines: trace.jsonLines()) == trace.events)
  }
}
