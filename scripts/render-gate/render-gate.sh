#!/bin/bash
# RENDER-GATE (healer:4b3d7c53, ONE-UI-COMPOSE-2026-10-23): every natively drawn element must match its React reference
# pixel for pixel. React is the reference; a failure means the native element moves, never the reference.
#
#   scripts/render-gate/render-gate.sh android|ios|all     exit 0 = every element passes; 1 = a divergence (diff PNGs listed)
#
# Reference: the live site page rendered by Playwright at the device's CSS size and density, every API call answered from
# fixtures/*.har (a synthetic test member, recorded with reference.mjs --record). Android: Chromium on this box seeing only
# Android's system fonts, unhinted, as a phone draws them. iOS: WebKit on the Mac, for Apple's fonts and text stack.
# Native: the same composable / SwiftUI view, fed the same recorded data, rendered at the same size and density
# (Android: RenderGateDeviceTest on the shared emulator, android-native.sh; iOS: the LoyaltyCard package tests on an
# iPhone 17 simulator over the macbook path, ios-native.sh).
# Diff: diff.mjs, full colour, per pixel. No baseline images, no allowed-gap list.
# Env: RENDER_GATE_OUT (default a fresh mktemp dir) · RENDER_GATE_SITE · RENDER_GATE_MAX_RATIO (default 0.002)
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$(cd "$HERE/../.." && pwd)"
SITE="${RENDER_GATE_SITE:-https://founders.balfourwinery.com}"
OUT="${RENDER_GATE_OUT:-$(mktemp -d "${TMPDIR:-/tmp}/render-gate-balfour.XXXXXX")}"; mkdir -p "$OUT"
MAX="${RENDER_GATE_MAX_RATIO:-0.002}"
TOKEN_KEY=balfour_member_token
which="${1:-all}"
fail=0

# element  page     har           native file   [DOM click that opens it, as the app does]
ANDROID=(
  "card    /dashboard my-id-overlay.har native-card.png a[href='/my-id']"
  "history /history history.har   native-history.png"
)
IOS=(
  "card    /dashboard my-id-overlay.har bf-native-ios-card.png a[href='/my-id']"
)

check() { # platform element path har native engine w h dpr [click]
  local ref="$OUT/$1-$2-react.png"
  # The iOS reference is rendered on the Mac by ios-native.sh; everything else here.
  [ "$6" = webkit ] && [ -s "$ref" ] || node "$HERE/reference.mjs" --site "$SITE" --path "$3" --har "$HERE/fixtures/$4" --out "$ref" --engine "$6" \
    --w "$7" --h "$8" --dpr "$9" --token-key "$TOKEN_KEY" ${10:+--click "${10}"} >/dev/null || { echo "FAIL $1 $2: reference render failed"; fail=1; return; }
  [ -s "$OUT/$5" ] || { echo "FAIL $1 $2: no native render ($OUT/$5)"; fail=1; return; }
  node "$HERE/diff.mjs" --ref "$ref" --native "$OUT/$5" --diff "$OUT/$1-$2-diff.png" --max-ratio "$MAX" --label "$1 $2" \
    || { echo "     diff: $OUT/$1-$2-diff.png"; fail=1; }
}

if [ "$which" = android ] || [ "$which" = all ]; then
  # On the shared emulator: a real Android text stack. Robolectric (CardRenderTest, RenderGateScreensTest) skips
  # variable-font axes, so its text is not a phone's: it is a quick local geometry check, never the gate.
  "$HERE/android-native.sh" "$APP" gs.boldthin.balfour.foundersclub gs.boldthin.balfour.foundersclub.RenderGateDeviceTest "$OUT" \
    || { echo "FAIL android: native render on the emulator failed"; fail=1; }
  for row in "${ANDROID[@]}"; do set -- $row; check android "$1" "$2" "$3" "$4" chromium 412 915 3 "${5:-}"; done
fi

if [ "$which" = ios ] || [ "$which" = all ]; then
  "$HERE/ios-native.sh" "$APP" bf "$OUT" "$SITE" /dashboard my-id-overlay.har "$TOKEN_KEY" "a[href='/my-id']" || { echo "FAIL ios: native render failed"; fail=1; }
  for row in "${IOS[@]}"; do set -- $row; check ios "$1" "$2" "$3" "$4" webkit 402 874 3 "${5:-}"; done
fi

# The iOS build runs on the Mac (build-ios.sh), outside this script: a clean-tree iOS pass stamps this commit on the Mac,
# and build-ios.sh refuses to archive a commit without a fresh stamp.
if [ $fail = 0 ] && { [ "$which" = ios ] || [ "$which" = all ]; }; then
  if [ -z "$(git -C "$APP" status --porcelain -- ios android scripts/render-gate)" ]; then
    sha="$(git -C "$APP" rev-parse HEAD)"
    ssh "${RENDER_GATE_MAC:-macbook}" "mkdir -p ~/.render-gate && date -u +%FT%TZ > ~/.render-gate/$(basename "$APP")-$sha-ios.pass" \
      && echo "ios pass stamped for $sha"
  else
    echo "ios passed on a dirty tree: no stamp (commit, then re-run for the release)"
  fi
fi

[ $fail = 0 ] && echo "RENDER GATE PASS ($which) - renders in $OUT" || echo "RENDER GATE FAIL ($which) - renders and diffs in $OUT"
exit $fail
