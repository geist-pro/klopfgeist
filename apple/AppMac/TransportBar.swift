import MarginVoiceCore
import SwiftUI

/// Playback along the bottom of the window, compact: back 15 s, Play, Comment, the
/// position with its markers between the elapsed and remaining time, and the speed.
/// Repeat, Bookmark and the rest are in the Playback menu, each with a shortcut.
struct TransportBar: View {
  let book: Book
  @Environment(SessionHost.self) private var session

  var body: some View {
    HStack(spacing: 14) {
      if session.isRecording {
        RoundButton(title: "Discard Comment", systemName: "trash", tint: Theme.record, help: "Discard the comment (⌘⌫)") {
          session.discardRecording()
        }
      } else {
        RoundButton(title: "Back 15 Seconds", systemName: "gobackward.15", help: "Back 15 seconds (⌘←)") { session.skipBack() }
      }

      BigControlButton(
        systemName: session.isPlaying ? "pause.fill" : "play.fill",
        label: session.isPlaying ? "Pause" : "Play", tint: Theme.accent, size: 48, prominent: true, glyph: Theme.onAccent,
        startsMedia: !session.isPlaying
      ) { session.togglePlay() }
      .focusable(false)
      .help(session.isPlaying ? "Pause (Space)" : "Play (Space)")

      commentButton

      TransportPosition(book: book)

      SpeedMenu()
    }
    .padding(.horizontal, 24)
    .padding(.vertical, 14)
    .background(Theme.surface)
    .overlay(alignment: .top) { ThemedDivider() }
  }

  /// A pill with the microphone, filled in the record colour, so it reads as the one action of the app.
  private var commentButton: some View {
    let recording = session.isRecording
    return Button {
      session.commentButton()
    } label: {
      Label(recording ? "Save" : "Comment", systemImage: recording ? "stop.fill" : "mic.fill")
        .font(.callout.weight(.bold))
        .foregroundStyle(Theme.onAccent)
        .padding(.horizontal, 14)
        .frame(minHeight: 36)
        .background(Theme.record, in: .capsule)
        .contentShape(.capsule)
    }
    .buttonStyle(.plain)
    .focusable(false)
    .fixedSize()
    .help(recording ? "Save the comment and play on (C or ⌘R)" : "Pause and record a spoken comment here (C or ⌘R)")
    .accessibilityLabel(recording ? "Save comment" : "Add comment")
    .accessibilityHint(recording ? "Saves the comment and plays on" : "Pauses the book and records a spoken comment here")
  }
}

/// Elapsed time, the scrubber and the remaining time: the part that follows the playhead,
/// in a view of its own so the buttons are not rebuilt ten times a second.
private struct TransportPosition: View {
  let book: Book
  @Environment(SessionHost.self) private var session

  var body: some View {
    let time = session.currentTime
    HStack(spacing: 10) {
      Text(TimeFormat.clock(time))
        .accessibilityHidden(true)
      ScrubberView(
        time: time, duration: session.duration, chapters: book.chapters, markers: book.scrubberMarkers, size: .compact,
        onSeek: { session.seek(to: $0) })
      Text("−" + TimeFormat.clock(max(0, session.duration - time)))
        .accessibilityHidden(true)
    }
    .font(.caption.monospacedDigit())
    .foregroundStyle(Theme.textSecondary)
    .frame(maxWidth: .infinity)
  }
}

/// A small round transport control.
private struct RoundButton: View {
  let title: String
  let systemName: String
  var tint = Theme.textPrimary
  let help: String
  let action: () -> Void

  var body: some View {
    Button(action: action) {
      Label(title, systemImage: systemName)
        .labelStyle(.iconOnly)
        .font(.system(size: 14, weight: .semibold))
        .foregroundStyle(tint)
        .frame(width: 34, height: 34)
        .background(Theme.surfaceBorder, in: .circle)
        .contentShape(.circle)
    }
    .buttonStyle(.plain)
    .focusable(false)
    .help(help)
    .accessibilityLabel(title)
  }
}

/// A pop-up of speeds; a rate set by voice or ⌘↑/⌘↓ that is not in the list shows as its own entry.
private struct SpeedMenu: View {
  private static let speeds: [Double] = [0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.75, 2.0, 2.5]
  @Environment(SessionHost.self) private var session

  var body: some View {
    let rate = session.rate
    let options = Self.speeds.contains(rate) ? Self.speeds : (Self.speeds + [rate]).sorted()
    Menu {
      Picker("Speed", selection: Binding(get: { rate }, set: { session.setRate($0) })) {
        ForEach(options, id: \.self) { Text(Self.label($0)).monospacedDigit().tag($0) }
      }
      .pickerStyle(.inline)
      .labelsHidden()
    } label: {
      // the chevron says it is a menu: the outline alone looks like a value
      HStack(spacing: 4) {
        Text(Self.label(rate)).monospacedDigit()
        Image(systemName: "chevron.down").font(.caption2.weight(.bold)).foregroundStyle(Theme.textSecondary)
      }
        .font(.callout.weight(.semibold))
        .foregroundStyle(Theme.textPrimary)
        .padding(.horizontal, 8)
        .frame(minWidth: 44, minHeight: 28)
        .background(Theme.background, in: .rect(cornerRadius: 6))
        .overlay { RoundedRectangle(cornerRadius: 6).stroke(Theme.control, lineWidth: 1) }
        .contentShape(.rect(cornerRadius: 6))
    }
    .menuStyle(.button)
    .buttonStyle(.plain)
    .menuIndicator(.hidden)
    .fixedSize()
    .help("Playback speed (⌘↑ / ⌘↓)")
    .accessibilityLabel("Playback speed")
    .accessibilityValue(Self.label(rate))
  }

  static func label(_ r: Double) -> String {
    r == r.rounded() ? String(format: "%.0f×", r) : String(format: "%g×", r)
  }
}
