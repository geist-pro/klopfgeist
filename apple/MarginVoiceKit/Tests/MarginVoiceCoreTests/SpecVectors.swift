import Foundation

/// The language-neutral conformance cases in `spec/vectors`, shared with the Kotlin core.
enum SpecVectors {
  static func url(_ name: String, file: String = #filePath) -> URL {
    // …/apple/MarginVoiceKit/Tests/MarginVoiceCoreTests/SpecVectors.swift → repository root
    var root = URL(fileURLWithPath: file)
    for _ in 0..<5 { root.deleteLastPathComponent() }
    return root.appendingPathComponent("spec/vectors/\(name).json")
  }

  static func load<T: Decodable>(_ name: String, as type: T.Type) throws -> T {
    try JSONDecoder().decode(T.self, from: Data(contentsOf: url(name)))
  }
}
