#!/bin/bash
# IOS-ICON-BUNDLE-GATE-2026-11-23 (healer:fe4a8dec, follow-up of healer:8937cd41): Apple's ITMS-90022/90023 (no 120 px iPhone / 152 px iPad icon)
# arrived by EMAIL after Villiers build 977126 was uploaded. Balfour is iPhone-only (TARGETED_DEVICE_FAMILY 1): the gate still proves the 120 px
# iPhone icon, Assets.car and CFBundleIconName are in the EXPORTED bundle, before anyone uploads it.
#   scripts/ios-icon-gate.sh APP.app        e.g. $CHECK_DIR/Payload/App.app from build-ios.sh / build-ios-cloudsign.sh (Mac build host)
# The ONE shared check is the kit's scripts/ios-icon-bundle-check.sh, read from the kit clone (SEQ1_KIT, default ~/seq1-native-kit or ../seq1-native-kit)
# at origin/main: a build tool, not app code. It reads XML plists, so a binary Info.plist in a COPY of the bundle is converted first (plutil, Mac).
# Exit 0 pass, 1 refuse (do NOT upload), 2 usage.
set -uo pipefail
app="${1:-}"; [ -d "$app" ] && [ -f "$app/Info.plist" ] || { echo "usage: $0 APP.app (a bundle with an Info.plist)" >&2; exit 2; }
here="$(cd "$(dirname "$0")/.." && pwd)"
kit="${SEQ1_KIT:-}"; [ -n "$kit" ] || for k in "$HOME/seq1-native-kit" "$here/../seq1-native-kit"; do [ -d "$k/.git" ] && { kit="$k"; break; }; done
gate="$(mktemp)"; copy="$(mktemp -d)"; trap 'rm -rf "$gate" "$copy"' EXIT
git -C "${kit:-/nonexistent}" show origin/main:scripts/ios-icon-bundle-check.sh > "$gate" 2>/dev/null
[ -s "$gate" ] || { echo "ios-icon-gate: FAIL ios-icon-bundle-check.sh not found in the kit clone (${kit:-none}; set SEQ1_KIT, git -C <kit> fetch origin). Do NOT upload this build."; exit 1; }
cp -R "$app" "$copy/" && b="$copy/$(basename "$app")"
if head -c 6 "$b/Info.plist" | grep -q bplist; then
  command -v plutil >/dev/null && plutil -convert xml1 "$b/Info.plist" || { echo "ios-icon-gate: FAIL Info.plist is binary and plutil is missing"; exit 1; }
fi
bash "$gate" "$b" || { echo "ios-icon-gate: FAIL icon bundle: App Store Connect would refuse this build (ITMS-90022/90023). Do NOT upload this build."; exit 1; }
