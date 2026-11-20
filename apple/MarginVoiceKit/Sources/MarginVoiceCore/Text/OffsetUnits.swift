import Foundation

/// Text offsets between devices are Unicode code points (like `timing.json`): Swift
/// counts Characters, Kotlin counts UTF-16 units, so each side converts at the edge.
public enum OffsetUnits {
  /// Character offset in `characters` → code point offset.
  public static func toCodePoints(_ characters: [Character], _ offset: Int) -> Int {
    characters.prefix(max(0, min(offset, characters.count))).reduce(0) { $0 + $1.unicodeScalars.count }
  }

  /// Code point offset → Character offset (inside a character: the character it starts in; past the end: the end).
  public static func fromCodePoints(_ characters: [Character], _ codePoints: Int) -> Int {
    if codePoints <= 0 { return 0 }
    var scalars = 0
    for (i, c) in characters.enumerated() {
      if scalars >= codePoints { return i }
      scalars += c.unicodeScalars.count
    }
    return characters.count
  }
}
