#!/bin/sh
# Launches Klopfgeist on the USB-connected Android device and records what the
# audio system does meanwhile: who holds audio focus, which microphone source
# records, echo cancellation and route decisions. For "opening the app stops
# my music" and "echo cancellation on headphones".
#
#   tools/device-audio-trace.sh [seconds] [am start extras...]
#   tools/device-audio-trace.sh 30 --ei open 0 --ez play true
#
# Start some audio on the device first (a music app, a podcast). The full log
# goes to build/audio-trace.log, the focus and recording state at the end to
# build/audio-trace-dumpsys.txt; the app's audio lines are printed. The command log
# lines carry the tag ReviewSession, the adapters' the tag audio. What the session
# heard and decided is in the trace: Settings › Diagnostics › Export Session Trace.
set -e
cd "$(dirname "$0")/.."
seconds=${1:-15}
[ $# -gt 0 ] && shift
. tools/env.sh >/dev/null
pkg=pro.geist.klopfgeist
log=build/audio-trace.log
mkdir -p build

adb shell am force-stop $pkg
adb logcat -c
adb logcat -v time audio:V ReviewSession:V BookStore:V AudioService:I MediaFocusControl:I AudioFlinger:W '*:S' >"$log" 2>&1 &
logcat=$!
trap 'kill $logcat 2>/dev/null' EXIT
sleep 1
adb shell am start -n $pkg/de.kaikunze.marginvoice.MainActivity "$@" >/dev/null
echo "recording for $seconds s…"
sleep "$seconds"
{
  echo "== audio focus"; adb shell dumpsys audio | sed -n '/Audio Focus stack/,/^$/p'
  echo "== recording"; adb shell dumpsys audio | sed -n '/Recording activity/,/^$/p'
  echo "== media sessions"; adb shell dumpsys media_session | grep -A3 "$pkg" || true
} >build/audio-trace-dumpsys.txt 2>&1
grep -E " (audio|ReviewSession|MediaFocusControl)\(" "$log" || true
echo "--- focus and recording: build/audio-trace-dumpsys.txt"
cat build/audio-trace-dumpsys.txt
