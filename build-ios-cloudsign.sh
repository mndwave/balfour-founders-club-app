#!/bin/zsh
# Balfour Founders Club iOS build, Apple CLOUD-SIGNED (healer:442b9908, 2026-10-01).
# BUILD ONLY: archives, signs via App Store Connect API key, exports an IPA and verifies the signed
# entitlements. It never uploads or submits anything. Run on the Mac build host.
#
# Why this exists: Balfour's Apple Distribution key used to live in an agent-created CI keychain that
# locked on every sleep/reboot (errSecInternalComponent over SSH, password nobody held). Apple's
# cloud-managed signing needs NO local private key, so no keychain is touched at all. It only works
# while no local "Apple Distribution: Balfour Winery LLP" identity is visible (that is why the old
# ci-build keychain reference was removed 2026-10-01). Do not re-add one.
#
# How: (1) archive UNSIGNED, (2) ad-hoc sign the archive with the app's REAL entitlements so they
# survive (an unsigned archive exports with NO push/Wallet/universal links, which is exactly how the
# first submission shipped), (3) export with automatic signing + API key, Apple signs in the cloud.
set -e
source ~/.zshrc 2>/dev/null || true
source ~/.appstoreconnect/balfour.env
KEY="$HOME/.appstoreconnect/private_keys/AuthKey_${ASC_KEY_ID}.p8"
TEAM_ID=2GRD8DS9U5
PASS_TYPE="${TEAM_ID}.pass.gs.balfour.loyalty"
DOMAINS=(founders.balfourwinery.com balfour.boldthin.gs)
APP_URL=https://founders.balfourwinery.com

cd ~/balfour-founders-club-app
npx cap sync ios
grep -q "\"url\": \"$APP_URL\"" ios/App/App/capacitor.config.json || { echo "❌ CONFIG-GUARD: server.url is not $APP_URL"; exit 1; }

cd ios/App
rm -rf build
echo "=== ARCHIVE (unsigned) START $(date) ==="
xcodebuild -project App.xcodeproj -scheme App -configuration Release -destination "generic/platform=iOS" \
  -archivePath build/App.xcarchive clean archive CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  DEVELOPMENT_TEAM=$TEAM_ID > build-archive-cloud.log 2>&1 || { tail -30 build-archive-cloud.log; exit 1; }
echo "=== ARCHIVE DONE $(date) ==="

# Entitlements come from THIS project's App.entitlements only. (Never "newest App.app.xcent in
# DerivedData": every Capacitor project here is named "App", and that once picked up Randalls' pass type.)
XC=$(mktemp /tmp/balfour.XXXXXX.entitlements)
sed "s/\$(TeamIdentifierPrefix)/${TEAM_ID}./" App/App.entitlements > "$XC"
plutil -lint "$XC" >/dev/null
grep -q "<string>${PASS_TYPE}</string>" "$XC" || { echo "❌ entitlements do not carry ${PASS_TYPE}"; exit 1; }
grep -qi randalls "$XC" && { echo "❌ entitlements mention randalls"; exit 1; }
APP=build/App.xcarchive/Products/Applications/App.app
for f in $APP/Frameworks/*.framework(N); do codesign --force --sign - "$f"; done
codesign --force --sign - --entitlements "$XC" --generate-entitlement-der "$APP"
rm -f "$XC"

echo "=== EXPORT (cloud-signed) START $(date) ==="
xcodebuild -exportArchive -archivePath build/App.xcarchive -exportOptionsPlist exportOptions-cloud.plist \
  -exportPath build/export -allowProvisioningUpdates \
  -authenticationKeyPath "$KEY" -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID"
echo "=== EXPORT DONE $(date) ==="
ls -la build/export

# ENTITLEMENT-GUARD on the exported IPA (same contract as build-ios.sh).
CHECK_DIR=$(mktemp -d)
unzip -q build/export/App.ipa -d "$CHECK_DIR"
ents=$(codesign -d --entitlements - --xml "$CHECK_DIR/Payload/App.app" 2>/dev/null | plutil -convert xml1 -o - - 2>/dev/null)
signer=$(codesign -dvv "$CHECK_DIR/Payload/App.app" 2>&1 | grep "Authority=Apple Distribution" || true)
fail=0
print -r -- "$ents" | grep -A1 '<key>aps-environment</key>' | grep -q '<string>production</string>' || { echo "❌ aps-environment is not production"; fail=1; }
print -r -- "$ents" | grep -q "<string>${PASS_TYPE}</string>" || { echo "❌ pass type ${PASS_TYPE} missing"; fail=1; }
for d in $DOMAINS; do print -r -- "$ents" | grep -q "<string>applinks:${d}</string>" || { echo "❌ applinks:${d} missing"; fail=1; }; done
[ -n "$signer" ] || { echo "❌ IPA is not signed by an Apple Distribution identity"; fail=1; }
rm -rf "$CHECK_DIR"
[ $fail -eq 0 ] || { echo "Do NOT upload this build."; exit 1; }
echo "✅ ENTITLEMENT-GUARD: production push, ${PASS_TYPE}, applinks ${DOMAINS[*]}, ${signer}"
echo "BUILD_SCRIPT_COMPLETE_OK"
