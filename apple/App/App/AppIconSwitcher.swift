import OSLog
import UIKit

/// Shows the home screen icon chosen in Settings (alternate app icons in the asset catalog).
@MainActor
enum AppIconSwitcher {
  private static let log = Logger.drafttalk("icon")

  /// `name` nil is the primary icon. Does nothing when that icon is already showing, so the
  /// system's "icon changed" notice appears only on a real change.
  static func apply(_ name: String?) async {
    let app = UIApplication.shared
    guard app.supportsAlternateIcons, app.alternateIconName != name else { return }
    do {
      try await app.setAlternateIconName(name)
    } catch {
      log.error("Could not change the app icon to \(name ?? "primary", privacy: .public): \(error.localizedDescription, privacy: .public)")
    }
  }
}
