import MarginVoiceCore
import SwiftData
import SwiftUI

/// Comments and bookmarks of a book, in reading order.
///
/// Beside the player (iPad inspector, Mac inspector) the note nearest the playhead is
/// highlighted and kept in view while the book plays; on iPad an "Ahead" divider
/// separates what is still to come.
struct CommentsView: View {
  enum Filter: String, CaseIterable, Identifiable {
    case all = "All", comments = "Comments", bookmarks = "Bookmarks"
    var id: Self { self }
  }

  let book: Book
  /// Shown as a sheet (iPhone, narrow iPad): needs its own title and Done.
  var isSheet = false
  @Environment(SessionHost.self) private var session
  @Environment(BookStore.self) private var store
  @Environment(\.modelContext) private var context
  @Environment(\.dismiss) private var dismiss
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(AppSettings.self) private var settings
  @State private var filter = Filter.all
  @State private var tagFilter = TagFilter.all
  @State private var confirmTranscribe = false
  /// A note swiped away, waiting for confirmation: a spoken comment cannot be recorded again.
  @State private var confirmDelete: Comment?
  /// Where the playhead is among the notes shown; written by `PlayheadTracker`.
  @State private var position = PlayheadPosition()
  @State private var editing: Comment?
  @State private var voiceNotes = VoiceNotePlayer()

  /// Beside the player: the current note is highlighted and followed.
  private var isColumn: Bool { !isSheet }
  /// The "Ahead" divider (iPad column; the Mac inspector has none).
  private var showsAhead: Bool {
    #if os(macOS)
      false
    #else
      isColumn && isTracking
    #endif
  }
  /// The session plays this book, so its playhead means something here.
  private var isTracking: Bool { session.book?.id == book.id }

