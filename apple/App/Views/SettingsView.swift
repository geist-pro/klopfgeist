import MarginVoiceCore
import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
  @Environment(AppSettings.self) private var settings
  @Environment(SessionHost.self) private var session
  @Environment(SyncCenter.self) private var sync
  @Environment(\.dismiss) private var dismiss
  @State private var server = ""
  @State private var user = ""
  @State private var password = ""
  @State private var showLicences = false
  #if os(macOS)
    @State private var macTab: MacTab = {
      #if DEBUG
        // screenshots: DRAFTTALK_TAB=Sync opens that tab
        if let tab = ProcessInfo.processInfo.environment["DRAFTTALK_TAB"].flatMap(MacTab.init) { return tab }
      #endif
      return .voice
    }()
    private enum MacTab: String, CaseIterable, Identifiable {
      case voice = "Voice", playback = "Playback", appearance = "Appearance", sync = "Sync", commands = "Commands", troubleshooting = "Troubleshooting"
      var id: Self { self }
      var symbol: String {
        switch self {
        case .voice: "waveform"
        case .playback: "play.circle"
        case .appearance: "paintpalette"
        case .sync: "arrow.triangle.2.circlepath"
        case .commands: "text.bubble"
        case .troubleshooting: "stethoscope"
        }
      }
    }
  #endif

  var body: some View {
    #if os(macOS)
      // a Mac settings window: tabs of grouped forms, no navigation and no Done button.
      // Our own tab bar: the system's marks the selected tab in the system accent colour.
      VStack(spacing: 0) {
        HStack(spacing: 4) {
          ForEach(MacTab.allCases) { tab in
            Button {
              macTab = tab
            } label: {
              VStack(spacing: 3) {
                Image(systemName: tab.symbol).font(.system(size: 18))
                Text(tab.rawValue).font(.caption)
              }
              .foregroundStyle(macTab == tab ? Theme.accent : Theme.textSecondary)
              .frame(minWidth: 84, minHeight: 46)
              .background(macTab == tab ? Theme.surfaceBorder : .clear, in: .rect(cornerRadius: 8))
              .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(macTab == tab ? .isSelected : [])
          }
        }
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity)
        .background(Theme.surface)
        ThemedDivider()
        switch macTab {
        case .voice:
          SettingsForm {
            voiceSection
            commentsSection
          }
        case .playback:
          SettingsForm {
            playbackSection
            audioSection
          }
        case .appearance: SettingsForm { appearanceSection }
        case .sync: SettingsForm { syncSection }
        case .commands: CommandListView()
        case .troubleshooting:
          SettingsForm {
            troubleshootingSection
            diagnosticsSection
            aboutSection
          }
        }
      }
      .tint(Theme.accent)
      .navigationTitle(macTab.rawValue)
      // the title would be drawn in white; the tab bar already says where you are
      .toolbar(removing: .title)
    #else
      NavigationStack {
        // a plain list: rows divided by rules on the page, like the rest of the app
        List {
          voiceSection
          commentsSection
          playbackSection
          audioSection
          appearanceSection
          syncSection
          troubleshootingSection
          diagnosticsSection
          aboutSection
        }
        .themedList()
        .navigationTitle("Settings")
        .toolbar {
          ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
        }
      }
    #endif
  }

  #if os(iOS)
    private var backgroundNote: String { settings.stopListeningAfter == 0 ? "" : ", or after 1 minute in the background" }
  #else
    private var backgroundNote: String { "" }
  #endif

  private var voiceSection: some View {
    @Bindable var settings = settings
    return Section {
      Toggle("Voice Commands", isOn: Binding(
        get: { settings.voiceCommandsEnabled },
        set: { value in Task { await session.setVoiceCommands(value) } }))
      if settings.voiceCommandsEnabled {
        Picker("Stop Listening When Paused", selection: $settings.stopListeningAfter) {
          Text("After 1 min").tag(1)
          Text("After 5 min").tag(5)
          Text("After 15 min").tag(15)
          Text("Never").tag(0)
        }
      }
      Picker("Command Language", selection: $settings.commandLanguage) {
        ForEach(VoiceCommand.Language.allCases, id: \.self) { Text($0.displayName).tag($0) }
      }
      .onChange(of: settings.commandLanguage) { Task { await session.restartListening() } }
      Picker("Comment Language", selection: $settings.commentLanguage) {
        ForEach(CommentLanguage.allCases) { Text($0.displayName).tag($0) }
      }
      #if os(iOS)
        NavigationLink("What You Can Say") { CommandListView() }
      #endif
    } header: {
      SettingsHeader("Voice")
    } footer: {
      SettingsFooter {
        Text("Klopfgeist listens only while a book is open, and speech is recognized on this device. Say a command on its own, with a short pause before and after. A paused book stops listening after the time above\(backgroundNote); press Play or tap the status to listen again. Comments are transcribed in the book’s language unless you choose one, with names and terms from the text around them.").foregroundStyle(Theme.textSecondary)
      }
    }
    .themedRows()
  }

  private var commentsSection: some View {
    @Bindable var settings = settings
    return Section {
      Toggle("End Comment After Silence", isOn: $settings.endCommentOnSilence)
      if settings.endCommentOnSilence {
        Stepper("After \(settings.silenceTimeout, specifier: "%.0f") s of silence", value: $settings.silenceTimeout, in: 3...20, step: 1)
      }
      Stepper("Anchor \(settings.reactionOffset, specifier: "%.1f") s before “stop”", value: $settings.reactionOffset, in: 0...5, step: 0.5)
      Stepper("Play on from \(settings.resumeRewind, specifier: "%.1f") s before “stop”", value: $settings.resumeRewind, in: 0...10, step: 0.5)
      Stepper("New books play at \(settings.defaultRate, specifier: "%.2g")×", value: $settings.defaultRate, in: 0.7...2.5, step: 0.1)
    } header: {
      SettingsHeader("Comments")
    } footer: {
      SettingsFooter {
        Text("A comment ends when you say “continue” or “weiter”\(settings.endCommentOnSilence ? ", or after the pause above" : "") (or after 5 minutes). The anchor moves the comment back a little, to the passage you were reacting to. After the comment, the book plays on from shortly before you said “stop”, starting at the beginning of a sentence when one is close.").foregroundStyle(Theme.textSecondary)
      }
    }
    .themedRows()
  }

  private var playbackSection: some View {
    Section {
      Toggle("Continue with Next Book", isOn: Binding(
        get: { settings.continueWithNextBook },
        set: { settings.continueWithNextBook = $0; session.refreshUpNext() }))
      #if os(macOS)
        Toggle("Single-Key Shortcuts", isOn: Binding(get: { settings.singleKeyShortcuts }, set: { settings.singleKeyShortcuts = $0 }))
          .help("Space plays and pauses, C comments, B bookmarks. Turn off if single keys get pressed by accident; the menu shortcuts stay.")
      #endif
    } header: {
      SettingsHeader("Playback")
    } footer: {
      SettingsFooter {
        Text("When a book ends, Klopfgeist says its title and plays the next unfinished book in your library.").foregroundStyle(Theme.textSecondary)
      }
    }
    .themedRows()
  }

  private var audioSection: some View {
    @Bindable var settings = settings
    return Section {
      LabeledContent("Microphone", value: session.microphone)
      #if os(iOS)
        Toggle("Use Car or Headset Microphone", isOn: $settings.useHeadsetMicrophone)
          .onChange(of: settings.useHeadsetMicrophone) { Task { await session.restartListening() } }
      #endif
      Toggle("Keep Screen On While Playing", isOn: $settings.keepScreenAwake)
    } header: {
      SettingsHeader("Audio")
    } footer: {
      SettingsFooter {
        Group {
        #if os(iOS)
          Text("AirPods with high-quality recording (AirPods Pro 2 and later, AirPods 4) are used as the microphone automatically, so commands also work with the iPad closed. For other headsets and cars, the car microphone switches Bluetooth to call quality, which makes the book sound worse.")
        #else
          Text("Klopfgeist listens with the Mac’s input device (System Settings › Sound). Echo cancellation is on only when the built-in microphone can hear the speakers.")
        #endif
        }
        .foregroundStyle(Theme.textSecondary)
      }
    }
    .themedRows()
  }

  private var appearanceSection: some View {
    @Bindable var settings = settings
    return Section {
      Picker("Theme", selection: $settings.colorTheme) {
        ForEach(ColorTheme.allCases) { theme in
          Label { Text(theme.displayName) } icon: { ThemeSwatch(palette: theme.palette) }.tag(theme)
        }
      }
      #if os(iOS)
        .pickerStyle(.inline)
      #endif
      Picker("Book Text", selection: $settings.bookFont) {
        ForEach(BookFont.allCases) { Text($0.displayName).tag($0) }
      }
      #if os(iOS)
        AppIconPicker(settings: settings)
        Toggle("Icon Follows Theme", isOn: $settings.appIconFollowsTheme)
        if !settings.appIconFollowsTheme {
          Picker("Icon Colours", selection: $settings.appIconTheme) {
            ForEach(ColorTheme.allCases.filter { $0 != .standard }) { Text($0.displayName).tag($0) }
          }
        }
      #endif
    } header: {
      SettingsHeader("Appearance")
    }
    .themedRows()
  }

  /// iCloud between Apple devices; Nextcloud / WebDAV to reach the Android app too.
  private var syncSection: some View {
    @Bindable var sync = sync
    return Section {
      if ICloudFolder.isBuiltIn {
        Toggle("iCloud Drive", isOn: $sync.iCloudEnabled)
          .onChange(of: sync.iCloudEnabled) { _, on in if on { sync.syncSoon() } }
      }
      Toggle("Nextcloud / WebDAV", isOn: $sync.webDAVEnabled)
        .onChange(of: sync.webDAVEnabled) { _, on in if on { sync.syncSoon() } }
      if sync.webDAVEnabled {
        TextField("Server", text: $server, prompt: Text("cloud.example.org"))
          .textContentType(.URL)
          .autocorrectionDisabled()
          #if os(iOS)
            .keyboardType(.URL)
            .textInputAutocapitalization(.never)
          #endif
        TextField("User Name", text: $user)
          .textContentType(.username)
          .autocorrectionDisabled()
          #if os(iOS)
            .textInputAutocapitalization(.never)
          #endif
        SecureField(sync.hasPassword ? "App Password (saved)" : "App Password", text: $password)
          .textContentType(.password)
      }
      HStack {
        if sync.isSyncing {
          ProgressView().controlSize(.small)
          Text("Syncing…").foregroundStyle(Theme.textSecondary)
        } else if let error = sync.error {
          Text(error).foregroundStyle(Theme.record)
        } else if let last = sync.lastSync {
          Text("Last synced \(last.formatted(date: .abbreviated, time: .shortened))").foregroundStyle(Theme.textSecondary)
        }
        Spacer()
        Button("Sync Now") {
          if sync.webDAVEnabled, !server.isEmpty, !user.isEmpty, server != sync.server || user != sync.user || !password.isEmpty {
            sync.configureWebDAV(server: server, user: user, password: password)
            password = ""
          } else {
            sync.syncSoon()
          }
        }
        .disabled(sync.isSyncing || !(sync.iCloudEnabled || sync.webDAVEnabled))
      }
      .font(.footnote)
    } header: {
      SettingsHeader("Sync")
    } footer: {
      SettingsFooter {
        Group {
        Text(
          ICloudFolder.isBuiltIn
            ? "Books, comments and recordings are kept in the Klopfgeist folder of iCloud Drive and, if you add one, of your Nextcloud (or another WebDAV server), where Klopfgeist for Android finds them. Use an app password (Nextcloud: Settings › Security). With both on, this device carries books between them. The first sync of a book downloads all of its audio."
            : "Books, comments and recordings are kept in the Klopfgeist folder of your Nextcloud (or another WebDAV server), where Klopfgeist on your other devices finds them. Use an app password (Nextcloud: Settings › Security). The first sync of a book downloads all of its audio."
        )
        }
        .foregroundStyle(Theme.textSecondary)
      }
    }
    .themedRows()
    .onAppear {
      server = sync.server
      user = sync.user
    }
  }

  private var aboutSection: some View {
    Section {
      #if os(iOS)
        NavigationLink("Licences") { LicencesView() }
      #else
        Button("Licences…") { showLicences = true }
          .sheet(isPresented: $showLicences) {
            LicencesView()
              .frame(width: 620, height: 560)
              .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { showLicences = false } } }
          }
      #endif
    } header: {
      SettingsHeader("About")
    } footer: {
      SettingsFooter {
        Text("Klopfgeist \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""). Everything you say is recognized and kept on this device, unless you turn on sync.").foregroundStyle(Theme.textSecondary)
      }
    }
    .themedRows()
  }

  private var troubleshootingSection: some View {
    @Bindable var settings = settings
    return Section {
      Toggle("Show Command Log", isOn: $settings.showDebugLog)
      if settings.showDebugLog {
        #if os(iOS)
          NavigationLink("Command Log") { CommandLogView() }
        #else
          CommandLogView().frame(minHeight: 260)
        #endif
      }
    } header: {
      SettingsHeader("Troubleshooting")
    }
    .themedRows()
  }

  /// The session's flight recorder, for a report when a command was not heard.
  private var diagnosticsSection: some View {
    Section {
      #if os(iOS)
        ShareLink(
          item: SessionTraceFile(text: session.traceJSONLines()),
          preview: SharePreview(SessionTraceFile.fileName, image: Image(systemName: "waveform.path.ecg"))
        ) {
          Label("Export Session Trace", systemImage: "square.and.arrow.up")
        }
      #else
        Button("Export Session Trace…", systemImage: "square.and.arrow.up") { SessionTraceFile(text: session.traceJSONLines()).saveWithPanel() }
      #endif
    } header: {
      SettingsHeader("Diagnostics")
    } footer: {
      SettingsFooter {
        Text("The last few minutes of what the microphone and the recognizer reported and what Klopfgeist decided, as a .jsonl file. It contains the words that were recognized, and no audio. Export it right after a command was missed.")
          .foregroundStyle(Theme.textSecondary)
      }
    }
    .themedRows()
  }
}

