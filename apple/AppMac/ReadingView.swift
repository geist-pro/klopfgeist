import MarginVoiceCore
import SwiftData
import SwiftUI

/// The book as a text to read along with: the narration, paragraph by paragraph, with
/// the sentence being read lit up, commented sentences underlined in the record colour and
/// bookmarked passages tinted. The comments themselves are in the inspector. The view
/// follows playback; a click on a sentence plays from there.
struct ReadingView: View {
  let book: Book
  @Environment(SessionHost.self) private var session
  @Environment(\.modelContext) private var context
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  /// Scrolls with playback until the reader scrolls away; "Show Current Sentence" brings it back.
  @State private var follows = true
  @State private var lastAutoScroll = Date.distantPast
  /// The sentence being read; written by `SentenceTracker`, so this view changes with the
  /// sentence and not with every tick of the playhead.
  @State private var current: Int?

  var body: some View {
    let content = session.content
    ScrollViewReader { proxy in
      ScrollView {
        LazyVStack(alignment: .leading, spacing: 20) {
          titleBlock
          if let content {
            let commented = book.commentedSentences(in: content.text)
            let bookmarked = book.bookmarkedSentences(in: content.text)
            ForEach(content.text.paragraphs.indices, id: \.self) { pi in
              ParagraphBlock(content: content, index: pi, current: current, commented: commented, bookmarked: bookmarked)
                .id(pi)
            }
          } else if book.textFile == nil {
            Label("This book has no narration text. Comments are placed by time only.", systemImage: "info.circle")
              .foregroundStyle(Theme.textSecondary)
          } else {
            ProgressView("Loading the text…")
          }
          Spacer(minLength: 200)
        }
        .padding(.horizontal, 48)
        .padding(.top, 28)
        // a reading column of about 640–720 points of text
        .frame(maxWidth: 820, alignment: .leading)
        .frame(maxWidth: .infinity)
      }
      .scrollIndicators(.automatic)
      .background { SentenceTracker { current = $0 } }
      .onChange(of: current) { _, index in
        guard follows, let index, let content, content.text.sentences.indices.contains(index),
          session.isPlaying || Date().timeIntervalSince(lastAutoScroll) > 1
        else { return }
        lastAutoScroll = Date()
        // with Reduce Motion the page jumps to the sentence instead of gliding with every one
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.35)) { proxy.scrollTo(content.text.sentences[index].paragraphIndex, anchor: .center) }
      }
      .onReceive(NotificationCenter.default.publisher(for: .draftTalkFollow)) { _ in
        follows = true
        if let index = current, let content, content.text.sentences.indices.contains(index) {
          withAnimation(reduceMotion ? nil : .default) { proxy.scrollTo(content.text.sentences[index].paragraphIndex, anchor: .center) }
        }
      }
    }
    .environment(\.openURL, OpenURLAction { url in
      // sentences are links: a click plays from that sentence
      guard url.scheme == "drafttalk", let index = Int(url.lastPathComponent), let content,
        content.text.sentences.indices.contains(index)
      else { return .discarded }
      session.seek(to: content.map.time(at: content.text.sentences[index].range.lowerBound))
      if !session.isPlaying { session.togglePlay() }
      return .handled
    })
    .background(Theme.background)
    .task(id: book.id) { await session.open(book, context: context) }
  }

  private var titleBlock: some View {
    VStack(alignment: .leading, spacing: 6) {
      Text(book.title)
        .font(Theme.bookFont(.largeTitle, weight: .bold))
        .foregroundStyle(Theme.textPrimary)
        .accessibilityAddTraits(.isHeader)
      HStack(spacing: 12) {
        Text(TimeFormat.clock(book.duration))
        if book.chapters.count > 1 { Text("\(book.chapters.count) chapters") }
        if let voice = book.voiceName { Text("Read by \(voice)") }
        if let content = session.content, !content.isExact { Label("Text position is approximate", systemImage: "info.circle") }
      }
      .font(.callout)
      .foregroundStyle(Theme.textSecondary)
    }
    .padding(.bottom, 12)
  }
}

