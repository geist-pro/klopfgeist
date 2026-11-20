import AppKit
import SwiftData
import SwiftUI

/// DraftTalk for Mac: the desk side of the app. The library sits in the sidebar,
/// the narration text fills the window with the sentence being read highlighted,
/// comments sit beside it, and the keyboard drives playback and commenting.
@main
struct MarginVoiceMacApp: App {
  @State private var store: BookStore
  @State private var settings: AppSettings

  /// "  (Space)" after a menu title while the single keys are on; they are not key equivalents.
  private func keyHint(_ key: String) -> String { settings.singleKeyShortcuts ? "  (\(key))" : "" }
  @State private var session: SessionHost
  @State private var conversions: ConversionCenter
  @State private var sync: SyncCenter
  @Environment(\.scenePhase) private var scenePhase
  private let container: ModelContainer

  init() {
    let store = BookStore()
    let settings = AppSettings()
    _store = State(initialValue: store)
    _settings = State(initialValue: settings)
    ThemeStore.shared.apply(settings)
    let session = SessionHost(store: store, settings: settings)
    _session = State(initialValue: session)
    let conversions = ConversionCenter(store: store, settings: settings)
    _conversions = State(initialValue: conversions)
    do {
      container = try ModelContainer(
        for: Schema(versionedSchema: DraftTalkCurrentSchema.self), migrationPlan: DraftTalkMigrationPlan.self,
        // the app has an iCloud container (for sync through iCloud Drive), which would otherwise
        // switch SwiftData to CloudKit: the library stays a local store
        configurations: ModelConfiguration(cloudKitDatabase: .none))
    } catch {
      // never fall back to an empty store: that would hide every comment
      fatalError("Could not open the library: \(error)")
    }
    store.removeLeftovers(context: container.mainContext)
    BookStore.removeTemporaryFiles()
    let sync = SyncCenter()
    sync.attach(context: container.mainContext, store: store, session: session)
    _sync = State(initialValue: sync)
  }

  var body: some Scene {
    WindowGroup {
      MacRootView()
        .onAppear { PlaybackKeys.shared.install(session, settings: settings) }
        .environment(store)
        .environment(settings)
        .environment(session)
        .environment(conversions)
        .environment(sync)
        // leaving the app hands this device's comments over; coming back fetches the others'
        .onChange(of: scenePhase) { _, phase in if phase != .inactive { sync.syncSoon() } }
        .preferredColorScheme(settings.colorTheme.colorScheme)
        .foregroundStyle(Theme.textPrimary)
        .tint(Theme.accent)
        .frame(minWidth: 960, minHeight: 600)
        .onChange(of: settings.colorTheme) { ThemeStore.shared.apply(settings) }
        .followsSystemContrast()
        .onChange(of: settings.bookFont) { ThemeStore.shared.apply(settings) }
    }
    .modelContainer(container)
    .defaultSize(width: 1280, height: 820)
    .commands {
      CommandGroup(replacing: .newItem) {
        Button("Convert Document…") { NotificationCenter.default.post(name: .draftTalkCommand, object: MacCommand.convert) }
          .keyboardShortcut("n")
        Button("Import Audiobook…") { NotificationCenter.default.post(name: .draftTalkCommand, object: MacCommand.importAudiobook) }
          .keyboardShortcut("o")
        Divider()
        Button("Export…") { NotificationCenter.default.post(name: .draftTalkCommand, object: MacCommand.export) }
          .keyboardShortcut("e")
          .disabled(session.book == nil)
      }
      CommandMenu("Playback") {
        // Space is handled by PlaybackKeys: as a menu key equivalent it would also
        // fire while typing in a text field, and together with a focused button
        Button((session.isPlaying ? "Pause" : "Play") + keyHint("Space")) { session.togglePlay() }
          .disabled(session.book == nil)
        Button("Back 15 Seconds") { session.skipBack() }
          .keyboardShortcut(.leftArrow, modifiers: .command)
          .disabled(session.book == nil)
        Button("Repeat Sentence") { session.repeatSentence() }
          .keyboardShortcut(.leftArrow, modifiers: [.command, .shift])
          .disabled(session.book == nil)
        Divider()
        Button("Faster") { session.setRate(min(2.5, (session.rate * 10 + 1).rounded() / 10)) }
          .keyboardShortcut(.upArrow, modifiers: .command)
          .disabled(session.book == nil)
        Button("Slower") { session.setRate(max(0.7, (session.rate * 10 - 1).rounded() / 10)) }
          .keyboardShortcut(.downArrow, modifiers: .command)
          .disabled(session.book == nil)
        Divider()
        // C and B alone also work outside text fields (PlaybackKeys)
        Button((session.isRecording ? "Save Comment" : "Comment") + keyHint("C")) { session.commentButton() }
          .keyboardShortcut("r")
          .disabled(session.book == nil)
        Button("Discard Comment") { session.discardRecording() }
          .keyboardShortcut(.delete, modifiers: .command)
          .disabled(!session.isRecording)
        Button((session.isBookmarkOpen ? "End Passage" : "Bookmark") + keyHint("B")) { session.addBookmark() }
          .keyboardShortcut("b")
          .disabled(session.book == nil)
        Button("Show Current Sentence") { NotificationCenter.default.post(name: .draftTalkFollow, object: nil) }
          .keyboardShortcut("l")
          .disabled(session.book == nil)
        Divider()
        Toggle("Voice Commands", isOn: Binding(
          get: { settings.voiceCommandsEnabled },
          set: { value in Task { await session.setVoiceCommands(value) } }))
          .keyboardShortcut("v", modifiers: [.command, .shift])
      }
      // the sidebar's row actions, which were only in its context menu
      CommandMenu("Book") {
        Button(session.book?.isFinished == true ? "Mark as Unplayed" : "Mark as Finished") {
          NotificationCenter.default.post(name: .draftTalkCommand, object: MacCommand.toggleFinished)
        }
        .disabled(session.book == nil)
        Divider()
        Button("Delete Book…") { NotificationCenter.default.post(name: .draftTalkCommand, object: MacCommand.deleteBook) }
          .disabled(session.book == nil)
      }
      CommentsInspectorCommands(hasBook: session.book != nil)
    }

    Settings {
      SettingsView()
        .environment(store)
        .environment(settings)
        .environment(session)
        .environment(sync)
        .preferredColorScheme(settings.colorTheme.colorScheme)
        .foregroundStyle(Theme.textPrimary)
        .background(Theme.background)
        .frame(width: 620, height: 600)
    }
  }
}

