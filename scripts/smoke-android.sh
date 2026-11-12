#!/usr/bin/env bash
# R8-BALFOUR-2026-11-12 (healer:8ab059db, copy of the Randalls Rewards RR-RELEASE-GATE-2026-10-19 smoke): launch smoke test. Installs the APK over the previous build on the shared emulator, grants
# notifications (the state a real member is in), cold-launches twice and fails on any FATAL EXCEPTION / dead process.
# A renamed (R8) release must still launch: Capacitor plugins, the JS bridges and the native card load by name.
#   scripts/smoke-android.sh path/to/app.apk [path/to/previous.apk]
set -euo pipefail
APK="${1:?apk path}"; PREV="${2:-}"
PKG=gs.boldthin.balfour.foundersclub
ADB="${ANDROID_HOME:-$HOME/android-sdk}/platform-tools/adb"
# GATE-HARDEN (healer:62036617): the AVD is the shared lane (hetzner-build node, behind one queue + lock; this box has no KVM,
# a plain adb sees no emulator). Take the lane as render-gate/android-native.sh does, reach it with the lane's adb wrapper,
# and power it down afterwards if we booted it. Exit 75 = the lane stayed busy (retry), never a pass.
LANE="${E2E_LANE_DIR:-$HOME/seq1-sessions-native/scripts}"
LOCK="$HOME/.cache/seq1-native-e2e.lock"
if [ -z "${ANDROID_SERIAL:-}" ] && [ -x "$LANE/e2e-emulator.sh" ]; then
  exec 9>"$LOCK"
  . "$LANE/e2e-queue.sh"
  e2e_queue_acquire "${SMOKE_LANE_WAIT:-2700}" "smoke-android $PKG" || { echo "SMOKE: emulator lane busy"; exit 75; }
  echo "smoke-android $PKG pid $$ cwd $PWD since $(date -u +%FT%TZ)" > "$HOME/.cache/seq1-native-e2e.holder"
  export SEQ1_E2E_LOCK_HELD=1
  ADB="$LANE/adb-nolock"
  WAS_UP=0; "$ADB" get-state >/dev/null 2>&1 && WAS_UP=1
  "$LANE/e2e-emulator.sh" ensure >/dev/null 2>&1 || { echo "SMOKE: emulator ensure failed"; exit 75; }
  trap '[ "$WAS_UP" = 1 ] || "$LANE/e2e-emulator.sh" stop >/dev/null 2>&1' EXIT
  A="$ADB"
else
  DEV="${ANDROID_SERIAL:-$($ADB devices | awk '/emulator-.*device$/ {print $1; exit}')}"
  [ -n "$DEV" ] || { echo "SMOKE FAIL: no emulator attached"; exit 1; }
  A="$ADB -s $DEV"
fi
$A uninstall "$PKG" >/dev/null 2>&1 || true
[ -n "$PREV" ] && { $A install "$PREV" >/dev/null; $A shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS; $A shell am start -W -n "$PKG/.MainActivity" >/dev/null; sleep 6; }
$A install -r "$APK" >/dev/null
$A shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
for i in 1 2; do
  $A shell am force-stop "$PKG"; $A logcat -c
  $A shell am start -W -n "$PKG/.MainActivity" >/dev/null
  sleep 10
  if $A logcat -d -b crash | grep -q "FATAL EXCEPTION"; then echo "SMOKE FAIL (launch $i):"; $A logcat -d -b crash | grep -E "FATAL|Caused by" | head -5; exit 1; fi
  [ -n "$($A shell pidof "$PKG")" ] || { echo "SMOKE FAIL (launch $i): process not running"; exit 1; }
done
echo "SMOKE OK: $APK launches twice, notifications granted, no crash"
