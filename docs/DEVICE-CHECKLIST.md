# Device checklist (Apple apps)

By-hand checks the rebuilt iOS/iPadOS and macOS apps need before a release. None of this
can run in a simulator or under `swift test`: the audio adapters (`MarginVoiceApple`) were
ported from device-tested code but have not themselves been on a device yet.

Each check names what to look for in **Settings › Troubleshooting › Command Log** and in the
trace exported with **Settings › Diagnostics › Export Session Trace** (`.jsonl`, one event
per line). Trace events: `hypothesis` (recognizer words with `start`/`end` on the microphone
timeline), `speech` (energy detector), `mode` (`listening` / `listeningOverSpeaker` /
`recording`), `outcome` (`fire stop “stop”`, `reject notIsolated “continue”`),
`machine` (event, state, effects), `route`, `plan` (with `change`: `none` / `recognizer` /
`session` / `capture`), `microphoneRestarted`, `remote`, `note` (a command-log line).

When a check fails: export the trace at once (it holds the last ~4000 events), replay it
with `TraceReplay.detectorOutcomes`, and reduce it to a case in
`spec/vectors/command-detector.json` before changing code.

Devices: iPhone, iPad (also with the cover closed), Mac (Apple silicon). Audio: built-in
speaker, AirPods with high-quality recording (Pro 2 or later, AirPods 4), an older or
third-party Bluetooth headset, wired headphones (with and without microphone), a car
(Bluetooth and CarPlay).

## 0. Before anything else

