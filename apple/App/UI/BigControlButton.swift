import SwiftUI

/// A large round transport control that is easy to hit without looking.
struct BigControlButton: View {
  let systemName: String
  let label: String
  var tint: Color = Theme.textPrimary
  var size: CGFloat = 76
  var prominent = false
  /// The glyph on a prominent (filled) button; white is too faint on a light tint (Gruvbox teal).
  var glyph: Color = .white
  /// What happens, for VoiceOver ("Records a spoken comment at this position").
  var hint: String?
  /// Other names Voice Control accepts ("Comment" for "Add comment").
  var inputLabels: [String] = []
  /// Play starts the book: VoiceOver stops talking over it.
  var startsMedia = false
  let action: () -> Void

  var body: some View {
    Button(action: action) {
      // a label, not a bare image: VoiceOver, Voice Control and the large content viewer read its title
      Label(label, systemImage: systemName)
        .labelStyle(.iconOnly)
        // sized with the button, which is deliberately large and fixed
        .font(.system(size: size * 0.38, weight: .semibold))
        .foregroundStyle(prominent ? glyph : tint)
        .contentTransition(.symbolEffect(.replace))
        .frame(width: size, height: size)
        .contentShape(.circle)
    }
    .buttonStyle(.plain)
    .modifier(ControlSurface(tint: prominent ? tint : nil))
    .accessibilityLabel(label)
    .accessibilityHint(hint ?? "")
    .accessibilityInputLabels([label] + inputLabels)
    .accessibilityAddTraits(startsMedia ? .startsMediaSession : [])
    #if os(iOS)
      .accessibilityShowsLargeContentViewer { Label(label, systemImage: systemName) }
    #endif
  }
}

/// A filled button keeps its colour: tinted glass washes out, and turns grey in a window
/// that isn't in front (Mac).
private struct ControlSurface: ViewModifier {
  let tint: Color?

  func body(content: Content) -> some View {
    if let tint {
      content.background(tint, in: .circle)
    } else {
      content.glassEffect(.regular.interactive(), in: .circle)
    }
  }
}
