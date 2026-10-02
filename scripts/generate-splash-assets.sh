#!/usr/bin/env bash
# BALFOUR-LAUNCH-SPLASH-2026-10-02: regenerates every native launch-screen asset
# from assets/splash-logo.svg (the same traced vector the web splash inlines, from
# balfour-founders-club/src/assets/brand/balfour-logo-traced.svg).
#
# The native launch screen must match the web splash's first frame exactly:
# cream #fbf6ed, forest logo 156pt/dp wide, centred on the full screen. The web
# side reads the same width from SPLASH_LOGO_WIDTH in
# balfour-founders-club/src/lib/launch-splash.ts. Change both together.
#
# Needs inkscape and ImageMagick (convert). Run from anywhere.
set -euo pipefail
cd "$(dirname "$0")/.."

SVG=assets/splash-logo.svg
CREAM="#fbf6ed"
LOGO_DP=156          # logo width in dp / pt
ICON_DP=288          # Android 12+ splash icon canvas (no icon background); visible circle is 192dp
RES=android/app/src/main/res
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

render() { # width_px out.png  (transparent background)
  inkscape "$SVG" --export-type=png --export-filename="$2" --export-width="$1" --export-background-opacity=0 >/dev/null 2>&1
}

# Android 12+ (and core-splashscreen compat below 12): windowSplashScreenAnimatedIcon.
for pair in mdpi:1 hdpi:1.5 xhdpi:2 xxhdpi:3 xxxhdpi:4; do
  d=${pair%%:*}; s=${pair##*:}
  canvas=$(awk "BEGIN{printf \"%d\", $ICON_DP*$s}")
  logo=$(awk "BEGIN{printf \"%d\", $LOGO_DP*$s}")
  mkdir -p "$RES/drawable-$d"
  render "$logo" "$TMP/l.png"
  convert -size "${canvas}x${canvas}" xc:none "$TMP/l.png" -gravity center -composite "$RES/drawable-$d/splash_logo.png"
done

# Legacy full-screen splash.png (only used if the Android 12 API path ever fails):
# cream with the logo centred at the same dp size, so even the fallback matches.
for f in "$RES"/drawable*/splash.png; do
  dir=$(basename "$(dirname "$f")")
  case "$dir" in
    *xxxhdpi) s=4 ;; *xxhdpi) s=3 ;; *xhdpi) s=2 ;; *hdpi) s=1.5 ;; *) s=1 ;;
  esac
  size=$(identify -format %wx%h "$f")
  logo=$(awk "BEGIN{printf \"%d\", $LOGO_DP*$s}")
  render "$logo" "$TMP/l.png"
  convert -size "$size" "xc:$CREAM" "$TMP/l.png" -gravity center -composite -alpha off "$f"
done

# iOS: SplashLogo imageset drawn by LaunchScreen.storyboard at 156pt, centred.
SET=ios/App/App/Assets.xcassets/SplashLogo.imageset
mkdir -p "$SET"
for s in 1 2 3; do render $((LOGO_DP * s)) "$SET/splash-logo@${s}x.png"; done
cat > "$SET/Contents.json" <<'JSON'
{
  "images" : [
    { "idiom" : "universal", "filename" : "splash-logo@1x.png", "scale" : "1x" },
    { "idiom" : "universal", "filename" : "splash-logo@2x.png", "scale" : "2x" },
    { "idiom" : "universal", "filename" : "splash-logo@3x.png", "scale" : "3x" }
  ],
  "info" : { "version" : 1, "author" : "xcode" }
}
JSON
echo "Splash assets regenerated (logo ${LOGO_DP}dp on ${CREAM})."
