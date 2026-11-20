import Foundation
import Testing
@testable import MarginVoiceCore

struct UtteranceDetectorTests {
  let rate = 16_000.0

  func tone(_ seconds: Double, amplitude: Float = 0.3) -> [Float] {
    (0..<Int(seconds * rate)).map { amplitude * sin(Float($0) * 0.2) }
  }

  func noise(_ seconds: Double, amplitude: Float = 0.001) -> [Float] {
    (0..<Int(seconds * rate)).map { _ in Float.random(in: -amplitude...amplitude) }
  }

  @Test func detectsIsolatedUtterance() {
    var d = UtteranceDetector(sampleRate: rate)
    var events = d.append(noise(1))
    events += d.append(tone(0.5))
    events += d.append(noise(1))
    #expect(events.count == 2)
    guard case .started(let t, let before) = events[0], case .ended(let end, let dur) = events[1] else {
      Issue.record("unexpected events \(events)")
      return
    }
    #expect(abs(t - 1) < 0.05)
    #expect(before > 0.9)
    #expect(abs(end - 1.5) < 0.05)
    #expect(abs(dur - 0.5) < 0.05)
  }

  @Test func chunkingDoesNotMatter() {
    let signal = noise(0.5) + tone(0.4) + noise(0.6)
    var a = UtteranceDetector(sampleRate: rate)
    var b = UtteranceDetector(sampleRate: rate)
    let whole = a.append(signal)
    var pieces: [UtteranceDetector.Event] = []
    var i = 0
    while i < signal.count {
      let n = min(517, signal.count - i)
      pieces += b.append(Array(signal[i..<(i + n)]))
      i += n
    }
    #expect(whole == pieces)
  }

  @Test func shortGapKeepsOneUtterance() {
    var d = UtteranceDetector(sampleRate: rate)
    let events = d.append(noise(0.5) + tone(0.3) + noise(0.15) + tone(0.3) + noise(0.5))
    #expect(events.filter { if case .started = $0 { true } else { false } }.count == 1)
  }
}
