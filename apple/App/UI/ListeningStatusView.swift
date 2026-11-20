import MarginVoiceCore
import SwiftUI

/// The status pill and what tapping it does: while listening it offers to stop; when
/// listening is paused, off or failed, it fixes that. Its own view, so the microphone
/// level (several updates a second) repaints the pill and nothing around it.
struct ListeningStatusView: View {
  /// The Mac toolbar: the full microphone description and the echo-cancellation state.
  var detailed = false
  @Environment(SessionHost.self) private var session
  @State private var showListenProblem = false
  /// "Comment saved" stays for three seconds after it arrives.
  @State private var showsFlash = false

  var body: some View {
    control
      .task(id: session.flash?.date) {
        guard let date = session.flash?.date else {
          showsFlash = false
          return
        }
        let left = 3 - Date().timeIntervalSince(date)
        showsFlash = left > 0
        guard left > 0 else { return }
        try? await Task.sleep(for: .seconds(left))
        showsFlash = false
      }
      // the pill changes in place: VoiceOver is told, except into a comment being recorded
      .onChange(of: status) { old, new in
        Announce.say(StatusAnnouncement.listening(from: old, to: new, recording: session.isRecording))
      }
      .alert("Voice Commands Unavailable", isPresented: $showListenProblem) {
        Button("Try Again") { Task { await session.startListening() } }
        Button("OK", role: .cancel) {}
      } message: {
        if case .unavailable(let reason) = session.listenState { Text(reason) }
      }
  }

  private var status: ListeningStatus {
    if session.isListeningPaused { return .paused }
    switch session.listenState {
    case .off: return .off
    case .starting: return .starting
    case .listening: return .listening
    case .unavailable: return .unavailable
    }
  }

  @ViewBuilder private var control: some View {
    switch session.listenState {
    case _ where session.isListeningPaused && !session.isRecording:
      Button { Task { await session.startListening() } } label: { pill }
        .buttonStyle(.plain)
        .accessibilityHint("Listens for voice commands again")
        .help("The book was paused for a while, so Klopfgeist stopped listening. Click, or play the book, to listen again.")
    case .listening where !session.isRecording:
      Menu {
        Button("Stop Listening", systemImage: "moon.zzz") { session.pauseListening("stopped from the player") }
        Button("Turn Off Voice Commands", systemImage: "mic.slash") { Task { await session.setVoiceCommands(false) } }
      } label: {
        pill
      }
      .menuStyle(.button)
      .buttonStyle(.plain)
      #if os(macOS)
        .menuIndicator(.hidden)
        .fixedSize()
      #endif
      .accessibilityHint("Offers to stop listening")
      .help("Klopfgeist listens for “stop”, “bookmark” and “back”. Click to stop listening.")
    case .off where !session.isRecording:
      Button { Task { await session.setVoiceCommands(true) } } label: { pill }
        .buttonStyle(.plain)
        .accessibilityHint("Turns voice commands on")
        .help("Say “stop”, “continue”, “bookmark” instead of pressing keys (⇧⌘V)")
    case .unavailable where !session.isRecording:
      Button { showListenProblem = true } label: { pill }
        .buttonStyle(.plain)
        .accessibilityHint("Shows why, and lets you try again")
    default:
      pill
    }
  }

  @ViewBuilder private var pill: some View {
    if session.isRecording {
      StatusPill(label: "Recording", systemName: "mic.fill", tint: Theme.record, level: session.micLevel, pulsing: true, compact: detailed)
    } else if showsFlash, let flash = session.flash {
      StatusPill(label: flash.label, systemName: flash.symbol, tint: flash.isBookmark ? Theme.bookmark : Theme.success, compact: detailed)
    } else if session.isBookmarkOpen {
      StatusPill(label: "Marking a passage", shortLabel: "Passage", systemName: "bookmark.fill", tint: Theme.bookmark, pulsing: true, compact: detailed)
    } else if session.isListeningPaused {
      StatusPill(label: "Listening paused", shortLabel: "Paused", systemName: "moon.zzz", tint: Theme.textSecondary, accessory: .action("Listen"), compact: detailed)
    } else {
      switch session.listenState {
      case .listening:
        StatusPill(
          label: ListeningLabel.text(microphone: session.microphone, echoCancellation: session.echoCancellation, detailed: detailed),
          shortLabel: "Listening", systemName: "waveform", tint: Theme.accent, level: session.micLevel,
          accessory: detailed ? nil : .symbol("chevron.down"), compact: detailed)
      case .starting:
        StatusPill(label: "Preparing voice commands…", shortLabel: "Preparing…", systemName: "hourglass", tint: Theme.textSecondary, compact: detailed)
      case .unavailable:
        StatusPill(label: "Voice commands unavailable", shortLabel: "Unavailable", systemName: "exclamationmark.triangle", tint: Theme.warning, accessory: .symbol("info.circle"), compact: detailed)
      case .off:
        StatusPill(label: "Voice commands off", shortLabel: "Voice off", systemName: "mic.slash", tint: Theme.textSecondary, accessory: .action("Turn On"), compact: detailed)
      }
    }
  }
}
