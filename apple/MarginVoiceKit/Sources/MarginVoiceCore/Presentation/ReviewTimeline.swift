import Foundation

/// Where the comments and bookmarks of a book stand relative to the playhead: which one
/// the comments column highlights, where its "Ahead" divider goes, which sentences carry
/// a comment, and the scrubber's markers.
///
/// Presentation rules of the Apple apps (iPad comments column, Mac inspector and reading
/// pane). Android has no twin yet, so there is no vector in `spec/vectors`; if the Android
/// screens adopt the same layout, port these with the tests in `ReviewTimelineTests`.
public enum ReviewTimeline {
  /// A comment or bookmark reduced to what the timeline needs.
  public struct Note: Equatable, Sendable {
    public var id: UUID
    public var kind: CommentRecord.Kind
    /// Book media time, seconds.
    public var start: Double
    /// End of a bookmarked passage.
    public var end: Double?

    public init(id: UUID, kind: CommentRecord.Kind, start: Double, end: Double? = nil) {
      self.id = id
      self.kind = kind
      self.start = start
      self.end = end
    }

    /// Seconds between the note and `time`; 0 inside a passage.
    func distance(to time: Double) -> Double {
      if time < start { return start - time }
      return max(0, time - (end ?? start))
    }
  }

  public enum MarkerKind: Equatable, Sendable {
    case comment, bookmark, passage
  }

  /// A dot on the scrubber, as a fraction of the book (0...1); a passage also spans to `end`.
  public struct Marker: Equatable, Sendable {
    public var kind: MarkerKind
    public var position: Double
    public var end: Double?

    public init(kind: MarkerKind, position: Double, end: Double? = nil) {
      self.kind = kind
      self.position = position
      self.end = end
    }
  }

  /// How the bar beside the sentence being read is drawn.
  public enum SentenceMark: Equatable, Sendable {
    case plain, comment, bookmark
  }

  /// The note nearest the playhead (a passage counts from its start to its end). A tie goes
  /// to the note that starts first, the one already heard.
  public static func nearest(_ notes: [Note], to time: Double) -> UUID? {
    notes.min { a, b in
      let da = a.distance(to: time), db = b.distance(to: time)
      return da == db ? a.start < b.start : da < db
    }?.id
  }

  /// Index in `notes` (sorted by start) of the first note still ahead of the playhead;
  /// `notes.count` when nothing lies ahead. The current note stays above the divider even
  /// when it starts a little after the playhead.
  public static func firstAhead(_ notes: [Note], at time: Double, current: UUID?) -> Int {
    let after = current.flatMap { id in notes.firstIndex { $0.id == id } }.map { $0 + 1 } ?? 0
    return notes.indices.first { $0 >= after && notes[$0].start > time } ?? notes.count
  }

  /// Sentences a comment is anchored to (the one heard just before it was made), from the
  /// comments' text offsets.
  public static func commentedSentences(commentOffsets: [Int], in text: NarrationText) -> Set<Int> {
    guard !text.sentences.isEmpty else { return [] }
    return Set(commentOffsets.map { AnnotatedTextExport.attachSentence(for: $0, in: text) })
  }

  /// A comment on the sentence wins over a bookmark around it.
  public static func sentenceMark(commented: Bool, inBookmark: Bool) -> SentenceMark {
    if commented { return .comment }
    return inBookmark ? .bookmark : .plain
  }

  /// One marker per note, in the order given, clamped to the track.
  public static func markers(_ notes: [Note], duration: Double) -> [Marker] {
    guard duration > 0 else { return [] }
    func fraction(_ t: Double) -> Double { min(1, max(0, t / duration)) }
    return notes.map { note in
      switch note.kind {
      case .comment: Marker(kind: .comment, position: fraction(note.start))
      case .bookmark:
        if let end = note.end {
          Marker(kind: .passage, position: fraction(note.start), end: fraction(end))
        } else {
          Marker(kind: .bookmark, position: fraction(note.start))
        }
      }
    }
  }
}
