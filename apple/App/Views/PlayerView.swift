import MarginVoiceCore
import SwiftData
import SwiftUI

/// Drive mode: where you are, the sentence being read in large type, what you can
/// say right now, and big controls. Comments and bookmarks open in an inspector
/// (beside the player on iPad, as a sheet on iPhone).
struct PlayerView: View {
  let book: Book
  /// Shows the library column again (iPad, when the player has the whole window).
  var onShowLibrary: (() -> Void)?
  @Environment(SessionHost.self) private var session
  @Environment(AppSettings.self) private var settings
  @Environment(\.modelContext) private var context
  @Environment(\.horizontalSizeClass) private var sizeClass
  @State private var showChapters = false
  @State private var showExport = false
  @State private var showNotes = false
  /// The notes open as a sheet: an inspector would cover the controls in a narrow
  /// iPad window (portrait, split view). Decided when they open, because the
  /// inspector itself narrows the player.
  @State private var notesInSheet = false
  @State private var width: CGFloat = 0
  /// The player pointed once to where saved comments are (after the first one).
  @AppStorage("commentsHintShown") private var commentsHintShown = false
  @State private var showCommentsHint = false
  /// The player explained once what listening means (the first time it listens).
  @AppStorage("listeningHintShown") private var listeningHintShown = false
  @State private var showListeningHint = false
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  /// Hints slide in; with Reduce Motion they appear.
  private var motion: Animation? { reduceMotion ? nil : .default }
  /// The body text size relative to the default (100 at Large), before any cap below.
  @ScaledMetric(relativeTo: .body) private var textUnit: CGFloat = 100

  /// At accessibility text sizes the sentence being read keeps the room.
  private var density: PlayerDensity { PlayerDensity(textScale: textUnit / 100) }

  var body: some View {
    player
      .frame(maxWidth: 720)
      .frame(maxWidth: .infinity)
      .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { newWidth in
        // in wide iPad windows the notes sit beside the player from the start
        if width == 0, sizeClass == .regular, newWidth > 900 { showNotes = true }
        width = newWidth
      }
      .background(Theme.background)
      // centred in the bar on iPad; on iPhone the header below names the book, and the
      // title stays for the back button and VoiceOver
      .navigationTitle(book.title)
      .inlineNavigationTitle()
      .toolbar(removing: sizeClass == .regular ? nil : .title)
      .toolbar { toolbarItems }
      .safeAreaInset(edge: .top) {
        if showListeningHint { listeningHint } else if showCommentsHint { commentsHint }
      }
      .onChange(of: session.flash?.date) { commentSaved() }
      .onChange(of: session.listenState) { startedListening() }
      .inspector(isPresented: Binding(get: { showNotes && !notesInSheet }, set: { showNotes = $0 })) {
        NavigationStack { CommentsView(book: book, isSheet: sizeClass == .compact) }
          .inspectorColumnWidth(min: 320, ideal: 360, max: 460)
          .presentationDetents([.medium, .large])
      }
      .sheet(isPresented: Binding(get: { showNotes && notesInSheet }, set: { showNotes = $0 })) {
        NavigationStack { CommentsView(book: book, isSheet: true) }
          .presentationDetents([.medium, .large])
      }
      .sheet(isPresented: $showChapters) { ChaptersView(book: book) }
      .sheet(isPresented: $showExport) { ExportView(book: book) }
      .task(id: book.id) {
        #if DEBUG
          // screenshots: DRAFTTALK_SHOW=notes|chapters|export opens that sheet
          switch ProcessInfo.processInfo.environment["DRAFTTALK_SHOW"] {
          case "notes":
            try? await Task.sleep(for: .milliseconds(300))
            toggleNotes()
          case "chapters": showChapters = true
          case "export": showExport = true
          default: break
          }
        #endif
        await session.open(book, context: context)
        startedListening()
      }
  }

