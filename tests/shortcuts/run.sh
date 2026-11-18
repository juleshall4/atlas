#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
sdk=${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK}
kotlin=${KOTLIN_HOME:?Set KOTLIN_HOME to your Kotlin compiler directory}
android="$sdk/platforms/android-36/android.jar"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
"$kotlin/bin/kotlinc" -language-version 2.2 -classpath "$android" -d "$work" \
  android/kotlin/com/atlas/keyboard/HyperKey.kt android/kotlin/com/atlas/keyboard/Modifiers.kt tests/shortcuts/HyperKeyChecks.kt
java -cp "$work:$android:$kotlin/lib/kotlin-stdlib.jar" com.atlas.keyboard.HyperKeyChecksKt
