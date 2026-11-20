import Testing
@testable import MarginVoiceCore

struct ReviewTagTests {
  @Test func extractsEnglishAndGermanTags() {
    let w = ReviewTag.extract("Weakness: the baseline is never tuned.")
    #expect(w.tag == .weakness && w.text == "The baseline is never tuned.")
    let s = ReviewTag.extract("Schwäche, die Baseline wird nie optimiert")
    #expect(s.tag == .weakness && s.text == "Die Baseline wird nie optimiert")
    #expect(ReviewTag.extract("strength the manipulation check is decisive").tag == .strength)
    #expect(ReviewTag.extract("Frage an die Autoren: wie groß war n?").tag == .question)
    #expect(ReviewTag.extract("Frage: wie groß war n?").tag == .question)
    #expect(ReviewTag.extract("minor issue, typo in figure 3").text == "Typo in figure 3")
    #expect(ReviewTag.extract("Kleinigkeit Tippfehler in Abbildung 3").tag == .minor)
    #expect(ReviewTag.extract("Summary. The paper asks whether awe leaves a signature.").tag == .summary)
  }

  @Test func stripsPunctuationAfterTheTag() {
    #expect(ReviewTag.extract("question - why n equals 29").text == "Why n equals 29")
    #expect(ReviewTag.extract("um, weakness, no baseline").text == "No baseline")
    #expect(ReviewTag.extract("Weakness.").text == "")
  }

  @Test func leadInHeardInTheBeepIsDropped() {
    let r = ReviewTag.extract("A weakness Maybe 24 participants enough")
    #expect(r.tag == .weakness && r.text == "Maybe 24 participants enough")
    #expect(ReviewTag.extract("hey question how loud are the wings").tag == .question)
    #expect(ReviewTag.extract("A weakness of the method is the small sample").tag == nil)
  }

  @Test func leavesUntaggedTextAlone() {
    let r = ReviewTag.extract("This claim needs a citation.")
    #expect(r.tag == nil && r.text == "This claim needs a citation.")
  }

  @Test func tagWordInsideTheSentenceIsNotATag() {
    #expect(ReviewTag.extract("The question is whether n is enough").tag == nil)
    #expect(ReviewTag.extract("Eine Schwäche sehe ich hier nicht").tag == nil)
  }

  @Test func exportsCarryTheTag() throws {
    let text = NarrationText("One a. Two b.\n")
    let c = CommentRecord(mediaTime: 5, textOffset: 6, confidence: 0.9, chapter: "Intro", transcript: "No baseline.", tag: .weakness)
    #expect(CommentExport.markdown(title: "T", comments: [c], text: text).contains("## 1. 0:05 · Intro · Weakness"))
    #expect(CommentExport.csv(comments: [c], text: text).contains(",comment,weakness,0.90,"))
    #expect(AnnotatedTextExport.markdown(text: text, comments: [c]).contains("{>> [Weakness] No baseline. <<}"))
    #expect(String(decoding: try CommentExport.json(title: "T", comments: [c]), as: UTF8.self).contains("\"tag\" : \"weakness\""))
  }
}