  @ToolbarContentBuilder private var toolbarItems: some ToolbarContent {
    if let onShowLibrary {
      ToolbarItem(placement: .topBarLeading) {
        Button(action: onShowLibrary) {
          Label("Library", systemImage: "chevron.backward")
            .labelStyle(.titleAndIcon)
        }
        .accessibilityHint("Shows the library beside the player")
      }
    }
    // round buttons of their own, as in the header of the iPad layout
    ToolbarItem(placement: .primaryAction) {
      Button("Chapters", systemImage: "list.bullet") { showChapters = true }
        .disabled(book.chapters.isEmpty)
    }
    ToolbarSpacer(.fixed, placement: .primaryAction)
    ToolbarItem(placement: .primaryAction) {
      Button("Export", systemImage: "square.and.arrow.up") { showExport = true }
    }
    ToolbarSpacer(.fixed, placement: .primaryAction)
    // beside the player on iPad (a sidebar toggle), a sheet on iPhone; the count
    // shows that saved comments are kept here
    ToolbarItem(placement: .primaryAction) {
      Button {
        toggleNotes()
      } label: {
        Image(systemName: sizeClass == .regular && !narrow ? "sidebar.trailing" : "text.bubble")
          .overlay(alignment: .topTrailing) { CountBadge(count: book.comments.count) }
      }
      .accessibilityLabel(book.comments.isEmpty ? "Comments and Bookmarks" : "Comments and Bookmarks, \(book.comments.count)")
      .accessibilityAddTraits(showNotes ? .isSelected : [])
    }
  }

  private func commentSaved() {
    guard let flash = session.flash, !flash.isBookmark, !commentsHintShown else { return }
    commentsHintShown = true
    // shown once ever, so it stays until it is answered
    withAnimation(motion) { showCommentsHint = true }
  }

  private func startedListening() {
    guard session.listenState == .listening, !listeningHintShown else { return }
    listeningHintShown = true
    withAnimation(motion) { showListeningHint = true }
  }

  /// Once, the first time the microphone is on: when it listens and how to stop it,
  /// before anyone wonders why the orange dot stays.
  private var listeningHint: some View {
    let minutes = settings.stopListeningAfter
    let stops = minutes == 0 ? "" : ", and stops \(minutes) min after you pause"
    return HStack(alignment: .top) {
      Text("Klopfgeist now listens for commands like “stop” and “bookmark” while this book is open\(stops). The orange dot at the top of the screen shows the microphone is on. Tap “Listening” to stop now.")
        .font(.subheadline)
        .foregroundStyle(Theme.textPrimary)
      Spacer(minLength: 8)
      Button("OK") { withAnimation(motion) { showListeningHint = false } }
        .foregroundStyle(Theme.accent)
    }
    .padding(.horizontal, 14)
    .padding(.vertical, 10)
    .background(Theme.surface, in: .rect(cornerRadius: 12))
    .padding(.horizontal, 12)
    .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
  }

  /// Once, after the first saved comment: where comments are kept (testers looked for them).
  private var commentsHint: some View {
    HStack {
      Text("Comment saved. All your comments and bookmarks are under \(Image(systemName: "text.bubble")) at the top right.")
        .font(.subheadline)
        .foregroundStyle(Theme.textPrimary)
      Spacer(minLength: 8)
      Button("Show") {
        showCommentsHint = false
        if !showNotes { toggleNotes() }
      }
      .foregroundStyle(Theme.accent)
      Button("OK") { withAnimation(motion) { showCommentsHint = false } }
        .foregroundStyle(Theme.accent)
    }
    .padding(.horizontal, 14)
    .padding(.vertical, 10)
    .background(Theme.surface, in: .rect(cornerRadius: 12))
    .padding(.horizontal, 12)
    .transition(reduceMotion ? .opacity : .move(edge: .top).combined(with: .opacity))
  }

  /// An iPad window too narrow for the notes beside the player.
  private var narrow: Bool { width > 0 && width < 800 }

