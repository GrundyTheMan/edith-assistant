#!/usr/bin/env bash
# Publish the release APK built by scripts/release.sh as a GitHub Release on the private repo, so the owner
# can download and install it on a phone. Usage: scripts/publish.sh [--yes]
#
# OUTWARD-FACING: AI agents must get the owner's explicit OK in chat before running this (02-standards §5).
# It creates and pushes the tag v<versionName>.<versionCode> on the current commit, which must be on master
# and identical to origin/master.
source "$(dirname "$0")/lib.sh"
start_log publish "$@"

command -v gh >/dev/null || die "The GitHub CLI (gh) is needed."
APK="$(release_apk)"; TAG="$(release_tag)"
[ -f "$APK" ] || die "$APK not found. Run scripts/release.sh first."
[ -z "$(git status --porcelain)" ] || die "Uncommitted changes."

git fetch -q origin
[ "$(git rev-parse --abbrev-ref HEAD)" = "master" ] || die "Publish from master (merged code only)."
[ "$(git rev-parse HEAD)" = "$(git rev-parse origin/master)" ] || die "Local master differs from origin/master. Pull/push first."
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "Tag $TAG already exists. Bump edith.versionCode in gradle.properties."
[ "$(cat "$APK.commit" 2>/dev/null)" = "$(git rev-parse HEAD)" ] || die "$APK wasn't built from this commit. Run scripts/release.sh again."

PRERELEASE=()
case "$(version_name)" in *-*) PRERELEASE=(--prerelease) ;; esac

echo "About to publish $APK as GitHub Release $TAG on $(gh repo view --json nameWithOwner -q .nameWithOwner) (commit $(git rev-parse --short HEAD))."
if [ "${1:-}" != "--yes" ]; then
  read -r -p "Publish? [y/N] " answer
  [ "$answer" = "y" ] || [ "$answer" = "Y" ] || die "Cancelled."
fi

step "Tagging and publishing"
git tag -a "$TAG" -m "Edith $(version_name) (versionCode $(version_code))"
git push origin "$TAG"
gh release create "$TAG" "$APK" "$APK.sha256" "${PRERELEASE[@]}" \
  --title "Edith $(version_name) (build $(version_code))" \
  --notes "Signed release APK built from $(git rev-parse --short HEAD).

Install on a phone: open this page in the phone's browser (signed in to GitHub — the repo is private), download the .apk, and allow your browser to install unknown apps when Android asks. Updates install over the previous version without losing data."
echo
gh release view "$TAG" --json url -q .url
