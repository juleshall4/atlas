#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# A git worktree has no signing folder, so it uses the one in the main checkout.
main=$(cd "$(git rev-parse --git-common-dir)/.." && pwd)
[[ ! -e signing && -d "$main/signing" ]] && ln -s "$main/signing" signing

# CI can provide credentials directly; local builds use the ignored signing folder.
if [[ -z "${INK_KEYSTORE_PASSWORD:-}" && -f signing/credentials.env ]]; then
  source signing/credentials.env
fi
: "${INK_KEYSTORE_PASSWORD:?Set INK_KEYSTORE_PASSWORD or restore signing/credentials.env}"
export INK_KEYSTORE_PASSWORD
export INK_KEY_PASSWORD="${INK_KEY_PASSWORD:-$INK_KEYSTORE_PASSWORD}"

bun run notes

# Debug-only code must never ship. tools/debug.sh copies it in and removes it after its build.
release=$(grep -q '^package = "com.atlas"$' ink.toml && echo 1 || true)
if [[ -n "$release" ]] && { [[ -e android/kotlin/com/atlas/debug ]] || grep -q com.atlas.debug android/src/main/AndroidManifest.xml; }; then
  echo "Debug-only code is in the build sources. Remove android/kotlin/com/atlas/debug and restore the manifest." >&2
  exit 1
fi

# Add the "Bug fixes" line, as the GitHub release does, then put the notes back after the build.
cp release-notes.txt release-notes.txt.bak
trap 'mv release-notes.txt.bak release-notes.txt' EXIT
bun .github/ink-release.mjs bug-fixes
bun run build "$@"

if [[ -n "$release" ]]; then
  apk=$(ls -t dist/*.apk | head -1)
  if unzip -p "$apk" 'classes*.dex' | grep -a 'com/atlas/debug/' >/dev/null; then
    rm -f "$apk"
    echo "Debug-only code is in $apk, so it was deleted." >&2
    exit 1
  fi
fi