/// The session trace as a file to share or save.
struct SessionTraceFile: Transferable {
  let text: String

  static var fileName: String {
    "MarginVoice-trace-\(Date().formatted(.iso8601.year().month().day().time(includingFractionalSeconds: false).timeSeparator(.omitted))).jsonl"
  }

  static var transferRepresentation: some TransferRepresentation {
    FileRepresentation(exportedContentType: .jsonLines) { trace in
      let url = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)
      try Data(trace.text.utf8).write(to: url, options: .atomic)
      return SentTransferredFile(url)
    }
  }

  #if os(macOS)
    @MainActor
    func saveWithPanel() {
      let panel = NSSavePanel()
      panel.nameFieldStringValue = Self.fileName
      panel.allowedContentTypes = [.jsonLines]
      panel.canCreateDirectories = true
      guard panel.runModal() == .OK, let url = panel.url else { return }
      do {
        try Data(text.utf8).write(to: url, options: .atomic)
      } catch {
        NSAlert(error: error).runModal()
      }
    }
  #endif
}

extension UTType {
  /// JSON lines: one JSON object per line.
  static let jsonLines = UTType(filenameExtension: "jsonl", conformingTo: .json) ?? .json
}

#if os(macOS)
  /// One tab of the Mac settings window.
  private struct SettingsForm<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
      Form { content }
        .formStyle(.grouped)
        .toggleStyle(.switch)
        .themedList()
    }
  }
