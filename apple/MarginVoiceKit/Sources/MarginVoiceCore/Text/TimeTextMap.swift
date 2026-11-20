import Foundation

/// A chapter marker read from the m4b (media time, seconds).
public struct ChapterMark: Codable, Sendable, Equatable, Hashable {
  public var title: String
  public var start: Double
  public var end: Double

  public init(title: String, start: Double, end: Double) {
    self.title = title
    self.start = start
    self.end = end
  }
}

/// Matches m4b chapters to heading lines in the narration text.
public enum ChapterMatcher {
  public struct Match: Sendable, Equatable {
    public let chapterIndex: Int
    public let textOffset: Int
  }

  public static func normalizeTitle(_ s: String) -> String {
    let folded = TextSegmentation.normalize(s)
    let kept = folded.unicodeScalars.map { CharacterSet.alphanumerics.contains($0) ? Character($0) : " " }
    return String(kept).split(separator: " ").joined(separator: " ")
  }

  static let abstractAliases: Set<String> = ["abstract", "zusammenfassung", "summary"]

  static func titlesMatch(_ a: String, _ b: String) -> Bool {
    if a == b { return true }
    if abstractAliases.contains(a) && abstractAliases.contains(b) { return true }
    // doc2audio truncates chapter titles to 120 characters
    let shorter = a.count < b.count ? a : b
    let longer = a.count < b.count ? b : a
    return shorter.count >= 20 && longer.hasPrefix(shorter)
  }

  /// Greedy in-order matching. Chapters that cannot be found are skipped.
  public static func match(chapters: [ChapterMark], text: NarrationText) -> [Match] {
    let headings = text.headings.map { (offset: $0.offset, title: normalizeTitle($0.title)) }
    var result: [Match] = []
    var cursor = 0
    for (ci, ch) in chapters.enumerated() {
      let wanted = normalizeTitle(ch.title)
      guard !wanted.isEmpty else { continue }
      var j = cursor
      while j < headings.count, !titlesMatch(headings[j].title, wanted) { j += 1 }
      if j < headings.count {
        result.append(Match(chapterIndex: ci, textOffset: headings[j].offset))
        cursor = j + 1
      }
    }
    return result
  }
}

/// Piecewise-linear mapping between book media time and character offsets.
public struct TimeTextMap: Sendable, Equatable {
  public struct Anchor: Sendable, Equatable {
    public let time: Double
    public let offset: Int
    public init(time: Double, offset: Int) {
      self.time = time
      self.offset = offset
    }
  }

  public let anchors: [Anchor]
  public let isExact: Bool

  public init(anchors raw: [Anchor], duration: Double, textLength: Int, isExact: Bool = false) {
    var pts = raw.filter { $0.time >= 0 && $0.offset >= 0 && $0.offset <= textLength }
    pts.sort { $0.time < $1.time }
    var mono: [Anchor] = [Anchor(time: 0, offset: 0)]
    for p in pts where p.time > mono.last!.time && p.offset >= mono.last!.offset {
      mono.append(p)
    }
    if duration > mono.last!.time {
      mono.append(Anchor(time: duration, offset: max(textLength, mono.last!.offset)))
    }
    anchors = mono
    self.isExact = isExact
  }

  /// Build from m4b chapters matched against the text (approximate).
  public static func fromChapters(_ chapters: [ChapterMark], text: NarrationText, duration: Double) -> TimeTextMap {
    let matches = ChapterMatcher.match(chapters: chapters, text: text)
    let anchors = matches.map { Anchor(time: chapters[$0.chapterIndex].start, offset: $0.textOffset) }
    return TimeTextMap(anchors: anchors, duration: duration, textLength: text.count)
  }

  /// Build from a doc2audio timing sidecar (exact at chunk boundaries).
  public static func fromSidecar(_ sidecar: TimingSidecar, textLength: Int, duration: Double) -> TimeTextMap {
    var anchors: [Anchor] = []
    for c in sidecar.chunks {
      anchors.append(Anchor(time: c.start, offset: c.charStart))
      anchors.append(Anchor(time: c.end, offset: c.charEnd))
    }
    return TimeTextMap(anchors: anchors, duration: max(duration, sidecar.chunks.last?.end ?? 0),
                       textLength: textLength, isExact: true)
  }

  public func offset(at time: Double) -> Int {
    guard let first = anchors.first else { return 0 }
    if time <= first.time { return first.offset }
    for i in 1..<anchors.count where time <= anchors[i].time {
      let a = anchors[i - 1], b = anchors[i]
      let f = (time - a.time) / max(b.time - a.time, 1e-9)
      return a.offset + Int((Double(b.offset - a.offset) * f).rounded())
    }
    return anchors.last!.offset
  }

  public func time(at offset: Int) -> Double {
    guard let first = anchors.first else { return 0 }
    if offset <= first.offset { return first.time }
    for i in 1..<anchors.count where offset <= anchors[i].offset {
      let a = anchors[i - 1], b = anchors[i]
      let span = b.offset - a.offset
      if span == 0 { return a.time }
      let f = Double(offset - a.offset) / Double(span)
      return a.time + (b.time - a.time) * f
    }
    return anchors.last!.time
  }
}
