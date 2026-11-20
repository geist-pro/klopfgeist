import Foundation

/// Finds where a short transcript of book audio sits in the narration text.
///
/// Used to refine a comment anchor: the ~20 s of book audio before the comment is
/// transcribed on device and aligned against the text around the estimated offset.
/// Local alignment (Smith–Waterman) over normalized word tokens tolerates recognizer
/// errors, dropped words and a cut-off first or last word.
public enum ContextAligner {
  public struct Result: Sendable, Equatable {
    /// Character offset just after the last matched word.
    public let offset: Int
    /// Matched text words (indices into `NarrationText.words`).
    public let wordRange: Range<Int>
    /// Fraction of transcript tokens that matched a text word, 0...1.
    public let confidence: Double
  }

  static let matchScore = 2
  static let mismatchScore = -1
  static let gapScore = -1

  /// - Parameters:
  ///   - transcript: recognizer output for the audio that ends at the anchor.
  ///   - estimate: estimated character offset of the anchor.
  ///   - window: characters searched on each side of `estimate`.
  ///   - minimumMatches: fewer matched words than this returns `nil`.
  public static func align(
    transcript: String, in text: NarrationText, near estimate: Int,
    window: Int = 2_000, minimumMatches: Int = 3
  ) -> Result? {
    let query = TextSegmentation.tokens(transcript)
    guard !query.isEmpty, !text.words.isEmpty else { return nil }
    let lo = text.wordIndex(atOrAfter: max(0, estimate - window))
    let hi = min(text.words.count, text.wordIndex(atOrAfter: estimate + window))
    guard lo < hi else { return nil }
    let target = text.words[lo..<hi].map(\.normalized)

    let m = query.count, n = target.count
    // score and traceback matrices, (m+1) x (n+1), row-major
    var score = [Int](repeating: 0, count: (m + 1) * (n + 1))
    var matches = [Int](repeating: 0, count: (m + 1) * (n + 1))
    var start = [Int](repeating: 0, count: (m + 1) * (n + 1))
    func idx(_ i: Int, _ j: Int) -> Int { i * (n + 1) + j }

    var best = (score: 0, j: 0, matches: 0, start: 0)
    var bestDistance = Int.max
    for i in 1...m {
      for j in 1...n {
        let same = wordsMatch(query[i - 1], target[j - 1])
        let diag = score[idx(i - 1, j - 1)] + (same ? matchScore : mismatchScore)
        let up = score[idx(i - 1, j)] + gapScore
        let left = score[idx(i, j - 1)] + gapScore
        let s = max(0, diag, up, left)
        let k = idx(i, j)
        score[k] = s
        guard s > 0 else { start[k] = j; continue }
        if s == diag {
          let p = idx(i - 1, j - 1)
          matches[k] = matches[p] + (same ? 1 : 0)
          start[k] = score[p] == 0 ? j - 1 : start[p]
        } else if s == up {
          matches[k] = matches[idx(i - 1, j)]
          start[k] = start[idx(i - 1, j)]
        } else {
          matches[k] = matches[idx(i, j - 1)]
          start[k] = start[idx(i, j - 1)]
        }
        // prefer the highest score; on ties, the end closest to the estimate
        let endOffset = text.words[lo + j - 1].range.upperBound
        let distance = abs(endOffset - estimate)
        if s > best.score || (s == best.score && distance < bestDistance) {
          best = (s, j, matches[k], start[k])
          bestDistance = distance
        }
      }
    }
    guard best.score > 0, best.matches >= min(minimumMatches, m) else { return nil }
    let first = lo + best.start
    let last = lo + best.j  // exclusive
    return Result(
      offset: text.words[last - 1].range.upperBound,
      wordRange: first..<last,
      confidence: min(1, Double(best.matches) / Double(m))
    )
  }

  /// Exact match, or one edit apart for words of five or more letters.
  static func wordsMatch(_ a: String, _ b: String) -> Bool {
    if a == b { return true }
    guard a.count >= 5, b.count >= 5, abs(a.count - b.count) <= 1 else { return false }
    return editDistance(Array(a), Array(b), limit: 1) <= 1
  }

  static func editDistance(_ a: [Character], _ b: [Character], limit: Int) -> Int {
    var prev = Array(0...b.count)
    var cur = [Int](repeating: 0, count: b.count + 1)
    for i in 1...a.count {
      cur[0] = i
      var rowMin = cur[0]
      for j in 1...b.count {
        cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] == b[j - 1] ? 0 : 1))
        rowMin = min(rowMin, cur[j])
      }
      if rowMin > limit { return limit + 1 }
      swap(&prev, &cur)
    }
    return prev[b.count]
  }
}
