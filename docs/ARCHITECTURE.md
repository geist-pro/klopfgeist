# Architecture

## Why it was rebuilt

The archived apps worked, but the hands-free loop was unreliable on headsets ("stop" and
"continue" over AirPods), and no test could reproduce it: the rules were spread over a
state machine, an arbiter that needed two detectors to agree, and audio code that rebuilt
the microphone whenever the route moved. Reading that code gave these causes:

| Symptom | Cause in the old code | Now |
|---|---|---|
| "stop" not heard on AirPods | A command needed an utterance from the energy detector *and* a recognizer result that matched it. AirPods level and gate their signal; the energy detector often segmented nothing, and the result was dropped without a log line. | `CommandDetector` decides from the recognizer's word times alone. The energy detector can only delay a decision, for at most 1.2 s. Every settled command phrase is fired or rejected with a reason. |
| "continue" ignored at the end of a comment | It had to be an utterance of its own (0.3 s of detector silence on both sides, whole result ≤ 2 words). Said after a comment it is the tail of a longer result. | A command is the *trailing phrase* of what was heard, with a pause before it measured between words. |
| A command right after Play or after AirPods connected was lost | Every route notification and the first Play rebuilt microphone and recognizer (0.3–2 s deaf each; AirPods report several changes per connection). | `AudioCoordinator` waits for the route to settle, applies one plan, and tells the adapter how little to rebuild. Play on headphones rebuilds nothing. |
| Comment ended while still speaking (silence timeout on) | Silence came from the energy detector only. | Silence is the time since the energy detector *or the recognizer* last heard anything. |
| Comment saved by itself when an AirPod was taken out and put back | Ear detection sends pause, then play; "play" ended the comment. | `ReviewMachine` treats pause→play during a comment as the earbud, not a decision. |
| One press, two actions | Some headsets and cars report a press twice. | Transport buttons within 0.3 s are one press. |

None of these could be verified on a headset while writing this; they are verified against
the old code's logic and covered by tests. The flight recorder below exists so that the
next failure on a real headset arrives as data.

## Layers

```
            SwiftUI / Compose screens
                     │ observe, call
        ┌────────────▼─────────────┐
        │ ReviewSession            │  orchestration, no platform imports,
        │  (MarginVoiceSession)    │  time = now() + tick()
        └──┬───────────────────┬───┘
   decides │                   │ ports (protocols)
┌──────────▼──────────┐  ┌─────▼────────────────────────────┐
│ MarginVoiceCore     │  │ BookPlayer · AudioCapture ·       │
│ ReviewMachine       │  │ RemoteControlling · ReviewBook    │
│ CommandDetector     │  └─────▲────────────────────────────┘
│ AudioPlan/Coordinator│       │ implemented by
│ OtherAudioWatch     │  ┌─────┴────────────────────────────┐
│ SessionTrace        │  │ Adapters: AVAudioEngine, Speech,  │
│ text · sync · export│  │ MediaPlayer / Media3, Vosk, …     │
└─────────────────────┘  └──────────────────────────────────┘
```

- **Core** (Swift `MarginVoiceCore`, Kotlin `:core`): values and pure functions. Shared rules
  are pinned by `spec/vectors/*.json`, which both test suites run.
- **Session** (`MarginVoiceSession`, `:session`): `ReviewSession` feeds the core what the ports
  report and performs the effects. It owns no timers; the app calls `tick()` ten times a
  second. Tests drive it with fakes (`Rig`) and read like what happens to the user.
- **Adapters**: one per port. They translate and execute, and decide nothing.
- **Apps**: screens observe the session and the library.

## The command path

1. The recognizer adapter turns each result into a `Hypothesis` (words with start/end on the
   microphone timeline) and sends `.hypothesis`. The energy detector sends `.speech`.
2. `CommandDetector` looks for a command phrase at the end of what was heard. It needs a
   pause before it (0.3 s listening, 0.45 s in a comment) and has to stay the last thing
   heard for a settle time (0.3 s / 0.6 s) counted from when the recognizer reported it.
3. A fired command goes to `ReviewMachine` with the book time at which it was *said*
   (microphone time mapped through recent clock samples, minus the route latency).
4. `ReviewMachine` returns effects; the session performs them through the ports.

Two rules came out of the first run of `android/tools/voice_suite.py` on a phone (2026-11-20), where
the traces of the failed trials showed what had been heard:

- **In a comment, a guessed command waits for the final words** when the energy detector heard speech
  go on for more than 0.4 s after it (`Policy.waitsForFinalWords`). Vosk reported "resume continue" for
  "we should continue with this design" and delivered the rest 0.3 s after the settle time.
- **The Vosk grammar holds one language, and no phrase with a word the model mishears**
  (`VoskResults.grammar`). Vosk takes the phrase list as a bag of words: "stop" came back as "go"
  (from "go on"), "back" as "that" (from "delete that"), and the English model heard "mark" as the
  German "mal". On Android "go on", "go back", "delete that" and "bookmark that" are therefore not
  commands; their one-word forms are. "zurückspulen" is out too: the German model never returned it.

## The audio plan

`AudioPlan.make(needs, route)` says which session category, whether to mix with other apps,
whether the microphone and recognizer run, and whether echo cancellation is on (only when
the built-in microphone hears the device's own speaker, and only once the book has played).
`AudioCoordinator` decides *when* a new plan is applied and reports how much differs
(`none < recognizer < session < capture`). Rules: a moving route is waited out (0.6 s);
headphones arriving while echo cancellation is on turn it off at once so they can take the
book; a comment keeps its microphone until it is saved; more than four echo-cancellation
toggles in 30 s pin it off until the book closes.

## Flight recorder

`ReviewSession.trace` keeps the last ~4000 inputs and decisions (`TraceEvent`). Settings ›
Diagnostics exports them as JSON lines. `TraceReplay.detectorOutcomes` runs a trace through a
fresh detector; a failing trace is reduced to a case in `spec/vectors/command-detector.json`.

## Testing

| Level | Where | Runs |
|---|---|---|
| Shared rules | `spec/vectors` via `*VectorTests` / `CommandDetectorTests` | `swift test`, `gradlew :core:test` |
| Pure units | `MarginVoiceCoreTests`, `android/core/src/test` | same |
| Session scenarios | `ReviewSessionTests` with `Rig` (Swift), `:session` tests (Kotlin) | same |
| Adapters, persistence, screens | app test targets (simulator / Robolectric) | `xcodebuild test`, `gradlew :app:testDebugUnitTest` |
| Devices and headsets | `docs/DEVICE-CHECKLIST.md`, traces | by hand |
