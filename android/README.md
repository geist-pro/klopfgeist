# Klopfgeist for Android

Kotlin, Jetpack Compose, Material 3. Application id `pro.geist.klopfgeist` (package `de.kaikunze.marginvoice`), minSdk 33,
targetSdk 36. The architecture is in `../docs/ARCHITECTURE.md`; the device checklist in
`../docs/DEVICE-CHECKLIST-ANDROID.md`.

```
core/     pure Kotlin twin of MarginVoiceCore (text, conversion, sync, export, voice decisions,
          SessionTrace); runs the shared vectors in ../spec/vectors
session/  pure Kotlin twin of MarginVoiceSession: Ports.kt, ReviewSession.kt; tested with fakes
          and a clock moved by hand (the port of ReviewSessionTests.swift, same names)
app/      Compose app and the adapters behind the session's ports:
            audio/ExoBookPlayer        BookPlayer         ExoPlayer, earcons, announcements
            voice/AndroidAudioCapture  AudioCapture       AudioRecord, Vosk, comment recorder,
                                                          microphone foreground service
            audio/MediaSessionRemote   RemoteControlling  Media3 session (PlaybackService)
            voice/SessionBook          ReviewBook         comments and position in the JSON Library
            voice/SessionHost          owns all of it: settings, the 10 Hz tick, Compose state
          plus model/ (Library, BookStore, Settings), conversion/, sync/ (WebDAV), ui/
tools/    env.sh, fetch_models.sh, device-audio-trace.sh, voice_suite.py (every command, spoken by a Mac),
          make_icon.py (launcher icon from the Apple artwork)
```

Adapters translate and execute. Anything that decides (is this a command, which microphone,
when to rebuild it) is in `:core` or `:session` and has a JVM test.

## Build and test

```bash
source tools/env.sh                 # JAVA_HOME (JDK 17 or 21) and ANDROID_HOME
tools/fetch_models.sh               # once: the Vosk command models, see below
./gradlew :core:test :session:test  # seconds, no Android
./gradlew :app:testDebugUnitTest    # Robolectric: library, import, sync, adapters, every screen
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug         # a device over USB
```

`:app:testDebugUnitTest` renders every screen, sheet and dialog in both colour themes to
`app/build/walkthrough/<theme>/` (`DesignWalkthroughTest`). `ScreenshotTest` (in
`app/src/screenTest`) renders the form factors to `app/build/screens/` and needs the sources
of `de.kaikunze.screentest`: by default `~/work/areas/android/studio/screentest`, or
`-Pscreentest=<dir>` / `MARGINVOICE_SCREENTEST`; without them it is left out.

A release build is signed with the upload key when `keystore.properties` (not in the
repository) is next to `settings.gradle.kts`, and debug-signed otherwise.
`-Pabi=arm64-v8a` builds for phones only.

## Voice command models

The two small Vosk models (English, German; Apache-2.0; about 160 MB unpacked) are assets in
`app/src/main/assets/vosk/{en,de}`, ignored by git and most of the APK.
`tools/fetch_models.sh` downloads them from alphacephei.com when they are missing. Without
them the app builds and plays, and the status pill says why voice commands are unavailable.

## On a device

Books come in through the + menu, "Open with"/Share, or the inbox:

```bash
adb push book.m4b book.narration.txt book.timing.json /sdcard/Android/data/pro.geist.klopfgeist/files/Inbox/
```

Debug launch extras: `adb shell am start -n pro.geist.klopfgeist/de.kaikunze.marginvoice.MainActivity --ei open 0
--ez play true --es show notes|chapters|export|settings|nowplaying|log`; `--ez clearLibrary true`
deletes every book first, `--ez trace true` writes the session trace to `Android/data/pro.geist.klopfgeist/files/`,
`--es commandLanguage en|de` sets the command language. `tools/device-audio-trace.sh [seconds] [extras]` records audio focus
and the recording source next to the app's log lines (logcat tags `audio`, `ReviewSession`).

When a command was not heard: Settings › Diagnostics › Export Session Trace, right after it
happened. The file (`session.trace.jsonl`, JSON lines) holds what the recognizer reported and
what the session decided; `TraceReplay.detectorOutcomes(TraceRecorder.parse(text))` runs it
through the detector again in a test.

## Stored data

Unchanged from the archived app (`../../old/draft-talk-android`), so an update keeps
everything: `files/library.json` (version 1), `files/Books/<folder>/`, the `settings` and
`sync` preferences, the Android Keystore alias of the WebDAV password, `.drafttalk` packages.
