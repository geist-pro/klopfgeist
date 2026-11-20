import SwiftUI

/// Shows what the app is doing right now: listening, recording, saved, paused.
struct StatusPill: View {
  let label: String
  /// Shown instead of `label` when the full one does not fit on one line (large text).
  var shortLabel: String?
  let systemName: String
  let tint: Color
  var level: Float = 0
  var pulsing = false
  /// Shows that tapping the pill does something.
  var accessory: Accessory?
  /// The Mac toolbar: a toolbar-height capsule in the smaller system text.
  var compact = false
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  enum Accessory {
    case symbol(String)
    /// A short action word in a filled chip ("Turn On").
    case action(String)
  }

  var body: some View {
    HStack(spacing: 8) {
      Image(systemName: systemName)
        .symbolEffect(.pulse, isActive: pulsing && !reduceMotion)
      if let shortLabel {
        ViewThatFits(in: .horizontal) {
          Text(label)
          Text(shortLabel)
        }
      } else {
        Text(label)
      }
      if level > 0.02 {
        LevelBars(level: level, tint: tint)
      }
      switch accessory {
      case .symbol(let name): Image(systemName: name)
      case .action(let title):
        Text(title)
          .padding(.horizontal, 8)
          .padding(.vertical, 2)
          .background(Theme.accent, in: .rect(cornerRadius: Theme.controlRadius))
          .foregroundStyle(Theme.onAccent)
      case nil: EmptyView()
      }
    }
    .font(compact ? .callout.weight(.medium) : .subheadline.weight(.semibold))
    .lineLimit(1)
    .foregroundStyle(tint)
    .padding(.horizontal, compact ? 10 : 0)
    .padding(.vertical, compact ? 4 : 0)
    .frame(minHeight: compact ? nil : 44)
    .background(compact ? tint.opacity(0.14) : .clear, in: .capsule)
    .overlay { if compact { Capsule().stroke(tint.opacity(0.45), lineWidth: 1) } }
    .contentShape(.rect)
    .accessibilityElement(children: .ignore)
    .accessibilityLabel(label)
  }
}

private struct LevelBars: View {
  let level: Float
  let tint: Color

  var body: some View {
    HStack(spacing: 2) {
      ForEach(0..<4, id: \.self) { i in
        Capsule()
          .fill(tint)
          .frame(width: 3, height: 4 + CGFloat(min(1, level * Float(4 - i) / 2)) * 10)
      }
    }
    .frame(height: 14)
    .animation(.easeOut(duration: 0.1), value: level)
    .accessibilityHidden(true)
  }
}
