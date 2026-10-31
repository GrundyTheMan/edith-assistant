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

# --- Build logs --------------------------------------------------------------------------------------------
# start_log copies everything the script prints (stdout and stderr) into build-logs/<date>_<time>_<name>.log
# while still showing it in the terminal, and keeps build-logs/latest.log as a copy of the newest log.
# A script started by another script (run.sh → emulator.sh) writes into its caller's log instead.
# The newest EDITH_LOG_KEEP logs (default 30) are kept; older ones are deleted.
LOG_DIR="$ROOT/build-logs"

start_log() {
  local name="$1"; shift
  [ -n "${EDITH_LOG:-}" ] && return 0
  mkdir -p "$LOG_DIR"
  EDITH_LOG="$LOG_DIR/$(date +%Y-%m-%d_%H-%M-%S)_$name.log"
  export EDITH_LOG
  LOG_STARTED=$SECONDS
  exec > >(tee -a "$EDITH_LOG") 2>&1
  LOG_TEE_PID=$!
  local dirty=""
  [ -n "$(git status --porcelain 2>/dev/null)" ] && dirty=" (with uncommitted changes)"
  echo "# scripts/$name.sh $* | $(date '+%Y-%m-%d %H:%M:%S %Z') | $(git rev-parse --abbrev-ref HEAD 2>/dev/null)@$(git rev-parse --short HEAD 2>/dev/null)$dirty"
  trap finish_log EXIT
}

finish_log() {
  local status=$?
  echo
  if [ "$status" = 0 ]; then echo "# Finished OK in $((SECONDS - LOG_STARTED))s"
  else echo "# FAILED (exit $status) after $((SECONDS - LOG_STARTED))s"; fi
  echo "# Log: ${EDITH_LOG#"$ROOT"/}"
  exec 1>&- 2>&-                                  # close our end so tee writes everything and exits
  wait "$LOG_TEE_PID" 2>/dev/null || true
  cp "$EDITH_LOG" "$LOG_DIR/latest.log" 2>/dev/null || true
  ls -1t "$LOG_DIR"/????-??-??_*.log 2>/dev/null | tail -n +$(( ${EDITH_LOG_KEEP:-30} + 1 )) |
    while IFS= read -r old; do rm -f -- "$old"; done
  exit "$status"
}

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