  private func toggleNotes() {
    if !showNotes { notesInSheet = sizeClass == .regular && narrow }
    showNotes.toggle()
  }

  private var player: some View {
    VStack(spacing: 18) {
      ChapterHeader(book: book, showsBookTitle: sizeClass != .regular, density: density)
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
      SentencePanel(book: book, large: sizeClass == .regular, density: density)
        .frame(maxHeight: .infinity)
    }
    .padding(.horizontal, 20)
    // the controls are pinned: at large text sizes the sentence gives way, never Play and Comment
    .safeAreaInset(edge: .bottom, spacing: 18) {
      controls
        .dynamicTypeSize(...DynamicTypeSize.accessibility2)
        .padding(.horizontal, 20)
        .padding(.bottom, 8)
    }
  }

  /// The parts that follow the playhead (header, sentence, scrubber, "Up next") and the
  /// status read the session in views of their own: the player itself is not rebuilt
  /// ten times a second.
  private var controls: some View {
    VStack(spacing: 12) {
      // one leading edge for text, status and controls
      VStack(alignment: .leading, spacing: 4) {
        ListeningStatusView()
        CommandHint(session: session, language: settings.commandLanguage)
      }
      .frame(maxWidth: .infinity, alignment: .leading)
      PlayerScrubber(book: book)
      transportRow
      toolStrip
      UpNextLine()
    }
  }

  /// Play (or Pause) under the left thumb, Comment under the right; while recording they
  /// become Cancel and Continue. Each says the voice command that does the same.
  private var transportRow: some View {
    let stop = phrase(.stop), resume = phrase(.resume), cancel = phrase(.cancel)
    return HStack(spacing: 8) {
      if session.isRecording {
        WideControlButton(
          systemName: "xmark", label: "Cancel", detail: "say \(cancel)", fill: Theme.control, foreground: Theme.textPrimary,
          outlined: true, hint: "Discards the comment you are recording", inputLabels: ["Discard"]
        ) { session.discardRecording() }
        WideControlButton(
          systemName: "play.fill", label: "Continue", detail: "saves, plays on", fill: Theme.accent, foreground: Theme.onAccent,
          hint: "Saves the comment and plays on", inputLabels: ["Save", "Done"]
        ) { session.commentButton() }
      } else {
        WideControlButton(
          systemName: session.isPlaying ? "pause.fill" : "play.fill", label: session.isPlaying ? "Pause" : "Play",
          detail: "say \(session.isPlaying ? stop : resume)",
          fill: session.isPlaying ? Theme.surface : Theme.accent, foreground: session.isPlaying ? Theme.textPrimary : Theme.onAccent,
          edged: session.isPlaying, hint: session.isPlaying ? "Pauses the book" : "Plays the book", startsMedia: !session.isPlaying
        ) { session.togglePlay() }
        WideControlButton(
          systemName: "mic.fill", label: "Comment", detail: "starts recording", fill: Theme.record, foreground: Theme.onAccent,
          hint: "Pauses the book and records a spoken comment here", inputLabels: ["Comment", "Record"]
        ) { session.commentButton() }
      }
    }
    .sensoryFeedback(.impact, trigger: session.isPlaying)
  }

  /// Back 15 s, repeat the sentence, bookmark, speed: one strip under the big buttons.
  private var toolStrip: some View {
    ToolStrip {
      Button { session.skipBack() } label: { ToolCell(systemName: "gobackward.15", label: "15 s") }
        .accessibilityLabel("Back 15 seconds")
      Button { session.repeatSentence() } label: { ToolCell(systemName: "arrow.counterclockwise", label: "Sentence") }
        .accessibilityLabel("Repeat sentence")
      Button { session.addBookmark() } label: {
        ToolCell(
          systemName: session.isBookmarkOpen ? "bookmark.fill" : "bookmark", label: session.isBookmarkOpen ? "End Passage" : "Bookmark",
          tint: session.isBookmarkOpen ? Theme.bookmark : Theme.textPrimary)
      }
      .accessibilityLabel(session.isBookmarkOpen ? "End passage" : "Bookmark")
      SpeedMenu(rate: session.rate) { session.setRate($0) }
    }
    .buttonStyle(PressedOpacity())
    .disabled(session.isRecording)
    .opacity(session.isRecording ? 0.45 : 1)
  }

