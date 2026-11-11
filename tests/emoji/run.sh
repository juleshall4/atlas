#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
javac --release 17 -d "$work" android/kotlin/com/atlas/engine/EmojiPages.java android/kotlin/com/atlas/engine/EmojiSearch.java \
  tests/emoji/EmojiPagesChecks.java tests/emoji/EmojiSearchChecks.java
java -cp "$work" com.atlas.engine.EmojiPagesChecks android/assets/emoji.txt android/assets/emoji-words.txt android/assets/emoji-tones.txt
java -cp "$work" com.atlas.engine.EmojiSearchChecks
