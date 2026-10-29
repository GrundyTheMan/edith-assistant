# Shared helpers for the scripts in this folder. Sourced, not run.
# Works in Git Bash on Windows and in bash on Linux/macOS (CI).

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

AVD="${EDITH_AVD:-Pixel_9_API_37}"
APP_ID="com.edith.app"
KEY_DIR="${HOME}/.edith"
KEYSTORE_PROPERTIES="${EDITH_KEYSTORE_PROPERTIES:-$KEY_DIR/keystore.properties}"

die()  { echo "ERROR: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) IS_WINDOWS=1; GRADLEW="./gradlew.bat"; EXE=".exe" ;;
  *)                    IS_WINDOWS=0; GRADLEW="./gradlew";     EXE="" ;;
esac

gradle() { "$GRADLEW" --console=plain "$@"; }

sdk_dir() {
  local d="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [ -n "$d" ] || die "ANDROID_HOME is not set (docs/design/05-environment.md §1)."
  if [ "$IS_WINDOWS" = 1 ]; then cygpath -u "$d"; else echo "$d"; fi
}

adb_bin()      { echo "$(sdk_dir)/platform-tools/adb$EXE"; }
emulator_bin() { echo "$(sdk_dir)/emulator/emulator$EXE"; }
adb()          { "$(adb_bin)" "$@"; }

# Newest installed build-tools' apksigner (a .bat on Windows).
apksigner_bin() {
  local bt; bt="$(ls -d "$(sdk_dir)"/build-tools/* 2>/dev/null | sort -V | tail -1)"
  [ -n "$bt" ] || die "No Android build-tools installed."
  if [ "$IS_WINDOWS" = 1 ]; then echo "$bt/apksigner.bat"; else echo "$bt/apksigner"; fi
}

# Reads a key from gradle.properties, e.g. prop edith.versionName
prop() { grep -E "^$1=" gradle.properties | head -1 | cut -d= -f2- | tr -d '\r'; }

version_name() { prop edith.versionName; }
version_code() { prop edith.versionCode; }
release_tag()  { echo "v$(version_name).$(version_code)"; }
release_apk()  { echo "dist/Edith-$(version_name).$(version_code).apk"; }

# True when exactly one device/emulator is connected and fully booted.
device_ready() {
  [ "$(adb devices | grep -cE $'\t(device)$')" -ge 1 ] &&
    [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]
}
