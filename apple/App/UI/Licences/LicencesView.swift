import SwiftUI

/// The NOTICE and the Apache licence, bundled as text.
struct LicencesView: View {
  private func text(_ name: String) -> String {
    Bundle.main.url(forResource: name, withExtension: "txt").flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? ""
  }

  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 24) {
        Text(text("NOTICE"))
        Text(text("Apache-2.0")).font(.caption.monospaced())
      }
      .font(.footnote)
      .foregroundStyle(Theme.textPrimary)
      .textSelection(.enabled)
      .padding()
    }
    .background(Theme.background)
    .navigationTitle("Licences")
  }
}
