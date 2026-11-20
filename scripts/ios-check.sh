#!/usr/bin/env bash
# C40: the iOS app's checks (ios-app/, Expo) — exactly what the ios-app CI workflow runs: type check, ESLint, Jest.
# Run before every commit that touches ios-app/. Usage: scripts/ios-check.sh [--bundle]
#   --bundle   also export the iOS JavaScript bundle (proves Metro can build it; slower)
# Needs Node.js (LTS). The Android checks are scripts/check.sh and are not affected.
source "$(dirname "$0")/lib.sh"
start_log ios-check "$@"
command -v node >/dev/null || die "Node.js isn't installed (05-environment.md, iOS app)."
cd "$ROOT/ios-app"

step "Install (npm ci)"
npm ci --no-audit --no-fund

step "Type check (tsc)"
npm run --silent typecheck

step "Lint (ESLint)"
npm run --silent lint

step "Unit tests (Jest)"
npm test --silent -- --ci

if [ "${1:-}" = "--bundle" ]; then
  step "Export the iOS bundle"
  out="$(mktemp -d)"
  npx expo export --platform ios --output-dir "$out"
  rm -rf "$out"
fi
