import Foundation
import Testing
@testable import MarginVoiceCore

struct ExportTests {
  let text = NarrationText("Section: Intro.\n\nFirst sentence here. Second sentence here.\n\nThird paragraph.\n")

  @Test func annotatedMarkdown() {
    let second = text.string.range(of: "Second sentence here.")!
    let offset = text.string.distance(from: text.string.startIndex, to: second.upperBound)
    let comments = [
      CommentRecord(mediaTime: 12, textOffset: offset - 3, confidence: 0.9, transcript: "Too long."),
      CommentRecord(mediaTime: 20, textOffset: text.count, confidence: 0.2, transcript: "Rephrase"),
      CommentRecord(kind: .bookmark, mediaTime: 3, textOffset: 15, confidence: 1, transcript: ""),
    ]
    let md = AnnotatedTextExport.markdown(text: text, comments: comments, title: "Demo", headingOffsets: [0])
    #expect(md == """
      # Demo

      ## Intro {>> Bookmark 0:03 <<}

      First sentence here. Second sentence here. {>> Too long. <<}

      {==Third paragraph.==} {>> Rephrase <<}

      """)
  }

  @Test func bookmarkedPassageIsHighlighted() {
    let text = NarrationText("One a. Two b. Three c.\n\nFour d. Five e.\n")
    func end(_ s: String) -> Int { text.string.distance(from: text.string.startIndex, to: text.string.range(of: s)!.upperBound) }
    let passage = CommentRecord(kind: .bookmark, mediaTime: 2, textOffset: end("Two b.") - 1, confidence: 1, transcript: "",
      endMediaTime: 9, endTextOffset: end("Four d.") - 1)
    let md = AnnotatedTextExport.markdown(text: text, comments: [passage])
    #expect(md == """
      One a. {==Two b. Three c.==}

      {==Four d.==} {>> Bookmark 0:02–0:09 <<} Five e.

      """)
    #expect(CommentExport.markdown(title: "T", comments: [passage], text: text).contains("> Two b. Three c. Four d."))
    // a single bookmark highlights the sentence just heard
    let single = CommentRecord(kind: .bookmark, mediaTime: 2, textOffset: end("Five e."), confidence: 1, transcript: "")
    #expect(AnnotatedTextExport.markdown(text: text, comments: [single]).contains("{==Five e.==} {>> Bookmark 0:02 <<}"))
  }

  @Test func commentListAndCSV() throws {
    let c = CommentRecord(mediaTime: 75, textOffset: 30, confidence: 0.9, chapter: "Intro", transcript: "Say \"why\", first", audioFile: "c1.m4a")
    let md = CommentExport.markdown(title: "Demo", comments: [c], text: text)
    #expect(md.contains("## 1. 1:15 · Intro"))
    #expect(md.contains("> First sentence here."))
    let csv = CommentExport.csv(comments: [c], text: text)
    #expect(csv.contains(#""Say ""why"", first""#))
    let json = try CommentExport.json(title: "Demo", comments: [c])
    #expect(String(decoding: json, as: UTF8.self).contains("\"transcript\""))
  }

  @Test func clock() {
    #expect(TimeFormat.clock(5) == "0:05")
    #expect(TimeFormat.clock(754) == "12:34")
    #expect(TimeFormat.clock(3723) == "1:02:03")
  }
}