#endif

private struct CommandRow: View {
  let say: String
  let does: String

  var body: some View {
    VStack(alignment: .leading, spacing: 2) {
      Text(say).font(.body.weight(.semibold))
      Text(does).font(.footnote).foregroundStyle(Theme.textSecondary)
    }
    .accessibilityElement(children: .combine)
  }
}

private struct CommandListView: View {
  var body: some View {
    Form {
      Section {
          CommandRow(say: "stop / Stopp", does: "Pause, beep, and record a comment")
        CommandRow(say: "continue / weiter", does: "Save the comment and play on")
        CommandRow(say: "cancel / verwerfen", does: "Discard the comment you're recording")
        CommandRow(say: "back / zurück", does: "Back 15 s (say twice for 30 s)")
        CommandRow(say: "again / nochmal", does: "Repeat the current sentence")
        CommandRow(say: "bookmark / Lesezeichen", does: "Mark the sentence you just heard. Say it again within two minutes to mark the whole passage.")
        CommandRow(say: "faster, slower / schneller, langsamer", does: "Change speed")
        CommandRow(
          say: "weakness / Schwäche, strength / Stärke, question / Frage, minor / Kleinigkeit, summary / Zusammenfassung",
          does: "Say one as the first word of a comment to sort it into the review draft (Export › Review draft).")
        CommandRow(say: "Next-track button", does: "Start a comment (AirPods, steering wheel)")
      } footer: {
        SettingsFooter {
          Text("Say a command on its own, with a short pause before and after. English and German both work.").foregroundStyle(Theme.textSecondary)
        }
      }
      .themedRows()
    }
    #if os(macOS)
      .formStyle(.grouped)
    #endif
    .themedList()
    #if os(iOS)
      .navigationTitle("What You Can Say")
      .inlineNavigationTitle()
    #endif
  }
}

