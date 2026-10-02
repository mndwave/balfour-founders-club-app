#!/bin/bash
# Build the signed Balfour Android release ONCE and ship the SAME build to both places:
#   - Google Play (AAB)                          : play-release.mjs
#   - media.seq1.net/apps/balfour-founders-club/ : APK, for Obtainium (Kyle's test channel)
# versionCode/versionName come from android/app/build.gradle (the single source of truth), so the
# APK on media and the AAB on Play can never carry different versions.
#
#   scripts/release-android.sh                      build + verify only
#   scripts/release-android.sh --media-only         build, publish the APK to media (catch-up: AAB already on Play)
#   scripts/release-android.sh --publish [track]    build, upload AAB to Play (default internal, draft), publish APK to media
#   scripts/release-android.sh --verify             compare media vs Play (exit 1 on drift), no build
#
# Passwords come from global.conf into this process's environment only; nothing secret is echoed
# (global-conf transcript-leak mandate).
set -euo pipefail
cd "$(dirname "$0")/.."
CONF="$HOME/seq1-healer/global.conf"
PKG="gs.boldthin.balfour.foundersclub"
PREFIX="apps/balfour-founders-club"
BT="$HOME/android-sdk/build-tools/$(ls "$HOME/android-sdk/build-tools" | sort -V | tail -1)"
MODE="${1:-build}"; TRACK="${2:-internal}"

# Version codes use the yyDDDHHMM scheme the old CI used (Kyle's installed build is 262121050): Android refuses a LOWER code,
# so a small counter would never install over it. Play needs a higher code per upload too.
if [ "$MODE" = "--bump" ]; then
  CODE="$(date -u +%y%j%H%M)"
  perl -pi -e "s/(findProperty\(\"versionCode\"\) \?: \")[0-9]+/\${1}$CODE/" android/app/build.gradle
  echo "versionCode -> $CODE in build.gradle: commit it, then run --publish"; exit 0
fi

conf_key() { awk -v k="$1" -F'"' '$0 ~ "^ *" k " *=" { print $2; exit }' "$CONF"; }
r2_env() {
  AWS_ACCESS_KEY_ID="$(conf_key R2_ACCESS_KEY_ID)"; export AWS_ACCESS_KEY_ID
  AWS_SECRET_ACCESS_KEY="$(conf_key R2_SECRET_ACCESS_KEY)"; export AWS_SECRET_ACCESS_KEY
  export AWS_DEFAULT_REGION=auto
  R2="--endpoint-url https://$(conf_key R2_ACCOUNT_ID).r2.cloudflarestorage.com"
}

# Highest versionCode Play knows on ANY track (internal, closed, production).
play_max_code() {
  local edit; edit=$(node ~/.local/bin/play-api.mjs POST "/applications/$PKG/edits" | sed "1s/^[0-9]* //" | node -e 'process.stdin.on("data",d=>console.log(JSON.parse(d).id))')
  node ~/.local/bin/play-api.mjs GET "/applications/$PKG/edits/$edit/tracks" \
    | sed '1s/^[0-9]* //' | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const c=(JSON.parse(s).tracks||[]).flatMap(t=>(t.releases||[]).flatMap(r=>(r.versionCodes||[]).map(Number)));console.log(c.length?Math.max(...c):0)})'
  node ~/.local/bin/play-api.mjs DELETE "/applications/$PKG/edits/$edit" >/dev/null 2>&1 || true
}
media_code() { curl -fsS "https://media.seq1.net/$PREFIX/index.html" | sed -n 's/.*code \([0-9]*\)).*/\1/p' | head -1; }

if [ "$MODE" = "--verify" ]; then
  P="$(play_max_code)"; M="$(media_code || true)"
  echo "Play highest versionCode: $P · media.seq1.net: ${M:-none}"
  [ "$P" = "${M:-none}" ] && { echo "ALIGNED"; exit 0; }
  echo "DRIFT: media APK is not the build on Play. Run scripts/release-android.sh --media-only (or --publish)."; exit 1
fi

# Ship only what is committed: a publish refuses a dirty tracked tree.
if [ "$MODE" != "build" ] && ! { git diff --quiet HEAD -- && git diff --cached --quiet; }; then
  echo "uncommitted changes to tracked files: not publishing"; git status --short -uno | head -10; exit 1
fi

