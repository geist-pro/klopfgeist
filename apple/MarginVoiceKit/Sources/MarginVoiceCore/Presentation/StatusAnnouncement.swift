/// The listening status as the player shows it.
public enum ListeningStatus: String, Sendable, CaseIterable {
  case off, starting, listening, paused, unavailable
}

/// What a screen reader is told when something changes that has no tone of its own.
/// Recording, saving and bookmarks are not here: they have tones, and a spoken
/// announcement would land in the comment being recorded or over the book.
public enum StatusAnnouncement {
  /// The sentence for a change of the listening status, or nil for silence.
  public static func listening(from: ListeningStatus, to: ListeningStatus, recording: Bool) -> String? {
    // the microphone is recording a comment: the screen reader stays out of it
    guard from != to, !recording else { return nil }
    switch to {
    case .starting: return nil
    case .listening: return "Listening for voice commands"
    case .paused: return "Listening paused"
    case .off: return "Voice commands off"
    case .unavailable: return "Voice commands unavailable"
    }
  }

  public static func conversionFinished(title: String) -> String { "\(title) is ready to play" }
  public static func conversionFailed(title: String) -> String { "Creating \(title) failed" }
  public static let syncFailed = "Sync failed"
  public static func exportReady(files: Int) -> String { files == 1 ? "1 file ready to share" : "\(files) files ready to share" }
}