struct CommandLogView: View {
  @Environment(SessionHost.self) private var session

  var body: some View {
    List(session.events) { entry in
      HStack(alignment: .top) {
        Image(systemName: entry.accepted ? "checkmark.circle.fill" : "xmark.circle")
          .foregroundStyle(entry.accepted ? Theme.success : Theme.textSecondary)
        VStack(alignment: .leading) {
          Text(entry.text)
          Text(entry.date, style: .time).font(.caption).foregroundStyle(Theme.textSecondary)
        }
      }
      .themedRows()
    }
    .themedList()
    .overlay {
      if session.events.isEmpty {
        ContentUnavailableView("Nothing Yet", systemImage: "waveform", description: Text("Recognized and ignored commands appear here."))
      }
    }
    .navigationTitle("Command Log")
  }
}

/// A theme's background and accent, split on the diagonal.
private struct ThemeSwatch: View {
  let palette: Palette

  var body: some View {
    Circle()
      .fill(LinearGradient(
        stops: [.init(color: palette.background, location: 0.5), .init(color: palette.accent, location: 0.5)],
        startPoint: .topLeading, endPoint: .bottomTrailing))
      .overlay { Circle().strokeBorder(Theme.surfaceBorder, lineWidth: 1) }
      .frame(width: 22, height: 22)
      .accessibilityHidden(true)
  }
}

