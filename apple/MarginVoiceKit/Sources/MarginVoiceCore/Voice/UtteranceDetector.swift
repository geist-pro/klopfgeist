import Foundation

/// Energy-based speech segmentation with an adaptive noise floor.
///
/// Time comes from sample counts, never a clock, so every decision is a pure
/// function of the samples (same approach as ai-Memo's `TranscriptionWindow`).
/// It reports when an utterance starts and ends. `CommandDetector` uses that only as a
/// hint that someone is still speaking; the recorder uses it to notice speech and silence.
public struct UtteranceDetector: Sendable {
  public struct Policy: Sendable, Equatable {
    public var frameDuration: Double = 0.02
    /// Consecutive speech frames needed to start an utterance.
    public var onsetFrames = 3
    /// Quiet time that ends an utterance.
    public var endSilence: Double = 0.3
    public var absoluteFloor: Float = 1e-5
    /// A frame is speech when its energy exceeds `noiseFloor * multiplier`.
    public var multiplier: Float = 6
    public init() {}
  }

  public enum Event: Sendable, Equatable {
    /// `time` is seconds since the detector started; `pauseBefore` is the silence before it.
    case started(time: Double, pauseBefore: Double)
    case ended(time: Double, duration: Double)
  }

  public let policy: Policy
  public let sampleRate: Double
  public private(set) var noiseFloor: Float
  public private(set) var inUtterance = false
  /// Seconds of silence since the last speech frame.
  public private(set) var currentSilence: Double = 0

  private var frameSize: Int { max(1, Int((sampleRate * policy.frameDuration).rounded())) }
  private var pending: [Float] = []
  private var processedFrames = 0
  private var speechRun = 0
  private var quietRun = 0
  private var utteranceStart = 0.0

  public init(sampleRate: Double, policy: Policy = Policy()) {
    self.sampleRate = sampleRate
    self.policy = policy
    noiseFloor = policy.absoluteFloor
  }

  public var time: Double { Double(processedFrames) * policy.frameDuration }

  public mutating func append(_ samples: [Float]) -> [Event] {
    pending.append(contentsOf: samples)
    var events: [Event] = []
    let n = frameSize
    var consumed = 0
    while pending.count - consumed >= n {
      var energy: Float = 0
      for i in consumed..<(consumed + n) { energy += pending[i] * pending[i] }
      energy /= Float(n)
      consumed += n
      if let e = classify(energy) { events.append(e) }
    }
    pending.removeFirst(consumed)
    return events
  }

  private mutating func classify(_ energy: Float) -> Event? {
    processedFrames += 1
    let threshold = max(policy.absoluteFloor, noiseFloor * policy.multiplier)
    let isSpeech = energy > threshold
    if isSpeech {
      speechRun += 1
      quietRun = 0
      // very slow drift so a raised floor (e.g. residual echo) can recover
      noiseFloor = noiseFloor * 0.999 + energy / policy.multiplier * 0.001
    } else {
      speechRun = 0
      quietRun += 1
      noiseFloor = max(policy.absoluteFloor, noiseFloor * 0.95 + energy * 0.05)
    }
    currentSilence = isSpeech ? 0 : currentSilence + policy.frameDuration

    if !inUtterance, speechRun >= policy.onsetFrames {
      inUtterance = true
      let start = time - Double(policy.onsetFrames) * policy.frameDuration
      // silence before = time since the previous utterance ended (or since start)
      let pauseBefore = start - lastEnd
      utteranceStart = start
      return .started(time: start, pauseBefore: max(0, pauseBefore))
    }
    if inUtterance, Double(quietRun) * policy.frameDuration >= policy.endSilence {
      inUtterance = false
      let end = time - Double(quietRun) * policy.frameDuration
      lastEnd = end
      return .ended(time: end, duration: end - utteranceStart)
    }
    return nil
  }

  private var lastEnd = 0.0
}
