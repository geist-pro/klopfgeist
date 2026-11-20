#!/bin/zsh
# Archive the iOS app (or, with --mac, the Mac app) and upload it to TestFlight.
#
# Needs, in the environment or in scripts/release.local.env (not in git):
#   APPLE_TEAM_ID     your Apple developer team
#   ASC_KEY_ID        App Store Connect API key id
#   ASC_ISSUER_ID     App Store Connect issuer id
#   ASC_KEY_PATH      path to AuthKey_<id>.p8   (default: ~/.appstoreconnect/private_keys/AuthKey_$ASC_KEY_ID.p8)
# Optional: BUILD_NUMBER (default: minutes since 2026-01-01, so every upload is higher than the last).
#           MV_SYNC=icloud adds the iCloud Drive entitlement; the container iCloud.pro.geist.klopfgeist has to
#           exist in the developer portal first (see apple/project.yml). The default builds without it.
#
# usage: scripts/testflight.sh [--mac] [--no-upload]
# Both platforms share one bundle id and one App Store Connect record; the Mac app is Apple silicon only.
set -euo pipefail
ROOT="${0:A:h:h}"
[[ -f "$ROOT/scripts/release.local.env" ]] && source "$ROOT/scripts/release.local.env"
: "${APPLE_TEAM_ID:?set APPLE_TEAM_ID}" "${ASC_KEY_ID:?set ASC_KEY_ID}" "${ASC_ISSUER_ID:?set ASC_ISSUER_ID}"
ASC_KEY_PATH="${ASC_KEY_PATH:-$HOME/.appstoreconnect/private_keys/AuthKey_$ASC_KEY_ID.p8}"
PLATFORM=iOS; SCHEME=MarginVoice; UPLOAD=1
for arg in "$@"; do case "$arg" in --mac) PLATFORM=macOS; SCHEME=MarginVoiceMac;; --no-upload) UPLOAD=0;; *) echo "unknown option $arg"; exit 1;; esac; done
BUILD_NUMBER="${BUILD_NUMBER:-$(( ($(date +%s) - 1767225600) / 60 ))}"
AUTH=(-allowProvisioningUpdates -authenticationKeyPath "$ASC_KEY_PATH" -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID")

cd "$ROOT/apple"
xcodegen generate --quiet
OUT="$ROOT/apple/build/release-$PLATFORM"
rm -rf "$OUT"; mkdir -p "$OUT"
xcodebuild -project MarginVoice.xcodeproj -scheme "$SCHEME" -configuration Release \
  -destination "generic/platform=$PLATFORM" -archivePath "$OUT/Klopfgeist.xcarchive" \
  -derivedDataPath "$ROOT/apple/build/dd-release" -skipPackagePluginValidation -skipMacroValidation \
  MV_SYNC="${MV_SYNC:-local}" DEVELOPMENT_TEAM="$APPLE_TEAM_ID" ARCHS=arm64 ONLY_ACTIVE_ARCH=NO \
  CURRENT_PROJECT_VERSION="$BUILD_NUMBER" "${AUTH[@]}" archive | tail -3
[[ -d "$OUT/Klopfgeist.xcarchive" ]] || { echo "archive failed"; exit 1; }
cp ExportOptions.plist "$OUT/ExportOptions.plist"
(( UPLOAD )) && /usr/libexec/PlistBuddy -c "Set :destination upload" "$OUT/ExportOptions.plist"
/usr/libexec/PlistBuddy -c "Add :teamID string $APPLE_TEAM_ID" "$OUT/ExportOptions.plist"
xcodebuild -exportArchive -archivePath "$OUT/Klopfgeist.xcarchive" -exportPath "$OUT/export" \
  -exportOptionsPlist "$OUT/ExportOptions.plist" "${AUTH[@]}" | tail -5
echo "$PLATFORM build $BUILD_NUMBER done ($( (( UPLOAD )) && echo uploaded || echo "not uploaded, see $OUT/export"))"
