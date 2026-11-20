# Device checklist: Android (Pixel)

Not yet run on a device for the rebuilt app. The archived app's checklist, updated for the
session architecture (`ARCHITECTURE.md`): each item says what to do and what the **command
log** (Settings › Diagnostics › Show Command Log) or the **exported trace** (Settings ›
Diagnostics › Export Session Trace, `session.trace.jsonl`) should show. Export the trace right
after anything that goes wrong: it holds the last ~4000 inputs and decisions.

Reading a trace: one JSON object per line, `t` in seconds, `kind` one of `hypothesis` (what
Vosk reported, with word times), `speech` (energy detector), `mode`
(`listening` | `listeningOverSpeaker` | `recording`), `outcome` (`fire stop “stop”`,
`reject notIsolated “continue”`), `machine` (event, state, effects), `route`, `plan` (what the
audio setup was brought to, and `change`: `none` < `recognizer` < `session` < `capture`),
`microphoneRestarted`, `remote`, `note` (every command-log line). `tools/device-audio-trace.sh`
adds audio focus and the recording source from `dumpsys`.

## Setup

1. Set a long screen timeout; `source tools/env.sh && ./gradlew :app:installDebug`. Installing
   over the archived app keeps the library: the books, comments and settings are still there.
2. Import a doc2audio book via the Inbox (or a sample). Settings › Diagnostics › Show Command Log on.

## The loop

3. **Speaker, "stop" → comment → "continue".** Earcons, resume 2 s back at a sentence start,
   transcript, "Matched to the text".
   Log: `“stop” stands out (+x/+y dB) at … dBFS, gain … dB, contrast gate ≥ 6 dB (speaker, book playing)`,
   `“stop” → stop at m:ss`, then `“continue” heard at … dBFS, gain … dB, no gate (recording)`,
   `“continue” → resume at …`, `Saved comment at m:ss`.
   Trace: `outcome` `fire stop`, `machine` state `recording`, `mode` `recording`, `fire resume`.
4. **False triggers on the speaker.** Play 10 minutes of narration without speaking, Settings ›
   Echo Cancellation on, then off.
   Log: no `→ stop`. Expected and harmless: `Ignored “…” (not on its own: +3/+2 dB) …` and
   `Ignored “…” (the book just said it)`. Compare the counts with and without echo cancellation.
5. **"continue" inside a sentence.** During a comment say "we should continue with this".
   Log: `Ignored “continue” (not said on its own)`; the comment keeps recording.
6. **Headphones (Bluetooth).** Same loop as 3.
   Log: `Microphone: Built-in microphone, book on <headphones>` (no `echo cancellation on`),
   `“stop” heard at … dBFS, gain … dB, no gate (headphones)`; no `stands out` lines at all.
   Trace: `plan` with `"bookAudible":false`; from open to the first "stop" exactly one `plan` with
   `change` `capture` (Play does not rebuild the microphone on headphones).

## Quiet voice (the archived app needed a raised voice)

Phone at arm's length, speak at the volume you would use not to wake someone in the next
room. Each case should work at the first try; if one does not, export the trace and note the
`dBFS`, `gain` and contrast figures of the lines below.

7. **Paused, speaker.** Book paused, say "continue".
   Log: `“continue” heard at -4x dBFS, gain +1x…+2x dB, no gate (book paused)`, then `→ resume`.
   A `gain` near +0 dB with a level below −35 dBFS means the gain was held down (steady noise:
   compare the room; or a loud sound in the half second before).
8. **Recording, speaker.** "stop" (normal voice), speak a quiet comment, pause, quiet "continue".
   Log: `“continue” heard at … dBFS, gain … dB, no gate (recording)`, `Saved comment`. Also
   quiet "cancel": `no gate (recording)`, the comment is discarded.
9. **Playing, speaker.** Quiet "stop" over the book at medium volume. This is the one case where
   loudness still counts, by design: the word has to stand 6 dB above what is left of the book.
   Log: either `“stop” stands out (+x/+y dB) …` and `→ stop`, or
   `Ignored “stop” (not on its own: +x/+y dB) at … dBFS, gain … dB, contrast gate ≥ 6 dB (speaker, book playing)`.
   Note x/y with Echo Cancellation on and off, and at two book volumes: these figures decide whether
   `CommandGatePolicy.minimumContrast` (6 dB) can come down. No line at all: Vosk did not hear
   the word (the trace has no `hypothesis` containing it); note the book volume.
10. **Playing, headphones.** Quiet "stop".
    Log: `“stop” heard at … dBFS, gain … dB, no gate (headphones)`, `→ stop`.
