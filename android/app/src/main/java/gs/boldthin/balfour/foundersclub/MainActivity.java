package gs.boldthin.balfour.foundersclub;

import android.os.Build;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    // ANDROID-15-STATUS-BAR-SCRIM-2026-07-30 (Kyle, live investigation via
    // on-device debug badge): a values-v29/styles.xml theme attribute
    // (android:enforceStatusBarContrast="false") was tried first and did NOT
    // suppress the scrim — confirmed still visible after reinstalling that
    // build. Window#setStatusBarContrastEnforced/setNavigationBarContrastEnforced
    // are Window INSTANCE methods (API 29+), and AppCompat's theme-inflation
    // path (BridgeActivity extends AppCompatActivity) does not reliably forward
    // every android:-namespaced Window attribute declared in styles.xml the
    // way plain Android would — calling them directly on the real Window
    // object in onCreate() is the documented-reliable path
    // (developer.android.com/design/ui/mobile/guides/foundations/system-bars).
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
    }
}
