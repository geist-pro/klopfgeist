import Foundation
import Testing
@testable import MarginVoiceCore

struct ReviewDraftTests {
  let text = NarrationText("Section: Intro.\n\nFirst sentence here. Second sentence here.\n\nThird paragraph.\n")

  func end(_ s: String) -> Int { text.string.distance(from: text.string.startIndex, to: text.string.range(of: s)!.upperBound) }

  var comments: [CommentRecord] {
    [
      CommentRecord(mediaTime: 70, textOffset: end("Third paragraph."), confidence: 0.9, chapter: "Results", transcript: "No baseline.", tag: .weakness),
      CommentRecord(mediaTime: 20, textOffset: end("First sentence here."), confidence: 0.9, chapter: "Intro", transcript: "Ambiguous n.", tag: .weakness),
      CommentRecord(mediaTime: 30, textOffset: end("Second sentence here."), confidence: 0.9, chapter: "Intro", transcript: "Clean manipulation check.", tag: .strength),
      CommentRecord(mediaTime: 5, textOffset: 5, confidence: 0.9, chapter: "Intro", transcript: "The paper asks whether awe leaves a signature.", tag: .summary),
      CommentRecord(mediaTime: 40, textOffset: end("Second sentence here."), confidence: 0.9, chapter: "Intro", transcript: "Check the figure.", tag: nil),
      CommentRecord(kind: .bookmark, mediaTime: 60, textOffset: end("Third paragraph.") - 1, confidence: 1, transcript: ""),
    ]
  }

  @Test func genericGroupsByTagInReadingOrder() {
    let md = ReviewDraftExport.markdown(title: "Demo", comments: comments, text: text, layout: .generic, language: .english)
    #expect(md.hasPrefix("# Review: Demo\n\n## Summary\n\nThe paper asks whether awe leaves a signature.\n\n## Strengths\n\n- Clean manipulation check. (§Intro · 0:30)\n  > Second sentence here.\n\n## Weaknesses\n\n- Ambiguous n. (§Intro · 0:20)\n  > First sentence here.\n- No baseline. (§Results · 1:10)\n  > Third paragraph.\n"))
    #expect(md.contains("## Questions for the authors\n\n_None yet._\n\n## Minor issues\n\n_None yet._"))
  }

  @Test func pcsFormMatchesTheVenueLayout() {
    let md = ReviewDraftExport.markdown(title: "Demo", comments: comments, text: text, layout: .pcsForm, language: .english)
    #expect(md.hasPrefix("# PCS form — Demo\n\n## Summary of Contributions\n\nThe paper asks whether awe leaves a signature.\n\nStrengths:\n- Clean manipulation check. (§Intro · 0:30)\n\nWeaknesses:\n- Ambiguous n. (§Intro · 0:20)\n- No baseline. (§Results · 1:10)\n\n## Expertise\n\nSelected: \n"))
    #expect(md.contains("## Overall Rating\n\n"))
    #expect(md.contains("## Review\n\nQuestions for the authors:\n_None yet._\n\nMinor issues:\n_None yet._\n\nOther notes:\n- Check the figure. (§Intro · 0:40)\n  > Second sentence here."))
  }

  @Test func germanHeadings() {
    let md = ReviewDraftExport.markdown(title: "Demo", comments: [], text: text, layout: .generic, language: .german)
    #expect(md.hasPrefix("# Gutachten: Demo\n\n## Zusammenfassung\n\n_Sage „Zusammenfassung: …“ beim Hören, oder schreibe sie hier._\n\n## Stärken\n\n_Noch nichts._\n\n## Schwächen"))
    #expect(md.contains("## Fragen an die Autoren") && md.contains("## Kleinigkeiten") && md.contains("## Weitere Notizen") && md.contains("## Passagen zum Nachlesen"))
  }

  @Test func untaggedAndBookmarksLandInTheirSections() {
    let md = ReviewDraftExport.markdown(title: "Demo", comments: comments, text: text, layout: .generic, language: .english)
    #expect(md.contains("## Other notes\n\n- Check the figure. (§Intro · 0:40)\n  > Second sentence here.\n\n## Passages to re-read\n\n- Third paragraph. (1:00)\n"))
  }

  @Test func emptyGroupsKeepPlaceholders() {
    let md = ReviewDraftExport.markdown(title: "Demo", comments: [], text: nil, layout: .generic, language: .english)
    #expect(md.contains("## Summary\n\n_Say “summary: …” while listening, or write it here._\n\n## Strengths\n\n_None yet._"))
    #expect(md.hasSuffix("## Passages to re-read\n\n_None yet._\n"))
  }

  @Test func polishedTextKeepsTheSpokenWords() {
    let c = comments[0]
    let md = ReviewDraftExport.markdown(
      title: "Demo", comments: [c], text: text, layout: .generic, language: .english,
      polished: [c.id: "The evaluation lacks a baseline."], summary: "A drafted summary.")
    #expect(md.contains("## Summary\n\nA drafted summary.\n\n"))
    #expect(md.contains("- The evaluation lacks a baseline. (§Results · 1:10)\n  > Third paragraph.\n  > said: No baseline.\n"))
  }
}