  var body: some View {
    let notes = book.sortedComments.filter { c in
      switch filter {
      case .all: true
      case .comments: c.kind == .comment
      case .bookmarks: c.kind == .bookmark
      }
    }.filter { $0.kind == .bookmark ? tagFilter == .all : tagFilter.includes($0) }
    let ahead = showsAhead ? min(position.firstAhead, notes.count) : notes.count
    ScrollViewReader { proxy in
      List {
        rows(notes, in: 0..<ahead)
        if showsAhead, !notes.isEmpty {
          AheadDivider()
          if ahead == notes.count { aheadHint }
          rows(notes, in: ahead..<notes.count)
        }
      }
      .themedList()
      #if os(macOS)
        .scrollContentBackground(.hidden)
        .background(Theme.surface)
      #endif
      .background {
        if isColumn {
          PlayheadTracker(notes: notes.map(\.timelineNote), isActive: isTracking) { position = $0 }
        }
      }
      // follows playback; between two notes the reader can scroll freely
      .onChange(of: position.current) { _, current in
        guard let current, session.isPlaying else { return }
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.35)) { proxy.scrollTo(current, anchor: .center) }
      }
    }
    .safeAreaInset(edge: .top) { header }
    #if os(macOS)
      .safeAreaInset(edge: .bottom, spacing: 0) { shortcutsFooter }
    #endif
    .overlay {
      if notes.isEmpty { emptyState }
    }
    .navigationDestination(item: $editing) { CommentDetailView(comment: $0, book: book) }
    .onDisappear { voiceNotes.stop() }
    .confirmationDialog(
      confirmDelete?.kind == .bookmark ? "Delete Bookmark?" : "Delete Comment?", isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } }),
      titleVisibility: .visible
    ) {
      Button("Delete", role: .destructive) {
        if let comment = confirmDelete { store.delete(comment, context: context) }
      }
    } message: {
      Text(confirmDelete?.deleteMessage ?? "")
    }
    .confirmationDialog(
      "Transcribe all comments again in \(transcriptLanguage.displayName)?", isPresented: $confirmTranscribe, titleVisibility: .visible
    ) {
      Button("Transcribe Again") { Task { await session.transcribeAgain(book) } }
    } message: {
      Text("This replaces the current transcripts, including any you edited. Change the language in Settings › Comment Language.")
    }
    .inlineNavigationTitle()
  }

  /// The notes in `range`; a swipe deletes (after confirmation) or plays from shortly before.
  private func rows(_ notes: [Comment], in range: Range<Int>) -> some View {
    ForEach(notes[range]) { comment in
      let isCurrent = isColumn && comment.id == position.current
      row(comment, isCurrent: isCurrent)
        .id(comment.id)
        .swipeActions(edge: .leading) {
          Button("Play", systemImage: "play.fill") { jump(to: comment) }
            .tint(Theme.accent)
        }
        .contextMenu {
          Button("Listen From Here", systemImage: "headphones") { jump(to: comment) }
          Button("Edit", systemImage: "pencil") { editing = comment }
          Divider()
          Button("Delete…", systemImage: "trash", role: .destructive) { confirmDelete = comment }
        }
        .listRowBackground(rowBackground(isCurrent: isCurrent))
        .listRowSeparator(.automatic)
        .listRowSeparatorTint(Theme.surfaceBorder)
    }
    .onDelete { offsets in
      confirmDelete = offsets.first.map { notes[range.lowerBound + $0] }
    }
  }

  @ViewBuilder private func row(_ comment: Comment, isCurrent: Bool) -> some View {
    #if os(macOS)
      // the current note's actions sit beside the link, not inside it
      VStack(alignment: .leading, spacing: 8) {
        NavigationLink {
          CommentDetailView(comment: comment, book: book)
        } label: {
          CommentRow(comment: comment, quote: quote(for: comment), language: settings.commandLanguage, isCurrent: isCurrent)
        }
        if isCurrent { currentActions(comment) }
      }
      .padding(.vertical, 4)
    #else
      NavigationLink {
        CommentDetailView(comment: comment, book: book)
      } label: {
        CommentRow(comment: comment, quote: quote(for: comment), language: settings.commandLanguage, isCurrent: isCurrent)
      }
    #endif
  }

  #if os(macOS)
    private func currentActions(_ comment: Comment) -> some View {
      HStack(spacing: 6) {
        if comment.audioFile != nil {
          let playing = voiceNotes.playing == comment.id
          Button(playing ? "Stop Voice Note" : "Play Voice Note", systemImage: playing ? "stop.fill" : "play.fill") {
            if session.isPlaying, !playing { session.togglePlay() }
            voiceNotes.toggle(comment.id, url: store.audioURL(comment, in: book))
          }
          .help("Plays the recorded comment")
        }
        Button("Edit", systemImage: "pencil") { editing = comment }
          .help("Edit the transcript, tag and position")
      }
      .labelStyle(.titleOnly)
      .buttonStyle(.bordered)
      .controlSize(.small)
      .padding(.leading, 13)
    }
  #endif

  @ViewBuilder private func rowBackground(isCurrent: Bool) -> some View {
    // the note nearest the playhead is lit with the surface colour; no card around it
    if isCurrent {
      Theme.surface
    } else {
      Theme.background
    }
  }

  // MARK: Header and footer

  @ViewBuilder private var header: some View {
    #if os(macOS)
      HStack(spacing: 8) {
        Text("Comments").font(.headline)
        Spacer()
        filterMenu
      }
      .padding(.horizontal, 16)
      .padding(.vertical, 10)
      .background(Theme.surface)
      .overlay(alignment: .bottom) { ThemedDivider() }
    #else
      VStack(spacing: 10) {
        // toolbar items would land in the player's bar (inspector content shares it)
        if isSheet {
          HStack {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
              Text("\(book.comments.filter { $0.kind == .comment }.count)").font(.system(.title2, weight: .heavy))
              Text("comments").font(.system(.title2, weight: .light))
            }
            .accessibilityElement(children: .combine)
            Spacer()
            Button("Done") { dismiss() }
              .buttonStyle(.glass)
          }
        } else {
          HStack(alignment: .firstTextBaseline, spacing: 6) {
            Text("\(book.comments.filter { $0.kind == .comment }.count)").font(.system(.title, weight: .heavy))
            Text("comments").font(.system(.title, weight: .light))
            Spacer()
          }
          .accessibilityElement(children: .combine)
          .accessibilityAddTraits(.isHeader)
        }
        HStack(spacing: 8) {
          Picker("Show", selection: $filter) {
            ForEach(Filter.allCases) { Text($0.rawValue).tag($0) }
          }
          .pickerStyle(.segmented)
          Menu("More", systemImage: tagFilter == .all ? "ellipsis" : "line.3.horizontal.decrease.circle.fill") {
            tagPicker
            transcribeButton
          }
          .labelStyle(.iconOnly)
          .buttonStyle(.glass)
        }
      }
      .padding(.horizontal)
      .padding(.top, isSheet ? 18 : 0)
      .padding(.bottom, 6)
    #endif
  }

  /// "7 · 2 bookmarks"
  private var counts: Text {
    let bookmarks = book.comments.filter { $0.kind == .bookmark }.count
    let comments = book.comments.count - bookmarks
    return bookmarks == 0 ? Text("\(comments)") : Text("\(comments) · ^[\(bookmarks) bookmark](inflect: true)")
  }

  private var tagPicker: some View {
    Picker("Tag", selection: $tagFilter) {
      ForEach(TagFilter.all_, id: \.self) { Text($0.label(settings.commandLanguage)).tag($0) }
    }
  }

  private var transcribeButton: some View {
    Button("Transcribe Comments Again", systemImage: "waveform") { confirmTranscribe = true }
      .disabled(!book.comments.contains { $0.audioFile != nil })
  }

  #if os(macOS)
    /// "All tags": which notes the inspector shows, and transcribing again.
    private var filterMenu: some View {
      Menu {
        Picker("Show", selection: $filter) {
          ForEach(Filter.allCases) { Text($0.rawValue).tag($0) }
        }
        .pickerStyle(.inline)
        tagPicker.pickerStyle(.inline)
        Divider()
        transcribeButton
      } label: {
        Text(filter == .all ? tagFilter.label(settings.commandLanguage) : "\(filter.rawValue) · \(tagFilter.label(settings.commandLanguage))")
      }
      .menuStyle(.button)
      .buttonStyle(.bordered)
      .controlSize(.small)
      .fixedSize()
      .help("Show some comments only")
    }

    private var shortcutsFooter: some View {
      Text("Space play/pause · C comment · B bookmark · ⌥⌘I hide inspector")
        .font(.caption)
        .foregroundStyle(Theme.textSecondary)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Theme.surface)
        .overlay(alignment: .top) { ThemedDivider() }
        .accessibilityLabel("Keyboard shortcuts: Space plays or pauses, C records a comment, B sets a bookmark, Option Command I hides the inspector")
    }
  #endif

  // MARK: Ahead

  /// Nothing lies ahead: how new comments get here.
  private var aheadHint: some View {
    let phrases = VoiceCommand.phrases(settings.commandLanguage)
    let stop = phrases[.stop]?.first ?? "stop"
    let resume = phrases[.resume]?.first ?? "continue"
    return Text("Comments you make from here on appear here as you go. Say “\(stop)”, speak, then “\(resume)”.")
      .font(.subheadline)
      .foregroundStyle(Theme.textSecondary)
      .listRowBackground(Color.clear)
      .listRowSeparator(.hidden)
      .deleteDisabled(true)
  }

  @ViewBuilder private var emptyState: some View {
    switch filter {
    case .bookmarks:
      ContentUnavailableView(
        "No Bookmarks", systemImage: "bookmark",
        description: Text("Say “bookmark” to mark the sentence you just heard. Say it again within two minutes to mark the whole passage in between."))
    default:
      ContentUnavailableView(
        "No Comments Yet", systemImage: "text.bubble",
        description: Text("While the book plays, say “stop”, wait for the beep, and speak. Say “continue” when you’re done."))
    }
  }

  private var transcriptLanguage: VoiceCommand.Language {
    settings.commentLanguage.resolved(bookLanguage: book.language)
  }

  private func quote(for comment: Comment) -> String? {
    guard let content = session.content, isTracking else { return nil }
    let q = comment.record(text: content.text).quotedText(in: content.text)
    return q.isEmpty ? nil : q
  }

  private func jump(to comment: Comment) {
    session.seek(to: max(0, comment.mediaTime - 8))
    if !session.isPlaying { session.togglePlay() }
  }
}

