import Foundation
import Testing
@testable import MarginVoiceCore

/// Where comments and bookmarks stand relative to the playhead (comments column, inspector, scrubber).
struct ReviewTimelineTests {
  typealias Note = ReviewTimeline.Note

  private static let a = Note(id: UUID(), kind: .comment, start: 160)
  private static let b = Note(id: UUID(), kind: .comment, start: 555)
  private static let passage = Note(id: UUID(), kind: .bookmark, start: 782, end: 850)
  private static let c = Note(id: UUID(), kind: .comment, start: 1100)
  private static let notes = [a, b, passage, c]

  // MARK: The note nearest the playhead

  @Test func nearestIsTheClosestNoteEitherSide() {
    #expect(ReviewTimeline.nearest(Self.notes, to: 1122) == Self.c.id)
    #expect(ReviewTimeline.nearest(Self.notes, to: 1000) == Self.c.id)
    #expect(ReviewTimeline.nearest(Self.notes, to: 200) == Self.a.id)
  }

  @Test func insideAPassageThePassageIsNearest() {
    // 840 is 260 s before c, but inside the passage
    #expect(ReviewTimeline.nearest(Self.notes, to: 840) == Self.passage.id)
    // after the passage, distance counts from its end
    #expect(ReviewTimeline.nearest(Self.notes, to: 900) == Self.passage.id)
  }

  @Test func aTieGoesToTheNoteAlreadyHeard() {
    let early = Note(id: UUID(), kind: .comment, start: 100)
    let late = Note(id: UUID(), kind: .bookmark, start: 120)
    #expect(ReviewTimeline.nearest([late, early], to: 110) == early.id)
  }

  @Test func noNotesNoNearest() {
    #expect(ReviewTimeline.nearest([], to: 10) == nil)
  }

  // MARK: What lies ahead

  @Test func aheadStartsAtTheFirstNoteAfterThePlayhead() {
    #expect(ReviewTimeline.firstAhead(Self.notes, at: 600, current: Self.b.id) == 2)
    #expect(ReviewTimeline.firstAhead(Self.notes, at: 0, current: nil) == 0)
  }

  @Test func theCurrentNoteStaysAboveTheDividerEvenWhenJustAhead() {
    // 1090: c starts 10 s ahead and is the nearest note
    #expect(ReviewTimeline.firstAhead(Self.notes, at: 1090, current: Self.c.id) == 4)
    #expect(ReviewTimeline.firstAhead(Self.notes, at: 540, current: Self.b.id) == 2)
  }

  @Test func nothingAheadIsTheEndOfTheList() {
    #expect(ReviewTimeline.firstAhead(Self.notes, at: 2000, current: Self.c.id) == Self.notes.count)
    #expect(ReviewTimeline.firstAhead([], at: 10, current: nil) == 0)
  }

  // MARK: Sentences

  private static let text = NarrationText("One a. Two b. Three c. Four d.")

  @Test func aCommentMarksTheSentenceJustHeard() {
    // offsets point just past the sentence heard ("Two b." ends at 13)
    let sentences = ReviewTimeline.commentedSentences(commentOffsets: [13], in: Self.text)
    #expect(sentences == [1])
    #expect(ReviewTimeline.commentedSentences(commentOffsets: [], in: Self.text).isEmpty)
  }

  @Test func theCurrentSentenceBarFollowsWhatIsAnchoredThere() {
    #expect(ReviewTimeline.sentenceMark(commented: true, inBookmark: true) == .comment)
    #expect(ReviewTimeline.sentenceMark(commented: false, inBookmark: true) == .bookmark)
    #expect(ReviewTimeline.sentenceMark(commented: false, inBookmark: false) == .plain)
  }

  // MARK: Scrubber markers

  @Test func markersAreFractionsOfTheBookColouredByKind() {
    let bookmark = Note(id: UUID(), kind: .bookmark, start: 50)
    let markers = ReviewTimeline.markers([Self.a, bookmark, Self.passage], duration: 1600)
    #expect(markers.map(\.kind) == [.comment, .bookmark, .passage])
    #expect(markers[0].position == 0.1)
    #expect(markers[1].end == nil)
    #expect(markers[2].position == 782.0 / 1600 && markers[2].end == 850.0 / 1600)
  }

  @Test func markersStayOnTheTrack() {
    let late = Note(id: UUID(), kind: .comment, start: 2000)
    let longPassage = Note(id: UUID(), kind: .bookmark, start: 1500, end: 1900)
    let markers = ReviewTimeline.markers([late, longPassage], duration: 1600)
    #expect(markers[0].position == 1)
    #expect(markers[1].end == 1)
    #expect(ReviewTimeline.markers([Self.a], duration: 0).isEmpty)
  }
}