extension Notification.Name {
  /// "Show Current Sentence" (Playback menu).
  static let draftTalkFollow = Notification.Name("de.kaikunze.drafttalk.follow")
}

/// Reads the playhead ten times a second and reports the sentence only when it changes.
private struct SentenceTracker: View {
  let onChange: (Int?) -> Void
  @Environment(SessionHost.self) private var session

  var body: some View {
    Color.clear
      .onChange(of: session.content?.sentenceIndex(at: session.currentTime), initial: true) { _, index in onChange(index) }
  }
}

/// One paragraph of the narration: a heading, or sentences as links with the current
/// one lit, commented ones underlined and bookmarked ones tinted.
private struct ParagraphBlock: View {
  let content: BookContent
  let index: Int
  let current: Int?
  let commented: Set<Int>
  let bookmarked: Set<Int>

  var body: some View {
    let paragraph = content.text.paragraphs[index]
    if content.headingOffsets.contains(paragraph.range.lowerBound) {
      Text(AnnotatedTextExportHeading.title(paragraph.text))
        .font(Theme.bookFont(.title2, weight: .semibold))
        .foregroundStyle(Theme.textPrimary)
        .padding(.top, 18)
        .accessibilityAddTraits(.isHeader)
    } else {
      Text(attributed(content.text.sentences.enumerated().filter { $0.element.paragraphIndex == index }))
        .font(Theme.bookFont(.title3))
        .lineSpacing(5)
        .tint(Theme.textPrimary)
    }
  }

  private func attributed(_ sentences: [(offset: Int, element: NarrationText.Sentence)]) -> AttributedString {
    var out = AttributedString()
    var cursor = content.text.paragraphs[index].range.lowerBound
    for (si, s) in sentences {
      if s.range.lowerBound > cursor { out += AttributedString(content.text.substring(cursor..<s.range.lowerBound)) }
      var piece = AttributedString(content.text.substring(s.range))
      piece.link = URL(string: "drafttalk://sentence/\(si)")
      // links are not underlined. The marks differ by line as well as by colour (the tints
      // alone are under 2:1 on the page): a comment is a solid line in the record colour, a
      // bookmark a dotted one, the sentence being read a solid line in the accent.
      if commented.contains(si) {
        piece.underlineStyle = Text.LineStyle(pattern: .solid, color: Theme.record)
      } else if bookmarked.contains(si) {
        piece.underlineStyle = Text.LineStyle(pattern: .dot, color: Theme.bookmark)
      } else if si == current {
        piece.underlineStyle = Text.LineStyle(pattern: .solid, color: Theme.accent)
      }
      if si == current {
        piece.foregroundColor = Theme.textPrimary
        piece.backgroundColor = Theme.accent.opacity(0.28)
      } else if bookmarked.contains(si) {
        piece.foregroundColor = Theme.textPrimary
        piece.backgroundColor = Theme.bookmark.opacity(0.18)
      } else if commented.contains(si) {
        piece.foregroundColor = Theme.textPrimary
      } else if let current, si < current {
        piece.foregroundColor = Theme.textSecondary
      } else {
        piece.foregroundColor = Theme.textPrimary.opacity(0.88)
      }
      out += piece
      cursor = s.range.upperBound
    }
    return out
  }
}

/// "Section: Introduction." → "Introduction" for the reading pane's headings.
enum AnnotatedTextExportHeading {
  static func title(_ s: String) -> String {
    var t = s.trimmingCharacters(in: .whitespacesAndNewlines)
    for prefix in ["Section: ", "Abschnitt: "] where t.hasPrefix(prefix) { t = String(t.dropFirst(prefix.count)) }
    if t.hasSuffix(".") { t.removeLast() }
    return t
  }
}
