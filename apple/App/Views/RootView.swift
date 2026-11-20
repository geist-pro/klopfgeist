import MarginVoiceCore
import SwiftData
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Library in the sidebar (iPad) or as the root of the stack (iPhone); player as detail.
struct RootView: View {
  @Query(sort: \Book.addedAt, order: .reverse) private var books: [Book]
  @State private var selection: UUID?
  /// Starts with the library showing, also in portrait (where the system would hide it and
  /// open on an empty page). It folds away when a book opens, so the player (and its notes) get the window.
  @State private var columns = NavigationSplitViewVisibility.all
  @State private var converting: IdentifiedURL?
  @State private var conversionQueue: [URL] = []
  @State private var isDropTarget = false
  @Environment(BookStore.self) private var store
  @Environment(SessionHost.self) private var session
  @Environment(AppSettings.self) private var settings
  @Environment(\.modelContext) private var context
  @Environment(\.horizontalSizeClass) private var sizeClass

  var body: some View {
    NavigationSplitView(columnVisibility: $columns) {
      LibraryView(selection: $selection) { convert($0) }
        // the player's own "Library" button replaces the system's sidebar toggle
        .toolbar(removing: showsLibraryButton ? .sidebarToggle : nil)
    } detail: {
      if let id = selection, let book = books.first(where: { $0.id == id }) {
        PlayerView(book: book, onShowLibrary: showsLibraryButton ? { withAnimation { columns = .all } } : nil)
          .id(book.id)
      } else {
        ContentUnavailableView {
          Label("No Book Open", systemImage: "headphones")
        } description: {
          Text("Choose a book from the library, or import an .m4b and its .txt.")
        } actions: {
          // the library can be dismissed in portrait
          if sizeClass == .regular, columns == .detailOnly {
            Button("Show Library") { withAnimation { columns = .all } }
          }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.background)
      }
    }
    .background(Theme.background)
    .onChange(of: selection) { _, id in
      // (a compact window shows one column whatever this says)
      withAnimation { columns = id == nil ? .all : .detailOnly }
    }
    // the session moves on to the next book by itself when one ends
    .onChange(of: session.book?.id) { _, id in
      if let id, id != selection { selection = id }
    }
    .sheet(item: $converting, onDismiss: showNextConversion) { item in
      ConvertView(sourceURL: item.url) {
        // the new book keeps its own copy of the source
        if BookStore.isInInbox(item.url) { try? FileManager.default.removeItem(at: item.url) }
      }
    }
    // here, not on the player: errors also come from the library's Now Playing bar
    .alert("Something Went Wrong", isPresented: Binding(presenting: Bindable(session).errorMessage)) {
      Button("OK") {}
    } message: {
      Text(session.errorMessage ?? "")
    }
    // files dragged in from a Mac (Universal Control) or another app
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
    #if DEBUG
      // Simulator testing: DRAFTTALK_CONVERT="/path/paper.pdf" opens the conversion sheet on launch.
      .task {
        if let path = ProcessInfo.processInfo.environment["DRAFTTALK_CONVERT"] { convert(URL(fileURLWithPath: path)) }
        // DRAFTTALK_OPEN=<n> opens the n-th book of the library (screenshots); DRAFTTALK_PLAY=1 also plays it
        let env = ProcessInfo.processInfo.environment
        // DRAFTTALK_ORIENTATION=landscape turns the simulator (screenshots without the Simulator app)
        if env["DRAFTTALK_ORIENTATION"] == "landscape",
          let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene
        {
          scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscapeLeft)) { _ in }
        }
        if let open = env["DRAFTTALK_OPEN"] {
          let i = Int(open) ?? 0
          // like a tap, after the first layout: a selection before it doesn't fold the library
          try? await Task.sleep(for: .seconds(2))
          selection = books.indices.contains(i) ? books[i].id : books.first?.id
          // DRAFTTALK_SHOW=nowplaying goes back to the library with the book still open
          if env["DRAFTTALK_SHOW"] == "nowplaying" {
            try? await Task.sleep(for: .seconds(2))
            selection = nil
          }
          if env["DRAFTTALK_PLAY"] != nil {
            try? await Task.sleep(for: .seconds(3))
            session.togglePlay()
          }
        }
      }
    #endif
    // "Open in DraftTalk" from Files, Mail or the share sheet
    .onOpenURL { url in
      if DocumentExtractor.canConvert(url) {
        convert(url)
      } else if BookFiles.isAudio(url) || url.pathExtension.lowercased() == BookPackage.fileExtension {
        Task {
          if let book = try? await store.importFiles([url], into: context, defaultRate: settings.defaultRate).first {
            selection = book.id
          }
        }
      }
    }
  }

  /// iPad with the player filling the window: "‹ Library" brings the library back.
  private var showsLibraryButton: Bool { sizeClass == .regular && columns == .detailOnly }

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
}
