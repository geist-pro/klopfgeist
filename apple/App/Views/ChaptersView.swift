import MarginVoiceCore
import SwiftUI

struct ChaptersView: View {
  let book: Book
  @Environment(SessionHost.self) private var session
  @Environment(\.dismiss) private var dismiss

  var body: some View {
    let current = book.chapterIndex(at: session.currentTime)
    NavigationStack {
      ScrollViewReader { proxy in
      List {
        ForEach(Array(book.chapters.enumerated()), id: \.offset) { i, chapter in
          Button {
            session.seek(to: chapter.start)
            dismiss()
          } label: {
            HStack {
              Image(systemName: "speaker.wave.2.fill")
                .foregroundStyle(Theme.accent)
                .opacity(current == i ? 1 : 0)
                .accessibilityHidden(current != i)
                .accessibilityLabel("Now playing")
              Text(chapter.title)
                .foregroundStyle(current == i ? Theme.accent : Theme.textPrimary)
                .fontWeight(current == i ? .semibold : .regular)
              Spacer()
              let count = book.comments.filter { $0.kind == .comment && $0.mediaTime >= chapter.start && $0.mediaTime < chapter.end }.count
              if count > 0 {
                Label("\(count)", systemImage: "text.bubble").foregroundStyle(Theme.record).font(.footnote)
                  .accessibilityLabel(count == 1 ? "1 comment" : "\(count) comments")
              }
              Text(TimeFormat.clock(chapter.start))
                .font(.caption.monospacedDigit())
                .foregroundStyle(Theme.textSecondary)
            }
          }
          .id(i)
        }
        .themedRows()
      }
      .onAppear { if let current { proxy.scrollTo(current, anchor: .center) } }
      }
      .themedList()
      .navigationTitle("Chapters")
      .inlineNavigationTitle()
      .toolbar {
        ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
      }
    }
  }
}
