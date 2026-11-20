import AppKit
import MarginVoiceCore
import SwiftData
import SwiftUI
import UniformTypeIdentifiers

/// Library in the sidebar, the book's text in the middle, comments in the inspector,
/// and the transport along the bottom of the window.
struct MacRootView: View {
  @Query(sort: \Book.addedAt, order: .reverse) private var books: [Book]
  @State private var selection: UUID?
  @State private var showInspector = true
  @State private var showChapters = false
  @State private var showExport = false
  @State private var importing = false
  @State private var pickingDocument = false
  @State private var converting: IdentifiedURL?
  @State private var conversionQueue: [URL] = []
  @State private var importError: String?
  @State private var isDropTarget = false
  @Environment(BookStore.self) private var store
  @Environment(SessionHost.self) private var session
  @Environment(AppSettings.self) private var settings
  @Environment(\.modelContext) private var context
  @Environment(\.openSettings) private var openSettings

  private var selectedBook: Book? { selection.flatMap { id in books.first { $0.id == id } } }

  var body: some View {
    NavigationSplitView {
      MacLibrarySidebar(selection: $selection, onConvert: convert)
        .navigationSplitViewColumnWidth(min: 240, ideal: 300, max: 400)
    } detail: {
      if let book = selectedBook {
        ReadingView(book: book)
          .id(book.id)
      } else {
        ContentUnavailableView(
          "No Book Open", systemImage: "headphones",
          description: Text("Choose a book in the sidebar, drop a PDF or an audiobook here, or press ⌘N to convert a document."))
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.background)
      }
    }
    .inspector(isPresented: $showInspector) {
      if let book = selectedBook {
        NavigationStack { CommentsView(book: book) }
          .id(book.id)
      } else {
        ContentUnavailableView("Comments & Bookmarks", systemImage: "text.bubble", description: Text("Open a book to see its comments."))
          .frame(maxWidth: .infinity, maxHeight: .infinity)
          .background(Theme.background)
      }
    }
    .inspectorColumnWidth(min: 300, ideal: 320, max: 520)
    .safeAreaInset(edge: .bottom, spacing: 0) {
      if let book = selectedBook {
        TransportBar(book: book)
      }
    }
    .background(Theme.background)
    // the title bar in the palette, not the window's material
    .toolbarBackground(Theme.background, for: .windowToolbar)
    .toolbarBackgroundVisibility(.visible, for: .windowToolbar)
    // the system draws the window title in white whatever the palette: draw our own
    // (hiding the system's title instead pulls the toolbar buttons away from the window's edge)
    .navigationTitle("")
    .background(WindowName("Klopfgeist"))
    .toolbar {
      ToolbarItem(placement: .navigation) {
        WindowTitle(book: selectedBook, showChapters: $showChapters)
      }
      .sharedBackgroundVisibility(.hidden)
      if selectedBook != nil {
        // what the microphone is doing, where everyone looks for it on a Mac: the title bar
        ToolbarItem(placement: .primaryAction) {
          ListeningStatusView(detailed: true)
        }
        .sharedBackgroundVisibility(.hidden)
      }
      ToolbarItem(placement: .primaryAction) {
        Button("Export…") { showExport = true }
          .disabled(selectedBook == nil)
          .help("Export the comments as Markdown, a review draft or an annotated PDF (⌘E)")
      }
      ToolbarItem(placement: .primaryAction) {
        Toggle(isOn: $showInspector) {
          Label("Comments", systemImage: "sidebar.trailing")
        }
        .toggleStyle(.button)
        .help(showInspector ? "Hide the comments inspector (⌥⌘I)" : "Show the comments inspector (⌥⌘I)")
        .accessibilityLabel("Comments inspector")
      }
    }
    .focusedSceneValue(\.commentsInspectorShown, $showInspector)
    .sheet(isPresented: $showExport) {
      if let book = selectedBook { ExportView(book: book).frame(width: 520, height: 560) }
    }
    .sheet(item: $converting, onDismiss: showNextConversion) { item in
      ConvertView(sourceURL: item.url) {
        if BookStore.isInInbox(item.url) { try? FileManager.default.removeItem(at: item.url) }
      }
      .frame(width: 560, height: 620)
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
          if case .success(let url) = result { convert(url) }
        }
    }
    .alert("Something Went Wrong", isPresented: Binding(presenting: Bindable(session).errorMessage)) {
      Button("OK") {}
    } message: {
      Text(session.errorMessage ?? "")
    }
    .alert("Import Failed", isPresented: Binding(presenting: $importError)) {
      Button("OK") {}
    } message: {
      Text(importError ?? "")
    }
    // files dragged in from the Finder land in the inbox, which the sidebar imports or offers for conversion
    .onDrop(of: [.item], isTargeted: $isDropTarget) { BookStore.receiveDrop($0) }
    .overlay {
      if isDropTarget {
        RoundedRectangle(cornerRadius: 24)
          .strokeBorder(Theme.accent, style: StrokeStyle(lineWidth: 3, dash: [10, 6]))
          .overlay {
            Label("Drop PDFs, documents or audiobooks", systemImage: "square.and.arrow.down")
              .font(.headline)
              .padding()
              .cardSurface()
          }
          .padding(8)
          .allowsHitTesting(false)
      }
    }
    // the session moves on to the next book by itself when one ends
    .onChange(of: session.book?.id) { _, id in
      if let id, id != selection { selection = id }
    }
    .onReceive(NotificationCenter.default.publisher(for: .draftTalkCommand)) { note in
      switch note.object as? MacCommand {
      case .convert: pickingDocument = true
      case .importAudiobook: importing = true
      case .export: if selectedBook != nil { showExport = true }
      case .chapters: if !(selectedBook?.chapters.isEmpty ?? true) { showChapters = true }
      case .toggleFinished, .deleteBook: break  // the sidebar's
      case nil: break
      }
    }
    #if DEBUG
      // screenshots and tests: DRAFTTALK_OPEN=<n> opens the n-th book, DRAFTTALK_SEEK=<s> moves there (paused),
      // DRAFTTALK_DEMO=1 adds a sample comment and bookmark to it, DRAFTTALK_PLAY=1 plays,
      // DRAFTTALK_SHOW=settings|export|chapters opens that window, sheet or popover
      .task {
        let env = ProcessInfo.processInfo.environment
        if env["DRAFTTALK_SHOW"] == "settings" { openSettings() }
        guard let open = env["DRAFTTALK_OPEN"] else { return }
        try? await Task.sleep(for: .seconds(1))
        let i = Int(open) ?? 0
        guard books.indices.contains(i) else { return }
        let book = books[i]
        selection = book.id
        try? await Task.sleep(for: .seconds(2))
        if let seek = env["DRAFTTALK_SEEK"].flatMap(Double.init) { session.seek(to: seek) }
        if env["DRAFTTALK_DEMO"] != nil, book.comments.isEmpty, let content = session.content {
          let t = session.currentTime
          let c = Comment(kind: .comment, mediaTime: t, estimatedOffset: content.offset(at: t), confidence: 0.8, chapter: book.chapterTitle(at: t), audioFile: nil)
          c.transcript = "This claim needs a citation, and the number should match the table."
          c.transcriptStatus = .done
          context.insert(c)
          c.book = book
          let b = Comment(kind: .bookmark, mediaTime: t + 40, estimatedOffset: content.offset(at: t + 40), confidence: 0.8, chapter: book.chapterTitle(at: t), audioFile: nil)
          b.endMediaTime = t + 70
          b.endEstimatedOffset = content.offset(at: t + 70)
          context.insert(b)
          b.book = book
          try? context.save()
        }
        if env["DRAFTTALK_PLAY"] != nil { session.togglePlay() }
        if env["DRAFTTALK_SHOW"] == "export" { showExport = true }
        if env["DRAFTTALK_SHOW"] == "chapters" { showChapters = true }
      }
    #endif
    .onOpenURL { url in
      if DocumentExtractor.canConvert(url) {
        convert(url)
      } else if BookFiles.isAudio(url) || url.pathExtension.lowercased() == BookPackage.fileExtension {
        Task { await importBooks([url]) }
      }
    }
  }

  /// One conversion sheet at a time; documents dropped together wait their turn.
  private func convert(_ url: URL) {
    if converting == nil {
      converting = IdentifiedURL(url: url)
    } else if converting?.url != url, !conversionQueue.contains(url) {
      conversionQueue.append(url)
    }
  }

  private func showNextConversion() {
    guard !conversionQueue.isEmpty else { return }
    converting = IdentifiedURL(url: conversionQueue.removeFirst())
  }

  private func importBooks(_ urls: [URL]) async {
    do {
      let imported = try await store.importFiles(urls, into: context, defaultRate: settings.defaultRate)
      if let first = imported.first { selection = first.id }
    } catch {
      importError = error.localizedDescription
    }
  }
}

