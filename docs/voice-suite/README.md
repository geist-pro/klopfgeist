# Voice suite results

`android/tools/voice_suite.py` on a Samsung Galaxy A16 (Android 16), book on the phone's speaker at
media volume 6 of 15, phrases spoken by macOS `say` voices from a MacBook next to it. One trial per
voice and phrase, so a single cell says little; the totals and the "heard as" column are what to read.

| Build | Room | English | German |
|---|---|---|---|
| [0.1.0](0.1.0/) | office | 141 of 168 (6 voices) | 66 of 96 (4 voices) |
| [0.1.1](0.1.1/) | living room | 135 of 144 (6 voices) | 74 of 96 (4 voices) |

The phrase sets differ: 0.1.1 no longer listens for "go on", "go back", "delete that" and
"bookmark that" (see `docs/ARCHITECTURE.md`, "The command path"), and its German run still included
"zurückspulen" (0 of 4), which was removed after it.

To separate the changes from the room, three builds were run back to back in the same room on
2026-11-20, six English voices, the thirteen phrases that have to be heard over the playing book and
three sentences that must not fire:

| Build | Commands heard | Sentences ignored |
|---|---|---|
| 0.1.0 | 66 of 78 | 15 of 18 |
| + in a comment, a guessed command waits for the final words; grammar in one language | 69 of 78 | 18 of 18 |
| + grammar without "go" and "that" (0.1.1) | 72 of 78 | 17 of 18 |

What still goes wrong in 0.1.1, from the traces of the failed trials:

- "back" is the weakest English command (3 of 6): the model returns "mark" instead, which sets a
  bookmark. "rewind" was heard in 28 of 30 trials across all runs.
- "slower" is sometimes returned as "note", which starts a comment.
- "stop" was returned as nothing in 2 of 6 trials of the full run and in 0 of 12 in the two runs before it.
- German: the voices Flo and Sandy account for most misses (14 and 16 of 24); Anna and Reed reach
  23 and 21 of 24. "stopp" was heard from 2 of 4 voices.
- These are synthetic voices through a laptop speaker. People have not been measured yet.
