import Foundation
import Testing
@testable import MarginVoiceCore

/// Runs `spec/vectors/command-detector.json`; the Kotlin core runs the same file.
struct CommandDetectorTests {
  struct File: Decodable {
    let cases: [Case]
  }

  struct Case: Decodable, CustomTestStringConvertible, Sendable {
    let name: String
    let context: ContextSpec
    let steps: [Step]
    var testDescription: String { name }
  }

  struct ContextSpec: Decodable, Sendable {
    let policy: String
    let allowed: String?
    let bookText: String?

    var context: CommandDetector.Context {
      let policy: CommandDetector.Policy =
        switch self.policy {
        case "recording": .recording
        case "listeningOverSpeaker": .listeningOverSpeaker
        default: .listening
        }
      return .init(
        policy: policy,
        allowed: allowed == "recording" ? CommandDetector.Context.recordingCommands : Set(VoiceCommand.allCases),
        recentBookText: bookText)
    }
  }

  struct Word: Decodable, Sendable {
    let word: SpokenWord
    init(from decoder: any Decoder) throws {
      var c = try decoder.unkeyedContainer()
      word = SpokenWord(try c.decode(String.self), start: try c.decode(Double.self), end: try c.decode(Double.self))
    }
  }

  struct Timed: Decodable, Sendable {
    let text: String
    let start: Double
    let end: Double
  }

  struct Expected: Decodable, Sendable {
    let fire: String?
    let start: Double?
    let reject: String?
    let text: String
  }

  struct Step: Decodable, Sendable {
    let at: Double
    let words: [Word]?
    let final: Bool?
    let approximate: Bool?
    let text: Timed?
    let speech: Bool?
    let tick: Bool?
    let forget: Double?
    let reset: Bool?
    let expect: [Expected]?
  }

  static let cases = (try? SpecVectors.load("command-detector", as: File.self).cases) ?? []

  @Test func theVectorFileLoads() throws {
    #expect(try SpecVectors.load("command-detector", as: File.self).cases.count >= 20)
  }

  @Test(arguments: cases)
  func conformsTo(_ c: Case) {
    var detector = CommandDetector()
    let context = c.context.context
    for step in c.steps {
      var outcomes: [CommandDetector.Outcome] = []
      if let words = step.words {
        outcomes = detector.hypothesis(
          .init(words: words.map(\.word), isFinal: step.final ?? false, timesApproximate: step.approximate ?? false), now: step.at,
          context: context)
      } else if let t = step.text {
        outcomes = detector.hypothesis(.init(text: t.text, start: t.start, end: t.end, isFinal: step.final ?? false), now: step.at, context: context)
      } else if let active = step.speech {
        detector.speech(active: active, at: step.at)
      } else if let time = step.forget {
        detector.forget(before: time)
      } else if step.reset == true {
        detector.reset()
      } else {
        outcomes = detector.tick(now: step.at, context: context)
      }
      #expect(Self.describe(outcomes) == Self.describe(step.expect ?? []), "\(c.name), at \(step.at)")
    }
  }

  static func describe(_ outcomes: [CommandDetector.Outcome]) -> [String] {
    outcomes.map {
      switch $0 {
      case .fire(let c): "fire \(c.command.rawValue) “\(c.text)” @\(String(format: "%.2f", c.start))"
      case .rejected(let text, let why): "reject \(why.rawValue) “\(text)”"
      }
    }
  }

  static func describe(_ expected: [Expected]) -> [String] {
    expected.map {
      if let fire = $0.fire { "fire \(fire) “\($0.text)” @\(String(format: "%.2f", $0.start ?? -1))" } else { "reject \($0.reject ?? "?") “\($0.text)”" }
    }
  }
}
