#!/bin/sh
# Downloads the small Vosk models (Apache-2.0) used for voice commands into the
# app's assets: app/src/main/assets/vosk/{en,de}. They are not checked in.
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/vosk"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
fetch() {
  lang="$1"; name="$2"
  if [ -f "$DEST/$lang/am/final.mdl" ]; then echo "$lang: present"; return; fi
  curl -sfL -o "$TMP/$name.zip" "https://alphacephei.com/vosk/models/$name.zip"
  unzip -q "$TMP/$name.zip" -d "$TMP"
  rm -rf "$DEST/$lang"; mkdir -p "$DEST"
  mv "$TMP/$name" "$DEST/$lang"
  echo "$name" > "$DEST/$lang/VERSION"
  # the English model ships without its licence text (Apache-2.0, like the German one)
  [ -f "$DEST/$lang/COPYING" ] || cp "$ROOT/app/src/main/assets/licenses/Apache-2.0.txt" "$DEST/$lang/COPYING"
  echo "$lang: $name"
}
fetch en vosk-model-small-en-us-0.15
fetch de vosk-model-small-de-0.15
[ -f "$DEST/en/COPYING" ] || cp "$ROOT/app/src/main/assets/licenses/Apache-2.0.txt" "$DEST/en/COPYING"