11. **After the tone.** "stop", wait for the record tone, say nothing for a second, quiet
    "cancel". Log: `heard … gain` well above +0 dB (the archived front end kept the gain down for
    many seconds after the tone).

## Buttons and the lock screen

12. **Headset buttons.** Next track starts a comment, play/pause saves it, previous discards
    it; outside a comment previous goes back 15 s. Lock screen controls work.
    Trace: one `remote` per press, or two within 0.3 s and one `machine` event with effects (a
    double-reported press is one press).
13. **Earbud out and in during a comment** (ear detection sends pause, then play): the comment
    keeps recording. Trace: `remote` `pause`, `remote` `play`, both with empty `effects`.

## Audio session and routes

14. **Music first.** Music playing in another app, open a book with voice on: the music keeps
    playing until Play (`dumpsys audio`: no Margin Voice focus, recording source 6 =
    VOICE_RECOGNITION). After the first Play on the speaker: source 7 (VOICE_COMMUNICATION).
    Log: `Microphone: Built-in microphone, book on the speaker, echo cancellation on`.
    Trace: `plan` `"echoCancellation":false,"mixWithOthers":true` at open, then
    `"echoCancellation":true,"mixWithOthers":false`, `change` `capture`.
15. **Headphones arrive and leave while playing on the speaker.**
    Log: `Audio device connected`, within about a second `Microphone: …, book on <headphones>`
    without `echo cancellation on`; `Audio device disconnected` and echo cancellation back.
    Trace: `route` `deviceArrived`, then at most two `plan` lines with `change` `capture` per
    connection (one to let go of echo cancellation, none more once settled), however many
    `route` lines the system sends.
16. **Flapping route.** Toggle Bluetooth 5× in 30 s while playing on the speaker.
    Trace: after four `plan` lines with `"echoCancellation":true` the following ones stay
    `false` until the book is closed (the archived app logged "Audio route keeps changing"; the
    coordinator now does this silently, the trace is the evidence).
17. **Route change during a comment.** Connect headphones while recording: the recording goes
    on. Trace: no `plan` with `change` `capture` until `Saved comment`, then one.
18. **Use Car or Headset Microphone** on, with a headset: Log `Microphone: <headset>, call quality`.

## Calls, other apps, background

19. **A call during a comment** (screen locked): Log `Audio interrupted: comment saved`; after
    the call the status returns to Listening (trace: `plan` `change` `capture`, `microphoneRestarted`).
20. **Nothing said.** "stop", then silence: Log `Nothing said, comment discarded, the book stays
    paused` after 20 s; `Stopped listening: paused for N min` after the time in Settings
    (`paused in the background` after 1 min with the app in the background). Play listens again.
21. **Another app starts.** "stop", then start a YouTube video: Log `Another app started playing:
    comment saved|discarded, the book stays paused` within ~3 s, then `Stopped listening: another
    app is playing`; the microphone notification and the green dot go away; the video is not
    interrupted later.
22. **Listening paused, screen locked, Play on the lock screen.** The book plays without
    crashing. Log: `Microphone failed: In the background: the microphone starts when Margin
    Voice is open again`, repeated about every 15 s with `Microphone unavailable: trying again`
    (known: noisier than the archived app). Open the app: Listening within a second.
23. **A book ends in the background** with Continue with Next Book on: "Up next: …" is spoken,
    the next book plays, voice commands still work (the microphone service was never stopped:
    no `plan` with `"capture":false` in the trace between the books).

## The rest

24. Earcons follow the media volume; a 5-minute comment (`Comment ended after 5 minutes`) and
    fast stop/continue give no ANR.
25. Settings › Echo Cancellation off while listening on the speaker after Play: logcat tag
    `audio`: `microphone on: source voice recognition`; trace: `microphoneRestarted`.
26. Convert `core/src/test/resources/fixtures/icra.pdf` and compare the chapters with iOS.
27. Say "weakness: the baseline is never tuned" → the comment shows the red Weakness chip and a
    transcript without the tag word; "Frage: …" → Question. Export the review draft in both
    layouts and the annotated PDF (pick the PDF once); open the PDF and check the highlights
    sit on the right sentences with the notes beside them.
28. Import, rotate mid-import: the book arrives once. Gesture and 3-button navigation, the
    largest font: Play and Comment stay above the navigation bar; delete dialogs appear; the
    status pill is easy to hit.
29. Sync with the WebDAV folder used by the archived app and the Apple apps: nothing is
    duplicated, a comment made here arrives there.
30. Settings › Diagnostics › Export Session Trace: the share sheet opens with
    `session.trace.jsonl`; the file has one JSON object per line.
