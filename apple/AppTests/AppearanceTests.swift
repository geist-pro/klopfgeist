import Foundation
import Testing
@testable import MarginVoice

/// The theme default, its one-time migration, and which icon asset each choice shows.
@MainActor
struct AppearanceTests {
  private func freshDefaults() -> UserDefaults {
    let name = "appearance-tests-\(UUID().uuidString)"
    return UserDefaults(suiteName: name)!
  }

  @Test func aNewInstallStartsInGeistDarkWithTheGhostIcon() {
    let settings = AppSettings(defaults: freshDefaults())
    #expect(settings.colorTheme == .geistDark)
    #expect(settings.appIconDesign == .ghost)
    #expect(settings.appIconName == nil)  // the primary icon
  }

  @Test func theOldGruvboxDefaultMovesToGeistDarkOnce() {
    let defaults = freshDefaults()
    defaults.set("gruvbox", forKey: "colorTheme")
    #expect(AppSettings(defaults: defaults).colorTheme == .geistDark)

    // a theme chosen after the move stays
    let settings = AppSettings(defaults: defaults)
    settings.colorTheme = .gruvbox
    #expect(AppSettings(defaults: defaults).colorTheme == .gruvbox)
  }

  @Test func theIconFollowsTheThemeOrItsOwnColours() {
    let settings = AppSettings(defaults: freshDefaults())
    settings.colorTheme = .gruvboxLight
    #expect(settings.appIconName == "AppIcon-Ghost-gruvboxLight")
    settings.appIconDesign = .readLine
    #expect(settings.appIconName == "AppIcon-ReadLine-gruvboxLight")
    settings.appIconFollowsTheme = false
    settings.appIconTheme = .geistDark
    #expect(settings.appIconName == "AppIcon-ReadLine-geistDark")
  }

  @Test func standardHasNoIconOfItsOwnAndBorrowsGeistDark() {
    #expect(AppIconDesign.ghost.iconName(for: .standard) == nil)
    #expect(AppIconDesign.readLine.iconName(for: .standard) == "AppIcon-ReadLine-geistDark")
  }

  @Test func singleKeyShortcutsAreOnUntilTurnedOff() {
    let defaults = freshDefaults()
    #expect(AppSettings(defaults: defaults).singleKeyShortcuts)
    AppSettings(defaults: defaults).singleKeyShortcuts = false
    #expect(!AppSettings(defaults: defaults).singleKeyShortcuts)
  }

  @Test func lightThemesAskForLightSystemControls() {
    #expect(ColorTheme.geist.colorScheme == .light)
    #expect(ColorTheme.gruvboxLight.colorScheme == .light)
    #expect(ColorTheme.geistDark.colorScheme == .dark)
  }
}
