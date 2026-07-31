# Balfour Founders Club — Capacitor App Guide

Server URL mode: the app loads `https://balfour.boldthin.gs` live.
Web deploys update the app instantly. Only rebuild the APK/IPA when native plugins or
platform manifests change.

## Repo structure

```
balfour-founders-club-app/
├── capacitor.config.ts                    # App ID, server URL, plugin config
├── android/                               # Android Studio project (Capacitor-managed)
├── ios/                                   # Xcode project (Capacitor-managed; build on Mac)
├── www/index.html                         # Placeholder only — server URL mode ignores this
└── balfour-founders-club-release.keystore # Android signing key (keep this)
```

**Web app lives in `~/balfour-founders-club`** — that's where all feature code goes.

## App ID + domains

| Field | Value |
|---|---|
| App ID | `gs.boldthin.balfour.foundersclub` |
| Android package | `gs.boldthin.balfour.foundersclub` |
| iOS bundle ID | `gs.boldthin.balfour.foundersclub` |
| Server URL | `https://balfour.boldthin.gs` |
| Deep links | none configured yet — magic-link auth is `/login?token=` (same URL as web); would need an `applinks:` universal-link intent to open the app directly instead of the browser |

## Bumping the version (when native changes require a rebuild)

Edit `APP_VERSION` in `capacitor.config.ts` — format `MAJOR.MINOR.PATCH`.
Then run `npx cap sync` before building.

## Everyday workflow

```bash
# After adding/updating a plugin:
npm install @capacitor/some-plugin
npx cap sync          # copies plugin into android/ + ios/

# After updating capacitor.config.ts:
npx cap sync

# Open Android Studio (requires Android Studio installed):
npx cap open android

# Open Xcode (Mac only):
npx cap open ios
```

## Android build (release APK)

```bash
npx cap sync
cd android
./gradlew assembleRelease
# APK: android/app/build/outputs/apk/release/app-release.apk
```

Signing is configured in `app/build.gradle` using `balfour-founders-club-release.keystore`
(freshly generated 2026-07-13 — own signing key, not shared with randalls-rewards-app).

**Android SDK (server-side builds):** SDK is at `~/android-sdk/` — NOT `/usr/lib/android-sdk/`
(that only has platform-tools). Set `sdk.dir=/home/mndwave/android-sdk` in `android/local.properties`.
Build credentials (keystore password, key alias) are in `~/seq1-healer/global.conf` under `[balfour_founders_club_app]`.

## Publishing releases (Obtainium)

Obtainium tracks GitHub releases — a push to `main` does NOT update the app on device.
After building, always publish a release:

```bash
GH_TOKEN=<mndwave_pat> gh release create vX.Y.Z \
  android/app/build/outputs/apk/release/app-release.apk \
  --title "vX.Y.Z — description"
```

Bump `APP_VERSION` in `capacitor.config.ts` for every APK that goes to users (native change marker).

CI (`.github/workflows/build-apk.yml`) builds + publishes automatically on every push to `main` —
mirrors randalls-rewards-app's pipeline. Secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`) already set on the repo.

## iOS build — headless, no Xcode GUI (2026-07-30)

**Fully solved and reproducible.** Own Apple Developer team: **Balfour Winery LLP, Team ID
`2GRD8DS9U5`** (separate from Randalls Limited's `RH845KUW68` — same Apple ID `hello@boldthin.gs`
as account Admin, Kiran Shukla `kiran@balfourwinery.com` is Account Holder). App Store Connect
app record: `Balfour Founders Club`, App ID `6796329888`.

**📖 Full canonical build process, including the GUI-session escape hatch that makes codesign
work over SSH at all:** `~/seq1-intelligence/memory/randalls-rewards/ios-headless-signing-ci-keychain-fix.md`

Signing setup (one-time, already done):
- Distribution cert generated via `openssl` CSR → developer.apple.com → imported into a
  dedicated `ci-build.keychain-db` (NOT the login keychain) on the build Mac.
- App ID `gs.boldthin.balfour.foundersclub` registered with Push Notifications, Associated
  Domains, Wallet capabilities.
- "Balfour Founders Club App Store" distribution provisioning profile generated and installed
  to `~/Library/MobileDevice/Provisioning Profiles/`.
