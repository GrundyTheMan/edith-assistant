#!/usr/bin/env bash
# Fetch the sherpa-onnx library (Edith's own voice, C36) into app/libs/. It is 38 MB, so it isn't in Git.
# The version and its SHA-256 are pinned below: a file that doesn't match is deleted and the script fails.
# Does nothing when the right file is already there. Usage: scripts/fetch-sherpa.sh
#
# Why 1.13.8 and this file: sherpa-onnx 2.x no longer reads Piper voices (no espeak-ng phonemizer), and the
# "static-link-onnxruntime" build holds ONNX Runtime inside libsherpa-onnx-jni.so, so it can't clash with the
# onnxruntime-android that the wake word uses (C36 §2, found by the spike).
source "$(dirname "$0")/lib.sh"
start_log fetch-sherpa "$@"

VERSION="1.13.8"
FILE="sherpa-onnx-static-link-onnxruntime-$VERSION.aar"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$VERSION/$FILE"
SHA256="b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"
DEST="$ROOT/app/libs/$FILE"

sha_of() { sha256sum "$1" | cut -d' ' -f1; }

mkdir -p "$ROOT/app/libs"
if [ -f "$DEST" ] && [ "$(sha_of "$DEST")" = "$SHA256" ]; then
  echo "sherpa-onnx $VERSION is already in app/libs/ (SHA-256 checked)."
  exit 0
fi

step "Downloading sherpa-onnx $VERSION (38 MB)"
rm -f "$DEST" "$DEST.part"
curl --fail --location --retry 3 --silent --show-error --output "$DEST.part" "$URL" || die "Couldn't download $URL"
[ "$(sha_of "$DEST.part")" = "$SHA256" ] || { rm -f "$DEST.part"; die "The downloaded file's SHA-256 isn't the pinned one. Deleted it."; }
mv "$DEST.part" "$DEST"
echo "OK: $DEST"
