import MarginVoiceCore
import Foundation

/// The parsed narration text of a book plus its time↔text map.
struct BookContent: Sendable {
  let text: NarrationText
  let map: TimeTextMap
  /// Paragraph offsets that are chapter headings (matched to m4b chapters).
  let headingOffsets: Set<Int>

  var isExact: Bool { map.isExact }

  init(text: NarrationText, chapters: [ChapterMark], duration: Double, sidecar: TimingSidecar?) {
    self.text = text
    if let sidecar, !sidecar.chunks.isEmpty {
      map = .fromSidecar(sidecar.convertedToCharacterOffsets(in: text), textLength: text.count, duration: duration)
    } else {
      map = .fromChapters(chapters, text: text, duration: duration)
    }
    headingOffsets = Set(ChapterMatcher.match(chapters: chapters, text: text).map(\.textOffset))
  }

  func offset(at time: Double) -> Int { map.offset(at: time) }

  func sentenceIndex(at time: Double) -> Int? {
    guard !text.sentences.isEmpty else { return nil }
    return text.sentenceIndex(containing: offset(at: time))
  }

  func sentence(_ index: Int) -> String? {
    guard text.sentences.indices.contains(index) else { return nil }
    return text.substring(text.sentences[index].range)
  }

  /// Start time of the sentence playing at `time`.
  func sentenceStartTime(at time: Double) -> Double {
    guard let i = sentenceIndex(at: time) else { return time }
    return map.time(at: text.sentences[i].range.lowerBound)
  }

  /// What the book narrated in the `seconds` before `time` (for echo rejection).
  func recentText(before time: Double, seconds: Double) -> String {
    let a = offset(at: max(0, time - seconds)), b = offset(at: time)
    // the map is approximate without a sidecar: widen by a sentence's worth
    let pad = isExact ? 20 : 120
    return text.substring(max(0, a - pad)..<min(text.count, b + pad))
  }

  /// The sentence a comment anchored at `offset` refers to.
  func attachedSentence(for offset: Int) -> String? {
    guard !text.sentences.isEmpty else { return nil }
    return sentence(AnnotatedTextExport.attachSentence(for: offset, in: text))
  }
}
