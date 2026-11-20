import MarginVoiceCore
import SwiftData
import SwiftUI

/// The library as a sidebar: books newest first, conversions in progress on top.
struct MacLibrarySidebar: View {
  @Binding var selection: UUID?
  /// Opens the conversion sheet for a document.
  let onConvert: (URL) -> Void
  @Environment(BookStore.self) private var store
  @Environment(AppSettings.self) private var settings
  @Environment(SessionHost.self) private var session
  @Environment(ConversionCenter.self) private var conversions
  @Environment(\.modelContext) private var context
  @Query(sort: \Book.addedAt, order: .reverse) private var books: [Book]
  @State private var confirmDelete: Book?
  @State private var showSamples = false
  @State private var isBusy = false
  @State private var inboxChanged = false
  @State private var watcher = InboxWatcher()
  /// Inbox documents already offered this session (cancelling leaves them in the folder).
  @State private var offeredDocuments: Set<URL> = []

  var body: some View {
    List(selection: $selection) {
      if !conversions.visibleJobs.isEmpty {
        Section {
          ForEach(conversions.visibleJobs) { job in ConversionRow(job: job) }
        } header: {
          Text("Creating").foregroundStyle(Theme.textSecondary)
        }
      }
      Section {
        ForEach(books) { book in
          SidebarBookRow(book: book)
            .tag(book.id)
            .listRowBackground(
              // the fill is close to the sidebar's surface: an accent edge marks the selection too
              RoundedRectangle(cornerRadius: Theme.controlRadius, style: .continuous)
                .fill(selection == book.id ? Theme.surfaceBorder : .clear)
                .overlay(alignment: .leading) {
                  if selection == book.id { Rectangle().fill(Theme.accent).frame(width: 3) }
                }
                .padding(.horizontal, 8))
            .contextMenu {
              Button(book.isFinished ? "Mark as Unplayed" : "Mark as Finished") { session.setFinished(!book.isFinished, book, context: context) }
              Divider()
              Button("Delete…", role: .destructive) { confirmDelete = book }
            }
        }
      } header: {
        Text("Library").foregroundStyle(Theme.textSecondary)
      }
    }
    .listStyle(.sidebar)
    .themedSidebar()
    .safeAreaInset(edge: .bottom, spacing: 0) { SyncFooter() }
    .background(NoSystemSelectionHighlight())
    .navigationTitle("Library")
    // Book menu: the row actions for the selected book, reachable without the context menu
    .onReceive(NotificationCenter.default.publisher(for: .draftTalkCommand)) { note in
      guard let book = books.first(where: { $0.id == selection }) else { return }
      switch note.object as? MacCommand {
      case .toggleFinished: session.setFinished(!book.isFinished, book, context: context)
      case .deleteBook: confirmDelete = book
      default: break
      }
    }
    .overlay {
      if books.isEmpty, conversions.visibleJobs.isEmpty {
        ContentUnavailableView {
          Label("No Books Yet", systemImage: "books.vertical")
        } description: {
          Text("Convert a PDF (⌘N) or import an audiobook you already have (⌘O).")
        } actions: {
          Button("Try a Sample Paper") { showSamples = true }
        }
      }
    }
    .toolbar {
      ToolbarItem(placement: .primaryAction) {
        Menu("Add", systemImage: "plus") {
          Button("Convert Document…", systemImage: "doc.text") {
            NotificationCenter.default.post(name: .draftTalkCommand, object: MacCommand.convert)
          }
          Button("Import Audiobook…", systemImage: "square.and.arrow.down") {
            NotificationCenter.default.post(name: .draftTalkCommand, object: MacCommand.importAudiobook)
          }
          Button("Sample Papers…", systemImage: "headphones") { showSamples = true }
        }
      }
    }
    .confirmationDialog(
      "Delete “\(confirmDelete?.title ?? "")”?", isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } }), titleVisibility: .visible
    ) {
      Button("Delete", role: .destructive) {
        guard let book = confirmDelete else { return }
        if selection == book.id { selection = nil }
        if session.book?.id == book.id { session.close() }
        store.delete(book, context: context)
        session.refreshUpNext()
      }
    } message: {
      let n = confirmDelete?.comments.count ?? 0
      Text(n == 0 ? "The audiobook is removed from the library." : "The audiobook, its \(n) comments and their recordings are removed.")
    }
    .sheet(isPresented: $showSamples) { SamplesView { selection = $0 } }
    .task {
      await importInbox()
      watcher.start(BookStore.inboxURL) { Task { await importInbox() } }
    }
  }

  /// Files dropped on the window or copied into the inbox folder: audiobooks are
  /// imported, documents offered for conversion once.
  private func importInbox() async {
    guard !isBusy else {
      inboxChanged = true
      return
    }
    isBusy = true
    defer {
      isBusy = false
      if inboxChanged {
        inboxChanged = false
        Task { await importInbox() }
      }
    }
    let imported = await store.importInbox(into: context, defaultRate: settings.defaultRate)
    if let first = imported.first, selection == nil { selection = first.id }
    for url in store.inboxDocuments() where !offeredDocuments.contains(url) {
      offeredDocuments.insert(url)
      onConvert(url)
    }
  }
}