| # | Check | Expect |
|---|---|---|
| 0.1 | Install over the archived app (same bundle id, build 5 over 4) | Library, comments, recordings, positions and settings are all there (SwiftData schema V1/V2 unchanged, `Application Support/Books`). |
| 0.2 | Open a book on each device with voice commands on | Status pill goes "Preparing…" → "Listening". Log: `Microphone: …, book on …`. Trace: one `plan` with `change: capture`, `session: playAndRecord`, `mixWithOthers: true`. |
| 0.3 | Music playing in another app, then open a book (do not press Play) | The music keeps playing (mixing until the book first plays; touching the engine's output must not activate a non-mixing session). Press Play: the music stops, lock screen shows the book. Trace: `plan` with `mixWithOthers: false`, `change: session` (not `capture`) on headphones. |
| 0.4 | Word times: say "so I think … (pause) … stop" | Trace `hypothesis` events carry one word per entry with distinct `start`/`end`, not evenly spread over the result. If all words of a result are evenly spaced, the recognizer delivered no `audioTimeRange` and the fallback is in use: note device and language. |

## 1. The symptom table (docs/ARCHITECTURE.md)

| # | Setup | Do | Expect | Look for |
|---|---|---|---|---|
| 1.1 | AirPods (high quality), iPhone in pocket | Play, say "stop" ten times at different moments | Every one starts a comment | `outcome: fire stop`. No `speech` events is fine (AirPods gate their signal). A miss shows either no `hypothesis` containing "stop" (recognizer) or a `reject …` with its reason. |
| 1.2 | AirPods | "stop", speak a sentence, then "continue" after a half-second pause, without waiting for silence | Comment saved, book plays on | `mode: recording`, then `outcome: fire resume`; the `hypothesis` shows a gap ≥ 0.45 s before "continue". `reject notIsolated` = said too close to the sentence. |
| 1.3 | AirPods | Speak "… we should continue with the analysis …" inside a comment | The comment goes on | `outcome: reject speechContinued “continue”` (or no outcome). |
| 1.4 | AirPods connected mid-session | Open the book on the speaker, put AirPods in, say "stop" 1–2 s after they connect | Command heard; the book moved to the AirPods | Several `route` events, then **one** `plan` after ~0.6 s. Count `microphoneRestarted`: one per connection, not one per route event. |
| 1.5 | Any headphones | Press Play, say "stop" within the first second | Command heard | No `plan` with `change: capture` between the `machine` play event and the `hypothesis` (Play on headphones rebuilds nothing). |
| 1.6 | Speaker (no headphones), silence timeout on (6 s) | "stop", speak for 20 s with natural pauses | The comment does not end while speaking; it ends 6 s after the last word | Log: `Comment ended after 6 s of silence` only after the last `hypothesis`. Repeat on AirPods (the energy detector alone used to end it early). |
| 1.7 | AirPods | During a comment take one AirPod out and put it back | The comment keeps recording | `remote: pause` then `remote: play`, `machine` effects empty for both. |
| 1.8 | Headset / car that double-reports | One press of play/pause; one press of next | One action each | Two `remote` events within 0.3 s, the second with empty effects. |

## 2. Routes, microphones and echo cancellation

| # | Setup | Do | Expect | Look for |
|---|---|---|---|---|
| 2.1 | iPhone speaker | Play, say "stop" over the book | Heard; the book's own "stop"/"continue" in the text does not trigger | `plan` with `echoCancellation: true` only **after** the first Play (`change: capture`); `mode: listeningOverSpeaker`; book words → `reject bookEcho`. |
| 2.2 | iPhone speaker → AirPods | With echo cancellation on, connect AirPods | The book moves to the AirPods within ~1 s (voice processing would hold it on the speaker) | `route` with reason `deviceArrived` → at once a `plan` with `echoCancellation: false`, then one settled plan. Log: `Audio device connected`. |
| 2.3 | AirPods → speaker | Take both AirPods out / switch them off while playing | The book **pauses** (never blasts the speaker) | `route` `deviceLeft`, log `Audio device disconnected`, player paused; after Play, `echoCancellation: true` again. |
| 2.4 | AirPods (high quality) | Check Settings › Audio › Microphone | "AirPods …, high quality"; the book stays full quality | `route.input: bluetoothLE`, outputs `bluetoothA2DP`. If it reads "call quality" with the headset option off, `bluetoothHighQualityRecording` fell back: note the model. |
| 2.5 | Older Bluetooth headset, "Use Car or Headset Microphone" off | Play, say "stop" from where the phone can hear you | Built-in microphone listens, the book stays on the headset in full quality | `route.input: builtInMic`, `echoCancellation: false`, `headsetMicrophoneAvailable: true`. |
| 2.6 | Same, option on | Toggle the option while a book is open | Microphone becomes "…, call quality"; book audio narrow-band | `plan` `allowHeadsetCallMicrophone: true`, `change: session`; `route.input: bluetoothHFP`. Toggle off: back to A2DP. |
| 2.7 | Wired headphones without microphone | Play, "stop" | Built-in microphone, no echo cancellation | outputs `wiredHeadphones`, input `builtInMic`, `echoCancellation: false`. |
| 2.8 | Wired headset with microphone | Same | Headset microphone listens | input `wiredHeadset`. |
| 2.9 | Car, Bluetooth | Drive mode: "stop", comment, "continue"; steering-wheel next/previous | Next starts a comment, previous discards while recording | outputs `bluetoothA2DP` or `carAudio`; `remote: next` → `startRecording`. |
| 2.10 | CarPlay | Same | Echo cancellation on (car speakers + built-in microphone) | outputs `carAudio`, `echoCancellation: true`. |
| 2.11 | Flapping route (walk in and out of Bluetooth range five times in 30 s) | — | The book keeps playing; echo cancellation stays off after four toggles until the book closes | `plan` events stop toggling `echoCancellation`. |
| 2.12 | AirPods connect during a comment | "stop", start speaking, put AirPods in, keep speaking, "continue" | One comment, complete, audible start to end (the recorder survives the sample-rate change) | `microphoneRestarted` during `mode: recording`, no `plan` with `change: capture` until the comment is saved, then one. Play the recording in the comment's detail. |
| 2.13 | iPad, cover closed, AirPods | Play, close the cover, say "stop" | Book keeps playing, command heard | No interruption (the `builtInMicMuted` interruption is ignored). |
| 2.14 | iPad, cover closed, **no** headphones | Close the cover while playing | Book keeps playing | No `Audio interrupted` line. |

## 3. Letting go of the microphone

| # | Do | Expect | Look for |
|---|---|---|---|
| 3.1 | Turn voice commands off (status pill menu) while playing | Orange microphone indicator disappears within a second or two and stays off while the book plays; playback continues after a short hiccup | `plan` `session: playback`, `capture: false`. If the indicator stays on, the engine still has its input node. |
| 3.2 | Pause and wait the "Stop Listening When Paused" time (set 1 min) | Pill: "Listening paused"; indicator off | Log: `Stopped listening: paused for 1 min`. Play → listening again. |
| 3.3 | Pause, go to the home screen, wait 1 min | Same, after 1 min in the background | Log: `Stopped listening: paused in the background`. |
| 3.4 | Close the book (✕ in the now-playing bar) | Indicator off, other apps' audio resumes if it was interrupted | `plan` `session: inactive`. |
| 3.5 | Voice commands off, tap the Comment button | Records with the microphone only for that comment | `plan` `capture: true`, `recognize: false`; after saving, `capture: false`. |
| 3.6 | Paused book, start a podcast in another app | After ~2 s the book stops listening; "continue" spoken by the podcast does not start the book | Log: `Stopped listening: another app is playing`, or `Ignored “…” (another app is playing)`. On the Mac: start music **after** pausing the book. |

## 4. Interruptions and failures

| # | Do | Expect | Look for |
|---|---|---|---|
| 4.1 | Phone call while playing | Book pauses; resumes after the call if the system says so; listening comes back | `plan` with `change: capture` after the call. |
| 4.2 | Phone call while recording a comment | The part spoken so far is saved | Log: `Audio interrupted: comment saved`. |
| 4.3 | Siri ("Hey Siri, what time is it") while playing | As 4.1 | |
| 4.4 | Another app holds the microphone (a call app, Voice Memos recording) when the book opens | Pill: "Voice commands unavailable"; tap → reason and **Try Again**; it also retries by itself every 15 s | Log: `Microphone failed: …`, then `Microphone unavailable: trying again`. |
| 4.5 | Deny microphone access on first open | Pill explains; the book still plays, lock-screen controls work, background playback works | `plan` `session: playback`. |
| 4.6 | Play to the end of a book with "Continue with Next Book" on | "Up next: …" is spoken, then the next unfinished book plays; the screen follows | Log: `Finished “…”`. Note: the next book opens after the announcement (the archived app opened it during). |
| 4.7 | Lock the phone while playing; use lock-screen play/pause, AirPods squeeze, next-track | Play/pause toggles; next starts a comment | `remote` events. |
| 4.8 | A `.m4b` Core Audio refuses by extension | Opens anyway (hard-linked as `.m4a` next to it) | No "Could not open the audiobook". |
| 4.9 | 5-minute comment / say nothing for 20 s after "stop" | Saved after 5 min / discarded after 20 s; the book stays paused | Log lines `Comment ended after 5 minutes…`, `Nothing said…`. |

## 5. Mac

| # | Setup | Do | Expect | Look for |
|---|---|---|---|---|
| 5.1 | Built-in speakers and microphone, voice commands on (⇧⌘V) | Play, "stop", comment, "continue" | Works over the speakers | `echoCancellation: true` **after the first Play** (the archived Mac app turned it on before; the plan now waits for the book to have played). Log shows the tap's channel count in Console (`tap installed, 9 ch`): the first channel is used as mono; a silent level meter here means the mix-down path is back. |
| 5.2 | AirPods on the Mac | Play, "stop" | Book stays in full quality on the AirPods; the Mac's built-in microphone (or the input chosen in System Settings › Sound) listens | outputs `bluetoothA2DP`, `echoCancellation: false`. If the AirPods drop to call quality, the input device is the AirPods (`route.input: bluetoothHFP`): expected only when the user chose them as input. |
| 5.3 | Connect/disconnect AirPods while a book is open | — | The plan follows within ~1 s without a notification (the Mac route is polled) | `plan` after the default device changed; one `microphoneRestarted`. |
| 5.4 | Media keys / AirPods squeeze | F8, squeeze | Play/pause reaches the app while a book is open, also when paused | (`playbackState` is set for this.) |
| 5.5 | Space, ⌘R, ⌘⌫, ⌘B, ⌘←, ⇧⌘←, ⌘↑/⌘↓, ⌥⌘C, ⌥⌘K, ⌘N, ⌘O, ⌘E | Each once; Space also while a text field is focused | Each does its menu command; Space types a space in a text field | |
| 5.6 | Voice commands off (the Mac default) | Play | No microphone indicator in the menu bar | `plan` `session: playback`. |
| 5.7 | Settings › Troubleshooting tab › Diagnostics › Export Session Trace… | Save | A `.jsonl` file that `TraceRecorder.parse` reads | |
| 5.8 | Display sleep | Play with "Keep Screen On While Playing" on / off | Display stays awake only while playing with the option on | |

## 6. Screens (after the review pass)

| # | Check |
|---|---|
| 6.1 | VoiceOver on the player: Play reads "Play, button, Plays the book" and starts media without VoiceOver talking over it; Comment reads "Add comment … Pauses the book and records a spoken comment here"; the scrubber is adjustable. |
| 6.2 | Voice Control: "Tap Comment", "Tap Play" work. |
| 6.3 | Largest Dynamic Type sizes, iPhone SE-size and iPad split view: Play and Comment stay at the edges and fully visible; the small actions (15 s, Sentence, Bookmark, speed) are at least 44 pt tall — measure this once, it was sized from the button style's padding and not verified on screen. |
| 6.4 | iOS: Settings › Diagnostics › Export Session Trace opens the share sheet with a `.jsonl` file. |
