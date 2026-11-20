import SwiftUI

/// Tells VoiceOver about a change that only shows on screen. What is said, and when
/// nothing is, is decided by `StatusAnnouncement` in the core.
@MainActor
enum Announce {
  static func say(_ text: String?) {
    guard let text, !text.isEmpty else { return }
    AccessibilityNotification.Announcement(text).post()
  }
}