/// The sidebar's selection in the palette, not the system accent colour: turns off the
/// table's own highlight, and the row paints its own. Keyboard selection keeps working.
private struct NoSystemSelectionHighlight: NSViewRepresentable {
  func makeNSView(context: Context) -> NSView {
    let view = NSView()
    DispatchQueue.main.async { Self.apply(from: view) }
    return view
  }

  func updateNSView(_ view: NSView, context: Context) {
    DispatchQueue.main.async { Self.apply(from: view) }
  }

  private static func apply(from view: NSView) {
    // the list is a sibling of this background view: look from the nearest shared parent
    var parent = view.superview
    while let p = parent {
      if let table = firstTable(in: p) {
        table.selectionHighlightStyle = .none
        return
      }
      parent = p.superview
    }
  }

  private static func firstTable(in view: NSView) -> NSTableView? {
    if let table = view as? NSTableView { return table }
    for sub in view.subviews {
      if let table = firstTable(in: sub) { return table }
    }
    return nil
  }
}

/// Title, progress, and "7 comments · 31 min left".
private struct SidebarBookRow: View {
  let book: Book
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(SessionHost.self) private var session

  var body: some View {
    let isCurrent = session.book?.id == book.id
    VStack(alignment: .leading, spacing: 4) {
      HStack(alignment: .firstTextBaseline, spacing: 6) {
        if isCurrent {
          Image(systemName: "waveform")
            // a symbol that loops for as long as the book plays stands still with Reduce Motion
            .symbolEffect(.variableColor.iterative, isActive: session.isPlaying && !reduceMotion)
            .foregroundStyle(Theme.accent)
            .accessibilityLabel(session.isPlaying ? "Playing" : "Open")
        }
        Text(book.title)
          .font(.body.weight(isCurrent ? .semibold : .regular))
          .lineLimit(2)
      }
      if !book.isFinished, book.position > 1 {
        ProgressView(value: book.progress)
          .tint(Theme.accent)
          .controlSize(.mini)
          .accessibilityLabel("Progress")
      }
      HStack(spacing: 6) {
        Text(
          LibraryRowSummary.line(
            comments: book.comments.filter { $0.kind == .comment }.count, position: book.position, duration: book.duration,
            isFinished: book.isFinished))
        if book.textFile == nil {
          Label("No text", systemImage: "exclamationmark.triangle")
            .labelStyle(.iconOnly)
            .foregroundStyle(Theme.warning)
            .help("This book has no narration text: comments are placed by time only")
        }
      }
      .font(.caption)
      .foregroundStyle(Theme.textSecondary)
      .lineLimit(1)
    }
    .padding(.vertical, 2)
    .accessibilityElement(children: .combine)
  }
}

/// "Synced with iCloud 2 min ago" under the library, from the sync centre's state.
private struct SyncFooter: View {
  @Environment(SyncCenter.self) private var sync

  var body: some View {
    TimelineView(.periodic(from: .now, by: 30)) { context in
      if let line = SyncStatusLine.text(
        iCloud: sync.iCloudEnabled, webDAV: sync.webDAVEnabled, isSyncing: sync.isSyncing, lastSync: sync.lastSync,
        error: sync.error, now: context.date)
      {
        Text(line)
          .font(.caption)
          .foregroundStyle(sync.error == nil ? Theme.textSecondary : Theme.warning)
          .lineLimit(2)
          .frame(maxWidth: .infinity, alignment: .leading)
          .padding(.horizontal, 18)
          .padding(.vertical, 10)
          .background(Theme.surface)
          .help(sync.error ?? sync.lastReport ?? "")
      }
    }
  }
}
