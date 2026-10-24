package gs.boldthin.loyalty.card

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

// loyalty-screen: card@1 (the My ID card; emit-screens-json.sh reads this marker for the build manifest)
//
// CARD-PAGE-WEBVIEW-2026-10-24 (healer:4b3d7c53, Kyle's answer to gate 339937c7): the member card is the React card, drawn
// by a WebView from the bundled offline copy of the live card page (assets/card/card.html, built by
// scripts/card-page/build-card-page.mjs). It is identical to the site by construction: same HTML, CSS, fonts and images,
// the QR drawn by the site's own qrcode.react, painted by the phone's Chromium. A Compose copy matched every layout rule
// but not Chromium's glyph rasterization (~0.5% of pixels). It needs no network: the page is a local asset and the member
// comes from the device snapshot. The page loads nothing else; any navigation is refused.
//
// The page asks the app through LoyaltyCardPage: card() (the member as JSON), close(), wallet(), copy(text).
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class CardPage(
    context: Context,
    private val onClose: () -> Unit,
    private val onWallet: () -> Unit,
    private val onCopy: (String) -> Unit,
    background: Int,
) : WebView(context) {
    @Volatile private var json: String = "null"

    /** True once the page has painted the member (fonts loaded, renderCard ran). */
    @Volatile var ready = false
        private set

    init {
        setBackgroundColor(background)
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        settings.apply {
            javaScriptEnabled = true
            textZoom = 100
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
        }
        addJavascriptInterface(Bridge(), JS_NAME)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript("document.fonts.ready.then(function(){${JS_NAME}.painted()})", null)
            }
        }
    }

    /** Shows [card]: the first call loads the page, later ones refill it in place. */
    fun show(card: CardSnapshot, offline: Boolean) {
        json = toJson(card, offline)
        if (url == null) loadUrl(ASSET) else evaluateJavascript("window.renderCard && window.renderCard($json)", null)
    }

    private inner class Bridge {
        @JavascriptInterface fun card(): String = json
        @JavascriptInterface fun close() = post { onClose() }
        @JavascriptInterface fun wallet() = post { onWallet() }
        @JavascriptInterface fun copy(text: String) = post { onCopy(text) }
        @JavascriptInterface fun painted() { ready = true }
    }

    companion object {
        const val ASSET = "file:///android_asset/card/card.html"
        const val JS_NAME = "LoyaltyCardPage"

        /** What the page's runtime reads (scripts/card-page/card-runtime.js). */
        fun toJson(card: CardSnapshot, offline: Boolean): String = JSONObject()
            .put("scan", card.scanValue)
            .put("name", card.name)
            .put("tier", card.tier)
            .put("offline", offline)
            .put("updatedAt", card.syncedAt)
            .toString()
    }
}
