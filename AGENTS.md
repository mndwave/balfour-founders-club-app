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
| Member card (FOUNDER label, QR, number, staff line, wallet button) | **the React card itself**: `loyalty/card/CardPage.kt` (a WebView) showing `assets/card/card.html`, the bundled offline copy of the My ID overlay built by `scripts/card-page/build-card-page.mjs` | `LoyaltyCard/.../BalfourMemberCard.swift`, `QrCode.swift`, `CardType.swift` | `src/app/my-id/page.tsx` + `src/components/MyIdCardContent.tsx` (`/my-id`) | ✅ android + ios `card` |
| Card overlay (hosts the card over the WebView, keep-awake, brightness) | `loyalty/card/MemberCardOverlay.kt` | `LoyaltyCard/.../MemberCardOverlay.swift` | `src/components/MyIdOverlay.tsx` (draws nothing beyond the card) | via `card` |
| History screen (balance banner, filter pills, rows, receipt prompt, Powered By credit) | — (the live page in the app WebView) | — (WebView) | `src/app/(app)/history/page.tsx` (`/history`) | n/a (is the page) |
| OS launch screen (cream #fbf6ed, logo) | `res/values/styles.xml` `Theme.SplashScreen` + `drawable*/splash_logo` (`scripts/generate-splash-assets.sh`) | `App/App/Base.lproj/LaunchScreen.storyboard` | first frame of `src/components/LaunchSplash.tsx` | ❌ not yet (OS-drawn: device/emulator only) |
| Shell (screen router, back priority, WebView background) | `loyalty/shell/*.kt`, `balfour/foundersclub/BalfourShell.kt` | — | — draws only the brand background colour | n/a |

A new native element is not done until it has a row here and a row in `scripts/render-gate/render-gate.sh`.

### Android: the card is the React card, from a bundled offline copy (CARD-PAGE-WEBVIEW-2026-10-24, Kyle, gate 339937c7)

Kyle 2026-10-24: on Android, history is the live page and the card is drawn by a WebView from a bundled offline copy of
the React card; iOS stays native (a native Android copy could not match Chromium's glyph rasterization). Same mechanism as
randalls-rewards-app (its AGENTS.md has the full description; keep the two `scripts/card-page/` copies identical apart from
`config.json` and `fixture.json`). Balfour specifics: the card is an overlay over `/dashboard` opened from the My ID link, so
the copy keeps only the opaque full-screen overlay (`keepOnly`), never the dashboard beneath; the QR is a fixed 288 px
(`fit: null`); the page has no offline line, so the copy has none either (the old native card added one, which the page
never had). Releases rebuild the copy from the deployed site before the gate (`release-android.sh`).


### The render gate: `scripts/render-gate/render-gate.sh android|ios|all`

Same gate and scripts as randalls-rewards-app (its AGENTS.md has the full description; keep the two `scripts/render-gate/`
copies identical apart from `render-gate.sh`'s site, token key and element table):
- **Reference:** `reference.mjs`, the live page as the app's WebView paints it (`<html>` `android-native`/`ios-native`,
  platform user agent, no insets, Europe/London) at 412x915 css @3 / 402x874 @3, API calls answered from `fixtures/*.har`.
  Android: the emulator's own WebView (`WebReference`, same run as the native capture; the AVD is set to 412x915 dp @3 and
  Europe/London for the run). iOS: WebKit on the Mac (Apple's fonts).
- **Native:** Android `RenderGateDeviceTest` (androidTest) on the shared emulator via `android-native.sh` (lane lock;
  exit 75 = busy / no emulator: retry) loads the production `CardPage` with `assets/card/card.html`. iOS `ios/LoyaltyCard`
  `RenderGateTests` on a fresh iPhone 17 / iOS 27 simulator via `ios-native.sh`.
- **iOS native render (found by running it, 2026-10-23):** a package test has no window scene, so `drawHierarchy`
  draws nothing; `RenderGateTests` draws through `ImageRenderer.render` into its own 8-bit sRGB bitmap and fails a
  single-colour render. ImageRenderer cannot draw a `ScrollView`, so the card's scroller is `CardScroll`, laid out
  statically (same geometry) only when the test sets `\.cardStaticRender`. Never put an explicit `.top` alignment
  guide inside a card element: frames forward it and the parent ZStack aligns by it.
- **Match WebKit, do not approximate it:** QR modules follow WebKit's `crispEdges` (a dark run from the floor of its
  edge to the ceiling, fitted on every module edge of the reference). Rasters the page paints from an SVG or rescales
  (wallet badge; Balfour logo; Randalls crest) ship as WebKit's own paint at the exact device-pixel box and are drawn
  1:1: capture one with `reference.mjs ... --engine webkit --raster <selector> --raster-out <png>` on the Mac.
- **Centred text is drawn as WebKit draws it** (`CssText(..., centred: true)` -> `CssCentredLine`): a SwiftUI `Text`
  draws from a pixel-snapped origin, WebKit from the float one, and both snap each glyph to a whole device pixel, so a
  fraction apart moves glyphs a pixel. Start = (width - line width rounded up to 1/64) / 2, rounded down to 1/64;
  baseline = half-leading baseline rounded to a device pixel, with WebKit's own ui-monospace metrics (`WebKitLineMetrics`);
  no subpixel glyph positioning. SwiftUI layout is float and matches WebKit's boxes (traced), but a Canvas layer is
  snapped to the nearest device pixel, so the drawing undoes that snap. `reference.mjs --eval <js>` prints page layout values for fitting a rule.
- **Diff:** `diff.mjs`, full colour, per pixel, channel delta > 16/255, fail above 0.2% of pixels, diff PNG on failure.
  No baselines, no recorded gaps. Never loosen it to pass a real divergence; a harness-caused failure is fixed in the harness.
- **Fixtures:** recorded from a synthetic member (`create_test_loyalty_session` with `tenant=balfour`; PATCH
  `/balfour/loyalty/portal/me` with a name and `date_of_birth`, POST `/balfour/loyalty/portal/welcome/seen`, else the
  page lands on /join/welcome; `cleanup_test_loyalty_session` after). Record with `reference.mjs --record --token <t>
  --token-key balfour_member_token`.
- **Release:** `scripts/release-android.sh` rebuilds the card copy and runs the Android gate before the build; `build-ios.sh` and
  `build-ios-cloudsign.sh` (Mac) refuse a commit without an iOS pass stamped in the last 24 h. The Android build needs
  `node_modules` (`npm-install-safe ci`) and `npx cap sync android` first, as the release script does.

### Single-sourcing assessment (healer:4b3d7c53)

- **Member card + overlay:** Android: the React card from the bundled copy, single-sourced with the page by construction.
  iOS: native SwiftUI, gated against WebKit (passes at 0.007%).
- **History screen:** the live page on both platforms.
- **OS launch screen:** must be native (drawn by the OS before code runs); generated from the same logo asset as the web
  splash, single-sourced at the asset level; frame check is device-only.
