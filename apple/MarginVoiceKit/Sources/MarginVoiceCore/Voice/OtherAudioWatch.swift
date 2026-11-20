import Foundation

/// Decides from repeated looks at the system whether another app is really playing.
/// Sounds of our own (an earcon, the tail of the paused book) and a short system
/// sound don't count: the other audio has to last.
public struct OtherAudioWatch: Sendable {
  /// How long the other audio has to last before it counts.
  public var settle: Double = 2
  /// How long after a sound of our own the system's answer can't be trusted.
  public var ownSoundMask: Double = 1.5

  public private(set) var isPlaying = false
  private var activeSince: Double?
  private var maskedUntil = -Double.infinity

  public init() {}

  /// A sound of our own just played (or the book just paused).
  public mutating func ownSound(now: Double) {
    maskedUntil = now + ownSoundMask
    activeSince = nil
  }

  /// Returns whether another app is playing now.
  @discardableResult
  public mutating func update(active: Bool, now: Double) -> Bool {
    guard active else {
      activeSince = nil
      isPlaying = false
      return false
    }
    if isPlaying { return true }
    guard now >= maskedUntil else { return false }
    let since = activeSince ?? now
    activeSince = since
    isPlaying = now - since >= settle
    return isPlaying
  }

  public mutating func reset() {
    activeSince = nil
    isPlaying = false
    maskedUntil = -Double.infinity
  }
}
