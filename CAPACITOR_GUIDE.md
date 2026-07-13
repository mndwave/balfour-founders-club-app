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

## iOS build (Mac only)

Requires:
1. Mac with Xcode 15+
2. Apple Developer Program membership — **pending, see "What's blocked" below**
3. `pod install` inside `ios/App/` (first time, or after plugin changes)

```bash
npx cap sync
# On Mac — open in Xcode:
npx cap open ios
# → Signing & Capabilities: sign in with the BoldThings Apple ID, enable "Automatic"
#   signing, add the Push Notifications + Associated Domains capabilities (this wires
#   App.entitlements into the project — not done yet, needs Xcode's UI)
# → Archive → Distribute App → App Store Connect
```

## 🚧 What's blocked — needs Kyle's action (not code)

Everything below is genuinely external-account work, not something buildable from this
server. Balfour is in the same "code ready, accounts pending" state Randalls' iOS side is
still in — this isn't a gap introduced by this build, it mirrors existing precedent.

| Item | What's needed | Blocks |
|---|---|---|
| iOS signing | Confirm whether Balfour ships under the same Apple Developer Team (`RH845KUW68`, same as randalls-rewards-app) or needs its own account/App ID registration | Any iOS build at all |
| App Store Connect | New app record (bundle ID `gs.boldthin.balfour.foundersclub`) | TestFlight/App Store submission |
| Google Play Console | New app listing (package `gs.boldthin.balfour.foundersclub`) | Play Store submission (Obtainium sideload works today without this) |
| Firebase project | New project for FCM (Android push) — download `google-services.json` into `android/app/` | Android push notifications (app builds fine without it — see build.gradle's try/catch) |
| APNs push cert | Apple Developer → Certificates → APNs key (.p8), upload to Firebase | iOS push notifications |
| Apple Wallet | New Pass Type ID + `.p12` cert if a wallet-pass-style member card is wanted (Randalls itself hasn't finished this either — see its own guide) | Apple Wallet save |
| App icons / splash art | Balfour-branded icon set + splash screen assets (currently using Capacitor's stock placeholder icons) | Store submission / real device polish |

**No CI/Fastlane exists for iOS anywhere in this ecosystem (Randalls included)** — iOS release is
entirely manual, on a Mac, forever, until someone builds Fastlane lanes. Not a Balfour-specific gap.

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
- `src/app/globals.css` — `html.ios-native`/`html.android-native` safe-area body padding.
  Balfour has no hamburger-nav/qr-header/BackHeader equivalent needing per-component overrides
  the way Randalls does — if a future screen needs one, add it as a matched pair per the
  Platform Parity Mandate below.

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
