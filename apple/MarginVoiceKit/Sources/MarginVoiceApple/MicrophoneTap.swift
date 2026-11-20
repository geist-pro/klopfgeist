import AVFoundation
import MarginVoiceCore

/// The single tap on the engine's input node. Runs the utterance detector and
/// forwards every buffer to the command listener and the comment recorder.
///
/// Called on the audio render thread; state is guarded by a lock.
final class MicrophoneTap: @unchecked Sendable {
  struct Snapshot: Sendable {
    var level: Float = 0
    /// Seconds of microphone audio since the tap was installed.
    var micTime: Double = 0
    var silence: Double = 0
    var inUtterance = false
  }

  typealias Sink = @Sendable (AVAudioPCMBuffer, Double) -> Void

  private let lock = NSLock()
  private var detector: UtteranceDetector?
  private var onEvent: (@Sendable (UtteranceDetector.Event) -> Void)?
  private var sink: Sink?
  private var level: Float = 0
  private weak var node: AVAudioInputNode?
  private var inputFormat: AVAudioFormat?
  /// Channels of the microphone's own format, for the log.
  private(set) var channelCount: AVAudioChannelCount = 0

  var format: AVAudioFormat? { lock.withLock { inputFormat } }

  var isInstalled: Bool { lock.withLock { node != nil } }

  /// Returns the input format, or nil when no microphone is available.
  @discardableResult
  func install(on node: AVAudioInputNode, onEvent: @escaping @Sendable (UtteranceDetector.Event) -> Void, sink: @escaping Sink) -> AVAudioFormat? {
    remove()
    let format = node.outputFormat(forBus: 0)
    // another app holding the microphone (or none at all) yields a 0 Hz format
    guard format.sampleRate > 0, format.channelCount > 0 else { return nil }
    // Everything downstream gets the first channel, which is the voice. With echo cancellation
    // the Mac's input has several channels, and a converter mixing them down delivers silence.
    guard let mono = AVAudioFormat(standardFormatWithSampleRate: format.sampleRate, channels: 1) else { return nil }
    channelCount = format.channelCount
    lock.withLock {
      detector = UtteranceDetector(sampleRate: format.sampleRate)
      self.onEvent = onEvent
      self.sink = sink
      inputFormat = mono
      self.node = node
    }
    node.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
      self?.process(buffer)
    }
    return mono
  }

  func remove() {
    let node = lock.withLock { () -> AVAudioInputNode? in
      let n = self.node
      self.node = nil
      detector = nil
      sink = nil
      onEvent = nil
      return n
    }
    node?.removeTap(onBus: 0)
  }

  func snapshot() -> Snapshot {
    lock.withLock {
      guard let d = detector else { return Snapshot() }
      return Snapshot(level: level, micTime: d.time, silence: d.currentSilence, inUtterance: d.inUtterance)
    }
  }

  private func firstChannel(of buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
    if buffer.format.channelCount == 1, !buffer.format.isInterleaved, buffer.format.commonFormat == .pcmFormatFloat32 { return buffer }
    guard let format = lock.withLock({ inputFormat }), let source = buffer.floatChannelData,
      let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: buffer.frameLength), let target = out.floatChannelData
    else { return nil }
    out.frameLength = buffer.frameLength
    target[0].update(from: source[0], count: Int(buffer.frameLength))
    return out
  }

  private func process(_ buffer: AVAudioPCMBuffer) {
    guard let channel = buffer.floatChannelData?[0] else { return }
    let n = Int(buffer.frameLength)
    let samples = Array(UnsafeBufferPointer(start: channel, count: n))
    var meanSquare: Float = 0
    for s in samples { meanSquare += s * s }
    meanSquare /= Float(max(1, n))

    let (events, handler, sink, startTime) = lock.withLock { () -> ([UtteranceDetector.Event], (@Sendable (UtteranceDetector.Event) -> Void)?, Sink?, Double) in
      let start = detector?.time ?? 0
      let events = detector?.append(samples) ?? []
      // RMS ×10, smoothed, for the level meter
      level = level * 0.6 + min(1, sqrt(meanSquare) * 10) * 0.4
      return (events, onEvent, self.sink, start)
    }
    if let sink, let mono = firstChannel(of: buffer) { sink(mono, startTime) }
    for e in events { handler?(e) }
  }
}
