import MarginVoiceCore
import SwiftData
import SwiftUI

/// The narrated sample papers: download one into the library, or open it once it is there.
struct SamplesView: View {
  /// Selects a book in the library.
  let onOpen: (UUID) -> Void
  @Query private var books: [Book]
  @Environment(\.dismiss) private var dismiss
  private let downloads = SampleDownloads.shared

  var body: some View {
    NavigationStack {
      List {
        Section {
          ForEach(SampleBooks.all) { sample in row(sample) }
        } header: {
          Text("Narrated papers with their PDF, to try the voice commands on. Each opens in the library like any other book.")
            .textCase(nil)
        } footer: {
          Text("Downloaded from github.com/kkai/marginvoice, narrated with the Kokoro voice.").foregroundStyle(Theme.textSecondary)
        }
        .themedRows()
      }
      .themedList()
      .navigationTitle("Sample Papers")
      #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
      #endif
      .sheetButtons(confirm: "Done", onConfirm: { dismiss() })
    }
    #if os(macOS)
      .frame(width: 520, height: 440)
    #endif
  }

  private func row(_ sample: SampleBooks.Sample) -> some View {
    let inLibrary = books.contains { $0.id == sample.id }
    let progress = downloads.progress[sample.file]
    return VStack(alignment: .leading, spacing: 6) {
      Text(sample.title).font(.headline)
      HStack {
        Text("\(sample.credit) · \(TimeFormat.clock(sample.duration)) · \(SampleBooks.size(sample.bytes))")
          .font(.caption)
          .foregroundStyle(Theme.textSecondary)
        Spacer(minLength: 8)
        if inLibrary {
          Button("Open", systemImage: "checkmark.circle.fill") {
            onOpen(sample.id)
            dismiss()
          }
        } else if progress != nil {
          Button("Stop Download", systemImage: "xmark.circle") { downloads.cancel(sample) }
            .labelStyle(.iconOnly)
            .foregroundStyle(Theme.textSecondary)
        } else {
          Button("Download") { downloads.download(sample) }
            .buttonStyle(.bordered)
        }
      }
      if let progress {
        ProgressView(value: progress).tint(Theme.accent)
      }
      if let error = downloads.errors[sample.file] {
        Text(error).font(.caption).foregroundStyle(Theme.warning)
      }
    }
    .padding(.vertical, 4)
  }
}
