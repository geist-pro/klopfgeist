import Foundation
import MarginVoiceCore
import Testing

@testable import MarginVoiceApple

@Suite struct TraceFileTests {
  private func directory() -> URL {
    FileManager.default.temporaryDirectory.appendingPathComponent("traces-\(UUID().uuidString)", isDirectory: true)
  }

  private func note(_ i: Int) -> TraceEvent { TraceEvent(t: Double(i), kind: .note("event \(i)")) }

  @Test func appendedEventsCanBeReadBackAsATrace() throws {
    let dir = directory()
    defer { try? FileManager.default.removeItem(at: dir) }
    let file = TraceFile(directory: dir)
    file.append([note(1), note(2)])
    file.append([TraceEvent(t: 3, kind: .hypothesis(Hypothesis(words: [SpokenWord("stop", start: 2.5, end: 2.8)], isFinal: true), mic: 3))])
    file.flush()
    let text = try String(contentsOf: file.current, encoding: .utf8)
    let events = TraceRecorder.parse(jsonLines: text)
    #expect(events.map(\.t) == [1, 2, 3])
    #expect(file.current.lastPathComponent == "current.jsonl")
  }

  @Test func aFullFileBecomesThePreviousOne() throws {
    let dir = directory()
    defer { try? FileManager.default.removeItem(at: dir) }
    let file = TraceFile(directory: dir, maximumBytes: 400)
    for i in 0..<40 { file.append([note(i)]) }
    file.flush()
    let current = try Data(contentsOf: file.current)
    let previous = try Data(contentsOf: file.previous)
    // bounded: never more than two files of about the limit
    #expect(current.count <= 400 + 100)
    #expect(previous.count <= 400 + 100)
    // nothing lost at the seam, newest last
    let kept = TraceRecorder.parse(jsonLines: String(decoding: previous, as: UTF8.self)) + TraceRecorder.parse(jsonLines: String(decoding: current, as: UTF8.self))
    #expect(kept.last?.t == 39)
    #expect(kept.map(\.t) == kept.map(\.t).sorted())
    #expect(zip(kept, kept.dropFirst()).allSatisfy { $1.t - $0.t == 1 })
  }

  @Test func anotherLaunchAppendsToTheSameFile() throws {
    let dir = directory()
    defer { try? FileManager.default.removeItem(at: dir) }
    let first = TraceFile(directory: dir)
    first.append([note(1)])
    first.flush()
    let second = TraceFile(directory: dir)
    second.append([note(2)])
    second.flush()
    let events = TraceRecorder.parse(jsonLines: try String(contentsOf: second.current, encoding: .utf8))
    #expect(events.map(\.t) == [1, 2])
  }
}
