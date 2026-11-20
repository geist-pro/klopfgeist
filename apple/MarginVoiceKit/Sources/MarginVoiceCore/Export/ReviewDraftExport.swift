import Foundation

/// The comments of a book sorted into the shape of a review: a generic Markdown
/// draft, or the IEEE VR PCS form. Every item keeps the sentence it refers to.
public enum ReviewDraftExport {
  public enum Layout: String, CaseIterable, Sendable {
    case generic, pcsForm
  }

  /// - Parameters:
  ///   - polished: reworded transcripts by comment id (from the on-device model); the
  ///     spoken words stay underneath as "said: …".
  ///   - summary: a drafted summary paragraph placed before the summary notes.
  public static func markdown(
    title: String, comments: [CommentRecord], text: NarrationText?, layout: Layout, language: VoiceCommand.Language,
    polished: [UUID: String] = [:], summary: String? = nil
  ) -> String {
    let de = language == .german
    let sorted = comments.sorted { ($0.textOffset, $0.mediaTime) < ($1.textOffset, $1.mediaTime) }
    let notes = sorted.filter { $0.kind == .comment }
    let bookmarks = sorted.filter { $0.kind == .bookmark }
    func group(_ tag: ReviewTag?) -> [CommentRecord] { notes.filter { $0.tag == tag } }

    func place(_ c: CommentRecord) -> String {
      var parts: [String] = []
      if let chapter = c.chapter, !chapter.isEmpty { parts.append("§\(chapter)") }
      parts.append(TimeFormat.clock(c.mediaTime))
      return parts.joined(separator: " · ")
    }
    func quote(_ c: CommentRecord) -> String? {
      guard let text, !text.sentences.isEmpty else { return nil }
      let q = c.quotedText(in: text).trimmingCharacters(in: .whitespacesAndNewlines)
      return q.isEmpty ? nil : q
    }
    func body(_ c: CommentRecord) -> String {
      let raw = c.transcript.trimmingCharacters(in: .whitespacesAndNewlines)
      if raw.isEmpty { return de ? "(nicht transkribiert)" : "(not transcribed)" }
      return polished[c.id] ?? raw
    }
    /// "- text (§Chapter · m:ss)" with the quoted sentence and, when reworded, the spoken words beneath.
    func item(_ c: CommentRecord, withQuote: Bool) -> String {
      var lines = ["- \(body(c)) (\(place(c)))"]
      if withQuote, let q = quote(c) { lines.append("  > \(q)") }
      if polished[c.id] != nil, !c.transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        lines.append("  > \(de ? "gesagt" : "said"): \(c.transcript.trimmingCharacters(in: .whitespacesAndNewlines))")
      }
      return lines.joined(separator: "\n")
    }
    func list(_ items: [CommentRecord], withQuote: Bool, empty: String) -> String {
      items.isEmpty ? empty : items.map { item($0, withQuote: withQuote) }.joined(separator: "\n")
    }
    func summaryText(empty: String) -> String {
      var paragraphs: [String] = []
      if let summary, !summary.trimmingCharacters(in: .whitespaces).isEmpty { paragraphs.append(summary) }
      for c in group(.summary) {
        paragraphs.append(body(c))
        if polished[c.id] != nil { paragraphs.append("> \(de ? "gesagt" : "said"): \(c.transcript)") }
      }
      return paragraphs.isEmpty ? empty : paragraphs.joined(separator: "\n\n")
    }
    func passages(empty: String) -> String {
      guard !bookmarks.isEmpty else { return empty }
      return bookmarks.map { b in
        let range = b.endMediaTime.map { "\(TimeFormat.clock(b.mediaTime))–\(TimeFormat.clock($0))" } ?? TimeFormat.clock(b.mediaTime)
        return "- \(quote(b) ?? (de ? "Lesezeichen" : "Bookmark")) (\(range))"
      }.joined(separator: "\n")
    }

    let none = de ? "_Noch nichts._" : "_None yet._"
    let noSummary = de
      ? "_Sage „Zusammenfassung: …“ beim Hören, oder schreibe sie hier._"
      : "_Say “summary: …” while listening, or write it here._"

    switch layout {
    case .generic:
      let h = de
        ? ["Gutachten", "Zusammenfassung", "Stärken", "Schwächen", "Fragen an die Autoren", "Kleinigkeiten", "Weitere Notizen", "Passagen zum Nachlesen"]
        : ["Review", "Summary", "Strengths", "Weaknesses", "Questions for the authors", "Minor issues", "Other notes", "Passages to re-read"]
      return """
        # \(h[0]): \(title)

        ## \(h[1])

        \(summaryText(empty: noSummary))

        ## \(h[2])

        \(list(group(.strength), withQuote: true, empty: none))

        ## \(h[3])

        \(list(group(.weakness), withQuote: true, empty: none))

        ## \(h[4])

        \(list(group(.question), withQuote: true, empty: none))

        ## \(h[5])

        \(list(group(.minor), withQuote: true, empty: none))

        ## \(h[6])

        \(list(group(nil), withQuote: true, empty: none))

        ## \(h[7])

        \(passages(empty: none))

        """
    case .pcsForm:
      // the IEEE VR form: section names stay English, the notes fill the free-text parts
      let ratings = [
        "Expertise", "Clarity of Presentation", "Technical Soundness", "Reproducibility", "References", "IRB Approval Check",
        "Participant Demographics", "Participant Diversity",
      ].map { "## \($0)\n\nSelected: \n" }.joined(separator: "\n")
      return """
        # PCS form — \(title)

        ## Summary of Contributions

        \(summaryText(empty: noSummary))

        Strengths:
        \(list(group(.strength), withQuote: false, empty: none))

        Weaknesses:
        \(list(group(.weakness), withQuote: false, empty: none))

        \(ratings)
        ## Overall Rating



        ## Review

        Questions for the authors:
        \(list(group(.question), withQuote: true, empty: none))

        Minor issues:
        \(list(group(.minor), withQuote: true, empty: none))

        Other notes:
        \(list(group(nil), withQuote: true, empty: none))

        Passages to re-read:
        \(passages(empty: none))

        """
    }
  }
}
