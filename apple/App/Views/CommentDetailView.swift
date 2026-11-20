import AVFoundation
import MarginVoiceCore
import SwiftData
import SwiftUI

struct CommentDetailView: View {
  @Bindable var comment: Comment
  let book: Book
  @Environment(SessionHost.self) private var session
  @Environment(BookStore.self) private var store
  @Environment(\.modelContext) private var context
  @Environment(\.dismiss) private var dismiss
  @Environment(AppSettings.self) private var settings
  @State private var player: AVAudioPlayer?
  @State private var isPlayingAudio = false
  @State private var confirmDelete = false

  var body: some View {
    Form {
      if comment.kind == .comment {
        Section("Comment") {
          TextField("Transcript", text: $comment.transcript, axis: .vertical)
            .lineLimit(3...12)
          Picker("Tag", selection: Binding(get: { comment.tag }, set: { comment.tag = $0 })) {
            Text("None").tag(ReviewTag?.none)
            ForEach(ReviewTag.allCases, id: \.self) { Text($0.displayName(settings.commandLanguage)).tag(Optional($0)) }
          }
          if comment.transcriptStatus == .pending {
            Label("Transcribing…", systemImage: "hourglass").foregroundStyle(Theme.textSecondary)
          }
          if comment.transcriptStatus == .failed {
            Button("Try Transcribing Again", systemImage: "arrow.clockwise") { Task { await session.postProcess(comment) } }
          }
          if comment.audioFile != nil {
            Button(isPlayingAudio ? "Stop Recording Playback" : "Play Recording (\(TimeFormat.clock(comment.duration)))",
              systemImage: isPlayingAudio ? "stop.fill" : "play.fill"
            ) { toggleAudio() }
          }
        }
        .themedRows()
      }

      Section {
        if let content = session.content, let offset = comment.textOffset(in: content.text) {
          ContextText(content: content, offset: offset)
          HStack {
            Button("Earlier", systemImage: "chevron.left") { comment.sentenceNudge -= 1 }
            Spacer()
            Text(comment.sentenceNudge == 0 ? positionLabel : "Moved \(comment.sentenceNudge > 0 ? "+" : "")\(comment.sentenceNudge)")
              .font(.caption)
              .foregroundStyle(Theme.textSecondary)
            Spacer()
            Button("Later", systemImage: "chevron.right") { comment.sentenceNudge += 1 }
          }
          .buttonStyle(.borderless)
        } else {
          Text("No narration text for this book, so the comment is placed by time only.")
            .foregroundStyle(Theme.textSecondary)
        }
        Button("Listen From Here", systemImage: "headphones") {
          session.seek(to: max(0, comment.mediaTime - 8))
          if !session.isPlaying { session.togglePlay() }
        }
      } header: {
        Text("Position · \(TimeFormat.clock(comment.mediaTime))\(comment.chapter.map { " · \($0)" } ?? "")")
      } footer: {
        Text("The comment is attached after the highlighted sentence. Use Earlier and Later to move it.").foregroundStyle(Theme.textSecondary)
      }
      .themedRows()

      Section {
        Button("Delete", systemImage: "trash", role: .destructive) { confirmDelete = true }
      }
      .themedRows()
      .confirmationDialog(comment.kind == .bookmark ? "Delete Bookmark?" : "Delete Comment?", isPresented: $confirmDelete, titleVisibility: .visible) {
        Button("Delete", role: .destructive) {
          store.delete(comment, context: context)
          dismiss()
        }
      } message: {
        Text(comment.deleteMessage)
      }
    }
    .themedList()
    .navigationTitle(comment.kind == .bookmark ? "Bookmark" : "Comment")
    .inlineNavigationTitle()
    .onDisappear {
      player?.stop()
      context.saveLogged("comment edits")
    }
  }

  private var positionLabel: String {
    if comment.refinedOffset != nil { return "Matched to the text" }
    return comment.confidence >= 0.5 ? "Placed by timing" : "Estimated position"
  }

  private func toggleAudio() {
    if isPlayingAudio {
      player?.stop()
      isPlayingAudio = false
      return
    }
    guard let url = store.audioURL(comment, in: book) else { return }
    if session.isPlaying { session.togglePlay() }
    player = try? AVAudioPlayer(contentsOf: url)
    player?.play()
    isPlayingAudio = player != nil
    let duration = player?.duration ?? 0
    Task {
      try? await Task.sleep(for: .seconds(duration + 0.2))
      isPlayingAudio = player?.isPlaying ?? false
    }
  }
}

/// The paragraph around the anchor with the attached sentence highlighted.
private struct ContextText: View {
  let content: BookContent
  let offset: Int

  var body: some View {
    let text = content.text
    let si = AnnotatedTextExport.attachSentence(for: offset, in: text)
    let lo = max(0, si - 1), hi = min(text.sentences.count - 1, si + 1)
    Text(attributed(lo...hi, highlight: si))
      .font(Theme.bookFont(.callout))
  }

  private func attributed(_ range: ClosedRange<Int>, highlight: Int) -> AttributedString {
    var result = AttributedString()
    for i in range {
      var piece = AttributedString(content.sentence(i) ?? "")
      piece.foregroundColor = i == highlight ? Theme.textPrimary : Theme.textSecondary
      if i == highlight { piece.inlinePresentationIntent = .stronglyEmphasized }
      result += piece
      var gap = AttributedString(i == highlight ? "  ◆ " : " ")
      gap.foregroundColor = Theme.record
      result += gap
    }
    return result
  }
}

extension Comment {
  /// What deleting this note loses, for the confirmation.
  var deleteMessage: String {
    if kind == .bookmark { return "The bookmark is removed from the book." }
    return audioFile == nil ? "The comment is removed from the book." : "The comment and its voice recording are deleted. This can’t be undone."
  }
}
