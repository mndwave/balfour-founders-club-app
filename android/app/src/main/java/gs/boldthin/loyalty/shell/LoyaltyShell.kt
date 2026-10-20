package gs.boldthin.loyalty.shell

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

// LOYALTY-NATIVE-SHELL-2026-10-20 (healer:1c19eae5, parent healer:cdefd3a3; design: seq1-intelligence
// memory/global/loyalty-apps-gradual-native-hardening-findings-2026-10-19.md sections 3-4).
//
// The native shell: every screen this build implements natively (and the gate allows) is drawn natively; every other
// screen is the hosted page in ONE warm WebView. That WebView is Capacitor's bridge WebView on purpose: the hosted
// pages call push, local notifications, haptics, biometrics, the ML Kit scanner, keep-awake, brightness, in-app review
// and Preferences through the Capacitor JS bridge, so keeping the bridge keeps every capability, the push token
// registration and the member's storage (rr_offline_card_v1, the session) exactly as they are: no second sign-in,
// nothing to migrate. The shell only chooses what draws each screen.
//
// A native screen COVERS the hosted page of the same screen; it never cancels the page's navigation. So beneath the
// native screen the WebView holds the identical page: the web menu, the status bar colour the page sets and the
// history stack stay exactly as they are, and anything the native copy does not draw (the menu drawer) falls through
// to the page underneath. The web top bar and menu change weekly (13 commits in 30 days), so they stay web until a
// native copy of them is versioned in the screens contract like any other screen; until then pages load without
// ?embed=1. How a native screen opens (each gated by ScreenRouter.nativeScreenFor):
//  - a tap on a link to it: a capture-phase click listener (INTERCEPTOR, injected at document start) tells the shell
//    at once, so the native screen is up before the page has rendered;
//  - a programmatic client-side navigation (router.push): the pushState hook tells the shell;
//  - a full page load (shouldOverrideUrlLoading): the native screen opens and the load is cancelled.
// Closing goes history.back() when the page underneath is the covered screen, so the member is where they were.
class LoyaltyShell(
    private val activity: ComponentActivity,
    private val brand: ShellBrand,
    private val webView: WebView,
    val router: ScreenRouter,
    /** Can [screenId] open natively right now (session present, card saved)? Called off the main thread. */
    private val canOpen: (screenId: String) -> Boolean,
    /** The card has its own overlay (MemberCardOverlay): open it, true when it showed. */
    private val openCard: () -> Boolean,
    /** The brand's native screens (tiers, history, ...). */
    private val screens: @Composable (screenId: String, actions: ShellActions) -> Unit,
) {
    /** What a native screen can ask of the shell. */
    class ShellActions(
        /** The screen's back button. */
        val close: () -> Unit,
        /** The API rejected the session: the hosted page takes over and signs the member in as it always has. */
        val signedOut: () -> Unit,
        /** The menu button: the hosted page underneath opens its own menu. */
        val openMenu: () -> Unit,
    )

    private val main = Handler(Looper.getMainLooper())
    private var host: FrameLayout? = null
    private var shownPath: String? = null
    private var cardPath: String? = null
    private var lastRefresh = 0L
    private var documentStartScript = false
    private var bypassPath: String? = null
    private val actions = ShellActions(close = { close(goBack = true) }, signedOut = ::signedOut, openMenu = ::openMenu)
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = close(goBack = true)
    }

    /** Before the bridge loads its first page: the JS interface only reaches pages loaded after it is added. */
    fun installBeforeFirstLoad() {
        activity.onBackPressedDispatcher.addCallback(activity, back)
        // Painted under the page before its first frame: no white flash between native and hosted screens.
        webView.setBackgroundColor(brand.background)
        webView.addJavascriptInterface(Bridge(), JS_NAME)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, INTERCEPTOR, setOf(brand.webBase))
            documentStartScript = true
        }
        refreshRegistry(force = true)
    }

    /** Older WebViews without document-start scripts get the interceptor once the page has loaded. */
    fun onPageFinished(view: WebView, url: String?) {
        if (!documentStartScript && url != null && url.startsWith(brand.webBase)) view.evaluateJavascript(INTERCEPTOR, null)
    }

    /** From the WebViewClient: true when a main-frame load of a natively drawn screen was turned into the native screen. */
    fun interceptNavigation(url: String): Boolean {
        if (!url.startsWith(brand.webBase)) return false
        val path = Uri.parse(url).path ?: return false
        if (path == bypassPath) {
            bypassPath = null
            return false
        }
        val id = router.nativeScreenFor(path) ?: return false
        if (!canOpen(id)) return false
        show(id, path)
        return true
    }

    /** Run [selector]'s click on the hosted page underneath (a native screen hands a web control over). */
    fun clickOnPage(selector: String) {
        close(goBack = false)
        webView.evaluateJavascript("(function(){var b=document.querySelector(${JSONObject.quote(selector)});if(b)b.click();})()", null)
    }

    fun onResume() = refreshRegistry(force = false)

    /** MemberCardOverlay closed: if the page underneath is /qr (the member came from a link), go back from it. */
    fun onCardClosed() {
        val path = cardPath ?: return
        cardPath = null
        backIfPageIs(path)
    }

    private fun refreshRegistry(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < REFRESH_EVERY_MS) return
        lastRefresh = now
        Thread { router.refresh() }.start()
    }

    private fun show(screenId: String, path: String) {
        if (screenId == CARD) {
            if (cardPath == null && openCard()) cardPath = path
            return
        }
        if (host != null) return
        shownPath = path
        val compose = ComposeView(activity).apply {
            setContent { screens(screenId, actions) }
        }
        // Clickable host: touches the native screen does not use never reach the page underneath.
        host = FrameLayout(activity).apply {
            isClickable = true
            setBackgroundColor(brand.background)
            addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        activity.addContentView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        back.isEnabled = true
    }

    /** Removes the native screen; with [goBack] the page underneath steps back too when it is the covered screen. */
    fun close(goBack: Boolean) {
        val h = host ?: return
        (h.parent as? ViewGroup)?.removeView(h)
        host = null
        back.isEnabled = false
        val path = shownPath
        shownPath = null
        if (goBack && path != null) backIfPageIs(path)
    }

    private fun currentPagePath(): String? = webView.url?.takeIf { it.startsWith(brand.webBase) }?.let { Uri.parse(it).path }

    private fun samePath(a: String?, b: String) = a != null && a.trimEnd('/').ifEmpty { "/" } == b.trimEnd('/').ifEmpty { "/" }

    private fun backIfPageIs(path: String) {
        if (samePath(currentPagePath(), path)) webView.evaluateJavascript("history.back()", null)
    }

    private fun signedOut() {
        val path = shownPath ?: return
        close(goBack = false)
        if (samePath(currentPagePath(), path)) return
        // This one load must reach the hosted page, not come straight back to the native screen.
        bypassPath = path
        webView.evaluateJavascript("location.assign(${JSONObject.quote(path)})", null)
    }

    private fun openMenu() = clickOnPage(brand.menuTrigger)

    private inner class Bridge {
        /** Hosted paths drawn natively right now, as a JSON array (read by the interceptor at each navigation). */
        @JavascriptInterface
        fun paths(): String = JSONArray(router.nativePaths().filter { p -> brand.screenFor(p)?.let(canOpen) == true }).toString()

        /** The page is about to show [path] (a link tap or a client-side navigation): cover it natively. */
        @JavascriptInterface
        fun open(path: String) {
            val id = router.nativeScreenFor(path) ?: return
            if (!canOpen(id)) return
            main.post { show(id, path) }
        }
    }

    companion object {
        const val JS_NAME = "LoyaltyShell"
        const val CARD = "card"
        private const val REFRESH_EVERY_MS = 5 * 60_000L

        /** ES5. Idempotent (also evaluated after load on WebViews without document-start scripts). */
        val INTERCEPTOR = """
(function(){
if(window.__loyaltyShell||!window.LoyaltyShell)return;window.__loyaltyShell=1;
var S=window.LoyaltyShell;
function norm(p){p=String(p||'').replace(/\/+$/,'');return p||'/';}
function tell(href){try{var u=new URL(href,location.href);if(u.origin!==location.origin)return;
var p=norm(u.pathname);if(p===norm(location.pathname))return;
if(JSON.parse(S.paths()).indexOf(p)>=0)S.open(p);}catch(x){}}
document.addEventListener('click',function(e){
if(e.defaultPrevented||e.button!==0||e.metaKey||e.ctrlKey||e.shiftKey||e.altKey)return;
var a=e.target&&e.target.closest?e.target.closest('a[href]'):null;if(!a)return;
if(a.target&&a.target!=='_self')return;if(a.hasAttribute('download'))return;
tell(a.getAttribute('href'));
},true);
var f=history.pushState;
history.pushState=function(st,t,url){if(url!=null)tell(url);return f.apply(this,arguments);};
})();
""".trimIndent()
    }
}
