import MarginVoiceCore
import Observation
import SwiftUI
#if canImport(UIKit)
  import UIKit
#else
  import AppKit
#endif

// Design tokens. GEIST dark is the default (the lab's colours, geist.pro); Gruvbox and GEIST
// come in dark and light, Standard is the original dark palette. Dark themes suit the car at night.

extension Color {
  init(hex: UInt32, opacity: Double = 1) {
    self.init(
      .sRGB,
      red: Double((hex >> 16) & 0xFF) / 255,
      green: Double((hex >> 8) & 0xFF) / 255,
      blue: Double(hex & 0xFF) / 255,
      opacity: opacity
    )
  }
}

/// A set of colors the whole app is painted with.
struct Palette: Equatable {
  var background: Color
  var surface: Color
  var surfaceBorder: Color
  var textPrimary: Color
  var textSecondary: Color
  var accent: Color
  var record: Color
  var success: Color
  var bookmark: Color
  var warning: Color
  /// Text on an accent-filled button.
  var onAccent: Color
  /// Outlines, tracks and the off state of a control: at least 3:1 against the background
  /// and the surface. `surfaceBorder` stays for plain dividers, which need no contrast.
  var control: Color
  /// Whether system controls (sheets, keyboards, menus) are drawn dark.
  var isDark = true

  /// For Increase Contrast: secondary text moves toward the primary, outlines take the old
  /// secondary text colour and dividers the control colour. Page, surface and hues stay.
  var strongerContrast: Palette {
    var p = self
    p.textSecondary = textSecondary.mix(with: textPrimary, by: 0.4)
    p.control = textSecondary
    p.surfaceBorder = control
    return p
  }

  static let standard = Palette(
    background: Color(hex: 0x0A0C10), surface: Color(hex: 0x111419), surfaceBorder: Color(hex: 0x23272E),
    textPrimary: Color(hex: 0xF5F6F7), textSecondary: Color(hex: 0x8A909A), accent: Color(hex: 0x2F80FF),
    record: Color(hex: 0xFF453A), success: Color(hex: 0x30D158), bookmark: Color(hex: 0xFFD60A),
    warning: Color(hex: 0xFF9500), onAccent: Color(hex: 0x0A0C10), control: Color(hex: 0x5C6370))

  /// Gruvbox dark (morhetz/gruvbox): warm, low-contrast, easy on the eyes at night.
  static let gruvbox = Palette(
    background: Color(hex: 0x1D2021), surface: Color(hex: 0x282828), surfaceBorder: Color(hex: 0x504945),
    textPrimary: Color(hex: 0xEBDBB2), textSecondary: Color(hex: 0xA89984), accent: Color(hex: 0x83A598),
    record: Color(hex: 0xFB4934), success: Color(hex: 0xB8BB26), bookmark: Color(hex: 0xFABD2F),
    warning: Color(hex: 0xFE8019), onAccent: Color(hex: 0x1D2021), control: Color(hex: 0x7C6F64))

  /// Gruvbox dark, medium contrast: one step lighter than `gruvbox`, yellow accent.
  static let gruvboxDark = Palette(
    background: Color(hex: 0x282828), surface: Color(hex: 0x32302F), surfaceBorder: Color(hex: 0x504945),
    textPrimary: Color(hex: 0xEBDBB2), textSecondary: Color(hex: 0xA89984), accent: Color(hex: 0xFABD2F),
    record: Color(hex: 0xFB6A57), success: Color(hex: 0xB8BB26), bookmark: Color(hex: 0xFE8019),
    warning: Color(hex: 0xE78A4E), onAccent: Color(hex: 0x1D2021), control: Color(hex: 0x928374))

  /// Gruvbox light: the cream page.
  static let gruvboxLight = Palette(
    background: Color(hex: 0xFBF1C7), surface: Color(hex: 0xF2E5BC), surfaceBorder: Color(hex: 0xD5C4A1),
    textPrimary: Color(hex: 0x282828), textSecondary: Color(hex: 0x665C54), accent: Color(hex: 0x076678),
    record: Color(hex: 0x9D0006), success: Color(hex: 0x6A660C), bookmark: Color(hex: 0x8F5902),
    warning: Color(hex: 0xAF3A03), onAccent: Color(hex: 0xFBF1C7), control: Color(hex: 0x7C6F64), isDark: false)

