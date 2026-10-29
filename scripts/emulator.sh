#!/usr/bin/env bash
# Start the emulator (if no device is connected) and wait until Android has fully booted.
# Usage: scripts/emulator.sh [--cold]
#   --cold   ignore the saved snapshot (use when the screen stays black or after an emulator crash)
# The AVD is Pixel_9_API_37 unless EDITH_AVD says otherwise.
source "$(dirname "$0")/lib.sh"

if device_ready; then
  echo "A device is already connected and booted:"; adb devices | sed 1d
  exit 0
fi

if ! adb devices | grep -q $'\tdevice$'; then
  args=(-avd "$AVD")
  [ "${1:-}" = "--cold" ] && args+=(-no-snapshot-load)
  "$(emulator_bin)" -list-avds | tr -d '\r' | grep -qx "$AVD" || die "No AVD named $AVD (see 'emulator -list-avds')."
  step "Starting emulator $AVD ${args[*]:2}"
  # Detached so it keeps running after this script exits.
  nohup "$(emulator_bin)" "${args[@]}" >/dev/null 2>&1 &
fi

step "Waiting for boot (up to 3 minutes)"
adb wait-for-device
for _ in $(seq 1 90); do
  if device_ready; then
    adb shell input keyevent 82 >/dev/null 2>&1 || true   # dismiss the lock screen if shown
    echo "Booted."; exit 0
  fi
  sleep 2
done
die "Emulator didn't finish booting. Try scripts/emulator.sh --cold."
