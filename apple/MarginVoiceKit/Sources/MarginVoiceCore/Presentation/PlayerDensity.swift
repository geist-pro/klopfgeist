/// What the player shows around the sentence being read. At large text the sentence keeps
/// the room: header and controls are pinned, so everything else gives way first.
public enum PlayerDensity: String, Sendable, CaseIterable {
  /// The sentence between the one before and the one after, under the book and chapter.
  case full
  /// Only the sentence being read, under a one-line chapter header.
  case focused

  /// From this text scale on the player is `focused`: Android's 150 %, iOS's first accessibility size.
  public static let focusedFrom = 1.5

  /// - Parameter textScale: 1.0 is the system's default text size (iOS: the scaled body
  ///   size / 17; Android: the font scale).
  public init(textScale: Double) {
    self = textScale >= Self.focusedFrom ? .focused : .full
  }

  public var showsNeighbouringSentences: Bool { self == .full }
  public var showsBookTitle: Bool { self == .full }
}
