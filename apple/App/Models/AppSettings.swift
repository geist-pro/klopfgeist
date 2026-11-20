import MarginVoiceCore
import Foundation
import Observation

/// User preferences, persisted in `UserDefaults`.
@MainActor
@Observable
final class AppSettings {
  private let defaults: UserDefaults

  var voiceCommandsEnabled: Bool { didSet { defaults.set(voiceCommandsEnabled, forKey: "voiceCommandsEnabled") } }
  /// Language the commands are spoken in (also the recognizer locale).
  var commandLanguage: VoiceCommand.Language { didSet { defaults.set(commandLanguage.rawValue, forKey: "commandLanguage") } }
  /// Language comments are spoken in (transcription locale).
  var commentLanguage: CommentLanguage { didSet { defaults.set(commentLanguage.rawValue, forKey: "commentLanguage") } }
  /// Seconds subtracted from the "stop" time to anchor what you just heard.
  var reactionOffset: Double { didSet { defaults.set(reactionOffset, forKey: "reactionOffset") } }
  /// After a comment, play on from this many seconds before "stop" was said.
  var resumeRewind: Double { didSet { defaults.set(resumeRewind, forKey: "resumeRewind") } }
  /// End a comment after `silenceTimeout` of silence; otherwise only "continue" (or a tap) ends it.
  var endCommentOnSilence: Bool { didSet { defaults.set(endCommentOnSilence, forKey: "endCommentOnSilence") } }
  /// Silence that ends a comment (when `endCommentOnSilence` is on).
  var silenceTimeout: Double { didSet { defaults.set(silenceTimeout, forKey: "silenceTimeout") } }
  var defaultRate: Double { didSet { defaults.set(defaultRate, forKey: "defaultRate") } }
  /// Use the car or headset microphone (Bluetooth HFP). Lowers book audio quality.
  var useHeadsetMicrophone: Bool { didSet { defaults.set(useHeadsetMicrophone, forKey: "useHeadsetMicrophone") } }
  var keepScreenAwake: Bool { didSet { defaults.set(keepScreenAwake, forKey: "keepScreenAwake") } }
  var colorTheme: ColorTheme { didSet { defaults.set(colorTheme.rawValue, forKey: "colorTheme") } }
  var bookFont: BookFont { didSet { defaults.set(bookFont.rawValue, forKey: "bookFont") } }
  /// Which of the two icon designs the home screen shows.
  var appIconDesign: AppIconDesign { didSet { defaults.set(appIconDesign.rawValue, forKey: "appIconDesign") } }
  /// The icon takes the colours of the theme; otherwise `appIconTheme`'s.
  var appIconFollowsTheme: Bool { didSet { defaults.set(appIconFollowsTheme, forKey: "appIconFollowsTheme") } }
  var appIconTheme: ColorTheme { didSet { defaults.set(appIconTheme.rawValue, forKey: "appIconTheme") } }
  /// The icon asset to show; nil is the primary icon.
  var appIconName: String? { appIconDesign.iconName(for: appIconFollowsTheme ? colorTheme : appIconTheme) }
  var showDebugLog: Bool { didSet { defaults.set(showDebugLog, forKey: "showDebugLog") } }
  /// When a book ends, open the next unfinished one in the library and keep playing.
  var continueWithNextBook: Bool { didSet { defaults.set(continueWithNextBook, forKey: "continueWithNextBook") } }
  /// Mac: Space, C and B act without a modifier. Off for people whose keyboard, switch or
  /// dictation software sends single keys they did not mean (the menu shortcuts stay).
  var singleKeyShortcuts: Bool { didSet { defaults.set(singleKeyShortcuts, forKey: "singleKeyShortcuts") } }
  /// Minutes a paused book keeps listening for commands; 0 = until the book closes.
  var stopListeningAfter: Int { didSet { defaults.set(stopListeningAfter, forKey: "stopListeningAfter") } }

  /// Voice chosen for on-device conversion, per narration language.
  func voiceIdentifier(for language: String) -> String? {
    defaults.string(forKey: "voice.\(language)")
  }

  func setVoiceIdentifier(_ id: String, for language: String) {
    defaults.set(id, forKey: "voice.\(language)")
  }

  init(defaults: UserDefaults = .standard) {
    self.defaults = defaults
    let preferredGerman = Locale.preferredLanguages.first?.hasPrefix("de") == true
    let fallback: VoiceCommand.Language = preferredGerman ? .german : .english
    #if os(macOS)
      // at a desk, the keyboard drives it; voice commands are a choice
      voiceCommandsEnabled = defaults.object(forKey: "voiceCommandsEnabled") as? Bool ?? false
    #else
      voiceCommandsEnabled = defaults.object(forKey: "voiceCommandsEnabled") as? Bool ?? true
    #endif
    commandLanguage = (defaults.string(forKey: "commandLanguage")).flatMap(VoiceCommand.Language.init) ?? fallback
    commentLanguage = (defaults.string(forKey: "commentLanguage")).flatMap(CommentLanguage.init) ?? .sameAsBook
    reactionOffset = defaults.object(forKey: "reactionOffset") as? Double ?? 1.5
    endCommentOnSilence = defaults.object(forKey: "endCommentOnSilence") as? Bool ?? false
    silenceTimeout = defaults.object(forKey: "silenceTimeout") as? Double ?? 6
    resumeRewind = defaults.object(forKey: "resumeRewind") as? Double ?? 2
    defaultRate = defaults.object(forKey: "defaultRate") as? Double ?? 1.0
    useHeadsetMicrophone = defaults.object(forKey: "useHeadsetMicrophone") as? Bool ?? false
    keepScreenAwake = defaults.object(forKey: "keepScreenAwake") as? Bool ?? true
    showDebugLog = defaults.object(forKey: "showDebugLog") as? Bool ?? false
    // Version 2 of the look made GEIST dark the default: move everyone to it once.
    if defaults.integer(forKey: "appearanceVersion") < 2 {
      defaults.removeObject(forKey: "colorTheme")
      defaults.set(2, forKey: "appearanceVersion")
    }
    colorTheme = defaults.string(forKey: "colorTheme").flatMap(ColorTheme.init) ?? .default
    appIconDesign = defaults.string(forKey: "appIconDesign").flatMap(AppIconDesign.init) ?? .ghost
    appIconFollowsTheme = defaults.object(forKey: "appIconFollowsTheme") as? Bool ?? true
    appIconTheme = defaults.string(forKey: "appIconTheme").flatMap(ColorTheme.init) ?? .default
    bookFont = defaults.string(forKey: "bookFont").flatMap(BookFont.init) ?? .newYork
    continueWithNextBook = defaults.object(forKey: "continueWithNextBook") as? Bool ?? true
    singleKeyShortcuts = defaults.object(forKey: "singleKeyShortcuts") as? Bool ?? true
    stopListeningAfter = defaults.object(forKey: "stopListeningAfter") as? Int ?? 5
  }
}

/// The language comments are transcribed in. Comments are usually spoken in the
/// book's language, so that is the default.
enum CommentLanguage: String, CaseIterable, Identifiable {
  case sameAsBook = "book", english = "en", german = "de"

  var id: Self { self }

  var displayName: String {
    switch self {
    case .sameAsBook: "Same as Book"
    case .english: VoiceCommand.Language.english.displayName
    case .german: VoiceCommand.Language.german.displayName
    }
  }

  func resolved(bookLanguage: String?) -> VoiceCommand.Language {
    switch self {
    case .sameAsBook: VoiceCommand.Language(code: bookLanguage)
    case .english: .english
    case .german: .german
    }
  }
}
