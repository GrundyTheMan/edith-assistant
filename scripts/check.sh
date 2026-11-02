#!/usr/bin/env bash
# Build, unit-test and lint the debug app — exactly what CI runs on every pull request.
# Run before every commit. Usage: scripts/check.sh [--device]
#   --device   also run the instrumented (Compose UI) tests on the connected emulator/phone
source "$(dirname "$0")/lib.sh"
start_log check "$@"

step "Build + unit tests + lint"
gradle clean assembleDebug testDebugUnitTest lintDebug

if [ "${1:-}" = "--device" ]; then
  device_ready || die "No booted emulator/phone. Start one with scripts/emulator.sh."
  # Not connectedDebugAndroidTest: that uninstalls the app afterwards, wiping the owner's data on the emulator.
  step "Instrumented tests on the device"
  gradle installDebug installDebugAndroidTest
  out="$(adb shell am instrument -w "$DEBUG_APP_ID.test/androidx.test.runner.AndroidJUnitRunner" | tr -d '\r')"
  if echo "$out" | grep -q "^OK ("; then
    echo "$out" | tail -5
  else
    # The failures themselves (each test's name and stack), so they're in the build log, not just the count.
    failures="$(echo "$out" | sed -En '/^There (was|were) [0-9]+ failure/,$p')"
    echo "${failures:-$(echo "$out" | tail -80)}" | head -300
    die "Instrumented tests failed."
  fi
fi

echo
echo "OK. Reports: app/build/reports/ (lint-results-debug.html, tests/)"