  private func phrase(_ command: VoiceCommand) -> String {
    "“\(VoiceCommand.phrases(settings.commandLanguage)[command]?.first ?? command.rawValue)”"
  }
}

/// The book (iPhone; on iPad it is the window's title), then "Chapter 4 of 27" and the
/// chapter title (once, when the first chapter is the title itself).
private struct ChapterHeader: View {
  let book: Book
  let showsBookTitle: Bool
  let density: PlayerDensity
  @Environment(SessionHost.self) private var session

  var body: some View {
    ChapterHeading(book: book, index: book.chapterIndex(at: session.currentTime), showsBookTitle: showsBookTitle, density: density)
  }
}

/// Rebuilt when the chapter changes, not with every tick of the playhead.
private struct ChapterHeading: View {
  let book: Book
  let index: Int?
  let showsBookTitle: Bool
  let density: PlayerDensity

  var body: some View {
    if density.showsBookTitle { full } else { oneLine }
  }

  /// "6 of 27 · Chapter title" on one line: at large text the room goes to the sentence.
  private var oneLine: some View {
    let chapter = index.map { book.chapters[$0].title }.flatMap { $0 == book.title ? nil : $0 }
    let number = index.flatMap { book.chapters.count > 1 ? "\($0 + 1) of \(book.chapters.count)" : nil }
    return Group {
      if let number {
        Text("\(Text(number).fontWeight(.semibold).foregroundStyle(Theme.accent)) · \(chapter ?? book.title)")
      } else {
        Text(chapter ?? book.title)
      }
    }
    .font(.footnote)
    .foregroundStyle(Theme.textSecondary)
    .lineLimit(1)
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(.top, 8)
    .accessibilityElement(children: .ignore)
    .accessibilityLabel([number.map { "Chapter \($0)" }, chapter].compactMap { $0 }.joined(separator: ", "))
  }

  private var full: some View {
    let chapter = index.map { book.chapters[$0].title }
    return VStack(alignment: .leading, spacing: 4) {
      if showsBookTitle {
        Text(book.title)
          .font(.subheadline)
          .foregroundStyle(Theme.textSecondary)
          .lineLimit(1)
      }
      if let index, let chapter, chapter != book.title {
        VStack(alignment: .leading, spacing: 2) {
          Text(chapter)
            .font(.system(showsBookTitle ? .title2 : .largeTitle, weight: .light))
            .foregroundStyle(Theme.textPrimary)
            .lineLimit(2)
          if book.chapters.count > 1 {
            Text("Chapter \(index + 1) of \(book.chapters.count)")
              .font(.footnote)
              .foregroundStyle(Theme.textSecondary)
              .contentTransition(.numericText())
          }
        }
      }
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(.top, showsBookTitle ? 8 : 20)
    .animation(.default, value: index)
    .accessibilityElement(children: .combine)
  }
}

/// Follows the playhead and hands the sentence on: the text below only changes with the sentence.
private struct SentencePanel: View {
  let book: Book
  let large: Bool
  let density: PlayerDensity
  @Environment(SessionHost.self) private var session

