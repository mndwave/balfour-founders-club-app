#!/bin/bash
# The iOS half of the render gate, on the Mac (macbook ssh path, builds only):
#   reference: reference.mjs in Playwright WebKit on macOS, so the page gets Apple's fonts and text stack (SF Pro, SF Mono
#              for ui-monospace), which WebKit on the Linux box does not have; ~/render-gate-tools holds Playwright there.
#   native:    the app's ios/LoyaltyCard package tests (RenderGateTests) on a fresh iPhone 17 / iOS 27 simulator.
# Both PNGs come back to <out dir> as ios-card-react.png and <prefix>-native-ios-card.png.
# Admitted through the Mac's mac-load-guard (exit 75 = Mac over its cap: retry later, never a pass).
#   ios-native.sh <app dir> <prefix> <out dir> <site> <path> <har> <token key> [click selector]
set -uo pipefail
APP="$1"; P="$2"; OUT="$3"; SITE="$4"; PAGE="$5"; HAR="$6"; TOKEN_KEY="$7"; CLICK="${8:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
MAC="${RENDER_GATE_MAC:-macbook}"
D="render-gate-$P-$$"
ssh "$MAC" "mkdir -p ~/$D" || exit 1
trap 'ssh "$MAC" "rm -rf ~/$D" >/dev/null 2>&1' EXIT
rsync -a --exclude .build "$APP/ios/LoyaltyCard/" "$MAC:$D/LoyaltyCard/" \
  && scp -q "$HERE/fixtures/card.json" "$HERE/fixtures/$HAR" "$HERE/reference.mjs" "$MAC:$D/" || exit 1
ssh "$MAC" "bash -s" > "$OUT/ios-$P.log" 2>&1 <<REMOTE
set -u
source ~/.zshrc >/dev/null 2>&1
cd ~/$D
[ -d ~/render-gate-tools/node_modules/playwright ] || { mkdir -p ~/render-gate-tools && cd ~/render-gate-tools \
  && { [ -f package.json ] || echo '{"name":"render-gate-tools","private":true}' > package.json; } \
  && npm install --no-audit --no-fund playwright@1.62.1 && npx playwright install webkit && cd ~/$D; } || exit 1
RENDER_GATE_PLAYWRIGHT_FROM=~/render-gate-tools node reference.mjs --site "$SITE" --path "$PAGE" --har "$HAR" \
  --out ~/$D/react.png --engine webkit --w 402 --h 874 --dpr 3 --token-key "$TOKEN_KEY" ${CLICK:+--click "$CLICK"} || exit 1
[ -x ~/bin/mac-load-guard ] && { ~/bin/mac-load-guard admit simulator || exit 75; }
SIM=\$(xcrun simctl create render-gate-$P-$$ "iPhone 17" com.apple.CoreSimulator.SimRuntime.iOS-27-0) || exit 1
cd LoyaltyCard
TEST_RUNNER_CARD_FIXTURE=~/$D/card.json TEST_RUNNER_CARD_RENDER_OUT=~/$D/native.png \
  xcodebuild test -scheme LoyaltyCard -destination "id=\$SIM" -only-testing:LoyaltyCardTests/RenderGateTests -derivedDataPath ~/$D/dd
rc=\$?
xcrun simctl shutdown \$SIM >/dev/null 2>&1; xcrun simctl delete \$SIM
exit \$rc
REMOTE
rc=$?
[ $rc = 0 ] || { echo "ios-native: exit $rc (log $OUT/ios-$P.log)"; exit $rc; }
scp -q "$MAC:$D/native.png" "$OUT/$P-native-ios-card.png" && scp -q "$MAC:$D/react.png" "$OUT/ios-card-react.png"
