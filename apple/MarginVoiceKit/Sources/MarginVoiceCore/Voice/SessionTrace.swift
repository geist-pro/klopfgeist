import Foundation

/// The flight recorder: everything that went into the review session and what it
/// decided, in order. When "stop" was not heard on some headset, the trace from that
/// moment can be exported, read, and replayed through the same code in a test.
public struct TraceEvent: Sendable, Equatable, Codable {
  public enum Kind: Sendable, Equatable, Codable {
    /// A recognizer result, at microphone time `mic`.
    case hypothesis(Hypothesis, mic: Double)
    /// The energy detector started or stopped hearing speech.
    case speech(active: Bool, mic: Double)
    /// What the detector listens for changed: listening | listeningOverSpeaker | recording.
    case mode(String)
    /// The microphone restarted; its timeline begins at zero again.
    case microphoneRestarted
    case remote(ReviewMachine.RemoteButton)
    /// A decision of the command detector ("fire stop", "reject notIsolated continue").
    case outcome(String)
    /// An event the state machine handled, the state afterwards and what it asked for.
    case machine(event: String, state: String, effects: [String])
    case route(AudioRoute, reason: String)
    case plan(AudioPlan, change: String)
    /// A line of the command log.
    case note(String)
  }

  /// Seconds on the session clock.
  public var t: Double
  public var kind: Kind

  public init(t: Double, kind: Kind) {
    self.t = t
    self.kind = kind
  }
}

/// Keeps the last events (a few minutes of use) and writes them as JSON lines.
public struct TraceRecorder: Sendable {
  public private(set) var events: [TraceEvent] = []
  public var capacity: Int
  /// Events recorded since the start, including those that have left the ring.
  public private(set) var recorded = 0

  public init(capacity: Int = 4000) {
    self.capacity = capacity
  }

  public mutating func record(_ kind: TraceEvent.Kind, at t: Double) {
    events.append(TraceEvent(t: t, kind: kind))
    recorded += 1
    if events.count > capacity { events.removeFirst(events.count - capacity) }
  }

  public mutating func clear() {
    events.removeAll()
  }

  /// The events recorded after the first `count` (a previous `recorded`), as far
  /// as they are still kept: what is new since someone last looked.
  public func events(after count: Int) -> ArraySlice<TraceEvent> {
    events.suffix(min(events.count, max(0, recorded - count)))
  }

  /// One JSON object per line, oldest first.
  public func jsonLines() -> String {
    events.compactMap(Self.jsonLine).joined(separator: "\n")
  }

  /// One event as a line of `jsonLines()`.
  public static func jsonLine(_ event: TraceEvent) -> String? {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys]
    return (try? encoder.encode(event)).flatMap { String(data: $0, encoding: .utf8) }
  }

  public static func parse(jsonLines text: String) -> [TraceEvent] {
    let decoder = JSONDecoder()
    return text.split(separator: "\n").compactMap { try? decoder.decode(TraceEvent.self, from: Data($0.utf8)) }
  }
}

/// Runs the recognizer results of a trace through a fresh `CommandDetector`.
public enum TraceReplay {
  /// The detector's decisions, as the session would describe them in `.outcome`.
  public static func detectorOutcomes(_ events: [TraceEvent], tickEvery step: Double = 0.1) -> [String] {
    var detector = CommandDetector()
    var mode = "listening"
    var mic = 0.0
    var result: [String] = []

    func context() -> CommandDetector.Context {
      switch mode {
      case "recording": .init(policy: .recording, allowed: CommandDetector.Context.recordingCommands)
      case "listeningOverSpeaker": .init(policy: .listeningOverSpeaker)
      default: .init(policy: .listening)
      }
    }
    func advance(to time: Double) {
      // the session ticks a few times a second between results
      while mic + step < time {
        mic += step
        result += detector.tick(now: mic, context: context()).map(describe)
      }
      mic = max(mic, time)
    }

    // Events carry session time; results and speech also carry microphone time.
    // The difference places every other event on the microphone timeline.
    var offset: Double?
    for event in events {
      if let offset { advance(to: event.t + offset) }
      switch event.kind {
      case .hypothesis(let h, let time):
        advance(to: time)
        offset = time - event.t
        result += detector.hypothesis(h, now: time, context: context()).map(describe)
      case .speech(let active, let time):
        advance(to: time)
        offset = time - event.t
        detector.speech(active: active)
      case .mode(let m):
        mode = m
        if m == "recording" { detector.forget(before: mic) }
      case .microphoneRestarted:
        detector.reset()
        mic = 0
        offset = -event.t
      default:
        break
      }
    }
    advance(to: mic + 2)
    return result
  }

  public static func describe(_ outcome: CommandDetector.Outcome) -> String {
    switch outcome {
    case .fire(let c): "fire \(c.command.rawValue) “\(c.text)”"
    case .rejected(let text, let why): "reject \(why.rawValue) “\(text)”"
    }
  }
}
