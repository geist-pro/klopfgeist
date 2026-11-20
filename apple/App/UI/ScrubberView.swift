import MarginVoiceCore
import SwiftUI

/// Playback position with chapter ticks, a dot above the track for every comment (record
/// colour) and a flag for every bookmark (bookmark colour); a bookmarked passage also spans the track.
struct ScrubberView: View {
  enum Size {
    /// iPad and iPhone: a thumb-sized track with the times below it.
    case regular
    /// The Mac transport bar: a thin track; the bar shows the times beside it.
    case compact
  }

  let time: Double
  let duration: Double
  let chapters: [ChapterMark]
  let markers: [ReviewTimeline.Marker]
  var size = Size.regular
  let onSeek: (Double) -> Void

  /// Resets by itself when the system cancels the drag (back swipe, alert).
  @GestureState private var dragTime: Double?

  private var track: CGFloat { size == .regular ? 3 : 4 }
  private var thumb: CGFloat { size == .regular ? 18 : 12 }
  private var height: CGFloat { size == .regular ? 44 : 24 }

  var body: some View {
    VStack(spacing: 6) {
      GeometryReader { geo in
        let w = geo.size.width
        let shown = dragTime ?? time
        ZStack(alignment: .leading) {
          // the control colour: the border colour is too close to the background to see the track
          Capsule().fill(Theme.control).frame(height: track)
          Capsule().fill(size == .regular ? Theme.textPrimary : Theme.accent).frame(width: x(shown, w), height: track)
          ScrubberMarks(chapters: chapters, markers: markers, duration: duration, width: w, track: track, compact: size == .compact)
          let grown = dragTime == nil ? thumb : thumb + 6
          Circle()
            .fill(Theme.textPrimary)
            .frame(width: grown, height: grown)
            .offset(x: x(shown, w) - grown / 2)
            .animation(.snappy(duration: 0.15), value: dragTime == nil)
        }
        .frame(height: height)
        .contentShape(Rectangle())
        .gesture(
          DragGesture(minimumDistance: 0)
            .updating($dragTime) { value, state, _ in state = seekTime(value.location.x, w) }
            .onEnded { onSeek(seekTime($0.location.x, w)) }
        )
      }
      .frame(height: height)
      if size == .regular {
        // the position as the big numeral, the time left beside it in the thin cut
        HStack(alignment: .firstTextBaseline, spacing: 10) {
          Text(TimeFormat.clock(dragTime ?? time))
            .font(.system(.largeTitle, weight: .heavy).monospacedDigit())
            .foregroundStyle(Theme.textPrimary)
          Text("−" + TimeFormat.clock(max(0, duration - (dragTime ?? time))))
            .font(.system(.title2, weight: .light).monospacedDigit())
            .foregroundStyle(Theme.textSecondary)
          Spacer(minLength: 0)
        }
        .lineLimit(1)
        .minimumScaleFactor(0.7)
      }
    }
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("Position")
    .accessibilityValue("\(TimeFormat.clock(time)) of \(TimeFormat.clock(duration))")
    .accessibilityAdjustableAction { direction in
      switch direction {
      case .increment: onSeek(min(duration, time + 30))
      case .decrement: onSeek(max(0, time - 30))
      @unknown default: break
      }
    }
  }

  private func seekTime(_ x: CGFloat, _ width: CGFloat) -> Double {
    max(0, min(duration, Double(x / max(width, 1)) * duration))
  }

  private func x(_ t: Double, _ width: CGFloat) -> CGFloat {
    duration > 0 ? CGFloat(t / duration) * width : 0
  }
}

/// A bookmark ribbon: a rectangle with a notch cut into its lower edge.
private struct BookmarkFlag: Shape {
  func path(in rect: CGRect) -> Path {
    var path = Path()
    path.move(to: CGPoint(x: rect.minX, y: rect.minY))
    path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
    path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
    path.addLine(to: CGPoint(x: rect.midX, y: rect.maxY - rect.height * 0.3))
    path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
    path.closeSubpath()
    return path
  }
}

/// Chapter ticks and note markers: their own view with equatable inputs, so the ten
/// position updates a second leave them alone.
private struct ScrubberMarks: View, Equatable {
  let chapters: [ChapterMark]
  let markers: [ReviewTimeline.Marker]
  let duration: Double
  let width: CGFloat
  let track: CGFloat
  let compact: Bool

  var body: some View {
    let dot: CGFloat = compact ? 6 : 8
    ZStack(alignment: .leading) {
      ForEach(chapters.indices.dropFirst(), id: \.self) { i in
        Capsule().fill(Theme.textPrimary).frame(width: 1.5, height: track + 6)
          .offset(x: (duration > 0 ? CGFloat(chapters[i].start / duration) : 0) * width - 0.75)
      }
      ForEach(markers.indices, id: \.self) { i in
        let m = markers[i]
        let tint = m.kind == .comment ? Theme.record : Theme.bookmark
        // one view per marker; a passage's span is just empty for the others
        ZStack(alignment: .leading) {
          Capsule().fill(tint.opacity(0.7))
            .frame(width: m.end.map { max(dot / 2, CGFloat($0 - m.position) * width) } ?? 0, height: track)
            .offset(x: CGFloat(m.position) * width)
            .opacity(m.end == nil ? 0 : 1)
          // a dot for a comment, a flag for a bookmark: the two colours alone are too close
          Group {
            if m.kind == .comment {
              Circle().fill(tint).frame(width: dot, height: dot)
            } else {
              BookmarkFlag().fill(tint).frame(width: dot, height: dot + 3)
            }
          }
          .offset(x: CGFloat(m.position) * width - dot / 2, y: -(track / 2 + dot / 2 + 4))
        }
      }
    }
  }
}