/// The current note and the first one still ahead, among the notes a list shows.
struct PlayheadPosition: Equatable {
  var current: UUID?
  var firstAhead = Int.max
}

/// Follows the playhead (ten updates a second) and reports only when the current note or
/// the divider moves, so the list itself is not rebuilt with every tick.
private struct PlayheadTracker: View {
  let notes: [ReviewTimeline.Note]
  let isActive: Bool
  let onChange: (PlayheadPosition) -> Void
  @Environment(SessionHost.self) private var session

  var body: some View {
    let position = isActive ? Self.position(notes, at: session.currentTime) : PlayheadPosition()
    Color.clear
      .onChange(of: position, initial: true) { _, new in onChange(new) }
  }

  static func position(_ notes: [ReviewTimeline.Note], at time: Double) -> PlayheadPosition {
    let current = ReviewTimeline.nearest(notes, to: time)
    return PlayheadPosition(current: current, firstAhead: ReviewTimeline.firstAhead(notes, at: time, current: current))
  }
}

/// "—— Ahead ——"
private struct AheadDivider: View {
  var body: some View {
    HStack(spacing: 8) {
      Rectangle().fill(Theme.surfaceBorder).frame(height: 1)
      Text("Ahead").font(.footnote).foregroundStyle(Theme.textSecondary)
      Rectangle().fill(Theme.surfaceBorder).frame(height: 1)
    }
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("Ahead of the playhead")
    .accessibilityAddTraits(.isHeader)
    .listRowBackground(Color.clear)
    .listRowSeparator(.hidden)
    .deleteDisabled(true)
  }
}