- `ios/App/exportOptions.plist` uses `signingStyle: manual` (not `automatic` +
  `-allowProvisioningUpdates`) — there's no Xcode-cached Apple ID session on this Mac and none
  is needed with manual signing.

**The one non-obvious rule:** any command touching a private key — `security import`,
`xcodebuild archive`, `xcodebuild -exportArchive` — fails with `errSecInternalComponent` /
"User interaction is not allowed" if run directly over `ssh macbook '...'`, no matter how the
keychain is unlocked or trust-flagged. Route those specific steps through a **Terminal.app
window** triggered via `osascript` instead (a real GUI-session process) — full pattern in the
linked doc. `xcrun altool --upload-app` is a pure network call and works fine over plain SSH.

```bash
npx cap sync ios   # fine over plain SSH
# archive + export → via the Terminal.app escape hatch (see linked doc)
# xcrun altool --upload-app -f App.ipa -t ios -u hello@boldthin.gs -p <app-specific-password>
#   → plain SSH is fine, but ALWAYS background it (nohup ... & disown) and poll a log file —
#   a client-side `timeout N ssh ...` killing your local connection does NOT kill the remote
#   altool process; it can keep running and land the build after your terminal reports dead.
#   Verify via App Store Connect → TestFlight → Builds, not local process state.
```

## Android 15+ status bar contrast scrim (2026-07-30 — solid black band behind status bar)

**Symptom:** a full-bleed page's background (a photo, in this case) genuinely extends behind
the status bar — `Capacitor.isNativePlatform()`, the `.android-native` class, and
`body`'s zeroed safe-area padding were all confirmed correct via an on-device debug badge — but
a solid **black** band still renders over that strip, fixed in place while content scrolls
underneath it (not behind the icons, *underneath the band*). Doesn't reproduce on
randalls-rewards-app's dashboard/nav-drawer on the same physical device, because those are flat
solid-colour backgrounds, not photos.

**Root cause:** `@capacitor/status-bar`'s `overlaysWebView`/`backgroundColor` config
(`capacitor.config.ts`) is documented as **"Not available on Android 15+"** — both this app and
randalls-rewards-app target SDK 36 (Android 15+), so that whole config block has been silently
ignored the entire time. `StatusBar.getInfo()` still reports the plugin's own remembered
defaults (`overlays=true, color=#000000`) regardless of what the OS is actually doing — it is
NOT reliable ground truth on Android 15+, don't trust it when debugging this class of issue.
Android 15 enforces edge-to-edge itself and, whenever it can't statically guarantee status-bar
icon legibility against the content underneath (a busy photo — exactly this), automatically
paints a translucent dark **contrast-protection scrim**. A flat solid-colour background doesn't
trigger it; a photo does.

**❌ First attempt — did NOT work:** a `values-v29/styles.xml` theme override —
```xml
<item name="android:enforceStatusBarContrast">false</item>
<item name="android:enforceNavigationBarContrast">false</item>
```
Compiles and installs fine, but the scrim was still visible after a real device test. These are
`Window` **instance** properties (API 29+) — `BridgeActivity` extends `AppCompatActivity`, and
AppCompat's theme-inflation path does not reliably forward every `android:`-namespaced Window
attribute declared in styles.xml the way plain Android would.

**❌ Second attempt — also did NOT visibly fix it:** `MainActivity.java` calling the `Window`
instance methods directly (the officially-documented path,
developer.android.com/design/ui/mobile/guides/foundations/system-bars) —
```java
getWindow().setStatusBarContrastEnforced(false);
getWindow().setNavigationBarContrastEnforced(false);
```
Confirmed installed (`v2026.07.30.1638`) and still showed the black band on a real device.
Kept both this and the styles.xml attribute — harmless, and the actual root cause below may
have been masking whether either of these genuinely helped.

**✅ The actual root cause — Chromium's Android WebView doesn't implement `env()` at all:**
CSS `env(safe-area-inset-*)` has never worked correctly in Android System WebView — it always
reports `0px` (long-standing unfixed Chromium bug,
issues.chromium.org/issues/40699457, confirmed via `@capacitor/core`'s own bundled
`node_modules/@capacitor/core/system-bars.md`). Every safe-area CSS rule in the web app
(`~/balfour-founders-club/src/app/globals.css`) had ALWAYS resolved to 0 on Android as a
result — the black band was never really about the status bar being opaque at all; it was that
none of the "push content down / extend background up" padding was ever actually being applied,
on either the scrim theory or not. It only ever appeared to work on iOS by coincidence (WKWebView
does implement `env()` correctly).

