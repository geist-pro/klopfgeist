import Foundation

/// Comment lists: Markdown for reading, CSV for spreadsheets, JSON for tools.
public enum CommentExport {
  public static func markdown(title: String, comments: [CommentRecord], text: NarrationText?) -> String {
    var out = "# Comments: \(title)\n\n"
    let sorted = comments.sorted { $0.mediaTime < $1.mediaTime }
    if sorted.isEmpty { return out + "No comments yet.\n" }
    for (i, c) in sorted.enumerated() {
      var header = "## \(i + 1). \(TimeFormat.clock(c.mediaTime))"
      if let chapter = c.chapter, !chapter.isEmpty { header += " · \(chapter)" }
      if let tag = c.tag { header += " · \(tag.displayName(.english))" }
      if c.kind == .bookmark { header += c.endMediaTime.map { " · Bookmark to \(TimeFormat.clock($0))" } ?? " · Bookmark" }
      out += header + "\n\n"
      if let text, !text.sentences.isEmpty {
        let marker = c.confidence < AnnotatedTextExport.lowConfidence ? " *(position estimated)*" : ""
        out += "> \(c.quotedText(in: text))\(marker)\n\n"
      }
      let transcript = c.transcript.trimmingCharacters(in: .whitespacesAndNewlines)
      if c.kind == .comment {
        out += (transcript.isEmpty ? "*(not transcribed)*" : transcript) + "\n\n"
      }
      if let audio = c.audioFile { out += "Audio: [\(audio)](\(audio))\n\n" }
    }
    return out
  }

  public static func csv(comments: [CommentRecord], text: NarrationText?) -> String {
    var rows = ["index,time,seconds,chapter,kind,tag,confidence,sentence,comment,audio"]
    for (i, c) in comments.sorted(by: { $0.mediaTime < $1.mediaTime }).enumerated() {
      var sentence = ""
      if let text, !text.sentences.isEmpty {
        sentence = c.quotedText(in: text)
      }
      let fields = [
        String(i + 1), TimeFormat.clock(c.mediaTime), String(format: "%.1f", c.mediaTime), c.chapter ?? "",
        c.kind.rawValue, c.tag?.rawValue ?? "", String(format: "%.2f", c.confidence), sentence, c.transcript, c.audioFile ?? "",
      ]
      rows.append(fields.map(quote).joined(separator: ","))
    }
    return rows.joined(separator: "\n") + "\n"
  }

  public static func json(title: String, comments: [CommentRecord]) throws -> Data {
    struct Payload: Encodable {
      let title: String
      let comments: [CommentRecord]
    }
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
    encoder.dateEncodingStrategy = .iso8601
    return try encoder.encode(Payload(title: title, comments: comments.sorted { $0.mediaTime < $1.mediaTime }))
  }

  static func quote(_ field: String) -> String {
    // a transcript starting with = + - @ would run as a spreadsheet formula
    let field = field.first.map { "=+-@".contains($0) } == true ? "'" + field : field
    guard field.contains(where: { $0 == "," || $0 == "\"" || $0.isNewline }) else { return field }
    return "\"" + field.replacingOccurrences(of: "\"", with: "\"\"") + "\""
  }
}
