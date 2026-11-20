import Testing
@testable import MarginVoiceCore

struct ContextVocabularyTests {
  @Test func picksNamesAcronymsAndCompounds() {
    let text = """
      Das Projekt WallBOT entwickelt mit dem HAFEN-Konsortium einen Greifer. WallBOT nutzt KI und \
      Robots4CleanTech. Die Fassadenreinigung ist ein wichtiges Thema, und es ist gut.
      """
    let terms = ContextVocabulary.terms(in: text)
    #expect(terms.first == "WallBOT")  // most frequent
    #expect(terms.contains("HAFEN-Konsortium"))
    #expect(terms.contains("KI"))
    #expect(terms.contains("Robots4CleanTech"))
    #expect(terms.contains("Fassadenreinigung"))
    #expect(terms.contains("Projekt") == false)  // short common noun
    #expect(!terms.contains("wichtiges") && !terms.contains("Das") && !terms.contains("es"))
  }

  @Test func nearbyTextAndTitle() {
    let text = NarrationText("Anfang mit AlphaWerk. " + String(repeating: "und so weiter ", count: 300) + "Ende mit BetaWerk.")
    let terms = ContextVocabulary.terms(around: 0, in: text, title: "Projektskizze WallBOT", radius: 200)
    #expect(Array(terms.prefix(2)) == ["Projektskizze", "WallBOT"])
    #expect(terms.contains("AlphaWerk") && !terms.contains("BetaWerk"))
  }

  @Test func respectsTheLimit() {
    let text = (1...50).map { "Name\($0)" }.joined(separator: " ")
    #expect(ContextVocabulary.terms(in: text, limit: 10).count == 10)
  }
}