`@capacitor/core` 8.3+ ships a `SystemBars` plugin (bundled — no new dependency) that injects the
REAL inset values as `--safe-area-inset-*` CSS custom properties. Two-part fix:
1. **This repo** (`capacitor.config.ts`): `SystemBars: { insetsHandling: 'css' }` (defaults to
   `'css'` already in 8.4.1, made explicit here to match Capacitor's own docs).
2. **Web app** (`~/balfour-founders-club/src/app/globals.css`): every `env(safe-area-inset-*)`
   replaced with `var(--safe-area-inset-*, env(safe-area-inset-*, 0px))` via `--sat`/`--sab`
   custom properties — reads the injected value first, falls back to `env()` (still correct on
   iOS, safe no-op everywhere else).

**Not yet confirmed on a real device as of this writing** — three fix attempts have shipped in
sequence (contrast scrim theme attribute → contrast scrim Window API → this). If this one also
doesn't resolve it, the contrast-scrim theory may still be independently real and additive
(now that insets are correct, a residual scrim would be visible as a much thinner/lighter tint
rather than a full solid band) — don't assume it was a red herring without a fresh screenshot.

**🚨 Anti-pattern:** don't trust a theme-XML-only fix for anything in the
`android:enforce*Contrast` / edge-to-edge family without a real device test — verify with
`./gradlew assembleDebug` locally before pushing (catches AAPT2 resource-linking typos, e.g.
`statusBarContrastEnforced` — wrong word order — vs the real `enforceStatusBarContrast`), but
compiling clean does NOT mean the runtime behaviour is correct on Android 15+; only a device
screenshot does.

**Not yet ported to randalls-rewards-app** — it has never hit this because none of its
full-bleed screens use a photo background. If a future Randalls screen does, mirror this fix
there per the Platform Parity Mandate below (same MainActivity.java pattern,
`gs.boldthin.randalls.rewards` package).

### Continued — html/body baseline (also insufficient alone)

Also found (and kept — genuinely correct, just not sufficient alone): `<html>` never had its own
`background-color` at all in `~/balfour-founders-club/src/app/globals.css` — only `<body>` did.
Found via `randalls-rewards` web repo's own git history (`git log --all -i --grep="status.?bar\|
edge.?to.?edge\|notch"` — Kyle's exact ask, "look at the git history" — turned up commit
`a035413`, an UNSCOPED `html, body { background-color }` rule, deliberately not gated behind
`.ios-native`/`.android-native`, added specifically as a defensive baseline). Also ported the
other half of that same commit's sibling technique from `randalls-rewards/src/app/qr/page.tsx`:
it sets `body.style.backgroundColor` dynamically per-page, not just a static default.
**On-device result: also no visible change** — confirmed via debug badge showing
`bodyBg`/`htmlBg` both correctly cream, yet the black band was pixel-identical to before.

### Continued — android:windowBackground (also insufficient alone)

