#!/usr/bin/env python3
"""Voice command suite over the air: the Mac speaks, the phone listens.

    tools/voice_suite.py [--language en|de] [--voices Samantha,Daniel] [--volumes 0.4,0.2]
                         [--only stop,resume] [--out build/voice-suite]

Every accepted phrase of every command is said by every voice, in the state the command belongs to:

    stop      while the book plays on the speaker   (the hard case: the word has to stand out of the book)
    resume    while the book is paused, and at the end of a spoken comment
    cancel    during a comment
    back, again, mark, faster, slower   while the book plays
    negative  a command word inside a sentence, during a comment: must NOT fire

The state is set with media keys over adb (play 126, pause 127, next 87 = start a comment, previous 88 =
discard it), never by voice, so one failed phrase does not derail the next. The verdict comes from the
app's command log (logcat tag ReviewSession): "“stop” → stop at 1:23". A phrase heard as another command
is reported as that command ("slower" for "stop"), which is worse than not heard.

Needs: macOS (`say`), adb, a debug build installed, a book in the library, the phone near the laptop
with media volume up. The phrases are those of the Android recognizer (VoskResults.grammar: VoiceCommand
without the phrases that contain a word the model mishears): keep PHRASES in step with it.
Writes results.csv and summary.md into --out and prints the summary.
"""
import argparse, csv, pathlib, re, subprocess, time

PKG = "pro.geist.klopfgeist"
ACTIVITY = f"{PKG}/de.kaikunze.marginvoice.MainActivity"
PHRASES = {
    "en": {
        "stop": ["stop", "note", "comment"],
        "resume": ["continue", "resume", "play"],
        "cancel": ["cancel", "discard"],
        "back": ["back", "rewind"],
        "again": ["again", "repeat"],
        "mark": ["bookmark", "mark", "bookmark this", "mark this"],
        "faster": ["faster"],
        "slower": ["slower"],
    },
    "de": {
        "stop": ["stopp", "Notiz", "Kommentar"],
        "resume": ["weiter", "weitermachen", "fortsetzen"],
        "cancel": ["abbrechen", "verwerfen", "löschen"],
        "back": ["zurück"],
        "again": ["nochmal", "wiederholen"],
        "mark": ["Lesezeichen", "Markierung", "markieren", "merken"],
        "faster": ["schneller"],
        "slower": ["langsamer"],
    },
}
COMMENT = {"en": "the baseline is never tuned", "de": "die Stichprobe ist zu klein"}
NEGATIVE = {
    "en": ["we should continue with this design", "they stop the trial too early", "I would cancel that claim"],
    "de": ["das sollten wir weiter verfolgen", "hier stoppt die Studie zu früh"],
}
VOICES = {
    "en": ["Samantha", "Daniel", "Karen", "Moira", "Rishi", "Tessa", "Reed (English (US))", "Flo (English (UK))", "Fred"],
    "de": ["Anna", "Flo (German (Germany))", "Reed (German (Germany))", "Sandy (German (Germany))"],
}
PLAY, PAUSE, NEXT, PREVIOUS = 126, 127, 87, 88


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True).stdout


def key(code, wait=0.6):
    adb("shell", "input", "keyevent", str(code))
    time.sleep(wait)


def playing():
    """Whether Klopfgeist's media session reports playback."""
    dump = adb("shell", "dumpsys", "media_session")
    mine = dump.split(f"SUMMARY: package={PKG}", 1)
    return len(mine) > 1 and "state=PLAYING" in mine[1].split("SUMMARY:", 1)[0]


def say(voice, volume, text):
    subprocess.run(["say", "-v", voice, f"[[volm {volume}]] {text}"], check=False)


def fired(since_clear=True):
    """Commands the app acted on since the log was cleared, and the lines explaining ignored ones."""
    log = adb("logcat", "-d", "-v", "time", "ReviewSession:V", "*:S")
    commands = re.findall(r"“([^”]*)” → (\w+)", log)
    ignored = re.findall(r"Ignored “([^”]*)” \(([^)]*)\)", log)
    levels = re.findall(r"at (-?\d+) dBFS", log)
    return commands, ignored, (levels[-1] if levels else "")


