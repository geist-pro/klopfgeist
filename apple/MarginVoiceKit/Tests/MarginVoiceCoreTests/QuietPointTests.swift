import Foundation
import Testing
@testable import MarginVoiceCore

/// Runs `spec/vectors/quiet-point.json`; the Kotlin core runs the same file.
struct QuietPointVectorTests {
  struct File: Decodable { let cases: [Case] }
  struct Run: Decodable, Sendable { let level: Double; let seconds: Double }

  struct Case: Decodable, Sendable, CustomTestStringConvertible {
    let name: String
    let start: Double
    let target: Double
    let runs: [Run]
    let resume: Double?
    var testDescription: String { name }
  }

  static let cases = (try? SpecVectors.load("quiet-point", as: File.self).cases) ?? []

  @Test func theVectorFileLoads() throws {
    #expect(try SpecVectors.load("quiet-point", as: File.self).cases.count >= 11)
  }

  @Test(arguments: cases)
  func conformsTo(_ c: Case) {
    let levels = c.runs.flatMap { Array(repeating: $0.level, count: Int(($0.seconds / QuietPoint.frame).rounded())) }
    let actual = QuietPoint.find(levels: levels, start: c.start, target: c.target)
    if let expected = c.resume, let actual {
      #expect(abs(actual - expected) < 0.005, "\(c.name): \(actual)")
    } else {
      #expect(actual == c.resume, "\(c.name)")
    }
  }

  @Test func levelsAreRmsPerFrame() {
    let samples = [Float](repeating: 0.5, count: 320) + [Float](repeating: 0, count: 320)
    let levels = QuietPoint.levels(samples: samples, sampleRate: 16_000)
    #expect(levels.count == 2)
    #expect(abs(levels[0] - 0.5) < 0.0001)
    #expect(levels[1] == 0)
  }
}
