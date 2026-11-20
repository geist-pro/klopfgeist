import AVFoundation
import MarginVoiceCore

/// Short generated tones that tell the driver what happened without looking.
///
/// - record start: rising two-note "ready" (C5 → G5)
/// - saved: falling two-note (G5 → C5)
/// - discarded: two low blips
/// - bookmark: single short tick
/// - bookmark end (passage): two rising ticks
public enum Earcons {
  public static let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1)!

  private struct Note {
    let frequency: Double
    let duration: Double
    var gap: Double = 0.03
  }

  public static func buffer(_ earcon: ReviewMachine.Earcon) -> AVAudioPCMBuffer {
    switch earcon {
    case .recordStart: render([Note(frequency: 523.25, duration: 0.11), Note(frequency: 783.99, duration: 0.16)])
    case .recordSaved: render([Note(frequency: 783.99, duration: 0.11), Note(frequency: 523.25, duration: 0.16)])
    case .recordDiscarded: render([Note(frequency: 220, duration: 0.09, gap: 0.07), Note(frequency: 220, duration: 0.09)])
    case .bookmark: render([Note(frequency: 1318.5, duration: 0.05)])
    case .bookmarkEnd: render([Note(frequency: 1318.5, duration: 0.05, gap: 0.05), Note(frequency: 1760, duration: 0.06)])
    }
  }

  private static func render(_ notes: [Note], amplitude: Float = 0.35) -> AVAudioPCMBuffer {
    let rate = format.sampleRate
    let total = notes.reduce(0) { $0 + $1.duration + $1.gap }
    let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(total * rate) + 1)!
    let out = buffer.floatChannelData![0]
    var i = 0
    for note in notes {
      let n = Int(note.duration * rate)
      let attack = Int(0.008 * rate)
      for k in 0..<n {
        // short attack, exponential decay: a soft "bell" rather than a harsh beep
        let env = Float(min(1, Double(k) / Double(attack))) * Float(exp(-3 * Double(k) / Double(n)))
        let phase = 2 * Double.pi * note.frequency * Double(k) / rate
        out[i] = amplitude * env * Float(sin(phase) + 0.25 * sin(2 * phase))
        i += 1
      }
      for _ in 0..<Int(note.gap * rate) {
        out[i] = 0
        i += 1
      }
    }
    buffer.frameLength = AVAudioFrameCount(i)
    return buffer
  }
}