  /// GEIST (geist.pro): white page, navy, cyan rules.
  static let geist = Palette(
    background: Color(hex: 0xFFFFFF), surface: Color(hex: 0xF2F6FB), surfaceBorder: Color(hex: 0xD6DEEA),
    textPrimary: Color(hex: 0x161616), textSecondary: Color(hex: 0x3A4A66), accent: Color(hex: 0x193F82),
    record: Color(hex: 0xB42318), success: Color(hex: 0x1F7A3A), bookmark: Color(hex: 0xA14A00),
    warning: Color(hex: 0xB54708), onAccent: .white, control: Color(hex: 0x7F8EA9), isDark: false)

  /// GEIST dark: deep navy, cyan accent. The default.
  static let geistDark = Palette(
    background: Color(hex: 0x0B1220), surface: Color(hex: 0x121B30), surfaceBorder: Color(hex: 0x26335A),
    textPrimary: Color(hex: 0xF4F7FB), textSecondary: Color(hex: 0xAAB7D2), accent: Color(hex: 0x29B7EA),
    record: Color(hex: 0xFF7A6B), success: Color(hex: 0x7FD18B), bookmark: Color(hex: 0xF2A865),
    warning: Color(hex: 0xFFB454), onAccent: Color(hex: 0x161616), control: Color(hex: 0x5A6EA6))
}

/// Raw values are stored in the settings: `gruvbox` (hard) and `standard` keep their old names.
enum ColorTheme: String, CaseIterable, Identifiable {
  case geistDark, geist, gruvbox, gruvboxDark, gruvboxLight, standard
  var id: Self { self }
  static let `default` = ColorTheme.geistDark
  var displayName: String {
    switch self {
    case .geistDark: "GEIST Dark"
    case .geist: "GEIST"
    case .gruvbox: "Gruvbox Hard"
    case .gruvboxDark: "Gruvbox Dark"
    case .gruvboxLight: "Gruvbox Light"
    case .standard: "Standard"
    }
  }
  var palette: Palette {
    switch self {
    case .geistDark: .geistDark
    case .geist: .geist
    case .gruvbox: .gruvbox
    case .gruvboxDark: .gruvboxDark
    case .gruvboxLight: .gruvboxLight
    case .standard: .standard
    }
  }
  var colorScheme: ColorScheme { palette.isDark ? .dark : .light }
}

/// The two app icon designs, each drawn in the five themed palettes.
enum AppIconDesign: String, CaseIterable, Identifiable {
  case ghost, readLine
  var id: Self { self }
  var displayName: String {
    switch self {
    case .ghost: "Listening Ghost"
    case .readLine: "Read Line"
    }
  }
  /// The asset catalog name of this design in a theme's colours; nil means the primary icon
  /// (the ghost in GEIST dark). Standard has no icon of its own and borrows GEIST dark.
  func iconName(for theme: ColorTheme) -> String? {
    let colours = theme == .standard ? ColorTheme.geistDark : theme
    if self == .ghost, colours == .geistDark { return nil }
    return "AppIcon-\(self == .ghost ? "Ghost" : "ReadLine")-\(colours.rawValue)"
  }
}

/// The face for the book's own words (sentences, chapter titles, quotes). The UI stays SF Pro,
/// so "the book" and "what I said" look different.
enum BookFont: String, CaseIterable, Identifiable {
  case newYork, charter, iowan, sans
  var id: Self { self }
  var displayName: String {
    switch self {
    case .newYork: "New York"
    case .charter: "Charter"
    case .iowan: "Iowan Old Style"
    case .sans: "San Francisco"
    }
  }

