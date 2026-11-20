import MarginVoiceCore
import Foundation
import Testing
@testable import MarginVoice

@MainActor
struct ReviewPolisherTests {
  struct Fail: Error {}

  @Test func keepsTheSpokenWordsWhenPolishFails() async {
    let good = CommentRecord(mediaTime: 1, textOffset: 0, confidence: 1, transcript: "no baseline", tag: .weakness)
    let bad = CommentRecord(mediaTime: 2, textOffset: 0, confidence: 1, transcript: "fails", tag: .question)
    let empty = CommentRecord(mediaTime: 3, textOffset: 0, confidence: 1, transcript: "  ")
    let bookmark = CommentRecord(kind: .bookmark, mediaTime: 4, textOffset: 0, confidence: 1, transcript: "")
    let polisher = ReviewPolisher(
      polish: { note, _ in
        if note == "fails" { throw Fail() }
        return "The evaluation has \(note)."
      },
      summarize: { _, _, _ in "Summary." })

    var seen: [(Int, Int)] = []
    let result = await polisher.run([good, bad, empty, bookmark], title: "T", language: .english) { seen.append(($0, $1)) }
    #expect(result.polished == [good.id: "The evaluation has no baseline."])
    #expect(result.summary == nil)  // no summary notes
    #expect(seen.map(\.0) == [0, 1, 2] && seen.allSatisfy { $0.1 == 2 })
  }

  @Test func draftsTheSummaryFromSummaryNotes() async {
    let s1 = CommentRecord(mediaTime: 1, textOffset: 0, confidence: 1, transcript: "asks whether awe leaves a signature", tag: .summary)
    let s2 = CommentRecord(mediaTime: 2, textOffset: 0, confidence: 1, transcript: "n equals 29", tag: .summary)
    let polisher = ReviewPolisher(
      polish: { note, _ in note.uppercased() },
      summarize: { notes, title, _ in "\(title): \(notes.joined(separator: "; "))" })
    let result = await polisher.run([s1, s2], title: "Awe", language: .english) { _, _ in }
    #expect(result.summary == "Awe: asks whether awe leaves a signature; n equals 29")
    #expect(result.polished[s2.id] == "N EQUALS 29")
  }
}