/// "Book — Chapter" in the title bar; the chapter opens the chapter list.
private struct WindowTitle: View {
  let book: Book?
  @Binding var showChapters: Bool
  @Environment(SessionHost.self) private var session

  var body: some View {
    if let book {
      let chapter = book.chapterTitle(at: session.currentTime).flatMap { $0 == book.title ? nil : $0 }
      HStack(spacing: 4) {
        Text(book.title)
          .font(.headline)
          .lineLimit(1)
        if let chapter {
          Button {
            showChapters = true
          } label: {
            Text("— \(chapter)")
              .font(.body)
              .foregroundStyle(Theme.textSecondary)
              .lineLimit(1)
          }
          .buttonStyle(.plain)
          .help("Chapters (⌥⌘K)")
          .accessibilityLabel("Chapter: \(chapter)")
          .accessibilityHint("Shows the chapters")
        }
      }
      .popover(isPresented: $showChapters) {
        ChaptersView(book: book).frame(width: 420, height: 520)
      }
      .foregroundStyle(Theme.textPrimary)
      .frame(maxWidth: 520, alignment: .leading)
      .fixedSize(horizontal: false, vertical: true)
    } else {
      Text("Klopfgeist")
        .font(.headline)
        .foregroundStyle(Theme.textPrimary)
        .fixedSize()
    }
  }
}

/// Names a window whose title is empty, for the Window menu and for VoiceOver.
private struct WindowName: NSViewRepresentable {
  let name: String
  init(_ name: String) { self.name = name }

  final class Marker: NSView {
    var name = ""
    override func viewDidMoveToWindow() {
      super.viewDidMoveToWindow()
      // after SwiftUI has set the window up
      DispatchQueue.main.async { [weak self] in
        guard let self, let window = self.window else { return }
        window.setAccessibilityTitle(self.name)
        NSApp.addWindowsItem(window, title: self.name, filename: false)
      }
    }
  }

  func makeNSView(context: Context) -> NSView {
    let marker = Marker()
    marker.name = name
    return marker
  }

  func updateNSView(_ view: NSView, context: Context) {}
}
