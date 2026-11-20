import Foundation
import NaturalLanguage

extension VoiceCommand.Language {
  /// The language of a book's `language` code ("de", "de-DE"); anything else is read as English.
  public init(code: String?) {
    self = code?.lowercased().hasPrefix("de") == true ? .german : .english
  }

  /// Region used for speech recognition and voices ("en-US", "de-DE").
  public var regionIdentifier: String {
    switch self {
    case .english: "en-US"
    case .german: "de-DE"
    }
  }

  public var locale: Locale { Locale(identifier: regionIdentifier) }

  public var displayName: String {
    switch self {
    case .english: "English"
    case .german: "Deutsch"
    }
  }

  /// English or German, whichever the text is closer to.
  public static func detect(_ text: String) -> Self {
    let recognizer = NLLanguageRecognizer()
    recognizer.languageConstraints = [.english, .german]
    recognizer.processString(String(text.prefix(6000)))
    return recognizer.dominantLanguage == .german ? .german : .english
  }
}

/// Which files make a book, and how they pair up by name.
public enum BookFiles {
  public static let audioExtensions: Set<String> = ["m4b", "m4a", "mp3", "aac", "wav"]
  /// Documents that can be turned into a book on the device.
  public static let documentExtensions: Set<String> = ["pdf", "txt", "md", "markdown", "rtf", "rtfd"]

  public static func isAudio(_ url: URL) -> Bool { audioExtensions.contains(url.pathExtension.lowercased()) }
  public static func isDocument(_ url: URL) -> Bool { documentExtensions.contains(url.pathExtension.lowercased()) }

  /// `x.m4b`, `x.txt`, `x.narration.txt` and `x.timing.json` all have the stem `x`.
  public static func stem(of url: URL) -> String {
    var name = url.deletingPathExtension().lastPathComponent
    for suffix in [".narration", ".timing"] where name.hasSuffix(suffix) { name = String(name.dropLast(suffix.count)) }
    return name
  }

  public struct Group: Equatable, Sendable {
    public var stem: String
    public var audio: URL
    public var text: URL?
    public var timing: URL?
  }

  /// One group per audio file, sorted by stem, plus the stems that have no audio.
  public static func group(_ urls: [URL]) -> (books: [Group], orphans: [String]) {
    let byStem = Dictionary(grouping: urls, by: stem(of:))
    var books: [Group] = []
    var orphans: [String] = []
    for (stem, files) in byStem.sorted(by: { $0.key < $1.key }) {
      guard let audio = files.first(where: isAudio) else {
        orphans.append(stem)
        continue
      }
      books.append(Group(
        stem: stem, audio: audio,
        text: files.first { $0.lastPathComponent.lowercased().hasSuffix(".narration.txt") }
          ?? files.first { $0.pathExtension.lowercased() == "txt" },
        timing: files.first { $0.pathExtension.lowercased() == "json" }))
    }
    return (books, orphans)
  }

  /// Splits an inbox listing into files that complete an audiobook and documents to convert.
  public static func sortInbox(_ urls: [URL]) -> (audiobookFiles: [URL], documents: [URL]) {
    let stemsWithAudio = Set(urls.filter(isAudio).map(stem(of:)))
    let audiobook = urls.filter { stemsWithAudio.contains(stem(of: $0)) }
    let documents = urls
      .filter { isDocument($0) && !stemsWithAudio.contains(stem(of: $0)) }
      .sorted { $0.lastPathComponent < $1.lastPathComponent }
    return (audiobook, documents)
  }

  /// A file-system-safe name for exports: letters, digits, `-` and `_`, at most 60 characters.
  public static func safeName(_ title: String) -> String {
    let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_ "))
    let cleaned = String(title.unicodeScalars.map { allowed.contains($0) ? Character($0) : "-" })
    return String(cleaned.prefix(60)).trimmingCharacters(in: .whitespaces).replacingOccurrences(of: " ", with: "-")
  }
}

extension Array where Element == ChapterMark {
  /// The chapter playing at `time` (a chapter starts counting 50 ms early).
  public func index(at time: Double) -> Int? {
    lastIndex { $0.start <= time + 0.05 }
  }
}

extension CommentRecord {
  /// Text offset after moving `sentenceNudge` sentences from `base`: the end of the target sentence.
  public static func nudgedOffset(_ base: Int, by sentenceNudge: Int, in text: NarrationText?) -> Int {
    guard sentenceNudge != 0, let text, !text.sentences.isEmpty else { return base }
    let current = AnnotatedTextExport.attachSentence(for: base, in: text)
    let target = Swift.min(Swift.max(0, current + sentenceNudge), text.sentences.count - 1)
    return text.sentences[target].range.upperBound
  }

  /// End of a bookmarked passage, moved by the same amount as the refined start,
  /// and never before it.
  public static func passageEnd(estimatedEnd: Int, estimatedStart: Int?, refinedStart: Int?) -> Int {
    guard let refinedStart, let estimatedStart else { return estimatedEnd }
    return Swift.max(refinedStart, estimatedEnd + refinedStart - estimatedStart)
  }
}
