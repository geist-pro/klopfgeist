import MarginVoiceCore
import SwiftData
import SwiftUI

struct LibraryView: View {
  @Binding var selection: UUID?
  /// Opens the conversion sheet for a picked document.
  let onConvert: (URL) -> Void
  @Environment(BookStore.self) private var store
  @Environment(AppSettings.self) private var settings
  @Environment(SessionHost.self) private var session
  @Environment(ConversionCenter.self) private var conversions
  @Environment(\.modelContext) private var context
  @Environment(\.scenePhase) private var scenePhase
  @Environment(\.horizontalSizeClass) private var sizeClass
  @Query(sort: \Book.addedAt, order: .reverse) private var books: [Book]
  @State private var importing = false
  @State private var pickingDocument = false
  @State private var showSettings = false
  @State private var showSamples = false
  @State private var importError: String?
  @State private var isImporting = false
  /// An import (from the inbox or picked files) is running.
  @State private var isBusy = false
  /// Files picked while another import ran.
  @State private var pickedWhileBusy: [URL] = []
  /// The inbox changed during an import: look again when it is done.
  @State private var inboxChanged = false
  @State private var watcher = InboxWatcher()
  /// Inbox documents already offered this session (cancelling leaves them in the folder).
  @State private var offeredDocuments: Set<URL> = []
  /// A book swiped away, waiting for confirmation (its comments and recordings go with it).
  @State private var confirmDelete: Book?

  var body: some View {
    List(selection: $selection) {
      if !conversions.visibleJobs.isEmpty {
        Section("Creating") {
          ForEach(conversions.visibleJobs) { job in ConversionRow(job: job) }
        }
        .themedRows()
      }
      ForEach(books) { book in
        BookRow(book: book)
          .themedRows()
          .tag(book.id)
          .swipeActions(edge: .leading) {
            Button(book.isFinished ? "Mark as Unplayed" : "Mark as Finished", systemImage: book.isFinished ? "arrow.uturn.backward" : "checkmark") {
              session.setFinished(!book.isFinished, book, context: context)
            }
            .tint(book.isFinished ? Theme.accent : Theme.success)
          }
      }
      .onDelete { offsets in
        confirmDelete = offsets.first.map { books[$0] }
      }
    }
    .themedList()
    .navigationTitle("Library")
    .safeAreaInset(edge: .bottom) {
      // iPhone: the player is pushed away when the library shows, so keep the open book in reach
      if sizeClass == .compact, selection == nil, let book = session.book {
        NowPlayingBar(book: book) { selection = book.id }
          .transition(.move(edge: .bottom).combined(with: .opacity))
      }
    }
    .overlay {
      if books.isEmpty, conversions.visibleJobs.isEmpty {
        ContentUnavailableView {
          Label("No Books Yet", systemImage: "books.vertical")
        } description: {
          Text("Turn a PDF into an audiobook right here, or import one you already have (an .m4b, with its .txt and .timing.json when there are any). You can also drop files onto the app or into the Klopfgeist folder in Files.")
        } actions: {
          Button("Convert a Document") { pickingDocument = true }
            .buttonStyle(.borderedProminent)
          Button("Import Audiobook") { importing = true }
          Button("Try a Sample Paper") { showSamples = true }
        }
      }
    }
    .overlay {
      if isImporting { ProgressView("Importing…").padding().cardSurface() }
    }
    .toolbar {
      ToolbarItem(placement: .primaryAction) {
        Menu("Add", systemImage: "plus") {
          Button("Convert Document…", systemImage: "doc.text") { pickingDocument = true }
          Button("Import Audiobook…", systemImage: "square.and.arrow.down") { importing = true }
          Button("Sample Papers…", systemImage: "headphones") { showSamples = true }
        }
      }
      ToolbarItem(placement: .topBarLeading) {
        Button("Settings", systemImage: "gearshape") { showSettings = true }
      }
    }
    .fileImporter(isPresented: $importing, allowedContentTypes: BookStore.importTypes, allowsMultipleSelection: true) { result in
      switch result {
      case .success(let urls): Task { await importBooks(urls) }
      case .failure(let error): importError = error.localizedDescription
      }
    }
    .background {
      // a second file importer must live on its own view
      Color.clear
        .fileImporter(isPresented: $pickingDocument, allowedContentTypes: DocumentExtractor.supportedTypes) { result in
          if case .success(let url) = result { onConvert(url) }
        }
    }
    .alert("Import Failed", isPresented: Binding(presenting: $importError)) {
      Button("OK") {}
    } message: {
      Text(importError ?? "")
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
    .sheet(isPresented: $showSettings) { SettingsView() }
    .sheet(isPresented: $showSamples) { SamplesView { selection = $0 } }
    .task {
      #if DEBUG
        if ProcessInfo.processInfo.environment["DRAFTTALK_SHOW"] == "settings" { showSettings = true }
        debugSamples()
      #endif
      await importInbox()
      watcher.start(BookStore.inboxURL) { Task { await importInbox() } }
    }
    .onChange(of: scenePhase) { _, phase in
      if phase == .active { Task { await importInbox() } }
    }
    #if DEBUG
      // Simulator testing: DRAFTTALK_IMPORT="/path/a.m4b:/path/a.txt" imports on launch.
      // Device setup: DRAFTTALK_CLEAR_LIBRARY=1 deletes every book with its comments (settings stay).
      .task {
        guard ProcessInfo.processInfo.environment["DRAFTTALK_CLEAR_LIBRARY"] == "1" else { return }
        session.close()
        for book in books { store.delete(book, context: context) }
        selection = nil
      }
      .task {
        guard books.isEmpty, let paths = ProcessInfo.processInfo.environment["DRAFTTALK_IMPORT"] else { return }
        await importBooks(paths.split(separator: ":").map { URL(fileURLWithPath: String($0)) })
      }
    #endif
  }

  #if DEBUG
    /// DRAFTTALK_SHOW=samples opens the sample papers; DRAFTTALK_SAMPLE=<file> also downloads one.
    private func debugSamples() {
      let env = ProcessInfo.processInfo.environment
      if env["DRAFTTALK_SHOW"] == "samples" { showSamples = true }
      let sample = SampleBooks.all.first { $0.file == env["DRAFTTALK_SAMPLE"] }
      if let sample { SampleDownloads.shared.download(sample) }
    }
  #endif

  private func importInbox() async {
    guard !isBusy else {
      inboxChanged = true
      return
    }
    // a spinner only when there is something to import
    await importing(spinner: store.inboxHasAudiobooks) {
      let imported = await store.importInbox(into: context, defaultRate: settings.defaultRate)
      if let first = imported.first, selection == nil { selection = first.id }
      for url in store.inboxDocuments() where !offeredDocuments.contains(url) {
        offeredDocuments.insert(url)
        onConvert(url)
      }
    }
  }

  private func importBooks(_ urls: [URL]) async {
    guard !isBusy else {
      pickedWhileBusy += urls
      return
    }
    await importing(spinner: true) {
      do {
        let imported = try await store.importFiles(urls, into: context, defaultRate: settings.defaultRate)
        if let first = imported.first { selection = first.id }
      } catch {
        importError = error.localizedDescription
      }
    }
  }

  /// Runs one import at a time (they share the model context), then what came in meanwhile.
  private func importing(spinner: Bool, _ work: () async -> Void) async {
    isBusy = true
    isImporting = spinner
    defer {
      isBusy = false
      isImporting = false
      if !pickedWhileBusy.isEmpty {
        let urls = pickedWhileBusy
        pickedWhileBusy = []
        Task { await importBooks(urls) }
      } else if inboxChanged {
        inboxChanged = false
        Task { await importInbox() }
      }
    }
    await work()
  }
}

private struct BookRow: View {
  let book: Book
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(SessionHost.self) private var session

