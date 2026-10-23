#!/bin/zsh
# Balfour Founders Club iOS build — ported from ~/randalls-rewards-app/build-ios.sh
# (RR commits 9aa6bd4, 329518a, e261f0d; healer:d8469b07). Run on the Mac build host.
# BUILD ONLY: archives and exports an IPA, verifies the signed entitlements, and stops.
# It never uploads or submits anything.
set -e
source ~/.zshrc 2>/dev/null || true

TEAM_ID=2GRD8DS9U5
PASS_TYPE="${TEAM_ID}.pass.gs.balfour.loyalty"
DOMAINS=(founders.balfourwinery.com balfour.boldthin.gs)
APP_URL=https://founders.balfourwinery.com

# ASC-API-KEY-SIGNING-2026-09-30 (RR parity): Xcode on the Mac has no Apple account signed in,
# so plain -allowProvisioningUpdates can never refresh a stale profile. Release signs manually
# with the "Balfour Founders Club App Store" profile (push, Wallet, associated domains), so a
# key is only needed if that profile has to be regenerated. IDs (not secret) come from
# ~/.appstoreconnect/balfour.env (ASC_KEY_ID, ASC_ISSUER_ID); the .p8 lives in
# ~/.appstoreconnect/private_keys.
[ -f ~/.appstoreconnect/balfour.env ] && source ~/.appstoreconnect/balfour.env
ASC_AUTH=()
if [ -n "$ASC_KEY_ID" ] && [ -n "$ASC_ISSUER_ID" ]; then
  ASC_AUTH=(-allowProvisioningUpdates -authenticationKeyPath "$HOME/.appstoreconnect/private_keys/AuthKey_${ASC_KEY_ID}.p8" -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID")
  echo "Using App Store Connect API key $ASC_KEY_ID for provisioning"
else
  echo "No Balfour ASC API key - signing with the installed manual profile only"
fi

cd ~/balfour-founders-club-app
# RENDER-GATE-2026-10-23 (healer:4b3d7c53): the native card must match its React reference pixel for pixel. The gate
# renders on the box (scripts/render-gate/render-gate.sh ios) and stamps the commit here on a pass; refuse a commit
# without a stamp from the last 24 h (the reference is the live site, so an old pass can be out of date).
RG_SHA=$(git -C ~/balfour-founders-club-app rev-parse HEAD)
RG_STAMP=~/.render-gate/balfour-founders-club-app-$RG_SHA-ios.pass
if [ ! -f "$RG_STAMP" ] || [ -n "$(find "$RG_STAMP" -mmin +1440)" ]; then
  echo "❌ RENDER-GATE: no iOS render-gate pass for $RG_SHA in the last 24 h. On the box: ~/balfour-founders-club-app/scripts/render-gate/render-gate.sh ios"
  exit 1
fi
echo "✅ RENDER-GATE: iOS render gate passed for $RG_SHA ($(cat "$RG_STAMP"))"
# capacitor.config.json is generated (gitignored): regenerate it so the binary carries the
# current server URL and plugin set, then refuse if the URL is not the public domain.
npx cap sync ios
if ! grep -q "\"url\": \"$APP_URL\"" ios/App/App/capacitor.config.json; then
  echo "❌ CONFIG-GUARD: ios/App/App/capacitor.config.json server.url is not $APP_URL"
  exit 1
fi

cd ios/App
# IOS-ROOT-VC-GUARD-2026-10-20 (healer:596fd32f): under the UIScene life cycle SceneDelegate builds the window and
# Main.storyboard is never loaded, so a bare CAPBridgeViewController silently skips MainViewController (the 6 s
# weak-signal fallback and the native member card). Refuse to archive unless SceneDelegate builds MainViewController.
if ! grep -q "rootViewController = MainViewController()" App/SceneDelegate.swift; then
  echo "❌ IOS-ROOT-VC-GUARD: App/SceneDelegate.swift does not build MainViewController - the native card and offline fallback would never run. Do NOT archive."
  exit 1
fi
echo "=== ARCHIVE START $(date) ==="
rm -rf build
xcodebuild \
  -project App.xcodeproj \
  -scheme App \
  -configuration Release \
  -destination "generic/platform=iOS" \
  -archivePath build/App.xcarchive \
  clean archive \
  "${ASC_AUTH[@]}" \
  DEVELOPMENT_TEAM=$TEAM_ID
echo "=== ARCHIVE DONE $(date) ==="
xcodebuild \
  -exportArchive \
  -archivePath build/App.xcarchive \
  -exportOptionsPlist exportOptions.plist \
  -exportPath build/export \
  "${ASC_AUTH[@]}"
echo "=== EXPORT DONE $(date) ==="
ls -la build/export

# ENTITLEMENT-GUARD (PUSH-ENTITLEMENT-GUARD-2026-09-29, extended BALFOUR-SINGLE-BUILD-2026-10-01):
# Balfour 1.1.1 build 2 was signed with NO entitlements at all because project.pbxproj never set
# CODE_SIGN_ENTITLEMENTS - push, Wallet and universal links were all silently missing. Refuse to
# finish unless the SIGNED app in both the archive and the exported IPA carries every one of them.
check_entitlements() {
  local label=$1 app=$2 ents
  ents=$(codesign -d --entitlements - --xml "$app" 2>/dev/null | plutil -convert xml1 -o - - 2>/dev/null)
  local fail=0
  if ! print -r -- "$ents" | grep -A1 '<key>aps-environment</key>' | grep -q '<string>production</string>'; then
    echo "❌ ENTITLEMENT-GUARD ($label): aps-environment is not production"; fail=1
  fi
  if ! print -r -- "$ents" | grep -q "<string>${PASS_TYPE}</string>"; then
    echo "❌ ENTITLEMENT-GUARD ($label): pass type ${PASS_TYPE} missing (Apple Wallet)"; fail=1
  fi
  for d in $DOMAINS; do
    if ! print -r -- "$ents" | grep -q "<string>applinks:${d}</string>"; then
      echo "❌ ENTITLEMENT-GUARD ($label): applinks:${d} missing (universal links)"; fail=1
    fi
  done
  if [ $fail -ne 0 ]; then
    echo "Do NOT upload this build. Check CODE_SIGN_ENTITLEMENTS, App.entitlements and the provisioning profile, then rebuild."
    return 1
  fi
  echo "✅ ENTITLEMENT-GUARD ($label): aps-environment=production, ${PASS_TYPE}, applinks for ${DOMAINS[*]}"
}

check_entitlements archive build/App.xcarchive/Products/Applications/App.app
CHECK_DIR=$(mktemp -d)
unzip -q build/export/App.ipa -d "$CHECK_DIR"
if ! check_entitlements ipa "$CHECK_DIR/Payload/App.app"; then
  rm -rf "$CHECK_DIR"
  exit 1
fi
rm -rf "$CHECK_DIR"
# SCENE-LIFECYCLE-GUARD-2026-10-03: iOS 27 SDK aborts at launch without UIApplicationSceneManifest (App Review rejected Randalls 1.0.9 (7), guideline 2.1(a)).
CHECK_DIR=$(mktemp -d)
unzip -q -o build/export/App.ipa "Payload/App.app/Info.plist" -d "$CHECK_DIR"
if ! plutil -extract UIApplicationSceneManifest xml1 -o - "$CHECK_DIR/Payload/App.app/Info.plist" >/dev/null 2>&1; then
  echo "❌ SCENE-LIFECYCLE-GUARD: signed App.app has NO UIApplicationSceneManifest - it will crash on launch on iOS 27. Do NOT upload this IPA."
  rm -rf "$CHECK_DIR"
  exit 1
fi
rm -rf "$CHECK_DIR"
echo "✅ SCENE-LIFECYCLE-GUARD: UIApplicationSceneManifest present in the signed app"
echo "BUILD_SCRIPT_COMPLETE_OK"