VERSION_CODE="$(sed -n 's/.*findProperty("versionCode") ?: "\([0-9]*\)".*/\1/p' android/app/build.gradle)"
VERSION_NAME="$(sed -n 's/.*findProperty("versionName") ?: "\([^"]*\)".*/\1/p' android/app/build.gradle)"
[ -n "$VERSION_CODE" ] && [ -n "$VERSION_NAME" ] || { echo "cannot read version from build.gradle"; exit 1; }

# Signing values come from the [balfour-founders-club] block of global.conf (3 lines either side of its key_alias).
near_alias() { awk -v want="$1" '
  /key_alias *= *"balfour-founders-club"/ { hit = NR } { lines[NR] = $0 }
  END { for (i = hit - 3; i <= hit + 3; i++) if (i > 0 && lines[i] ~ "^ *" want " *=") { v = lines[i]; sub(/^[^=]*= *"/, "", v); sub(/".*$/, "", v); print v; exit } }' "$CONF"; }
KEYSTORE_PASSWORD="$(near_alias keystore_password)"; export KEYSTORE_PASSWORD
KEY_PASSWORD="$(near_alias key_password)"; export KEY_PASSWORD
export KEY_ALIAS="balfour-founders-club"
[ -n "$KEYSTORE_PASSWORD" ] && [ -n "$KEY_PASSWORD" ] || { echo "keystore credentials not found in global.conf"; exit 1; }

npx cap sync android >/dev/null
( cd android && ./gradlew -q :app:bundleRelease -PversionCode="$VERSION_CODE" -PversionName="$VERSION_NAME" && ./gradlew -q :app:assembleRelease -PapkAbi=arm64-v8a -PversionCode="$VERSION_CODE" -PversionName="$VERSION_NAME" )
unset KEYSTORE_PASSWORD KEY_PASSWORD

APK=android/app/build/outputs/apk/release/app-release.apk
AAB=android/app/build/outputs/bundle/release/app-release.aab
"$BT/apksigner" verify --print-certs "$APK" | grep -E 'Signer #1 certificate (DN|SHA-256)'
"$BT/aapt2" dump badging "$APK" | sed -n 1p
echo "built version $VERSION_NAME (code $VERSION_CODE): APK + AAB from one gradle run, commit $(git rev-parse --short HEAD)"
[ "$MODE" = "build" ] && exit 0

if [ "$MODE" = "--publish" ]; then
  node ~/.local/bin/play-release.mjs "$PKG" "$AAB" "$TRACK" draft "$VERSION_NAME" "" \
    || { echo "Play upload FAILED: not publishing the APK to media (they must stay aligned)"; exit 1; }
fi

# ---- media.seq1.net: public versioned APK + index page (Obtainium source = HTML, newest *.apk link) ----
r2_env
VERSIONED="balfour-founders-club-$VERSION_NAME-$VERSION_CODE.apk"
TYPE="application/vnd.android.package-archive"
mkdir -p build
aws $R2 s3 cp "$APK" "s3://media/$PREFIX/$VERSIONED" --content-type "$TYPE" --only-show-errors
aws $R2 s3 cp "$APK" "s3://media/$PREFIX/balfour-founders-club-latest.apk" --content-type "$TYPE" --cache-control no-cache --only-show-errors
# Obtainium compares versionCode (the number in the filename), so refreshes stop flagging phantom updates.
OBT_LINK="$(node "$(dirname "$0")/obtainium-link.mjs" "$PKG" "Balfour Founders Club" "https://media.seq1.net/$PREFIX/index.html")"
cat > build/index.html <<HTML
<!doctype html><meta charset="utf-8"><title>Balfour Founders Club (Android)</title>
<p>Latest: <a href="$VERSIONED">$VERSIONED</a> (version $VERSION_NAME, code $VERSION_CODE)</p>
<p><a href="$OBT_LINK">Add to Obtainium with the correct update settings</a> (tap once on the phone; replaces any existing entry for this app)</p>
HTML
aws $R2 s3 cp build/index.html "s3://media/$PREFIX/index.html" --content-type "text/html; charset=utf-8" --cache-control no-cache --only-show-errors
unset AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
code=$(curl -s -o /dev/null -w '%{http_code}' "https://media.seq1.net/$PREFIX/$VERSIONED")
echo "media.seq1.net: $VERSIONED -> HTTP $code · index https://media.seq1.net/$PREFIX/index.html (Obtainium needs the explicit index.html; the bare folder 404s)"
[ "$code" = "200" ] || { echo "public APK not reachable"; exit 1; }
