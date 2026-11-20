# Parity with the archived apps

State on 2026-11-14. "Ported" means the code is carried over, builds and its unit tests
pass. Nothing here has run on a physical device or headset yet: see
`DEVICE-CHECKLIST.md` and `DEVICE-CHECKLIST-ANDROID.md`.

| Area | iOS / iPadOS | macOS | Android |
|---|---|---|---|
| Library, import, inbox, samples, `.drafttalk` packages | ported | ported | ported |
| On-device conversion (PDF/text → audiobook) | ported | ported | ported |
| Player, chapters, speed, reading view, themes | ported | ported | ported |
| Hands-free loop (commands, comments, bookmarks, headset buttons) | rebuilt on `ReviewSession` | rebuilt | rebuilt (`:session`) |
| Comment transcription, review tags, text anchoring | ported | ported | ported |
| Exports (Markdown, review draft, annotated PDF, rewording on Apple) | ported | ported | ported |
| Sync (iCloud on Apple, WebDAV everywhere) | ported | ported | ported |
| Settings, licences, command log | ported | ported | ported |
| Session trace export (new) | Settings › Diagnostics | Troubleshooting tab | Settings › Diagnostics |

## Known differences from the archived apps

- **Apple, engine reconfiguration**: the tap is reinstalled and the recognizer keeps running;
  the old app rebuilt everything. New code, untested on hardware.
- **Apple, listening paused**: the microphone is released by rebuilding the engine, so the
  next Play has a short hiccup.
- **Mac**: echo cancellation starts with the first Play, not when the book opens. It is
  reconsidered whenever the engine reconfigures (the Mac has no route notification).
- **Apple, "Up next"**: the next book opens after the announcement, not during it.
- **Android, speaker while playing**: a command fires about 0.8 s after it was said
  (loudness verdict, then the detector's settle time); the old app took about 0.5 s.
- **Android**: the Echo Cancellation setting is honoured in the adapter, not in the plan;
  the log can say "echo cancellation on" while the setting is off.
- **Android**: `ScreenshotTest` needs the screentest sources (`-Pscreentest`); the design
  walkthrough runs without them.
- **Logging (Apple)**: adapters log under `de.kaikunze.marginvoice`, app code still under
  `de.kaikunze.drafttalk`.

- **Passage end** whose bookmark was deleted meanwhile now logs "No bookmark to extend" and
  confirms nothing (the old apps did nothing silently).
- **Android**: a comment's audio file is finished on a background thread; transcription
  waits for it, but sync, export and in-app playback started within about 3 s of the
  comment do not.
- **Wide layouts (v1 design, 2026-11-14)**: Android shows player beside comments at 600–839 dp
  and a navigation rail with comments list + detail at ≥ 840 dp; iPad shows the comments column
  with the comment at the playhead highlighted and an "Ahead" divider; the Mac has a compact
  transport bar, listening status in the toolbar, commented sentences underlined in the reading
  pane, and inline Play/Edit on the current comment. Mac shortcuts changed: ⌥⌘I shows/hides
  comments (was ⌥⌘C); bare Space, C and B work outside text fields; Repeat and Bookmark moved
  from the transport bar to the Playback menu; the comment cards under each paragraph are gone.
- **Android**: a voice note playing in the comment detail stops when the window changes size class.

## Reviewed after the first build (2026-11-14)

Independent reviews of the audio adapters found and fixed, among others: the Apple player
losing its position when the system stopped the engine (the book jumped back after a route
change or a call), stale recognizer results crossing a microphone restart on both
platforms, Vosk word times shifted early by the recognizer's start-up, a next book that
could start by itself after a player error on Android, and a rebuild request lost while
the microphone was being set up. Each has a test where a pure seam exists.

## Not verified

- Any audio route, recognizer or remote button on real hardware, on any platform.
- Apple: that `SpeechTranscriber` delivers per-run `audioTimeRange` (the detector depends on
  word times; the adapter falls back to spreading words evenly).
- Android: whether Vosk recognises a quiet voice after the new automatic gain, and whether
  6 dB of contrast is still too strict on the speaker while the book plays.
- VoiceOver, Dynamic Type and large-font layouts on screen.
