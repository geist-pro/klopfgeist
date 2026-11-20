import SwiftUI

/// A wide, flat transport button in thumb reach: symbol, label and what it does (or the
/// word that does the same by voice) on one left edge. Two of them fill the row.
struct WideControlButton: View {
  let systemName: String
  let label: String
  /// The smaller line under the label ("say “stop”", "starts recording").
  var detail: String?
  let fill: Color
  let foreground: Color
  /// Drawn as an outline instead of a filled block (Cancel).
  var outlined = false
  /// An edge around a filled block whose fill is close to the page (Pause, on the surface colour).
  var edged = false
  /// What happens, for VoiceOver.
  var hint: String?
  /// Other names Voice Control accepts.
  var inputLabels: [String] = []
  var startsMedia = false
  let action: () -> Void

  var body: some View {
    Button(action: action) {
      HStack(alignment: .firstTextBaseline, spacing: 10) {
        Image(systemName: systemName)
          .font(.title3.weight(.semibold))
          .frame(width: 24)
          .contentTransition(.symbolEffect(.replace))
        VStack(alignment: .leading, spacing: 2) {
          Text(label)
            .font(.title3.weight(.heavy))
          if let detail {
            Text(detail)
              .font(.footnote)
          }
        }
        Spacer(minLength: 0)
      }
      .lineLimit(1)
      .minimumScaleFactor(0.8)
      .foregroundStyle(foreground)
      .padding(.horizontal, 16)
      .frame(maxWidth: .infinity, minHeight: 76)
      .background(outlined ? .clear : fill, in: .rect(cornerRadius: Theme.controlRadius))
      .overlay {
        if outlined {
          RoundedRectangle(cornerRadius: Theme.controlRadius).strokeBorder(fill, lineWidth: 1.5)
        } else if edged {
          RoundedRectangle(cornerRadius: Theme.controlRadius).strokeBorder(Theme.control, lineWidth: 1)
        }
      }
      .contentShape(.rect)
    }
    .buttonStyle(PressedOpacity())
    .accessibilityLabel(label)
    .accessibilityHint(hint ?? "")
    .accessibilityInputLabels([label] + inputLabels)
    .accessibilityAddTraits(startsMedia ? .startsMediaSession : [])
    #if os(iOS)
      .accessibilityShowsLargeContentViewer { Label(label, systemImage: systemName) }
    #endif
  }
}

/// The small actions under the transport buttons as one strip of equal cells, divided by
/// rules: symbol over a short word, each cell at least 52 pt tall.
struct ToolStrip<Content: View>: View {
  @ViewBuilder let content: Content

  var body: some View {
    HStack(spacing: 0) {
      Group(subviews: content) { cells in
        ForEach(Array(cells.enumerated()), id: \.offset) { i, cell in
          if i > 0 { Rectangle().fill(Theme.control).frame(width: 1) }
          cell.frame(maxWidth: .infinity)
        }
      }
    }
    .fixedSize(horizontal: false, vertical: true)
    .overlay { RoundedRectangle(cornerRadius: Theme.controlRadius).strokeBorder(Theme.control, lineWidth: 1) }
  }
}

/// One cell of a `ToolStrip`.
struct ToolCell: View {
  let systemName: String
  let label: String
  var tint: Color = Theme.textPrimary

  var body: some View {
    VStack(spacing: 4) {
      Image(systemName: systemName)
        .font(.body.weight(.semibold))
        .contentTransition(.symbolEffect(.replace))
        .frame(height: 22)
      // wraps instead of shrinking: at large text a smaller label is the wrong answer
      Text(label)
        .font(.footnote.weight(.medium))
        .lineLimit(2)
        .multilineTextAlignment(.center)
    }
    .foregroundStyle(tint)
    .frame(maxWidth: .infinity, minHeight: 52)
    .contentShape(.rect)
  }
}

/// Dims while pressed; the flat surfaces have no glass to react.
struct PressedOpacity: ButtonStyle {
  func makeBody(configuration: Configuration) -> some View {
    configuration.label.opacity(configuration.isPressed ? 0.7 : 1)
  }
}
