import Foundation

/// Optional `<name>.timing.json` written by doc2audio next to the m4b.
///
/// ```json
/// {"version": 1, "unit": "codepoint", "chunks": [{"start": 0.0, "end": 3.2, "char_start": 0, "char_end": 58}]}
/// ```
/// Times are media seconds in the encoded file (tempo already applied).
/// Offsets are Unicode code point indices into the narration `.txt` (Python `str` indices).
public struct TimingSidecar: Codable, Sendable, Equatable {
  public struct Chunk: Codable, Sendable, Equatable {
    public var start: Double
    public var end: Double
    public var charStart: Int
    public var charEnd: Int

    enum CodingKeys: String, CodingKey {
      case start, end
      case charStart = "char_start"
      case charEnd = "char_end"
    }

    public init(start: Double, end: Double, charStart: Int, charEnd: Int) {
      self.start = start
      self.end = end
      self.charStart = charStart
      self.charEnd = charEnd
    }
  }

  public var version: Int
  public var chunks: [Chunk]

  public init(version: Int = 1, chunks: [Chunk]) {
    self.version = version
    self.chunks = chunks
  }

  public static func decode(_ data: Data) throws -> TimingSidecar {
    try JSONDecoder().decode(TimingSidecar.self, from: data)
  }

  /// Converts code point offsets to `Character` offsets of `text`.
  public func convertedToCharacterOffsets(in text: NarrationText) -> TimingSidecar {
    var scalarToChar: [Int] = []
    scalarToChar.reserveCapacity(text.count + 16)
    for (ci, ch) in text.characters.enumerated() {
      for _ in ch.unicodeScalars { scalarToChar.append(ci) }
    }
    scalarToChar.append(text.count)
    func map(_ s: Int) -> Int { scalarToChar[max(0, min(s, scalarToChar.count - 1))] }
    return TimingSidecar(version: version, chunks: chunks.map {
      Chunk(start: $0.start, end: $0.end, charStart: map($0.charStart), charEnd: map($0.charEnd))
    })
  }
}