def trial(state, voice, volume, phrase, language):
    """Brings the app into `state`, says `phrase`, returns (commands, ignored, level, seconds until it fired)."""
    key(PAUSE, 0.3)
    if state == "playing":
        key(PLAY, 4.0)
        if not playing():
            return [], [("harness", "the book did not start playing")], "", ""
    elif state == "paused":
        key(PLAY, 1.5); key(PAUSE, 1.0)
    elif state in ("recording", "after comment", "negative"):
        key(PLAY, 2.0); key(NEXT, 2.2)          # the record tone has to be over
    adb("logcat", "-c")
    if state == "after comment":
        say(voice, volume, COMMENT[language]); time.sleep(1.2)
    said = time.time()
    say(voice, volume, phrase)
    deadline, result = time.time() + 3.5, ([], [], "")
    while time.time() < deadline:
        result = fired()
        if result[0]:
            break
        time.sleep(0.25)
    latency = round(time.time() - said, 1) if result[0] else ""
    # leave nothing behind: a running comment is discarded, the book paused
    key(PREVIOUS, 0.4) if state in ("recording", "after comment", "negative") or (result[0] and result[0][-1][1] == "stop") else None
    key(PAUSE, 0.3)
    return (*result, latency)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--language", default="en", choices=["en", "de"])
    ap.add_argument("--voices")
    ap.add_argument("--volumes", default="0.4")
    ap.add_argument("--only", help="commands to run, e.g. stop,resume,negative")
    ap.add_argument("--out", default="build/voice-suite")
    ap.add_argument("--traces", action="store_true", help="debug builds: pull the session trace after every trial that went wrong")
    a = ap.parse_args()
    lang = a.language
    voices = a.voices.split(",") if a.voices else VOICES[lang]
    volumes = a.volumes.split(",")
    only = set(a.only.split(",")) if a.only else None

    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", ACTIVITY, "--ei", "open", "0", "--ez", "play", "true", "--es", "commandLanguage", lang)
    time.sleep(9)
    # The media keys only reach the app once it has played: without this check every "playing" trial
    # would run against a silent, paused book and pass far too easily.
    if not playing():
        raise SystemExit("the book is not playing: open a book and press Play once, then run the suite again")
    key(PAUSE)

    plan = []  # (expected command or None, state, phrase)
    for command, phrases in PHRASES[lang].items():
        for phrase in phrases:
            if command == "stop":
                plan.append((command, "playing", phrase))
            elif command == "resume":
                plan += [(command, "paused", phrase), (command, "after comment", phrase)]
            elif command == "cancel":
                plan.append((command, "recording", phrase))
            else:
                plan.append((command, "playing", phrase))
    plan += [(None, "negative", s) for s in NEGATIVE[lang]]
    if only:
        plan = [p for p in plan if (p[0] or "negative") in only]

    out = pathlib.Path(a.out); out.mkdir(parents=True, exist_ok=True)
    out = pathlib.Path(a.out); out.mkdir(parents=True, exist_ok=True)
    rows = []
    for voice in voices:
        for volume in volumes:
            for expected, state, phrase in plan:
                commands, ignored, level, latency = trial(state, voice, volume, phrase, lang)
                got = commands[-1][1] if commands else ""
                if expected is None:
                    verdict = "ok" if not commands else f"FALSE {got}"
                else:
                    verdict = "ok" if got == expected else (f"WRONG {got}" if got else "missed")
                why = "; ".join(f"{t}: {r}" for t, r in ignored)
                heard = commands[-1][0] if commands else ""
                if a.traces and verdict != "ok":
                    # what the recognizer reported and what the detector decided, for this trial's last seconds
                    adb("shell", "am", "start", "-n", ACTIVITY, "--ez", "trace", "true")
                    time.sleep(0.8)
                    name = re.sub(r"[^A-Za-z0-9]+", "-", f"{lang}-{voice}-{state}-{phrase}").strip("-")
                    adb("pull", f"/sdcard/Android/data/{PKG}/files/session.trace.jsonl", str(out / f"trace-{name}.jsonl"))
                rows.append([lang, voice, volume, expected or "none", state, phrase, verdict, got, heard, level, latency, why])
                print(f"{voice:24} {volume:4} {state:14} {phrase:34} {verdict:12} {level:>4} dBFS {latency}", flush=True)
    with open(out / f"results-{lang}.csv", "w", newline="") as f:
        csv.writer(f).writerows([["language", "voice", "volume", "expected", "state", "phrase", "verdict", "got", "heard as", "dBFS", "seconds", "ignored"], *rows])

    def rate(subset):
        ok = sum(1 for r in subset if r[6] == "ok")
        return f"{ok}/{len(subset)}"
    lines = [f"# Voice suite, {lang}, {time.strftime('%Y-%m-%d %H:%M')}", "",
             f"{len(voices)} voices, volumes {', '.join(volumes)}, {len(rows)} trials, {rate(rows)} as expected.", "",
             "| Command | State | Phrase | As expected | Not heard | Heard as something else |", "|---|---|---|---|---|---|"]
    for expected, state, phrase in plan:
        subset = [r for r in rows if r[4] == state and r[5] == phrase]
        missed = ", ".join(sorted({r[1] for r in subset if r[6] == "missed"}))
        wrong = ", ".join(sorted({f"{r[1]} → {r[7]} (“{r[8]}”)" for r in subset if r[6].startswith(("WRONG", "FALSE"))}))
        lines.append(f"| {expected or 'none (negative)'} | {state} | {phrase} | {rate(subset)} | {missed} | {wrong} |")
    lines += ["", "| Voice | As expected |", "|---|---|"] + [f"| {v} | {rate([r for r in rows if r[1] == v])} |" for v in voices]
    (out / f"summary-{lang}.md").write_text("\n".join(lines) + "\n")
    print("\n" + "\n".join(lines))


if __name__ == "__main__":
    main()
