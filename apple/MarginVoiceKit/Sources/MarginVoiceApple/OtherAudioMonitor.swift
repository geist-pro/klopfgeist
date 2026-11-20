import AVFoundation
#if os(macOS)
  import CoreAudio
#endif

/// Is another app playing sound? The book must not start by itself over it, and
/// the microphone should not keep listening to it (its speech can sound like commands).
@MainActor
final class OtherAudioMonitor {
  #if os(macOS)
    /// Processes already running output when the book paused. Some apps hold an output
    /// open while silent, so only what starts after the pause counts as "started playing".
    private var ignored: Set<pid_t> = []
  #endif

  /// The book paused (or a book opened): what plays from here on is someone else's doing.
  func markBaseline() {
    #if os(macOS)
      ignored = Self.runningOutputProcesses()
    #endif
  }

  func isPlaying() -> Bool {
    #if os(iOS)
      return AVAudioSession.sharedInstance().isOtherAudioPlaying
    #else
      let running = Self.runningOutputProcesses()
      // one that stopped and starts again later has started again
      ignored.formIntersection(running)
      return !running.subtracting(ignored).isEmpty
    #endif
  }

  #if os(macOS)
    /// The processes with audio output running, other than this one.
    private static func runningOutputProcesses() -> Set<pid_t> {
      var address = AudioObjectPropertyAddress(
        mSelector: kAudioHardwarePropertyProcessObjectList, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
      let system = AudioObjectID(kAudioObjectSystemObject)
      var size: UInt32 = 0
      guard AudioObjectGetPropertyDataSize(system, &address, 0, nil, &size) == noErr, size > 0 else { return [] }
      var objects = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
      guard AudioObjectGetPropertyData(system, &address, 0, nil, &size, &objects) == noErr else { return [] }
      let me = ProcessInfo.processInfo.processIdentifier
      var result: Set<pid_t> = []
      for object in objects {
        var running: UInt32 = 0
        var pid: pid_t = -1
        guard read(kAudioProcessPropertyIsRunningOutput, of: object, into: &running), running != 0,
          read(kAudioProcessPropertyPID, of: object, into: &pid), pid != me
        else { continue }
        result.insert(pid)
      }
      return result
    }

    private static func read<T: BitwiseCopyable>(_ selector: AudioObjectPropertySelector, of object: AudioObjectID, into value: inout T) -> Bool {
      var address = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain)
      var size = UInt32(MemoryLayout<T>.size)
      return AudioObjectGetPropertyData(object, &address, 0, nil, &size, &value) == noErr
    }
  #endif
}
