import MarginVoiceCore
import SwiftUI

/// The open book at the bottom of the library on iPhone: back to the player with
/// one tap, play/pause and comment without opening it, and close it (which lets
/// go of the microphone). The symbol shows whether it is listening.
struct NowPlayingBar: View {
  let book: Book
  let onOpen: () -> Void
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(SessionHost.self) private var session

  var body: some View {
    let time = session.currentTime
    HStack(spacing: 12) {
      Button(action: onOpen) {
        HStack(spacing: 12) {
          Image(systemName: isListening ? "waveform" : session.isListeningPaused ? "moon.zzz" : "speaker.wave.2")
            // a symbol that loops for as long as the book plays stands still with Reduce Motion
            .symbolEffect(.variableColor.iterative, isActive: session.isPlaying && !reduceMotion)
            .foregroundStyle(isListening ? Theme.accent : Theme.textSecondary)
            .frame(width: 28)
            .contentTransition(.symbolEffect(.replace))
          VStack(alignment: .leading, spacing: 2) {
            Text(book.title)
              .font(.subheadline.weight(.semibold))
              .foregroundStyle(Theme.textPrimary)
              .lineLimit(1)
            Text(subtitle(at: time))
              .font(.caption)
              .foregroundStyle(Theme.textSecondary)
              .lineLimit(1)
          }
          Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
      }
      .buttonStyle(.plain)
      .accessibilityLabel("Now playing: \(book.title)\(isListening ? ", listening" : session.isListeningPaused ? ", listening paused" : "")")
      .accessibilityHint("Opens the player")

      Button(session.isPlaying ? "Pause" : "Play", systemImage: session.isPlaying ? "pause.fill" : "play.fill") {
        session.togglePlay()
      }
      .labelStyle(.iconOnly)
      .font(.title3)
      .foregroundStyle(Theme.textPrimary)
      .frame(width: 44, height: 44)
      .contentTransition(.symbolEffect(.replace))

      Button(session.isRecording ? "Save Comment" : "Add Comment", systemImage: session.isRecording ? "stop.fill" : "mic.fill") {
        session.commentButton()
      }
      .labelStyle(.iconOnly)
      .font(.title3)
      .foregroundStyle(Theme.record)
      .frame(width: 44, height: 44)

      Button("Close Book", systemImage: "xmark") {
        withAnimation { session.close() }
      }
      .labelStyle(.iconOnly)
      .font(.body.weight(.semibold))
      .foregroundStyle(Theme.textSecondary)
      .frame(width: 44, height: 44)
      .accessibilityHint("Stops the book and the microphone")
    }
    .padding(.leading, 16)
    .padding(.trailing, 4)
    .padding(.vertical, 6)
    .glassEffect(.regular.interactive(), in: .capsule)
    .padding(.horizontal, 16)
    .padding(.bottom, 4)
  }

  private var isListening: Bool { session.listenState == .listening && !session.isListeningPaused }

  private func subtitle(at time: Double) -> String {
    let left = TimeFormat.clock(max(0, session.duration - time)) + " left"
    guard let chapter = book.chapterTitle(at: time) else { return left }
    return "\(chapter) · \(left)"
  }
}
