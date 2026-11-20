import Foundation
import Testing
@testable import MarginVoiceCore

/// Runs `spec/vectors/review-machine.json`; the Kotlin core runs the same file.
struct ReviewMachineVectorTests {
  struct File: Decodable { let cases: [Case] }

  struct Case: Decodable, Sendable, CustomTestStringConvertible {
    let name: String
    let steps: [Step]
    var testDescription: String { name }
  }

  struct Step: Decodable, Sendable {
    let `do`: String
    let time: Double
    let rate: Double
    let now: Double
    let effects: [String]?
    let state: String?
  }

  static let cases = (try? SpecVectors.load("review-machine", as: File.self).cases) ?? []

  @Test func theVectorFileLoads() throws {
    #expect(try SpecVectors.load("review-machine", as: File.self).cases.count >= 12)
  }

  @Test(arguments: cases)
  func conformsTo(_ c: Case) throws {
    var machine = ReviewMachine()
    for (i, step) in c.steps.enumerated() {
      let parts = step.do.split(separator: ":").map(String.init)
      let argument = parts.count > 1 ? parts[1] : ""
      var effects: [ReviewMachine.Effect] = []
      switch parts[0] {
      case "setPlaying": machine.setPlaying(argument == "true")
      case "command": effects = machine.handle(.command(try #require(VoiceCommand(rawValue: argument))), time: step.time, rate: step.rate, now: step.now)
      case "remote": effects = machine.handle(.remote(try #require(ReviewMachine.RemoteButton(rawValue: argument))), time: step.time, rate: step.rate, now: step.now)
      case "silenceTimeout": effects = machine.handle(.silenceTimeout, time: step.time, rate: step.rate, now: step.now)
      case "commentAbandoned": effects = machine.handle(.commentAbandoned(save: argument == "true"), time: step.time, rate: step.rate, now: step.now)
      case "commentButton": effects = machine.handle(.commentButton, time: step.time, rate: step.rate, now: step.now)
      default: Issue.record("unknown step \(step.do)")
      }
      if let expected = step.effects {
        #expect(effects.map(Self.describe) == expected, "\(c.name), step \(i + 1) (\(step.do))")
      }
      if let state = step.state {
        #expect(Self.describe(machine.state) == state, "\(c.name), step \(i + 1) (\(step.do))")
      }
    }
  }

  static func number(_ x: Double) -> String {
    x == x.rounded() ? String(Int(x)) : String(x)
  }

  static func describe(_ state: ReviewMachine.State) -> String {
    switch state {
    case .paused: "paused"
    case .playing: "playing"
    case .recording: "recording"
    }
  }

  static func describe(_ effect: ReviewMachine.Effect) -> String {
    switch effect {
    case .play: "play"
    case .pause: "pause"
    case .seek(let t): "seek:\(number(t))"
    case .seekToSentence(let t): "seekToSentence:\(number(t))"
    case .beep(let e): "beep:\(e)"
    case .startRecording(let a): "startRecording:\(number(a))"
    case .finishRecording(let save): "finishRecording:\(save)"
    case .addBookmark(let t): "addBookmark:\(number(t))"
    case .closeBookmark(let t): "closeBookmark:\(number(t))"
    case .setRate(let r): "setRate:\(number(r))"
    case .restartSentence: "restartSentence"
    case .announce(let s): "announce:\(s)"
    }
  }
}
