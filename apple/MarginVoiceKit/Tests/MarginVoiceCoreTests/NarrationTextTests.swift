import Testing
@testable import MarginVoiceCore

struct NarrationTextTests {
  @Test func splitsParagraphsAndSentences() {
    let t = NarrationText("Title.\n\nSection: Intro.\n\nFirst one, e.g. this. Second one! Third?\n")
    #expect(t.paragraphs.map(\.text) == ["Title.", "Section: Intro.", "First one, e.g. this. Second one! Third?"])
    let third = t.sentences.filter { $0.paragraphIndex == 2 }.map { t.substring($0.range) }
    #expect(third == ["First one, e.g. this.", "Second one!", "Third?"])
  }

  @Test func findsHeadings() {
    let t = NarrationText("Section: Introduction.\n\nA long paragraph that has two sentences. It is not a heading.\n\nResults.\n")
    #expect(t.headings.map(\.title) == ["Introduction", "Results"])
  }

  @Test func sentenceLookup() {
    let t = NarrationText("One two. Three four. Five.")
    #expect(t.sentenceIndex(containing: 0) == 0)
    #expect(t.sentenceIndex(containing: 10) == 1)
    #expect(t.sentenceIndex(containing: 100) == 2)
  }

  @Test func normalizesWords() {
    #expect(TextSegmentation.tokens("Zurück, bitte! Don't") == ["zuruck", "bitte", "dont"])
  }
}
