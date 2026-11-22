#!/usr/bin/env bash
# Builds "Atlas Debug": a separate app with its own package and orange icon, so it installs next to the
# real Atlas without replacing it or touching its saved words. Pass --install to install it over adb.
set -euo pipefail
cd "$(dirname "$0")/.."

# A git worktree has no signing folder, so it uses the one in the main checkout.
main=$(cd "$(git rev-parse --git-common-dir)/.." && pwd)
[[ ! -e signing && -d "$main/signing" ]] && ln -s "$main/signing" signing

backup=$(mktemp -d)
files=(ink.toml android/src/main/AndroidManifest.xml
  android/src/main/res/mipmap-anydpi-v26/ic_launcher.xml android/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml)
for file in "${files[@]}"; do mkdir -p "$backup/$(dirname "$file")"; cp "$file" "$backup/$file"; done
restore() {
  for file in "${files[@]}"; do cp "$backup/$file" "$file"; done
  rm -f android/src/main/res/values/atlas_debug.xml
  rmdir android/src/main/res/values 2>/dev/null || true
  rm -rf android/kotlin/com/atlas/debug
  rm -rf "$backup"
}
trap restore EXIT

# sed -i takes different arguments on macOS and Linux, so write to a temporary file.
edit() { local file=$1; shift; sed "$@" "$file" > "$file.tmp" && mv "$file.tmp" "$file"; }

edit ink.toml -e 's/^name = .*/name = "Atlas Debug"/' -e 's/^package = .*/package = "com.atlas.debug"/'
edit android/src/main/AndroidManifest.xml 's/android:label="Atlas"/android:label="Atlas Debug"/'
cp android/debug/ic_launcher.xml android/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
cp android/debug/ic_launcher.xml android/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
mkdir -p android/src/main/res/values
cp android/debug/colors.xml android/src/main/res/values/atlas_debug.xml

# Debug-only code, kept out of android/kotlin so release builds never compile it.
# Android starts the battery log provider with the app, so no Atlas code calls it.
mkdir -p android/kotlin/com/atlas/debug
cp android/debug/kotlin/*.kt android/kotlin/com/atlas/debug/
edit android/src/main/AndroidManifest.xml 's|</application>|<provider android:name="com.atlas.debug.BatteryLog" android:authorities="com.atlas.debug.batterylog" android:exported="false" /></application>|'

# Contributors have no release key, so sign with a test key. It is kept outside the checkout,
# so every clone and worktree signs the same way and can update the installed app.
if [[ -z "${INK_KEYSTORE_PASSWORD:-}" && ! -f signing/credentials.env ]]; then
  key="$HOME/.android/atlas-debug.keystore"
  if [[ ! -f "$key" ]]; then
    mkdir -p "$(dirname "$key")"
    keytool -genkeypair -keystore "$key" -alias atlas -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass android -keypass android -dname "CN=Atlas Debug" >/dev/null
  fi
  edit ink.toml "s|^keystore = .*|keystore = \"$key\"|"
  export INK_KEYSTORE_PASSWORD=android
fi

bash tools/release.sh
apk=$(ls -t dist/*.apk | head -1)
echo "Built $apk"
if [[ "${1:-}" == "--install" ]]; then adb install -r "$apk"; fi