  /// PostScript names (both faces ship with iOS); nil = system font.
  fileprivate func postScriptName(bold: Bool) -> String? {
    switch self {
    case .charter: bold ? "Charter-Bold" : "Charter-Roman"
    case .iowan: bold ? "IowanOldStyle-Bold" : "IowanOldStyle-Roman"
    case .newYork, .sans: nil
    }
  }
}

/// The current palette. Observable, so views that read `Theme` repaint when it changes.
@MainActor
@Observable
final class ThemeStore {
  static let shared = ThemeStore()
  var palette = Palette.geistDark {
    didSet { applyToUIKit() }
  }
  var bookFont = BookFont.newYork
  /// The theme's palette as chosen, before the contrast variant.
  private var chosen = Palette.geistDark
  /// The system's Increase Contrast setting (set by `followsSystemContrast()` at the root).
  var increaseContrast = false {
    didSet { if increaseContrast != oldValue { repaint() } }
  }

  private func repaint() {
    let next = increaseContrast ? chosen.strongerContrast : chosen
    if palette != next { palette = next }
  }

  /// Follows the appearance settings (called at launch and when they change).
  func apply(_ settings: AppSettings) {
    chosen = settings.colorTheme.palette
    repaint()
    bookFont = settings.bookFont
    #if DEBUG
      // screenshots: DRAFTTALK_FONT=newYork|charter|iowan|sans
      if let font = ProcessInfo.processInfo.environment["DRAFTTALK_FONT"].flatMap(BookFont.init) { bookFont = font }
    #endif
  }

  /// Navigation titles and segmented controls are drawn by UIKit and ignore SwiftUI styles.
  private func applyToUIKit() {
    #if canImport(UIKit)
    let text = UIColor(palette.textPrimary)
    let bar = UINavigationBar.appearance()
    bar.titleTextAttributes = [.foregroundColor: text]
    bar.largeTitleTextAttributes = [.foregroundColor: text]
    // alerts, confirmation dialogs and pickers are UIKit and ignore SwiftUI's tint
    UIView.appearance(whenContainedInInstancesOf: [UIAlertController.self]).tintColor = UIColor(palette.accent)
    let segments = UISegmentedControl.appearance()
    segments.selectedSegmentTintColor = UIColor(palette.surfaceBorder)
    segments.setTitleTextAttributes([.foregroundColor: UIColor(palette.textSecondary)], for: .normal)
    segments.setTitleTextAttributes([.foregroundColor: text], for: .selected)
    #endif
  }
}

@MainActor
enum Theme {
  static var background: Color { ThemeStore.shared.palette.background }
  static var surface: Color { ThemeStore.shared.palette.surface }
  static var surfaceBorder: Color { ThemeStore.shared.palette.surfaceBorder }
  static var textPrimary: Color { ThemeStore.shared.palette.textPrimary }
  static var textSecondary: Color { ThemeStore.shared.palette.textSecondary }
  static var accent: Color { ThemeStore.shared.palette.accent }
  static var record: Color { ThemeStore.shared.palette.record }
  static var success: Color { ThemeStore.shared.palette.success }
  static var bookmark: Color { ThemeStore.shared.palette.bookmark }
  static var warning: Color { ThemeStore.shared.palette.warning }
  static var onAccent: Color { ThemeStore.shared.palette.onAccent }
  static var control: Color { ThemeStore.shared.palette.control }

  /// The colour of a review tag, from the palette: strength green, weakness red, question blue, minor grey, summary cream.
  static func tagTint(_ tag: ReviewTag) -> Color {
    switch tag {
    case .strength: success
    case .weakness: record
    case .question: accent
    case .minor: textSecondary
    case .summary: textPrimary
    }
  }

  /// The book's words in the chosen face, scaling with Dynamic Type like `style`.
  static func bookFont(_ style: Font.TextStyle, weight: Font.Weight = .regular) -> Font {
    let face = ThemeStore.shared.bookFont
    if let name = face.postScriptName(bold: [.semibold, .bold, .heavy, .black].contains(weight)), fontExists(name) {
      return .custom(name, size: pointSize(style), relativeTo: style)
    }
    return .system(style, design: face == .sans ? .default : .serif, weight: weight)
  }

