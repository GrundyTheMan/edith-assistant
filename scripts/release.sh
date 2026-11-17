#!/usr/bin/env bash
# Build the signed release APK into dist/. Usage: scripts/release.sh [--allow-dirty]
#
# Needs the signing key from scripts/create-signing-key.sh (owner's PC only). The version comes from
# gradle.properties (edith.versionName / edith.versionCode); bump versionCode before each published APK.
# --allow-dirty  build even with uncommitted changes (for testing only; never publish such a build)
source "$(dirname "$0")/lib.sh"
start_log release "$@"
scripts/fetch-sherpa.sh

[ -f "$KEYSTORE_PROPERTIES" ] || die "No signing key ($KEYSTORE_PROPERTIES). Release builds are made on the owner's PC only; see 05-environment.md §6."

# The Google and Microsoft client IDs come from local.properties, which isn't in Git: a fresh checkout or worktree
# has none, and the APK would ship with Google and Microsoft "not set up" (dev.14 did). Refuse to build without them.
for key in edith.googleWebClientId edith.googleDesktopClientId edith.microsoftClientId; do
  grep -Eq "^${key}=.+" local.properties 2>/dev/null || die "local.properties is missing $key. Copy local.properties from the main checkout (it isn't in Git) before a release build."
done
if [ "${1:-}" != "--allow-dirty" ] && [ -n "$(git status --porcelain)" ]; then
  die "Uncommitted changes. Commit first so the APK matches a commit (or pass --allow-dirty to test)."
fi

APK="$(release_apk)"
step "Building Edith $(version_name) (versionCode $(version_code)) from commit $(git rev-parse --short HEAD)"
gradle clean testDebugUnitTest lintRelease assembleRelease

BUILT="app/build/outputs/apk/release/app-release.apk"
[ -f "$BUILT" ] || die "$BUILT wasn't produced (an unsigned build is named app-release-unsigned.apk — is the key set up?)."

step "Verifying the signature"
CERTS="$("$(apksigner_bin)" verify --print-certs "$BUILT")" || die "apksigner says the APK isn't validly signed."
echo "$CERTS" | grep -E "certificate (DN|SHA-256)"
EXPECTED="$(sed -n 's/^releaseCertSha256=//p' "$KEYSTORE_PROPERTIES" | tr -d '\r')"
if [ -n "$EXPECTED" ]; then
  echo "$CERTS" | grep -qi "SHA-256 digest: $EXPECTED" || die "APK is signed with a different key than $KEYSTORE_PROPERTIES expects."
fi

mkdir -p dist
cp "$BUILT" "$APK"
( cd dist && sha256sum "$(basename "$APK")" > "$(basename "$APK").sha256" )
if [ "${1:-}" = "--allow-dirty" ]; then rm -f "$APK.commit"; else git rev-parse HEAD > "$APK.commit"; fi
echo
echo "Release APK: $APK ($(du -h "$APK" | cut -f1)), SHA-256 in $APK.sha256"
echo "Try it on the emulator: adb install \"$APK\"   (debug builds are $DEBUG_APP_ID, so they don't clash. A debug build from before C17 installed as $APP_ID does: adb uninstall $APP_ID first, which wipes its data.)"
echo "Publish it:             scripts/publish.sh   (owner's approval required)"
