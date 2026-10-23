#!/bin/bash
# Renders the Android native elements for the render gate on the shared emulator (a real Android text stack: fonts and
# variable-font axes as on a phone, which Robolectric does not reproduce). Takes the emulator lane lock for the whole run.
#   android-native.sh <app dir> <application id> <test class> <out dir>
# Exit 75 = the lane stayed busy past RENDER_GATE_LANE_WAIT seconds (default 2700): retry, never a pass.
set -uo pipefail
APP="$1"; PKG="$2"; CLASS="$3"; OUT="$4"
HERE="$(cd "$(dirname "$0")" && pwd)"
LOCK="$HOME/.cache/seq1-native-e2e.lock"
ADB="${ANDROID_HOME:-$HOME/android-sdk}/platform-tools/adb"
DEVDIR="/data/local/tmp/render-gate-$PKG"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
( cd "$APP/android" && ./gradlew --console=plain -q -PallowNoFirebase :app:assembleDebug :app:assembleDebugAndroidTest ) \
  > "$OUT/android-build.log" 2>&1 || { echo "android-native: debug build failed ($OUT/android-build.log)"; exit 1; }
APK="$APP/android/app/build/outputs/apk/debug/app-debug.apk"
TAPK="$APP/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
exec 9>"$LOCK"
flock -w "${RENDER_GATE_LANE_WAIT:-2700}" 9 || { echo "android-native: emulator lane busy"; exit 75; }
export SEQ1_E2E_LOCK_HELD=1
DEV="${ANDROID_SERIAL:-$("$ADB" devices | awk '/emulator-.*device$/ {print $1; exit}')}"
[ -n "$DEV" ] || { echo "android-native: no emulator attached (the shared AVD is booted by the hetzner-build lane): retry"; exit 75; }
A=("$ADB" -s "$DEV")
cleanup() { "${A[@]}" uninstall "$PKG" >/dev/null 2>&1; "${A[@]}" uninstall "$PKG.test" >/dev/null 2>&1; "${A[@]}" shell rm -rf "$DEVDIR" >/dev/null 2>&1; }
trap cleanup EXIT
cleanup
"${A[@]}" install -r "$APK" >/dev/null && "${A[@]}" install -r "$TAPK" >/dev/null || { echo "android-native: install failed"; exit 1; }
"${A[@]}" shell mkdir -p "$DEVDIR" && "${A[@]}" push "$HERE/fixtures" "$DEVDIR/" >/dev/null && "${A[@]}" shell chmod -R a+rX "$DEVDIR"
"${A[@]}" shell am instrument -w -e renderGateDir "$DEVDIR" -e class "$CLASS" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" \
  > "$OUT/android-instrument.log" 2>&1
grep -q '^OK (' "$OUT/android-instrument.log" || { echo "android-native: render test failed ($OUT/android-instrument.log)"; exit 1; }
for f in $("${A[@]}" shell run-as "$PKG" ls files/render-gate); do
  "${A[@]}" exec-out run-as "$PKG" cat "files/render-gate/$f" > "$OUT/$f"
done