  private static func fontExists(_ name: String) -> Bool {
    #if canImport(UIKit)
      UIFont(name: name, size: 17) != nil
    #else
      NSFont(name: name, size: 17) != nil
    #endif
  }

  /// Default (Large) Dynamic Type sizes of the text styles.
  private static func pointSize(_ style: Font.TextStyle) -> CGFloat {
    switch style {
    case .largeTitle: 34
    case .title: 28
    case .title2: 22
    case .title3: 20
    case .headline, .body: 17
    case .callout: 16
    case .subheadline: 15
    case .footnote: 13
    case .caption: 12
    case .caption2: 11
    default: 17
    }
  }

  /// Flat surfaces with a 2 pt corner (the Klopfgeist look: rules instead of rounded cards).
  nonisolated static let cardRadius: CGFloat = 2
  nonisolated static let controlRadius: CGFloat = 2
  nonisolated static let cardPadding: CGFloat = 18
}

private struct CardSurface: ViewModifier {
  var padding: CGFloat = Theme.cardPadding
  func body(content: Content) -> some View {
    content
      .padding(padding)
      .background(Theme.surface, in: .rect(cornerRadius: Theme.cardRadius))
      .overlay { RoundedRectangle(cornerRadius: Theme.cardRadius, style: .continuous).stroke(Theme.surfaceBorder, lineWidth: 1) }
  }
}

/// Paints a List or Form with the theme, and the sheet it may be in: a sheet's default
/// glass lets the Play and record buttons behind it show through.
private struct ThemedList: ViewModifier {
  func body(content: Content) -> some View {
    content
      #if os(iOS)
        .listStyle(.plain)
      #endif
      .scrollContentBackground(.hidden)
      .background(Theme.background)
      .presentationBackground(Theme.background)
      .foregroundStyle(Theme.textPrimary)
      .tint(Theme.accent)
      // a sheet keeps the colour scheme it opened with: switching to a light theme in
      // Settings would leave system text white on the light page
      .preferredColorScheme(ThemeStore.shared.palette.isDark ? .dark : .light)
      .environment(\.colorScheme, ThemeStore.shared.palette.isDark ? .dark : .light)
      #if os(macOS)
        // the title and button rows of a Mac sheet
        .toolbarBackground(Theme.background, for: .automatic)
        .toolbarBackgroundVisibility(.visible, for: .automatic)
      #endif
  }
}

private struct ThemedRows: ViewModifier {
  func body(content: Content) -> some View {
    #if os(iOS)
      content.listRowBackground(Theme.background).listRowSeparatorTint(Theme.surfaceBorder)
    #else
      content.listRowBackground(Theme.surface).listRowSeparatorTint(Theme.surfaceBorder)
    #endif
  }
}

/// A hairline in the theme's border colour; `Divider()` stays system grey.
struct ThemedDivider: View {
  var body: some View {
    Rectangle().fill(Theme.surfaceBorder).frame(height: 1)
  }
}

/// Paints a sidebar list (Mac) with the theme's surface instead of the system material.
private struct ThemedSidebar: ViewModifier {
  func body(content: Content) -> some View {
    content.scrollContentBackground(.hidden).background(Theme.surface)
  }
}

/// Hands the system's Increase Contrast setting to the theme.
private struct FollowsSystemContrast: ViewModifier {
  @Environment(\.colorSchemeContrast) private var contrast

  func body(content: Content) -> some View {
    content.onChange(of: contrast, initial: true) { _, new in ThemeStore.shared.increaseContrast = new == .increased }
  }
}

extension View {
  /// Apply once at the root of each window.
  func followsSystemContrast() -> some View { modifier(FollowsSystemContrast()) }
  func themedSidebar() -> some View { modifier(ThemedSidebar()) }
  func themedList() -> some View { modifier(ThemedList()) }
  /// Apply to a Section or ForEach inside a themed list.
  func themedRows() -> some View { modifier(ThemedRows()) }

  func cardSurface(padding: CGFloat = Theme.cardPadding) -> some View {
    modifier(CardSurface(padding: padding))
  }
}
