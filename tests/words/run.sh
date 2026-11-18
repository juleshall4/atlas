#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
sdk=${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
engine=android/kotlin/com/atlas/engine
javac --release 17 -cp "$sdk/platforms/android-36/android.jar" -d "$work" \
  "$engine/LanguageHabits.java" "$engine/SwipeWords.java" "$engine/SuggestionPolicy.java" \
  "$engine/BlockedWords.java" "$engine/TypedPredictions.java" "$engine/SentenceCase.java" "$engine/CombinedDictionary.java" "$engine/TypingLanguages.java" "$engine/LanguageFiles.java" \
  tests/words/SavedWordsChecks.java tests/words/BlockedWordsChecks.java tests/words/ContextSuggestionChecks.java tests/words/SentenceCaseChecks.java tests/words/LanguageChecks.java
java -cp "$work:$sdk/platforms/android-36/android.jar" com.atlas.engine.SavedWordsChecks
java -cp "$work:$sdk/platforms/android-36/android.jar" com.atlas.engine.BlockedWordsChecks
java -cp "$work:$sdk/platforms/android-36/android.jar" com.atlas.engine.ContextSuggestionChecks
java -cp "$work:$sdk/platforms/android-36/android.jar" com.atlas.engine.SentenceCaseChecks
java -cp "$work:$sdk/platforms/android-36/android.jar" com.atlas.engine.LanguageChecks
