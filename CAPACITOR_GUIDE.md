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
