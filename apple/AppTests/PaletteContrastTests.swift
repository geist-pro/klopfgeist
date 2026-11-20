import SwiftUI
import Testing
@testable import MarginVoice

/// WCAG 2.2 contrast of the colour pairs the app draws, in every palette: text needs
/// 4.5:1, an outline or track that is the only sign of a control 3:1.
@MainActor
struct PaletteContrastTests {
  private static func luminance(_ color: Color) -> Double {
    let c = color.resolve(in: EnvironmentValues())
    return 0.2126 * Double(c.linearRed) + 0.7152 * Double(c.linearGreen) + 0.0722 * Double(c.linearBlue)
  }

  private static func contrast(_ a: Color, _ b: Color) -> Double {
    let (la, lb) = (luminance(a), luminance(b))
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
  }

  @Test(arguments: ColorTheme.allCases)
  func textReadsOnThePageAndTheSurface(theme: ColorTheme) {
    let p = theme.palette
    for (name, text) in [("primary", p.textPrimary), ("secondary", p.textSecondary), ("accent", p.accent)] {
      #expect(Self.contrast(text, p.background) >= 4.5, "\(theme) \(name) on the page")
      #expect(Self.contrast(text, p.surface) >= 4.5, "\(theme) \(name) on the surface")
    }
  }

  /// "Recording", "Comment saved", the tag names, "Not transcribed".
  @Test(arguments: ColorTheme.allCases)
  func statusColoursReadAsText(theme: ColorTheme) {
    let p = theme.palette
    for (name, text) in [("record", p.record), ("success", p.success), ("bookmark", p.bookmark), ("warning", p.warning)] {
      #expect(Self.contrast(text, p.background) >= 4.5, "\(theme) \(name) on the page")
    }
  }

  /// Play and Comment: the label and the smaller line under it.
  @Test(arguments: ColorTheme.allCases)
  func labelsReadOnTheFilledButtons(theme: ColorTheme) {
    let p = theme.palette
    #expect(Self.contrast(p.onAccent, p.accent) >= 4.5, "\(theme) on the accent")
    #expect(Self.contrast(p.onAccent, p.record) >= 4.5, "\(theme) on the record colour")
  }

  /// Increase Contrast: secondary text and outlines get stronger, dividers become visible lines.
  @Test(arguments: ColorTheme.allCases)
  func theStrongerVariantRaisesSecondaryTextOutlinesAndDividers(theme: ColorTheme) {
    let p = theme.palette
    let strong = p.strongerContrast
    #expect(Self.contrast(strong.textSecondary, p.background) > Self.contrast(p.textSecondary, p.background), "\(theme) secondary text")
    #expect(Self.contrast(strong.control, p.background) > Self.contrast(p.control, p.background), "\(theme) outlines")
    #expect(Self.contrast(strong.surfaceBorder, p.background) >= 3, "\(theme) dividers on the page")
    #expect(Self.contrast(strong.surfaceBorder, p.surface) >= 3, "\(theme) dividers on the surface")
    // hue and layout stay: the page, the surface and the accent are the theme's
    #expect(strong.background == p.background && strong.surface == p.surface && strong.accent == p.accent)
  }

  /// Outlines, tracks and the off state of a switch.
  @Test(arguments: ColorTheme.allCases)
  func theControlColourShowsOnThePageAndTheSurface(theme: ColorTheme) {
    let p = theme.palette
    #expect(Self.contrast(p.control, p.background) >= 3, "\(theme) on the page")
    #expect(Self.contrast(p.control, p.surface) >= 3, "\(theme) on the surface")
  }
}