  var body: some View {
    let time = session.currentTime
    let content = session.content
    let index = content?.sentenceIndex(at: time)
    let commented = content.flatMap { c in index.map { book.commentedSentences(in: c.text).contains($0) } } ?? false
    // at large text only the sentence being read: its neighbours took the room it needs
    let neighbours = density.showsNeighbouringSentences
    SentenceText(
      previous: neighbours ? index.flatMap { content?.sentence($0 - 1) } : nil, current: index.flatMap { content?.sentence($0) },
      next: neighbours ? index.flatMap { content?.sentence($0 + 1) } : nil, index: index, isExact: content?.isExact ?? true,
      fallback: book.title, large: large,
      mark: ReviewTimeline.sentenceMark(commented: commented, inBookmark: isInBookmark(at: time)))
  }

  /// The sentence being read belongs to a bookmark (or to the passage being marked).
  private func isInBookmark(at time: Double) -> Bool {
    if session.isBookmarkOpen, let open = book.comments.max(by: { $0.createdAt < $1.createdAt }), open.kind == .bookmark,
      time >= open.mediaTime - 1
    {
      return true
    }
    return book.comments.contains { c in
      c.kind == .bookmark && time >= c.mediaTime - 4 && time <= (c.endMediaTime ?? c.mediaTime) + 0.5
    }
  }
}

/// The sentence being read, large, between the previous and next one, with a bar
/// above it: record colour and "Commented" when a comment is anchored there, bookmark
/// colour and "Bookmarked" inside a bookmark, the bare accent otherwise.
private struct SentenceText: View {
  let previous: String?
  let current: String?
  let next: String?
  let index: Int?
  let isExact: Bool
  let fallback: String
  let large: Bool
  let mark: ReviewTimeline.SentenceMark
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  var body: some View {
    VStack(alignment: .leading, spacing: 14) {
      Spacer(minLength: 0)
      if let index, let current {
        if let previous {
          Text(previous)
            .font(Theme.bookFont(large ? .title3 : .body))
            .lineSpacing(2)
            .foregroundStyle(Theme.textSecondary)
            .lineLimit(large ? 3 : 2)
        }
        VStack(alignment: .leading, spacing: 14) {
          // the mark is named beside its bar: the bar's colour alone does not say what it means
          HStack(spacing: 8) {
            if mark != .plain {
              TagOutline(text: mark == .comment ? "Commented" : "Bookmarked", tint: barTint)
                .dynamicTypeSize(...DynamicTypeSize.accessibility2)
            }
            Rectangle()
              .fill(barTint)
              .frame(height: 2)
              .accessibilityHidden(true)
          }
          Text(current)
            .font(Theme.bookFont(large ? .title : .title2, weight: .medium))
            .lineSpacing(2)
            .foregroundStyle(Theme.textPrimary)
        }
        .fixedSize(horizontal: false, vertical: true)
        .id(index)
        // the old sentence leaves at once, so two sentences never overlap
        // with Reduce Motion the sentence fades in where it stands
        .transition(.asymmetric(insertion: reduceMotion ? .opacity : .opacity.combined(with: .offset(y: 14)), removal: .identity))
        if let next {
          Text(next)
            .font(Theme.bookFont(large ? .title3 : .body))
            .lineSpacing(2)
            .foregroundStyle(Theme.textSecondary)
            .lineLimit(large ? 5 : 3)
        }
        if !isExact {
          Label("Text position is approximate", systemImage: "info.circle")
            .font(.footnote)
            .foregroundStyle(Theme.textSecondary)
        }
      } else {
        Text(fallback)
          .font(Theme.bookFont(.title, weight: .semibold))
          .foregroundStyle(Theme.textPrimary)
          .transition(.identity)
      }
      Spacer(minLength: 0)
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .animation(.easeInOut(duration: 0.35), value: index)
    .animation(.easeInOut(duration: 0.25), value: mark)
    .minimumScaleFactor(0.6)
  }

  private var barTint: Color {
    switch mark {
    case .comment: Theme.record
    case .bookmark: Theme.bookmark
    case .plain: Theme.accent
    }
  }
}

/// The scrubber, following the playhead.
private struct PlayerScrubber: View {
  let book: Book
  @Environment(SessionHost.self) private var session