  /// An index row: the title in the light cut, the time left in the heavy one, then the
  /// counts and a hairline for how far you are.
  var body: some View {
    let isCurrent = session.book?.id == book.id
    let isUpNext = session.upNext?.id == book.id
    VStack(alignment: .leading, spacing: 8) {
      HStack(alignment: .firstTextBaseline, spacing: 12) {
        Text(book.title)
          .font(.system(.title3, weight: .light))
          .lineLimit(3)
          .frame(maxWidth: .infinity, alignment: .leading)
        timeLeft
      }
      HStack(spacing: 10) {
        if isCurrent {
          Image(systemName: "waveform")
            // a symbol that loops for as long as the book plays stands still with Reduce Motion
            .symbolEffect(.variableColor.iterative, isActive: session.isPlaying && !reduceMotion)
            .foregroundStyle(Theme.accent)
            .accessibilityLabel(session.isPlaying ? "Playing" : "Open")
        }
        let comments = book.comments.filter { $0.kind == .comment }.count
        let bookmarks = book.comments.count - comments
        if comments > 0 { Label("\(comments)", systemImage: "text.bubble").accessibilityLabel("\(comments) comments") }
        if bookmarks > 0 { Label("\(bookmarks)", systemImage: "bookmark").accessibilityLabel("\(bookmarks) bookmarks") }
        if book.textFile == nil {
          Label("No text", systemImage: "exclamationmark.triangle")
            .foregroundStyle(Theme.warning)
        }
        if isUpNext {
          Text("Up next")
            .font(.caption.weight(.semibold))
            .foregroundStyle(Theme.accent)
        }
        // how far you are, as a hairline
        GeometryReader { geo in
          ZStack(alignment: .leading) {
            Rectangle().fill(Theme.control)
            Rectangle().fill(Theme.textPrimary).frame(width: geo.size.width * (book.isFinished ? 1 : book.progress))
          }
        }
        .frame(height: 2)
        .accessibilityHidden(true)
      }
      .font(.footnote)
      .foregroundStyle(Theme.textSecondary)
    }
    .padding(.vertical, 8)
    .overlay(alignment: .bottom) {
      // the open book carries the accent rule
      if isCurrent { Rectangle().fill(Theme.accent).frame(height: 2).offset(y: 9) }
    }
    .accessibilityElement(children: .combine)
  }

  @ViewBuilder private var timeLeft: some View {
    if book.isFinished {
      Label("Done", systemImage: "checkmark").font(.headline.weight(.heavy)).foregroundStyle(Theme.success)
    } else {
      let left = book.position > 1 ? max(0, book.duration - book.position) : book.duration
      let minutes = Int(left / 60)
      HStack(alignment: .firstTextBaseline, spacing: 3) {
        Text(minutes >= 60 ? "\(minutes / 60):\(String(format: "%02d", minutes % 60))" : "\(max(1, minutes))")
          .font(.system(.title2, weight: .heavy).monospacedDigit())
          .foregroundStyle(Theme.textPrimary)
        Text(minutes >= 60 ? "h" : "min")
          .font(.footnote)
          .foregroundStyle(Theme.textSecondary)
        if book.position > 1 { Text("left").font(.footnote).foregroundStyle(Theme.textSecondary) }
      }
      // one spoken duration instead of "12", "min", "left"
      .accessibilityElement(children: .ignore)
      .accessibilityLabel(
        (Duration.seconds(max(60, minutes * 60)).formatted(.units(allowed: [.hours, .minutes], width: .wide)))
          + (book.position > 1 ? " left" : ""))
      .fixedSize()
    }
  }
}
