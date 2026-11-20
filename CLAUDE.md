# Klopfgeist (working title: Margin Voice)

Listen to a paper as an audiobook and review it by voice: "stop" → speak a comment →
"continue". Three native apps from one repository: iOS/iPadOS and macOS (Swift, SwiftUI),
Android (Kotlin, Compose). The previous code base (`draft-talk`, `draft-talk-android`, kept outside this repository in `../old/`)
is the reference for behaviour that is not yet rebuilt here
(`docs/PARITY.md`), and its comments record lessons learned on real devices. Never edit it.

## Layout

```
spec/vectors/          language-neutral conformance cases, run by BOTH cores
apple/MarginVoiceKit/  SwiftPM package
  MarginVoiceCore      pure Swift (text, conversion, sync, export, voice decisions)
  MarginVoiceSession   the review session against ports; tested with fakes + a hand-moved clock
  MarginVoiceApple     adapters: AVFoundation, Speech, MediaPlayer, PDFKit, SwiftData
apple/App*             iOS and macOS app targets (XcodeGen: apple/project.yml)
android/core           pure Kotlin twin of MarginVoiceCore (JVM)
android/session        Kotlin twin of MarginVoiceSession (JVM)
android/app            Compose app and Android adapters
docs/                  ARCHITECTURE.md, PARITY.md, device checklists
```

## Rules

- **Decisions are pure, effects are adapters.** Anything that decides (is this a command?
  which microphone? does the comment end?) lives in a core type with no platform import and
  no clock of its own; time is a parameter. Adapters only translate platform events into
  core inputs and carry out what the core asks. If a bug fix needs an `if` in an adapter,
  the decision probably belongs in the core, with a test.
- **Test first, at the seam.** A behaviour change starts with a failing test: a vector in
  `spec/vectors` when both platforms share the rule, otherwise a session test against the
  fakes (`ReviewSessionTests`), otherwise a unit test. See `.claude/skills/test-driven-development`.
- **Both cores, same vectors.** A change to a shared rule changes the vector file first, then
  Swift and Kotlin until both pass. Never edit a vector to make one platform pass.
- **A field failure becomes a trace, then a test.** The session records its inputs and
  decisions (`SessionTrace`). Export it (Settings › Diagnostics), replay it with
  `TraceReplay`, and add the reduced case to the vectors before fixing anything.
- **Verify before claiming.** Run the commands below and read their output before saying
  something works. What could not be exercised (a device, a headset) is said plainly.
- No hard-coded `/Users/...` paths, no secrets in the repository (`keystore.properties`,
  `*.jks` are ignored), commits without AI attribution.
- Offsets into narration text are code points in files (`.drafttalk`, timing sidecars);
  Swift indexes characters, Kotlin UTF-16. Convert at the edge (`OffsetUnits`, `TextChars`).
- Identifiers: bundle and application id `pro.geist.klopfgeist`; the code keeps the working title
  (`MarginVoice*` modules, the Kotlin package `de.kaikunze.marginvoice`, the `de.kaikunze.drafttalk.book`
  file type). Stored formats are those of the archived apps: the SwiftData schema (V1 frozen, V2
  current), Android's library file version 1, the `.drafttalk` package and the `MarginVoice` WebDAV folder.
- Unpublished documents never go into the repository: the private test fixtures (`icra*`, `hace_de*`)
  are git-ignored and their tests skip without them.

## Commands

```
# Swift core + session (fast, no simulator)
cd apple/MarginVoiceKit && swift test

# Kotlin core + session
cd android && source tools/env.sh && ./gradlew :core:test :session:test

# Apps
cd apple && xcodegen && xcodebuild -scheme MarginVoice -destination 'generic/platform=iOS Simulator' build
cd android && source tools/env.sh && ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Xcode builds need `-skipPackagePluginValidation -skipMacroValidation`. In zsh `log` is a
builtin: use `/usr/bin/log`.

## Skills

Vetted, markdown-only skills are in `.claude/skills/` (sources and licences in
`SOURCES.md`): process (`test-driven-development`, `systematic-debugging`,
`verification-before-completion`), Apple (`swift-concurrency`, `swiftui-pro`,
`axiom-media` for audio session / Now Playing / speech, `axiom-macos`, `hig-ios`,
`hig-macos`), Android (`android-*`, `compose-expert`, `material-3`). They are kept local
(`.claude/` is not in the repository). Read the matching one before working in its area. There is no public skill for Android audio focus, Media3
sessions or foreground services: `docs/ARCHITECTURE.md` carries what was learned here.
