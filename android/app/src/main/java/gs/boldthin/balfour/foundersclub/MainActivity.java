package gs.boldthin.balfour.foundersclub;

import android.graphics.Color;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import androidx.core.view.WindowCompat;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

public class MainActivity extends BridgeActivity {
    // BALFOUR-OFFLINE-LOAD-TIMEOUT-2026-10-19 (healer:52c5943e, port of Randalls RR-OFFLINE-LOAD-TIMEOUT):
    // errorPath only fires on a load ERROR. With a weak venue signal the live site can stall with no
    // error at all and the member sees a blank page. If the live page has not become visible within
    // this bound, show the saved card (www/offline.html).
    private static final long LIVE_LOAD_TIMEOUT_MS = 6000;

    // ANDROID-15-EDGE-TO-EDGE-NEVER-ENGAGED-2026-07-30 (Kyle, live device
    // investigation — read @capacitor/status-bar's ACTUAL Java source, not
    // just docs): setOverlaysWebView(true) calls the DEPRECATED
    // Window#setStatusBarColor()/View#setSystemUiVisibility() — both
    // confirmed no-ops for apps targeting API 35+. Every fix before this one
    // controlled what's PAINTED once edge-to-edge is active — none of them
    // could matter if edge-to-edge itself never actually engaged.
    //
    // ACTIONBAR-REAPPEARED-2026-07-30: androidx.activity.EdgeToEdge.enable()
    // was tried first and DID engage real edge-to-edge (confirmed — hero
    // image genuinely reached the true top) but had a confirmed side effect
    // on this AppCompatActivity-based BridgeActivity: it made the native
    // ActionBar reappear (a real android:id/action_bar containing a
    // "Founders Club" title TextView, confirmed via a local Android 15
    // emulator + `adb shell uiautomator dump` view-hierarchy inspection —
    // NOT visible from a screenshot alone, needed the actual view tree).
    // getSupportActionBar().hide() did NOT suppress it (tested). A/B tested
    // by commenting EdgeToEdge.enable() out entirely on the same emulator —
    // the fake title bar vanished completely, confirming it as the trigger.
    // WindowCompat.setDecorFitsSystemWindows(window, false) is the lower-
    // level call EdgeToEdge.enable() wraps internally, without whatever
    // extra window-feature negotiation triggers the ActionBar side effect.
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        // WHITE-BAND-AFTER-EDGE-TO-EDGE-2026-07-30 (Kyle, live device test:
        // "really broken white bar at the top" — a real change from the
        // previous black band, confirming EdgeToEdge.enable() above
        // genuinely engaged edge-to-edge for the first time in this
        // investigation). android.webkit.WebView has its OWN default
        // background (white) as a distinct child view, separate from the
        // Activity's android:windowBackground (already set to cream) —
        // before the WebView's own content/CSS has composited a frame,
        // the WebView's raw default paints through instead. getBridge() is
        // only initialised inside super.onCreate() above, so this must run
        // after it, not before (unlike EdgeToEdge.enable()).
        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().setBackgroundColor(Color.parseColor("#fbf6ed"));
        }
        Bridge b = getBridge();
        if (b != null) b.setWebViewClient(new TimeoutClient(b));
    }

    private static class TimeoutClient extends BridgeWebViewClient {
        private final Bridge bridge;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private Runnable pending;

        TimeoutClient(Bridge bridge) {
            super(bridge);
            this.bridge = bridge;
        }

        private boolean isLive(String url) {
            return url != null && url.startsWith("https://founders.balfourwinery.com");
        }

        private void disarm() {
            if (pending != null) handler.removeCallbacks(pending);
            pending = null;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            disarm();
            if (!isLive(url)) return;
            final String errorUrl = bridge.getErrorUrl();
            if (errorUrl == null) return;
            pending = () -> {
                pending = null;
                view.loadUrl(errorUrl);
            };
            handler.postDelayed(pending, LIVE_LOAD_TIMEOUT_MS);
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            disarm();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            disarm();
        }
    }
}