#if os(iOS)
  /// The two icon designs as tappable previews, in the colours the icon will have.
  private struct AppIconPicker: View {
    @Bindable var settings: AppSettings

    var body: some View {
      let theme = settings.appIconFollowsTheme ? settings.colorTheme : settings.appIconTheme
      let colours = theme == .standard ? ColorTheme.geistDark : theme
      VStack(alignment: .leading, spacing: 10) {
        Text("App Icon")
        HStack(spacing: 16) {
          ForEach(AppIconDesign.allCases) { design in
            let selected = settings.appIconDesign == design
            Button {
              settings.appIconDesign = design
            } label: {
              VStack(spacing: 6) {
                Image("IconPreview-\(design == .ghost ? "Ghost" : "ReadLine")-\(colours.rawValue)")
                  .resizable()
                  .frame(width: 60, height: 60)
                  .clipShape(.rect(cornerRadius: 13.5, style: .continuous))
                  .overlay {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                      .strokeBorder(selected ? Theme.textPrimary : .clear, lineWidth: 2)
                      .padding(-4)
                  }
                Text(design.displayName)
                  .font(.footnote.weight(selected ? .bold : .regular))
                  .foregroundStyle(selected ? Theme.textPrimary : Theme.textSecondary)
              }
              .padding(4)
              .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(design.displayName) icon")
            .accessibilityAddTraits(selected ? .isSelected : [])
          }
        }
      }
      .padding(.vertical, 6)
    }
  }
#endif

/// A settings section's title: bold, over a rule in the accent colour.
private struct SettingsHeader: View {
  let title: String
  init(_ title: String) { self.title = title }

  var body: some View {
    #if os(iOS)
      VStack(alignment: .leading, spacing: 6) {
        Text(title)
          .font(.headline)
          .foregroundStyle(Theme.textPrimary)
        Rectangle().fill(Theme.accent).frame(height: 2)
      }
      .padding(.top, 18)
      .textCase(nil)
      .accessibilityAddTraits(.isHeader)
    #else
      Text(title)
    #endif
  }
}

/// The explanation under a settings section: small, secondary, with room after it.
private struct SettingsFooter<Content: View>: View {
  @ViewBuilder let content: Content

  var body: some View {
    content
      #if os(iOS)
        .font(.footnote)
        .foregroundStyle(Theme.textSecondary)
        .padding(.top, 6)
        .padding(.bottom, 14)
      #endif
  }
}
