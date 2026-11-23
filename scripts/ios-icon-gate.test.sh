#!/bin/bash
# IOS-ICON-BUNDLE-GATE-2026-11-23 (healer:fe4a8dec, follow-up of healer:8937cd41): scripts/ios-icon-gate.sh on synthetic exported bundles
# (iPhone-only, as Balfour ships: TARGETED_DEVICE_FAMILY 1). No Mac, no signing. Needs the kit clone at ~/seq1-native-kit (or SEQ1_KIT).
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"; S="$(mktemp -d)"; trap 'rm -rf "$S"' EXIT; fail=0
png() { perl -e 'binmode STDOUT; print "\x89PNG\r\n\x1a\n", pack("N",13), "IHDR", pack("NN",$ARGV[0],$ARGV[0]), "\x08\x06\x00\x00\x00", "\0\0\0\0"' "$1" > "$2"; }
mk() { local a="$S/$1/App.app"; mkdir -p "$a"; echo car > "$a/Assets.car"; png 120 "$a/AppIcon60x60@2x.png"
  cat > "$a/Info.plist" <<P
<?xml version="1.0" encoding="UTF-8"?>
<plist version="1.0"><dict><key>UIDeviceFamily</key><array><integer>1</integer></array>
<key>CFBundleIcons</key><dict><key>CFBundlePrimaryIcon</key><dict><key>CFBundleIconFiles</key><array><string>AppIcon60x60</string></array><key>CFBundleIconName</key><string>AppIcon</string></dict></dict></dict></plist>
P
}
expect() { local n=$1 want=$2 re=$3 d=$4 out rc; out="$("$ROOT/scripts/ios-icon-gate.sh" "$S/$d/App.app" 2>&1)"; rc=$?
  if [ "$rc" = "$want" ] && grep -qE "$re" <<<"$out"; then echo "PASS $n"; else echo "FAIL $n (rc=$rc want $want /$re/): $out"; fail=1; fi; }
mk good;  expect "complete iPhone-only bundle passes" 0 "icon-gate: PASS" good
mk no120; rm "$S/no120/App.app/AppIcon60x60@2x.png"; expect "a bundle without the 120 px icon is refused, ITMS-90022" 1 "ITMS-90022" no120
mk nocar; rm "$S/nocar/App.app/Assets.car"; expect "a bundle without Assets.car is refused" 1 "Assets.car" nocar
SEQ1_KIT=/nonexistent expect "no kit clone refuses the build, never skips the gate" 1 "not found in the kit clone" good
exit $fail