private struct CommentRow: View {
  let comment: Comment
  let quote: String?
  var language: VoiceCommand.Language = .english
  var isCurrent = false

  var body: some View {
    // a log row: the time in its own column, then the tag and the comment
    HStack(alignment: .firstTextBaseline, spacing: 12) {
      time
        .font(.subheadline.weight(isCurrent ? .bold : .medium).monospacedDigit())
        .foregroundStyle(isCurrent ? Theme.textPrimary : Theme.textSecondary)
        .frame(width: 52, alignment: .leading)
      VStack(alignment: .leading, spacing: 6) {
        meta
        if comment.kind == .bookmark {
          Text(quote.map { "“\($0)”" } ?? "Bookmark")
            .font(Theme.bookFont(.body))
            .foregroundStyle(quote == nil ? Theme.textPrimary : Theme.textSecondary)
            .lineLimit(comment.isPassage ? 5 : 3)
        } else {
          transcript
          if let quote {
            Text(quote)
              .font(Theme.bookFont(.footnote))
              .foregroundStyle(Theme.textSecondary)
              .lineLimit(2)
          }
        }
        #if !os(macOS)
          if isCurrent {
            Text(nearPlayhead)
              .font(.footnote)
              .foregroundStyle(Theme.textSecondary)
          }
        #endif
      }
    }
    .padding(.vertical, 2)
    .accessibilityElement(children: .combine)
    .accessibilityAddTraits(isCurrent ? .isSelected : [])
  }

  /// "Near the playhead · 0:22 voice note"
  private var nearPlayhead: String {
    guard comment.audioFile != nil, comment.duration > 0 else { return "Near the playhead" }
    return "Near the playhead · \(TimeFormat.clock(comment.duration)) voice note"
  }

  private var meta: some View {
    HStack(spacing: 8) {
      if comment.kind == .bookmark {
        TagOutline(text: comment.isPassage ? "Passage" : "Bookmark", tint: Theme.bookmark)
        if let end = comment.endMediaTime { Text("to \(TimeFormat.clock(end))").monospacedDigit() }
      } else if let tag = comment.tag {
        TagChip(tag: tag, language: language)
      }
      if let chapter = comment.chapter { Text(chapter).lineLimit(1) }
    }
    .font(.caption)
    .foregroundStyle(isCurrent ? Theme.textPrimary : Theme.textSecondary)
  }

  private var time: Text { Text(TimeFormat.clock(comment.mediaTime)) }

  @ViewBuilder private var transcript: some View {
    switch comment.transcriptStatus {
    case .pending:
      Label("Transcribing…", systemImage: "hourglass").font(.subheadline).foregroundStyle(Theme.textSecondary)
    case .failed:
      Label("Not transcribed. Tap to listen.", systemImage: "waveform").font(.subheadline).foregroundStyle(Theme.warning)
    default:
      Text(comment.transcript.isEmpty ? "(empty)" : comment.transcript)
        .font(.body)
        .foregroundStyle(Theme.textPrimary)
    }
  }
}
