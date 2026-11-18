#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
kotlin=${KOTLIN_HOME:?Set KOTLIN_HOME to your Kotlin compiler directory}
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
"$kotlin/bin/kotlinc" -language-version 2.2 -d "$work" \
  android/kotlin/com/atlas/speech/Endpoint.kt tests/speech/EndpointChecks.kt
java -cp "$work:$kotlin/lib/kotlin-stdlib.jar" com.atlas.speech.EndpointChecksKt