  var body: some View {
    ScrubberView(
      time: session.currentTime, duration: session.duration, chapters: book.chapters, markers: book.scrubberMarkers,
      onSeek: { session.seek(to: $0) })
  }
}

/// Shown near the end, so the switch to the next book is expected.
private struct UpNextLine: View {
  @Environment(SessionHost.self) private var session

  var body: some View {
    let remaining = session.duration - session.currentTime
    if let next = session.upNext, session.duration > 0, remaining < 120 {
      Label {
        Text("\(Text("Up next: ").foregroundStyle(Theme.textSecondary))\(Text(next.title).foregroundStyle(Theme.textPrimary))")
      } icon: {
        Image(systemName: "text.line.first.and.arrowtriangle.forward").foregroundStyle(Theme.accent)
      }
      .font(.footnote)
      .lineLimit(1)
      .frame(maxWidth: .infinity, alignment: .leading)
      .transition(.opacity)
    }
  }
}

/// What can be said right now, in the command language: each command after its symbol.
private struct CommandHint: View {
  let session: SessionHost
  let language: VoiceCommand.Language

  var body: some View {
    if case .listening = session.listenState, !session.isListeningPaused {
      text
        .font(.footnote)
        .foregroundStyle(Theme.textSecondary)
        .contentTransition(.opacity)
    }
  }

  private var parts: [(symbol: String, command: VoiceCommand, does: String)] {
    if session.isRecording {
      [("play.fill", .resume, "saves"), ("xmark", .cancel, "discards")]
    } else if session.isBookmarkOpen {
      [("bookmark.fill", .mark, "ends the passage here")]
    } else if session.isPlaying {
      [("pause.fill", .stop, "comment"), ("bookmark", .mark, "bookmark"), ("gobackward.15", .back, "15 s back")]
    } else {
      [("play.fill", .resume, "plays")]
    }
  }

  private var text: Text {
    parts.enumerated().reduce(Text("")) { out, item in
      let (i, part) = item
      let word = VoiceCommand.phrases(language)[part.command]?.first ?? part.command.rawValue
      let piece = Text("\(Image(systemName: part.symbol)) ").foregroundStyle(Theme.textPrimary)
        + Text("“\(word)”").fontWeight(.semibold).foregroundStyle(Theme.textPrimary)
        + Text(" \(part.does)")
      return i == 0 ? piece : out + Text(",   ") + piece
    }
  }
}

private struct SpeedMenu: View {
  let rate: Double
  let onChange: @MainActor @Sendable (Double) -> Void
  private let options: [Double] = [0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.75, 2.0, 2.5]

  var body: some View {
    Menu {
      Picker("Speed", selection: Binding(get: { rate }, set: onChange)) {
        ForEach(options, id: \.self) { Text(label($0)).tag($0) }
      }
    } label: {
      VStack(spacing: 4) {
        Text(label(rate))
          .font(.body.weight(.heavy).monospacedDigit())
          .frame(height: 22)
        Text("Speed")
          .font(.caption.weight(.medium))
      }
      .foregroundStyle(Theme.textPrimary)
      .frame(maxWidth: .infinity, minHeight: 52)
      .contentShape(.rect)
    }
    .accessibilityLabel("Playback speed \(label(rate))")
  }

  private func label(_ r: Double) -> String {
    r == r.rounded() ? String(format: "%.0f×", r) : String(format: "%g×", r)
  }
}

/// The number of saved comments and bookmarks on the toolbar button.
private struct CountBadge: View {
  let count: Int

  var body: some View {
    if count > 0 {
      Text("\(count)")
        .font(.caption2.weight(.bold).monospacedDigit())
        .foregroundStyle(Theme.background)
        .padding(.horizontal, 4)
        .frame(minWidth: 16, minHeight: 16)
        .background(Theme.accent, in: .capsule)
        .offset(x: 9, y: -8)
        .accessibilityHidden(true)
    }
  }
}
