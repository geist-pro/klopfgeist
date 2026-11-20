import AVFoundation

/// Writes microphone buffers to an AAC `.m4a` while a comment is being recorded.
/// `write` is called on the audio thread.
final class CommentRecorder: @unchecked Sendable {
  private let lock = NSLock()
  private var file: AVAudioFile?
  private var frames: AVAudioFramePosition = 0
  private var sampleRate: Double = 48_000
  private var failure: Error?
  /// For a microphone that changed sample rate mid-comment (AirPods connecting).
  private var converter: AVAudioConverter?

  var isRecording: Bool { lock.withLock { file != nil } }

  func start(url: URL, inputFormat: AVAudioFormat) throws {
    let settings: [String: Any] = [
      AVFormatIDKey: kAudioFormatMPEG4AAC,
      AVSampleRateKey: inputFormat.sampleRate,
      AVNumberOfChannelsKey: 1,
      AVEncoderBitRateKey: 64_000,
    ]
    let f = try AVAudioFile(forWriting: url, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)
    lock.withLock {
      file = f
      frames = 0
      failure = nil
      converter = nil
      sampleRate = inputFormat.sampleRate
    }
  }

  func write(_ buffer: AVAudioPCMBuffer) {
    lock.withLock {
      guard let file else { return }
      guard let mono = mono(buffer, format: file.processingFormat) else { return }
      do {
        try file.write(from: mono)
        frames += AVAudioFramePosition(mono.frameLength)
      } catch {
        self.file = nil
        failure = error
      }
    }
  }

  /// Closes the file and returns the recorded duration in seconds, and the write
  /// error if the recording stopped early (what was written up to then is kept).
  @discardableResult
  func stop() -> (duration: Double, error: Error?) {
    lock.withLock {
      file = nil  // AVAudioFile finalizes on release
      return (Double(frames) / sampleRate, failure)
    }
  }

  /// The buffer in the file's format (mono Float32 at the rate the recording started with).
  private func mono(_ buffer: AVAudioPCMBuffer, format: AVAudioFormat) -> AVAudioPCMBuffer? {
    if buffer.format.sampleRate != format.sampleRate {
      if converter?.inputFormat != buffer.format { converter = AVAudioConverter(from: buffer.format, to: format) }
      guard let converter else { return nil }
      let capacity = AVAudioFrameCount((Double(buffer.frameLength) * format.sampleRate / buffer.format.sampleRate).rounded(.up)) + 32
      guard let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return nil }
      let feeder = BufferFeeder(buffer)
      var error: NSError?
      let status = converter.convert(to: out, error: &error) { _, outStatus in feeder.next(outStatus) }
      return status == .error || out.frameLength == 0 ? nil : out
    }
    if buffer.format.channelCount == 1, buffer.format.commonFormat == .pcmFormatFloat32, !buffer.format.isInterleaved {
      return buffer
    }
    guard let src = buffer.floatChannelData, let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: buffer.frameLength),
      let dst = out.floatChannelData
    else { return nil }
    out.frameLength = buffer.frameLength
    dst[0].update(from: src[0], count: Int(buffer.frameLength))
    return out
  }
}
