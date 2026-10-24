# balfour-founders-club-app — agent instructions

Capacitor shell for Balfour Founders Club (`https://founders.balfourwinery.com`, web app in `~/balfour-founders-club`).
Every screen the WebView shows is the React site, identical by construction. The elements below are drawn natively and so
can drift. Setup, versions and store details: `CAPACITOR_GUIDE.md`. The native card and shell are ports of
`~/randalls-rewards-app` (same `gs.boldthin.loyalty` packages, same `ios/LoyaltyCard` core); fix both when a fix is shared.

## 🎯 NATIVE ELEMENTS MATCH THE REACT REFERENCE PIXEL FOR PIXEL — ZERO TOLERANCE (healer:4b3d7c53, 2026-10-23)

Kyle 2026-10-23 (ONE-UI-COMPOSE-2026-10-23, `~/seq1-intelligence/memory/seq1/kyle-decision-one-ui-compose-everywhere-2026-10-23.md`):
the native elements "cannot differ in the slightest" from the React. **React is the reference: a divergence moves the native
element, never the page.**

### Inventory: every natively drawn element and its React reference

| Native element | Android (`android/app/src/main/java/gs/boldthin/`) | iOS (`ios/`) | React reference (`~/balfour-founders-club`) | Render gate |
|---|---|---|---|---|
| Member card (FOUNDER label, QR, number, staff line, wallet button) | `loyalty/card/BalfourMemberCard.kt`, `QrMatrix.kt`, `balfour/foundersclub/BalfourCard.kt` | `LoyaltyCard/.../BalfourMemberCard.swift`, `QrCode.swift`, `CardType.swift` | `src/app/my-id/page.tsx` + `src/components/MyIdCardContent.tsx` (`/my-id`) | ✅ android + ios `card` |
| Card overlay (hosts the card over the WebView, keep-awake, brightness) | `loyalty/card/MemberCardOverlay.kt` | `LoyaltyCard/.../MemberCardOverlay.swift` | `src/components/MyIdOverlay.tsx` (draws nothing beyond the card) | via `card` |
| History screen (balance banner, filter pills, rows, receipt prompt) | `loyalty/screens/BalfourHistory.kt` (+ `BalfourHistoryModel.kt`, `BalfourRoutes.kt`, `balfour/foundersclub/BalfourScreens.kt`) | — (WebView) | `src/app/(app)/history/page.tsx` (`/history`) | ✅ android `history` |
| OS launch screen (cream #fbf6ed, logo) | `res/values/styles.xml` `Theme.SplashScreen` + `drawable*/splash_logo` (`scripts/generate-splash-assets.sh`) | `App/App/Base.lproj/LaunchScreen.storyboard` | first frame of `src/components/LaunchSplash.tsx` | ❌ not yet (OS-drawn: device/emulator only) |
| Shell (screen router, back priority, WebView background) | `loyalty/shell/*.kt`, `balfour/foundersclub/BalfourShell.kt` | — | — draws only the brand background colour | n/a |

A new native element is not done until it has a row here and a row in `scripts/render-gate/render-gate.sh`.

### The render gate: `scripts/render-gate/render-gate.sh android|ios|all`

Same gate and scripts as randalls-rewards-app (its AGENTS.md has the full description; keep the two `scripts/render-gate/`
copies identical apart from `render-gate.sh`'s site, token key and element table):
- **Reference:** `reference.mjs`, the live page as the app's WebView paints it (`<html>` `android-native`/`ios-native`,
  platform user agent, no insets, Europe/London) at 412x915 css @3 / 402x874 @3, API calls answered from `fixtures/*.har`.
  Android: Chromium on the box with only Android's system fonts. iOS: WebKit on the Mac (Apple's fonts).
- **Native:** Android `RenderGateDeviceTest` (androidTest) on the shared emulator via `android-native.sh` (lane lock;
  exit 75 = busy / no emulator: retry); the history screen calls its real `PortalClient` against `HarServer` on 127.0.0.1
  (cleartext for 127.0.0.1 in debug builds only). iOS `ios/LoyaltyCard` `RenderGateTests` on a fresh iPhone 17 / iOS 27
  simulator via `ios-native.sh`. Robolectric (`BalfourCardRenderTest`, `RenderGateScreensTest`) is a quick geometry check
  only: it skips variable-font axes.
- **iOS native render (found by running it, 2026-10-23):** a package test has no window scene, so `drawHierarchy`
  draws nothing; `RenderGateTests` draws through `ImageRenderer.render` into its own 8-bit sRGB bitmap and fails a
  single-colour render. ImageRenderer cannot draw a `ScrollView`, so the card's scroller is `CardScroll`, laid out
  statically (same geometry) only when the test sets `\.cardStaticRender`. Never put an explicit `.top` alignment
  guide inside a card element: frames forward it and the parent ZStack aligns by it.
- **Match WebKit, do not approximate it:** QR modules follow WebKit's `crispEdges` (a dark run from the floor of its
  edge to the ceiling, fitted on every module edge of the reference). Rasters the page paints from an SVG or rescales
  (wallet badge; Balfour logo) ship as WebKit's own paint at the exact device-pixel box and are drawn 1:1.
- **Diff:** `diff.mjs`, full colour, per pixel, channel delta > 16/255, fail above 0.2% of pixels, diff PNG on failure.
  No baselines, no recorded gaps. Never loosen it to pass a real divergence; a harness-caused failure is fixed in the harness.
- **Fixtures:** recorded from a synthetic member (`create_test_loyalty_session` with `tenant=balfour`; PATCH
  `/balfour/loyalty/portal/me` with a name and `date_of_birth`, POST `/balfour/loyalty/portal/welcome/seen`, else the
  page lands on /join/welcome; `cleanup_test_loyalty_session` after). Record with `reference.mjs --record --token <t>
  --token-key balfour_member_token`. `history.har` carries two hand-added cash rows at The Cellar Door.
- **Release:** `scripts/release-android.sh` runs the Android gate before the build; `build-ios.sh` and
  `build-ios-cloudsign.sh` (Mac) refuse a commit without an iOS pass stamped in the last 24 h. The Android build needs
  `node_modules` (`npm-install-safe ci`) and `npx cap sync android` first, as the release script does.

### Single-sourcing assessment (healer:4b3d7c53)

- **Member card + overlay:** stays native: instant and offline is the point (Kyle 2026-10-19, card first). A WebView-drawn
  card from a bundled page would wait on a cold WebView. The drift risk is the two native copies; the one-source route is
  Compose Multiplatform for iOS (ONE-UI-COMPOSE-2026-10-23), one Compose card for both platforms, gated against React.
  Until then both copies are gated.
- **History screen:** a page that still changes; under the 2026-10-19 rule it is a candidate to return to the WebView.
  It stays native only while the gate passes on every release; a web change the gate flags is ported the same day or the
  native screen is switched off.
- **OS launch screen:** must be native (drawn by the OS before code runs); generated from the same logo asset as the web
  splash, single-sourced at the asset level; frame check is device-only.
