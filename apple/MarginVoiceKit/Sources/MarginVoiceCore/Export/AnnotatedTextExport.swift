import Foundation

/// The narration text with every comment inserted after the sentence it refers to,
/// as Markdown with CriticMarkup: `{>> comment <<}`. Sentences whose anchor is only
/// estimated are additionally highlighted `{==like this==}` so they can be checked.
public enum AnnotatedTextExport {
  public static let lowConfidence = 0.5

  /// - Parameter headingOffsets: paragraph start offsets that are chapter headings
  ///   (from `ChapterMatcher`); those paragraphs become `##` headings.
  public static func markdown(
    text: NarrationText, comments: [CommentRecord], title: String? = nil, headingOffsets: Set<Int> = []
  ) -> String {
    // group comments by the sentence they attach to; bookmarks attach after the
    // last sentence of their passage and highlight all of it
    var bySentence: [Int: [CommentRecord]] = [:]
    var highlighted = Set<Int>()
    for c in comments.sorted(by: { ($0.textOffset, $0.mediaTime) < ($1.textOffset, $1.mediaTime) }) {
      let range = c.kind == .bookmark ? c.sentenceRange(in: text) : attachSentence(for: c.textOffset, in: text)...attachSentence(for: c.textOffset, in: text)
      bySentence[range.upperBound, default: []].append(c)
      if c.kind == .bookmark || c.confidence < lowConfidence { highlighted.formUnion(range) }
    }

    var out = ""
    if let title { out += "# \(title)\n\n" }
    var sentenceIndex = 0
    var closedForNotes = false
    for (pi, p) in text.paragraphs.enumerated() {
      if headingOffsets.contains(p.range.lowerBound) {
        out += "## \(headingTitle(p.text))"
      } else {
        var line = ""
        var cursor = p.range.lowerBound
        while sentenceIndex < text.sentences.count, text.sentences[sentenceIndex].paragraphIndex == pi {
          let s = text.sentences[sentenceIndex]
          line += text.substring(cursor..<s.range.lowerBound)
          let sentence = text.substring(s.range)
          let notes = bySentence[sentenceIndex] ?? []
          // one {== … ==} per paragraph run of highlighted sentences
          let lit = highlighted.contains(sentenceIndex)
          let opens = lit && (closedForNotes || sentenceIndex == 0 || !highlighted.contains(sentenceIndex - 1) || text.sentences[sentenceIndex - 1].paragraphIndex != pi)
          let next = sentenceIndex + 1
          let closes = lit && (!notes.isEmpty || next >= text.sentences.count || !highlighted.contains(next) || text.sentences[next].paragraphIndex != pi)
          line += (opens ? "{==" : "") + sentence + (closes ? "==}" : "")
          for c in notes { line += critic(c) }
          closedForNotes = closes && !notes.isEmpty
          cursor = s.range.upperBound
          sentenceIndex += 1
        }
        line += text.substring(cursor..<p.range.upperBound)
        out += line
      }
      // comments attached to a heading paragraph (no sentence of its own beyond the heading)
      if headingOffsets.contains(p.range.lowerBound) {
        while sentenceIndex < text.sentences.count, text.sentences[sentenceIndex].paragraphIndex == pi {
          for c in bySentence[sentenceIndex] ?? [] { out += critic(c) }
          sentenceIndex += 1
        }
      }
      out += "\n\n"
    }
    return out.trimmingCharacters(in: .whitespacesAndNewlines) + "\n"
  }

  /// A comment made at `offset` refers to what was just heard: the sentence that
  /// contains the last character before the offset.
  public static func attachSentence(for offset: Int, in text: NarrationText) -> Int {
    text.sentenceIndex(containing: max(0, offset - 1)) ?? 0
  }

  static func critic(_ c: CommentRecord) -> String {
    let body: String
    switch c.kind {
    case .bookmark:
      body = c.endMediaTime.map { "Bookmark \(TimeFormat.clock(c.mediaTime))–\(TimeFormat.clock($0))" } ?? "Bookmark \(TimeFormat.clock(c.mediaTime))"
    case .comment:
      let t = c.transcript.trimmingCharacters(in: .whitespacesAndNewlines)
      let text = t.isEmpty ? "Voice comment \(TimeFormat.clock(c.mediaTime)) (not transcribed)" : t
      body = c.tag.map { "[\($0.displayName(.english))] \(text)" } ?? text
    }
    let safe = body.replacingOccurrences(of: "<<}", with: "<< }").replacingOccurrences(of: "\n", with: " ")
    return " {>> \(safe) <<}"
  }

  static func headingTitle(_ s: String) -> String {
    var t = s.trimmingCharacters(in: .whitespacesAndNewlines)
    for prefix in ["Section: ", "Abschnitt: "] where t.hasPrefix(prefix) { t = String(t.dropFirst(prefix.count)) }
    if t.hasSuffix(".") { t.removeLast() }
    return t
  }
}
