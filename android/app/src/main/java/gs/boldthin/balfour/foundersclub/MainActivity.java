package gs.boldthin.balfour.foundersclub;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    // ANDROID-15-EDGE-TO-EDGE-NEVER-ENGAGED-2026-07-30 (Kyle, live device
    // investigation — read @capacitor/status-bar's ACTUAL Java source, not
    // just docs): setOverlaysWebView(true) calls the DEPRECATED
    // Window#setStatusBarColor()/View#setSystemUiVisibility() — both
    // confirmed no-ops for apps targeting API 35+ (Android's own SDK docs:
    // "if the window belongs to an app targeting VANILLA_ICE_CREAM or
    // above, this attribute is ignored"). Every previous fix in this
    // investigation (theme contrast attributes, Window#set*ContrastEnforced,
    // windowBackground) controlled what's PAINTED once edge-to-edge is
    // active — none of them could matter if edge-to-edge itself never
    // actually engaged for this Activity. EdgeToEdge.enable() (androidx
    // .activity, already a transitive dependency via Capacitor/AppCompat)
    // is the current, non-deprecated, explicit way to request it — must be
    // called BEFORE super.onCreate() per Android's own convention.
    @Override
    public void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
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
    }
}
