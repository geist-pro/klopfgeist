import AVFoundation
import MarginVoiceCore
import Testing

@testable import MarginVoiceApple

@Suite struct EarconsAndRecorderTests {
  @Test func everyEarconIsAShortSound() {
    for earcon in [ReviewMachine.Earcon.recordStart, .recordSaved, .recordDiscarded, .bookmark, .bookmarkEnd] {
      let buffer = Earcons.buffer(earcon)
      let seconds = Double(buffer.frameLength) / buffer.format.sampleRate
      #expect(seconds > 0.03 && seconds < 0.6)
      let samples = UnsafeBufferPointer(start: buffer.floatChannelData![0], count: Int(buffer.frameLength))
      #expect(samples.contains { abs($0) > 0.05 })
      #expect(samples.allSatisfy { abs($0) <= 1 })
    }
  }

  private func tone(rate: Double, seconds: Double) -> AVAudioPCMBuffer {
    let format = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 1)!
    let n = AVAudioFrameCount(rate * seconds)
    let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: n)!
    buffer.frameLength = n
    for i in 0..<Int(n) { buffer.floatChannelData![0][i] = Float(sin(2 * Double.pi * 440 * Double(i) / rate)) * 0.3 }
    return buffer
  }

  /// AirPods connecting mid-comment change the microphone's sample rate: the recording goes on.
  @Test func recorderSurvivesASampleRateChange() throws {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("recorder-\(UUID().uuidString).m4a")
    defer { try? FileManager.default.removeItem(at: url) }
    let recorder = CommentRecorder()
    try recorder.start(url: url, inputFormat: AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1)!)
    #expect(recorder.isRecording)
    recorder.write(tone(rate: 48_000, seconds: 0.5))
    recorder.write(tone(rate: 24_000, seconds: 0.5))
    let (duration, error) = recorder.stop()
    #expect(error == nil)
    #expect(abs(duration - 1.0) < 0.05)
    #expect(!recorder.isRecording)
    let file = try AVAudioFile(forReading: url)
    #expect(file.length > 0)
  }

  @Test func bufferFeederHandsItsBufferOverOnce() {
    let feeder = BufferFeeder(tone(rate: 16_000, seconds: 0.01))
    var status = AVAudioConverterInputStatus.endOfStream
    #expect(feeder.next(&status) != nil)
    #expect(status == .haveData)
    #expect(feeder.next(&status) == nil)
    #expect(status == .noDataNow)
  }
}
