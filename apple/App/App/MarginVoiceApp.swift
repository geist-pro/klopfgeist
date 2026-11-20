import SwiftData
import SwiftUI

@main
struct MarginVoiceApp: App {
  @State private var store: BookStore
  @State private var settings: AppSettings
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
      RootView()
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
        .onChange(of: settings.colorTheme) { ThemeStore.shared.apply(settings) }
        .followsSystemContrast()
        // the home screen icon follows the settings (alternate app icons)
        .task(id: settings.appIconName) { await AppIconSwitcher.apply(settings.appIconName) }
        .onChange(of: settings.bookFont) { ThemeStore.shared.apply(settings) }
    }
    .modelContainer(container)
  }
}
