import { CapacitorConfig } from '@capacitor/cli';

// Bump this when making a native change that requires an APK/IPA rebuild.
// Format: MAJOR.MINOR.PATCH — Obtainium uses this to detect updates.
export const APP_VERSION = '1.1.7';

const config: CapacitorConfig = {
  appId: 'gs.boldthin.balfour.foundersclub',
  appName: 'Founders Club',
  webDir: 'www',
  server: {
    // Server URL mode: loads the live web app rather than bundled assets.
    // This means web deploys update the app instantly — no APK/IPA rebuild needed.
    // Only rebuild when native plugins or AndroidManifest/Info.plist change.
    url: 'https://balfour.boldthin.gs',
    cleartext: false,
    androidScheme: 'https',
  },
  android: {
    allowMixedContent: false,
    captureInput: true,
    webContentsDebuggingEnabled: false,
    // Prevent overscroll glow/bounce effect — this is an app, not a webpage.
    overScrollMode: 'never',
  },
  ios: {
    // 'never' + overlaysWebView:true → WKWebView fills full screen behind status bar.
    // CSS env(safe-area-inset-top) on body pushes content below the notch.
    contentInset: 'never',
    allowsLinkPreview: false,
  },
  plugins: {
    SplashScreen: {
      // launchShowDuration 0 — the web layer handles the loading animation.
      // backgroundColor matches the site's cream (#fbf6ed) so there is no colour flash.
      backgroundColor: '#fbf6ed',
      launchShowDuration: 0,
      autoHide: true,
    },
    StatusBar: {
      // Default for the (mostly light-background) app; NativeAppShell.tsx switches
      // this dynamically per-route for dark full-bleed screens (e.g. /my-id).
      // overlaysWebView/backgroundColor here are BOTH documented as "Not available
      // on Android 15+" (@capacitor/status-bar's own type defs) — setStyle() (icon
      // contrast, called per-route by NativeAppShell.tsx) is unaffected and still works.
      style: 'Dark',
      overlaysWebView: true,
    },
    // ANDROID-15-REAL-SAFE-AREA-2026-07-30 (Kyle, live investigation): Chromium's
    // Android System WebView has never properly implemented CSS env(safe-area-inset-*)
    // — it always reports 0px (long-standing unfixed Chromium bug, issues.chromium.org/
    // issues/40699457) — so globals.css's env()-based rules were always inert on Android,
    // working only by iOS coincidence (WKWebView DOES support env() correctly there).
    // SystemBars (bundled in @capacitor/core 8.3+) with insetsHandling:"css" injects the
    // REAL inset values as --safe-area-inset-* CSS custom properties instead — the
    // official first-party fix, no third-party plugin needed. globals.css reads these
    // with a var(...,env(...)) fallback so iOS (already correct via env()) is unaffected.
    SystemBars: {
      insetsHandling: 'css',
    },
    PushNotifications: {
      presentationOptions: ['badge', 'sound', 'alert'],
    },
    LocalNotifications: {
      smallIcon: 'ic_stat_balfour',
      iconColor: '#bb8459',
      channels: [
        {
          id: 'loyalty-events',
          name: 'Founders Club Events',
          description: 'Benefit redeemed, tier update, surprise & delight moments',
          importance: 4,
          visibility: 1,
          vibration: true,
        },
        {
          id: 'offers',
          name: 'Offers',
          description: 'New seasonal and partner offers',
          importance: 3,
          visibility: 1,
          vibration: false,
        },
      ],
    },
  },
};

export default config;
