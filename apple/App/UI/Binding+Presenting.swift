import SwiftUI

extension View {
  /// `.navigationBarTitleDisplayMode(.inline)` where it exists (iOS); nothing on macOS.
  func inlineNavigationTitle() -> some View {
    #if os(iOS)
      navigationBarTitleDisplayMode(.inline)
    #else
      self
    #endif
  }
}

extension Binding where Value == Bool {
  /// Presented while `value` is set; dismissing (button or swipe) clears it.
  init<T: Sendable>(presenting value: Binding<T?>) {
    self.init(get: { value.wrappedValue != nil }, set: { if !$0 { value.wrappedValue = nil } })
  }
}
