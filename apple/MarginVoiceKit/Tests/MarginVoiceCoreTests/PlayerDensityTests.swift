import Foundation
import Testing
@testable import MarginVoiceCore

/// Runs `spec/vectors/player-density.json`; the Kotlin core runs the same file.
struct PlayerDensityVectorTests {
  struct File: Decodable { let cases: [Case] }

  struct Case: Decodable, Sendable, CustomTestStringConvertible {
    let name: String
    let textScale: Double
    let density: String
    var testDescription: String { name }
  }

  static let cases = (try? SpecVectors.load("player-density", as: File.self).cases) ?? []

  @Test func theVectorFileLoads() throws {
    #expect(try SpecVectors.load("player-density", as: File.self).cases.count >= 9)
  }

  @Test(arguments: cases)
  func conformsTo(_ c: Case) {
    #expect(PlayerDensity(textScale: c.textScale).rawValue == c.density, "\(c.name)")
  }

  @Test func focusedDropsEverythingAroundTheSentence() {
    #expect(PlayerDensity.full.showsNeighbouringSentences)
    #expect(PlayerDensity.full.showsBookTitle)
    #expect(!PlayerDensity.focused.showsNeighbouringSentences)
    #expect(!PlayerDensity.focused.showsBookTitle)
  }
}