/// The View menu's inspector toggle and chapter list. The toggle names what it will do
/// ("Hide Comments") and acts on the front window's inspector.
struct CommentsInspectorCommands: Commands {
  let hasBook: Bool
  @FocusedBinding(\.commentsInspectorShown) private var shown: Bool?

  var body: some Commands {
    CommandGroup(after: .sidebar) {
      Button(shown == true ? "Hide Comments" : "Show Comments") { shown?.toggle() }
        .keyboardShortcut("i", modifiers: [.command, .option])
        .disabled(shown == nil)
      Button("Chapters") { NotificationCenter.default.post(name: .draftTalkCommand, object: MacCommand.chapters) }
        .keyboardShortcut("k", modifiers: [.command, .option])
        .disabled(!hasBook)
    }
  }
}

extension FocusedValues {
  /// Whether the front window shows its comments inspector.
  @Entry var commentsInspectorShown: Binding<Bool>?
}

/// Space plays and pauses, C comments and B bookmarks, from anywhere in the main window
/// except a text field.
///
/// A bare Space key equivalent on a menu item is matched before the text being
/// edited sees it, and a focused transport button presses itself on Space too,
/// so the key toggled twice or typed nothing. One local event monitor instead.
@MainActor
final class PlaybackKeys {
  static let shared = PlaybackKeys()
  private var monitor: Any?
  private weak var session: SessionHost?
  private weak var settings: AppSettings?

  func install(_ session: SessionHost, settings: AppSettings) {
    self.session = session
    self.settings = settings
    guard monitor == nil else { return }
    monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
      guard let self, self.handle(event) else { return event }
      return nil
    }
  }

  private func handle(_ event: NSEvent) -> Bool {
    guard let session, session.book != nil, settings?.singleKeyShortcuts ?? true else { return false }
    guard event.modifierFlags.intersection([.command, .option, .control, .shift]).isEmpty, !event.isARepeat else { return false }
    let action: (@MainActor (SessionHost) -> Void)? =
      switch (event.keyCode, event.charactersIgnoringModifiers?.lowercased()) {
      case (49, _): { $0.togglePlay() }
      case (_, "c"): { $0.commentButton() }
      case (_, "b"): { $0.addBookmark() }
      default: nil
      }
    guard let action else { return false }
    // only the main window, and not while typing (the field editor is a text view)
    guard let window = NSApp.keyWindow, window == NSApp.mainWindow, !(window.firstResponder is NSTextView) else { return false }
    action(session)
    return true
  }
}

/// Menu commands that need the window's state (sheets, pickers, the inspector).
enum MacCommand: Sendable {
  case convert, importAudiobook, export, chapters, toggleFinished, deleteBook
}

extension Notification.Name {
  static let draftTalkCommand = Notification.Name("de.kaikunze.drafttalk.command")
}