`android:background="@null"` in the theme does NOT set `android:windowBackground` — different
attributes. `windowBackground` was never set anywhere in this theme chain, falling through to
`Theme.AppCompat.DayNight`'s own dark-mode default. Set explicitly to `#fbf6ed` (cream), verified
locally, verified the correct APK version reached the device (`App.getInfo()` added to the debug
badge specifically to remove any ambiguity here — Kyle's ask, "put the version number in the
green text"). **Also no visible change.**

### ✅ The actual root cause — edge-to-edge was never explicitly engaged at all

Confirmed by reading `@capacitor/status-bar`'s ACTUAL Android Java source
(`node_modules/@capacitor/status-bar/android/.../StatusBar.java`), not just its docs:
`setOverlaysWebView(true)` calls the DEPRECATED `Window#setStatusBarColor()` and
`View#setSystemUiVisibility()` — both confirmed complete no-ops for apps targeting API 35+ per
Android's own SDK docs ("if the window belongs to an app targeting VANILLA_ICE_CREAM or above,
this attribute is ignored"). Every fix above controlled what gets PAINTED once edge-to-edge is
active — none of them could ever matter if edge-to-edge itself never actually engaged for this
Activity, which "automatic enforcement on API 35+" docs implied but this investigation never
actually confirmed true for a Capacitor `BridgeActivity`/AppCompat setup specifically.

**First attempt, `androidx.activity.EdgeToEdge.enable(this)` — DID engage real edge-to-edge
(confirmed: hero image genuinely reached the true top for the first time) but had a side effect:**
it made the native AppCompat ActionBar reappear — a real `android:id/action_bar` containing a
"Founders Club" title `TextView` and a mystery icon, which read from a screenshot alone as
"a broken white bar with garbled text." `getSupportActionBar().hide()` did NOT suppress it.

**How this was actually diagnosed and fixed — set up a local Android 15 emulator**
(`~/android-sdk` already had `platform-tools`/`build-tools`/`platforms` but not `emulator` or a
system image — installed via `sdkmanager --install "emulator"
"system-images;android-35;google_apis;x86_64"`, created an AVD targeting API 35 to match, booted
headless with KVM hardware acceleration via `sudo -u mndwave -g kvm` — the invoking user wasn't
in the `kvm` group and mid-session `usermod -aG kvm` doesn't refresh an already-running shell's
groups). This turned a "build → wait for Kyle to test → wait for a screenshot → repeat" loop
(each round ~15-20 min) into direct iteration (~30s per round: edit → `./gradlew assembleDebug`
→ `adb install -r` → `adb shell am start` → `adb exec-out screencap`).

`adb shell uiautomator dump` (NOT visible from a screenshot alone) confirmed the exact element:
`android:id/action_bar_container` containing a real, laid-out `android:id/action_bar`. A/B tested
by commenting `EdgeToEdge.enable()` out entirely and reinstalling — the fake title bar vanished
completely on the same emulator, confirming it as the trigger; some interaction between
`EdgeToEdge.enable()`'s window-feature negotiation and `AppCompatActivity`'s own theme-driven
ActionBar setup was making a supposedly-`NoActionBar` theme show one anyway.

**✅ Actual working fix — the lower-level call `EdgeToEdge.enable()` wraps internally, without
whatever triggers the ActionBar side effect:**
```java
import androidx.core.view.WindowCompat;
// ...
@Override
public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
    ...
}
```
Confirmed on the local emulator across both a photo-background page (home) and the original
dark-forest `/login` page — hero image and forest background both now genuinely extend to the
true top with status bar icons floating transparently over real content, no artifact of any
kind. Shipped as `v2026.07.30.1941`.

### Continued — system dark mode reproduced a real extra-dark band (2026-07-31)

Kyle confirmed real progress on-device ("looking nicer... but the bar at the top is still dark
grey") — a genuinely different symptom from the earlier solid black/white bands, and this time
reproducible **directly on the local emulator**, no device access needed: `adb shell cmd uimode
night yes` on an otherwise-clean build (the `WindowCompat.setDecorFitsSystemWindows` fix above,
confirmed working in light mode) introduced a real extra-dark band at the status bar specifically,
not present with the same build in light mode. `Theme.AppCompat.DayNight.NoActionBar` resolves to
its dark variant when the *system* is in dark mode — this changes the default/fallback status bar
appearance resolution independently of anything the app's own JS does.

**✅ Fix — stop tracking system dark mode entirely.** Balfour's own JS already handles all
per-route light/dark treatment explicitly (`NativeAppShell.tsx` calling `StatusBar.setStyle()` for
`/login`, `/wifi`, `/my-id`) — the native theme never needed `DayNight`'s system-tracking in the
first place, it was just inherited from the Capacitor template default with no thought given to
it. Changed the parent theme:
```xml
<style name="AppTheme.NoActionBar" parent="Theme.AppCompat.Light.NoActionBar">
```
Verified in BOTH system light and dark mode (cold start + warm nav, home hero + `/login`) — clean
in every combination, zero crashes (checked `adb logcat` for `FATAL EXCEPTION`). **Not** the same
combination that crashed randalls-rewards-app historically (`overlaysWebView` + Light theme +
custom `StatusBar` `backgroundColor`) — this app relies on none of those three. Shipped as
`v2026.07.31.0452` — confirm on Kyle's real device before treating this as fully closed.

**Lesson:** when a real device shows something a stock emulator profile doesn't, the fix isn't
always "get the exact same device" — check which *device settings* (dark mode, accessibility
options, display cutout mode) differ first. This one turned out to be reproducible on the exact
same emulator that had shown a clean result minutes earlier, just with one setting flipped.

**Anti-pattern for next time:** `EdgeToEdge.enable()` reads as the officially-recommended,
"just works" API in every piece of documentation — it is NOT a safe drop-in for an
`AppCompatActivity`-based `BridgeActivity` specifically. Prefer the lower-level
`WindowCompat.setDecorFitsSystemWindows()` call directly for any Capacitor Android project using
AppCompat, and verify via a real view-hierarchy dump (`uiautomator dump`), not just a screenshot
— this exact bug was genuinely unreadable from pixels alone (looked like "garbled text and a
broken image icon," not "the ActionBar you explicitly disabled is back").

### The actual root cause — SystemBars' native-padding fallback, not colour at all (2026-07-31)

Kyle reported the dark-mode theme fix helped ("looking nicer") but was explicit that the real ask
was still unmet: **"the content is not going behind the icons"** — regardless of bar colour. That
reframed the whole investigation: every fix up to this point (windowBackground, theme pinning,
WebView background colour) controlled what's *painted* in the gap above the WebView. None of them
questioned whether the WebView itself actually reached the top of the screen.

It didn't. `adb shell uiautomator dump` on a fresh cold start showed:
```
android.webkit.WebView [0,136][1080,2337]
```
on a 1080×2400 device — the WebView's own top edge started at y=136 (≈ status bar height), not
y=0. No image, no content, no icon-overlay was ever possible in that strip — it was never part of
the WebView, native or web.

Read `@capacitor/core`'s actual `SystemBars.java` source
(`node_modules/@capacitor/android/capacitor/src/main/java/com/getcapacitor/plugin/SystemBars.java`)
rather than trusting the config docs. `insetsHandling: "css"` does inject `--safe-area-inset-*` CSS
vars, but it has a **second, undocumented behaviour**: unless
`getWebViewMajorVersion() >= 140 && hasViewportCover` (`hasViewportCover` = the page's
`<meta name="viewport">` tag already contains `viewport-fit=cover` **at the plugin's own
`onDOMReady` check**), it falls through to a native-padding fallback —
`v.setPadding(systemBarsInsets.top, ...)` on `getBridge().getWebView().getParent()` — which
physically pads the WebView's container by the status bar height. That's the "bar": empty native
window background showing through a gap the WebView was never drawn into.

`NativeAppShell.tsx` (the web repo) *did* patch `viewport-fit=cover` onto the meta tag — but only
inside `if (Capacitor.getPlatform() === "ios")`. Android fell through to the fallback on every
single load, unconditionally, independent of theme/colour — explaining exactly why the colour fix
"helped" (fixed a real, separate bug) without fixing the actual full-bleed ask.

**✅ Fix — set it server-side, for both platforms, matching randalls-rewards exactly.**
`randalls-rewards/src/app/layout.tsx` already does this correctly:
```ts
export const viewport: Viewport = { viewportFit: 'cover' }
```
Next.js requires this live in the dedicated `viewport` export, not inside `metadata` — silently
ignored otherwise. Ported to `balfour-founders-club/src/app/layout.tsx` verbatim. This makes the
meta tag correct in the **first** HTML response, before any client JS runs, for both iOS and
Android — no race against `SystemBars`' own `onDOMReady` read. Removed the now-redundant,
Android-blind client-side patch from `NativeAppShell.tsx`.

**Web-only change** — server URL mode means this went live via a Vercel deploy alone, no APK
rebuild. Verified via `adb shell uiautomator dump` post-deploy: WebView bounds now `[0,0][1080,2400]`
— full screen. Verified visually on both the light home hero (cream background, photo genuinely
behind the status bar, icons overlaying transparently) and the dark `/login` route (forest-green
full-bleed, white icons per `NativeAppShell.tsx`'s per-route `StatusBar.setStyle()`).

**Lesson:** "colour is now right but content still isn't behind the icons" was the tell that this
was never a theme/painting problem — it was a layout problem one level down, in whether the
WebView itself was ever laid out into that region at all. Read the actual plugin source, not just
its config surface, once documented behaviour stops explaining what's on screen.

### The flip side — full-bleed now means bottom-pinned content needs real protection too (2026-07-31)

Kyle, immediately after confirming the full-bleed fix: "now we've got buttons and content going
up behind the nav bar." Expected, in hindsight — making the WebView genuinely edge-to-edge doesn't
just fix backgrounds, it also removes the native padding that used to accidentally protect any
element pinned to the true bottom of the viewport. Anything relying on that padding rather than
its own explicit safe-area handling was always going to be exposed the moment full-bleed actually
started working.

Also discovered while investigating: **this emulator's bundled Android System WebView is version
133** — below `@capacitor/core`'s own `WEBVIEW_VERSION_WITH_SAFE_AREA_FIX` threshold of **140**
(`SystemBars.java`). Below that version, `shouldPassthroughInsets` can never be true regardless of
the viewport meta tag, so the plugin permanently uses its native-padding fallback on this
particular emulator — not directly reproducible here as a *result*, but exactly why hunting for
the real bug meant reading the app's own CSS/component code for missing safe-area coverage rather
than trusting only visual emulator confirmation. Real devices auto-update WebView via Play Store
far more aggressively, so this is very unlikely to be Kyle's actual device's ceiling — but it's
why this specific round of fixes shipped on code-audit confidence rather than an emulator
screenshot, unlike every fix before it in this file.

Found via `grep -rn "env(safe-area-inset" src` — the codebase-wide convention (established earlier
in this file) is to always go through `--sab`/`--sat`, never a bare `env()` call, because Chromium
Android WebView always reports `env(safe-area-inset-*)` as 0px. Two violations of that convention:

1. **`VenueBookingWidget.tsx`** — the booking bottom-sheet's scrollable content used
   `pb-[calc(1.5rem+env(safe-area-inset-bottom))]` directly. The real "Confirm Booking" button
   lives inside that padding. Fixed to `var(--sab, env(safe-area-inset-bottom, 0px))`.
2. **`DashboardSidebar.tsx`** — the "Need support" link uses `mt-auto` inside an `h-svh` drawer
   with no bottom safe-area padding at all (never needed it before, because native padding always
   covered the gap up to now). Added `paddingBottom: calc(0.75rem + var(--sab, 0px))`.

`grep -rln "mt-auto" src` turned up two more matches (`VenueCard.tsx`, `FeaturedBenefit.tsx`,
`DashboardBenefitCard.tsx`) but all three push a button to the bottom of a *card*, not a
viewport-height container — confirmed via `grep -n "h-screen\|h-svh\|h-dvh"` on each, none matched,
so they're not a safe-area concern.

**Lesson:** every time full-bleed / edge-to-edge gets fixed at one layer, re-audit the OTHER edge
of the screen for anything that was quietly relying on the padding that just got removed. Grep for
the anti-pattern (`env(safe-area-inset`) and the structural pattern (`mt-auto`/`fixed bottom-0`
inside a viewport-height container) rather than waiting for it to be reported page-by-page.

## Remaining, genuinely external

| Item | What's needed | Blocks |
|---|---|---|
| Google Play Console | New app listing (package `gs.boldthin.balfour.foundersclub`) | Play Store submission (Obtainium sideload works today without this) |
| APNs push cert | Apple Developer → Certificates → APNs key (.p8) for Balfour's own team, upload to Firebase | iOS push **notifications** (app itself builds and ships fine without it) |

**No CI/Fastlane exists for iOS anywhere in this ecosystem (Randalls included)** — iOS release is
driven by SSH + the Terminal.app escape hatch above, on this build Mac, until someone builds
Fastlane lanes. Not a Balfour-specific gap.

## Native plugins installed (parity with randalls-rewards-app)

| Plugin | Purpose | Wired into a real UI feature? |
|---|---|---|
| `@capacitor/app` | Deep link handling + Android back button | ✅ `NativeAppShell.tsx` |
| `@capacitor/haptics` | Tactile feedback | ✅ light haptic on `/my-id` QR presentation |
| `@capacitor-community/keep-awake` | Screen stays on while QR is shown | ✅ `/my-id` |
| `@capacitor/status-bar` | Per-route icon contrast (dark `/my-id` vs light everything else) | ✅ `NativeAppShell.tsx` |
| `@capacitor/push-notifications` | FCM/APNs registration | ✅ backend endpoint already tenant-generic (`loyalty_portal.rs::register_push_token`) — code wired, blocked only on Firebase/APNs cert setup above |
| `@capacitor-mlkit/barcode-scanning` | QR/barcode scan | ⏳ installed, not called — Balfour's QR flow is display-only (member shows their code, staff scan it), same as Randalls' `/qr` |
| `@aparajita/capacitor-biometric-auth` | Face ID / fingerprint | ⏳ installed, not called — Balfour has no biometric-gated feature yet (no Vault/admin-impersonation equivalent) |
| `@capacitor-community/in-app-review` | Store review prompt | ⏳ installed, not called — needs a "genuine positive moment" trigger point (e.g. benefit redeemed) to be identified first |
| `@capacitor/preferences` | Offline key-value storage | ⏳ installed, backs `maybePromptReview()`'s cooldown state once that's wired |
| `@capacitor/network`, `@capacitor/keyboard`, `@capacitor/local-notifications`, `@capacitor/splash-screen` | Config-level only, no direct JS import needed | ✅ configured in `capacitor.config.ts` |

## Web app integration (`~/balfour-founders-club`)

- `src/lib/native.ts` — full port of randalls-rewards' equivalent (haptics, biometric,
  push, QR scan, keep-awake, review prompt) — every export is a safe no-op off-native.
- `src/components/NativeAppShell.tsx` — deep links, back button, per-route status bar,
  push registration, bfcache reload guard. Deliberately does NOT port Randalls' web-usage-ping /
  app-open-ping (feeds an analytics dashboard that doesn't exist for Balfour).
- `src/app/globals.css` — `html.ios-native`/`html.android-native` safe-area rules: base body
  padding, plus per-component overrides (`.native-full-bleed`, `.app-top-bar`,
  `.sidebar-nav-trigger`/`.sidebar-nav-drawer`, `.myid-header-safe-top`) ported from
  randalls-rewards' BackHeader/qr-header/mobile-nav-* technique 2026-07-30 — the original claim
  here that Balfour didn't need these (App Store submission review) was wrong; add new ones as
  matched `ios-native`/`android-native` pairs per the Platform Parity Mandate below.
- `src/lib/use-native-full-bleed.ts` — zeros body's safe-area padding-top while a full-bleed
  page (`.native-full-bleed`) is mounted, so that page's own background carries the inset
  instead of leaving a gap of body's plain colour above it.

## 🚨 PLATFORM PARITY MANDATE — ZERO TOLERANCE

**Any native capability added to one platform MUST be mirrored on the other in the same commit.**

This includes: plugins, permissions, deep link schemes, push notification config, wallet
integrations, biometrics, location, sharing — anything native. Capacitor's cross-platform
shell builds both iOS and Android simultaneously, so plugin installation is automatic. What
requires deliberate mirroring:

- **Permissions**: AndroidManifest.xml ↔ Info.plist
- **Push / wallet / auth credentials**: separate per-platform setup (FCM vs APNs, Google Wallet vs Apple Wallet)
- **Platform-specific CSS/JS**: `html.ios-native` ↔ `html.android-native` rules must always be added as pairs

## Google Play setup (when ready to submit)

1. Create app at play.google.com/console
2. App ID: `gs.boldthin.balfour.foundersclub`
3. Upload signed AAB from `android/app/build/outputs/bundle/release/` (CI already produces this)
4. Add `google-services.json` from Firebase console (for push notifications) to `android/app/`

## Push notifications setup

**Android (FCM):**
1. Create Firebase project at console.firebase.google.com
2. Add Android app with package `gs.boldthin.balfour.foundersclub`
3. Download `google-services.json` → place in `android/app/google-services.json` locally, and
   base64-encode it into the `GOOGLE_SERVICES_JSON` GitHub secret for CI
4. `npx cap sync`
5. Server endpoint already live: `POST /balfour/loyalty/portal/push/register` receives `{ token, platform: 'android' }`

**iOS (APNs):**
1. Apple Developer → Certificates → APNs key (.p8)
2. Upload to Firebase console
3. Same server endpoint receives `{ token, platform: 'ios' }`
