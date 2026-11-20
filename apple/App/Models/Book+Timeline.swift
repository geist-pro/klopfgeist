import MarginVoiceCore

extension Comment {
  /// The comment as the timeline helpers see it.
  var timelineNote: ReviewTimeline.Note {
    ReviewTimeline.Note(id: id, kind: kind, start: mediaTime, end: endMediaTime)
  }
}

extension Book {
  /// Comments and bookmarks in reading order, reduced for `ReviewTimeline`.
  var timelineNotes: [ReviewTimeline.Note] { sortedComments.map(\.timelineNote) }

  /// Scrubber markers of every comment and bookmark.
  var scrubberMarkers: [ReviewTimeline.Marker] { ReviewTimeline.markers(timelineNotes, duration: duration) }

  /// Sentences that carry a spoken comment (not a bookmark).
  func commentedSentences(in text: NarrationText) -> Set<Int> {
    let offsets = comments.filter { $0.kind == .comment }.compactMap { $0.textOffset(in: text) }
    return ReviewTimeline.commentedSentences(commentOffsets: offsets, in: text)
  }

  /// Sentences inside a bookmark (the one sentence, or the whole passage).
  func bookmarkedSentences(in text: NarrationText) -> Set<Int> {
    var result = Set<Int>()
    for c in comments where c.kind == .bookmark && c.textOffset(in: text) != nil {
      result.formUnion(c.record(text: text).sentenceRange(in: text))
    }
    return result
  }
}
