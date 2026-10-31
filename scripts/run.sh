#!/usr/bin/env bash
# Build the debug app, install it on the emulator (starting the emulator if needed), launch it and
# check it didn't crash. Usage: scripts/run.sh
#
# App data survives reinstalling the debug build. It does NOT survive switching between the debug build and
# a release APK (different signing keys): Android requires an uninstall first. See 05-environment.md §6.
source "$(dirname "$0")/lib.sh"
start_log run "$@"

scripts/emulator.sh

step "Build and install the debug app"
gradle installDebug

step "Launch"
adb logcat -b crash -c
adb shell am force-stop "$APP_ID"
adb shell am start -W -n "$APP_ID/.MainActivity" | grep -E "Status|TotalTime" || true

sleep 3
if [ -n "$(adb logcat -b crash -d | grep -E "$APP_ID|FATAL" || true)" ]; then
  adb logcat -b crash -d
  die "Edith crashed on launch (crash log above)."
fi
adb shell pidof "$APP_ID" >/dev/null || die "Edith isn't running after launch."
echo "Edith $(version_name) (debug) is running on the emulator."
