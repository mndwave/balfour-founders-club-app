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
# The lane is one shared AVD behind one lock, owned by ~/seq1-sessions-native (E2E-LANE-ENSURE-2026-10-24, healer:fc5a8a10): queue first come
# first served, then let the lane boot the AVD for us (it lives on the hetzner-build node; this box has no KVM) and reach it with the
# lane's adb wrapper (tunnel to that node's adb server). A plain adb here sees no emulator, which is how the 2026-10-24 run stalled.
LANE="${E2E_LANE_DIR:-$HOME/seq1-sessions-native/scripts}"
[ -x "$LANE/e2e-emulator.sh" ] || { echo "android-native: lane scripts missing ($LANE): retry"; exit 75; }
. "$LANE/e2e-queue.sh"
e2e_queue_acquire "${RENDER_GATE_LANE_WAIT:-2700}" "render-gate $PKG" || { echo "android-native: emulator lane busy"; exit 75; }
echo "render-gate $PKG pid $$ cwd $PWD since $(date -u +%FT%TZ)" > "$HOME/.cache/seq1-native-e2e.holder"
export SEQ1_E2E_LOCK_HELD=1
ADB="$LANE/adb-nolock"
WAS_UP=0; "$ADB" get-state >/dev/null 2>&1 && WAS_UP=1
"$LANE/e2e-emulator.sh" ensure > "$OUT/android-emulator.log" 2>&1 || { echo "android-native: emulator ensure failed ($OUT/android-emulator.log): retry"; exit 75; }
A=("$ADB")
cleanup() { "${A[@]}" uninstall "$PKG" >/dev/null 2>&1; "${A[@]}" uninstall "$PKG.test" >/dev/null 2>&1; "${A[@]}" shell rm -rf "$DEVDIR" >/dev/null 2>&1; }
# We booted it, we power it down (the lane stops it when the last gate leaves; stop is a no-op while a release holds a lease).
stopped() { [ "$WAS_UP" = 1 ] || "$LANE/e2e-emulator.sh" stop >/dev/null 2>&1; }
trap 'cleanup; stopped' EXIT
cleanup
"${A[@]}" install -r "$APK" >/dev/null && "${A[@]}" install -r "$TAPK" >/dev/null || { echo "android-native: install failed"; exit 1; }
"${A[@]}" shell mkdir -p "$DEVDIR" && "${A[@]}" push "$HERE/fixtures" "$DEVDIR/" >/dev/null && "${A[@]}" shell chmod -R a+rX "$DEVDIR"
"${A[@]}" logcat -c >/dev/null 2>&1
"${A[@]}" shell am instrument -w -e renderGateDir "$DEVDIR" -e class "$CLASS" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" \
  > "$OUT/android-instrument.log" 2>&1
# On failure keep the device log of the run (app, test runner, network policy, crashes) next to the instrument output.
grep -q '^OK (' "$OUT/android-instrument.log" || {
  "${A[@]}" logcat -d -v time 2>/dev/null | grep -E "$PKG|TestRunner|AndroidRuntime|System.err|NetworkSecurity|[Cc]leartext" > "$OUT/android-logcat.txt"
  echo "android-native: render test failed ($OUT/android-instrument.log, $OUT/android-logcat.txt)"; exit 1; }
for f in $("${A[@]}" shell run-as "$PKG" ls files/render-gate); do
  "${A[@]}" exec-out run-as "$PKG" cat "files/render-gate/$f" > "$OUT/$f"
done
