import Testing
@testable import MarginVoiceCore

struct ContextAlignerTests {
  @Test(.enabled(if: Fixture.hasPrivate)) func recoversOffsetFromNoisyTranscript() throws {
    let text = try Fixture.text("icra")
    // pick ~45 words ending at a known word in the middle of the text
    let endWord = text.words.count / 2
    let words = text.words[(endWord - 45)..<endWord]
    var spoken = words.map { text.substring($0.range) }
    // recognizer errors: drop, substitute, misspell
    spoken.remove(at: 10)
    spoken[20] = "banana"
    spoken[30] = String(spoken[30].dropLast()) + "x"
    spoken.removeFirst(2)  // cut-off start
    let truth = text.words[endWord - 1].range.upperBound
    let estimate = truth + 900  // chapter-based estimate is off by ~15 s of speech
    let r = try #require(ContextAligner.align(transcript: spoken.joined(separator: " "), in: text, near: estimate))
    #expect(r.offset == truth)
    #expect(r.confidence > 0.8)
  }

  @Test(.enabled(if: Fixture.hasPrivate)) func rejectsUnrelatedTranscript() throws {
    let text = try Fixture.text("icra")
    let r = ContextAligner.align(transcript: "the weather in Berlin is sunny and the bakery opens", in: text, near: 20_000, minimumMatches: 5)
    #expect(r == nil)
  }

  @Test func repeatedPhrasePrefersClosestToEstimate() {
    let text = NarrationText("Alpha beta gamma delta. Filler words here. Alpha beta gamma delta. More filler.")
    let first = ContextAligner.align(transcript: "alpha beta gamma delta", in: text, near: 10)
    let second = ContextAligner.align(transcript: "alpha beta gamma delta", in: text, near: 60)
    #expect(first?.offset == 22)
    #expect(second?.offset == 65)
  }
}
