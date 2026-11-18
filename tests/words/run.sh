#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
sdk=${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}
kotlin=${KOTLIN_HOME:?Set KOTLIN_HOME to your Kotlin compiler directory}
stdlib=${KOTLIN_STDLIB:-$kotlin/lib/kotlin-stdlib.jar}
android="$sdk/platforms/android-36/android.jar"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
engine=android/kotlin/com/atlas/engine
keyboard=android/kotlin/com/atlas/keyboard
"$kotlin/bin/kotlinc" -language-version 2.2 -classpath "$stdlib" -d "$work/layout" "$keyboard/Layout.kt"
javac --release 17 -cp "$work/layout:$android:$stdlib" -d "$work/classes" \
  "$engine/"*.java android/kotlin/org/futo/ml/inference/*.java tests/words/*.java
"$kotlin/bin/kotlinc" -language-version 2.2 -classpath "$work/classes:$android:$stdlib" -d "$work/composer" \
  "$keyboard/Composer.kt" "$keyboard/Field.kt" tests/words/SavedWordCaseChecks.kt
for check in SavedWordsChecks BlockedWordsChecks ContextSuggestionChecks SentenceCaseChecks LanguageChecks SavedWordCaseChecksKt; do
  java -cp "$work/classes:$work/layout:$work/composer:$android:$stdlib" "com.atlas.engine.$check"
done
