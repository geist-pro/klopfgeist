import Foundation
import Testing
@testable import MarginVoiceCore

enum Fixture {
  /// The paper and the proposal some tests read (`icra.*`, `hace_de.*`) are unpublished and not in the
  /// public repository (`Fixtures/README.md`). Tests that need them carry `.enabled(if: Fixture.hasPrivate)`.
  static let hasPrivate = Bundle.module.url(forResource: "icra", withExtension: "txt", subdirectory: "Fixtures") != nil

  struct Chapters: Decodable {
    let duration: Double
    let chapters: [ChapterMark]
  }

  static func text(_ name: String) throws -> NarrationText {
    let url = try #require(Bundle.module.url(forResource: name, withExtension: "txt", subdirectory: "Fixtures"))
    return NarrationText(try String(contentsOf: url, encoding: .utf8))
  }

  static func chapters(_ name: String) throws -> Chapters {
    let url = try #require(Bundle.module.url(forResource: "\(name)_chapters", withExtension: "json", subdirectory: "Fixtures"))
    return try JSONDecoder().decode(Chapters.self, from: Data(contentsOf: url))
  }
}
