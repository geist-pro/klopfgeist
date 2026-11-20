/// Where playback resumes so that it starts after a pause in the narration. Sentence times are
/// interpolated inside a timing chunk (a chunk can be one 20 s sentence), so a resume point taken
/// from them often lands inside a word: a fragment, the pause, then the text. The audio knows better.
/// Pinned by `spec/vectors/quiet-point.json`.
public enum QuietPoint {
  /// Length of one loudness frame, seconds.
  public static let frame = 0.02
  /// How far before and after the wanted time a pause may end.
  public static let before = 3.0
  public static let after = 1.0
  public static let minPause = 0.15
  /// A frame is quiet below this share of the loudest frame (about −30 dB).
  public static let quietShare = 0.03
  /// Playback starts this long before the pause ends.
  public static let lead = 0.1

  /// - Parameter levels: linear loudness (RMS) of consecutive `frame`s, the first starting at `start`.
  /// - Returns: the time to resume at, or nil when no pause ends near `target`.
  public static func find(levels: [Double], start: Double, target: Double) -> Double? {
    guard let peak = levels.max(), peak > 0 else { return nil }
    let quiet = peak * quietShare
    var best: Double?
    var bestDistance = Double.greatestFiniteMagnitude
    var i = 0
    while i < levels.count {
      if levels[i] >= quiet { i += 1; continue }
      var j = i
      while j < levels.count, levels[j] < quiet { j += 1 }
      // a pause still running at the end of the window has no end to start from
      if j < levels.count {
        let from = start + Double(i) * frame
        let to = start + Double(j) * frame
        let distance = abs(to - target)
        if to - from >= minPause - 1e-9, to >= target - before, to <= target + after + 1e-9, distance < bestDistance {
          best = max(from, to - lead)
          bestDistance = distance
        }
      }
      i = j
    }
    return best
  }

  /// RMS per `frame` of mono samples.
  public static func levels(samples: [Float], sampleRate: Double) -> [Double] {
    let size = Int(sampleRate * frame)
    guard size > 0 else { return [] }
    return (0..<samples.count / size).map { f in
      var sum = 0.0
      for k in f * size..<(f + 1) * size { sum += Double(samples[k]) * Double(samples[k]) }
      return (sum / Double(size)).squareRoot()
    }
  }
}
