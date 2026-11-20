import Foundation
import Testing
@testable import MarginVoiceCore

/// Runs `spec/vectors/status-announcement.json`; the Kotlin core runs the same file.
struct StatusAnnouncementVectorTests {
  struct File: Decodable { let cases: [Case] }

  struct Case: Decodable, Sendable, CustomTestStringConvertible {
    let name: String
    let from: String
    let to: String
    let recording: Bool
    let say: String?
    var testDescription: String { name }
  }

  static let cases = (try? SpecVectors.load("status-announcement", as: File.self).cases) ?? []

  @Test func theVectorFileLoads() throws {
    #expect(try SpecVectors.load("status-announcement", as: File.self).cases.count >= 13)
  }

  @Test(arguments: cases)
  func conformsTo(_ c: Case) throws {
    let from = try #require(ListeningStatus(rawValue: c.from))
    let to = try #require(ListeningStatus(rawValue: c.to))
    #expect(StatusAnnouncement.listening(from: from, to: to, recording: c.recording) == c.say, "\(c.name)")
  }

  @Test func resultsOfWorkInTheBackgroundNameTheBook() {
    #expect(StatusAnnouncement.conversionFinished(title: "Cuddle-fish") == "Cuddle-fish is ready to play")
    #expect(StatusAnnouncement.conversionFailed(title: "Cuddle-fish") == "Creating Cuddle-fish failed")
    #expect(StatusAnnouncement.syncFailed == "Sync failed")
    #expect(StatusAnnouncement.exportReady(files: 1) == "1 file ready to share")
    #expect(StatusAnnouncement.exportReady(files: 3) == "3 files ready to share")
  }
}
