package gs.boldthin.loyalty.rendergate

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject

/**
 * The Android render gate's React reference, painted by the phone's own WebView (RENDER-GATE-DEVICE-WEBVIEW-REF-2026-10-24,
 * healer:4b3d7c53). In the app the page IS drawn by this WebView, so this is the reference by construction: the same
 * Chromium build, Skia text gamma, kerning and fonts as the app, which a Linux Chromium reference cannot reproduce (its
 * glyph anti-aliasing alone left ~1% of pixels apart). The page is the live site, set up as the Capacitor shell sets it up
 * (html.android-native, the androidBridge interface, the member token in localStorage), with every API call answered from
 * the gate's recorded HAR, and drawn in software into a bitmap, the same CPU Skia path the native capture uses.
 * The gate sets the display to the reference's 412x915 dp at density 3 for the run (android-native.sh), so CSS px = dp.
 */
class WebReference(private val activity: Activity, har: File, private val tokenKey: String) {
    private val recorded = HashMap<String, Triple<Int, String, ByteArray>>()
    private val lastRequest = AtomicLong(SystemClock.uptimeMillis())
    val unanswered: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    init {
        val entries = JSONObject(har.readText()).getJSONObject("log").getJSONArray("entries")
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            val req = e.getJSONObject("request")
            if (req.getString("method") != "GET") continue
            val res = e.getJSONObject("response")
            val content = res.getJSONObject("content")
            val text = content.optString("text", "")
            val bytes = if (content.optString("encoding") == "base64") Base64.getDecoder().decode(text) else text.toByteArray()
            recorded[req.getString("url")] = Triple(res.getInt("status"), content.optString("mimeType", "application/json"), bytes)
        }
    }

    /** Renders [url] at [w] x [h] device px into [out]; [click] is a DOM selector clicked first, as reference.mjs --click. */
    @SuppressLint("SetJavaScriptEnabled")
    fun render(url: String, out: File, w: Int, h: Int, click: String? = null, settleMs: Long = 2500) {
        val inst = InstrumentationRegistry.getInstrumentation()
        lateinit var web: WebView
        inst.runOnMainSync {
            WebView.enableSlowWholeDocumentDraw()
            // No insets, as the native capture (bars hidden) and the iOS reference: the page's env(safe-area-inset-*) is 0.
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            web = WebView(activity)
            ViewCompat.setOnApplyWindowInsetsListener(web) { _, _ -> WindowInsetsCompat.CONSUMED }
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                textZoom = 100
                mediaPlaybackRequiresUserGesture = true
            }
            web.overScrollMode = View.OVER_SCROLL_NEVER
            web.isVerticalScrollBarEnabled = false
            web.isHorizontalScrollBarEnabled = false
            // Capacitor's bridge object: @capacitor/core reports native from it (plugin calls never answer, as reference.mjs).
            web.addJavascriptInterface(object { @JavascriptInterface fun postMessage(@Suppress("UNUSED_PARAMETER") m: String) {} }, "androidBridge")
            check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) { "WebView lacks DOCUMENT_START_SCRIPT" }
            WebViewCompat.addDocumentStartJavaScript(web, startScript(), setOf("*"))
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url.toString()
                    if (!DATA.containsMatchIn(u)) return null
                    lastRequest.set(SystemClock.uptimeMillis())
                    val cors = mapOf("Access-Control-Allow-Origin" to "*", "Access-Control-Allow-Headers" to "*", "Access-Control-Allow-Methods" to "GET, OPTIONS")
                    if (request.method == "OPTIONS") return WebResourceResponse("text/plain", "utf-8", 204, "No Content", cors, ByteArrayInputStream(ByteArray(0)))
                    val hit = recorded[u]
                    if (hit == null) {
                        unanswered += u
                        return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", cors, ByteArrayInputStream(ByteArray(0)))
                    }
                    val (status, mime, body) = hit
                    return WebResourceResponse(mime.substringBefore(';'), "utf-8", status, if (status == 200) "OK" else "Status", cors, ByteArrayInputStream(body))
                }
            }
            activity.setContentView(
                FrameLayout(activity).apply { addView(web, FrameLayout.LayoutParams(w, h)) },
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            web.loadUrl(url)
        }
        waitFor(60_000, "page load of $url") { eval(web, "document.readyState === 'complete' && document.fonts.status === 'loaded'") == "true" }
        if (click != null) {
            // The element appears once the page has its data (hydration + recorded API), not at readyState complete.
            val sel = JSONObject.quote(click)
            waitFor(30_000, "element for click $click") { eval(web, "!!document.querySelector($sel)") == "true" }
            eval(web, "document.querySelector($sel).click()")
        }
        // Network idle (as networkidle: no recorded-API call for 500 ms), fonts settled, then the reference's fixed settle time.
        waitFor(30_000, "network idle") { SystemClock.uptimeMillis() - lastRequest.get() > 500 && eval(web, "document.fonts.status") == "\"loaded\"" }
        SystemClock.sleep(settleMs)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        inst.runOnMainSync { web.draw(Canvas(bmp)) }
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        inst.runOnMainSync { web.destroy() }
    }

    private fun startScript() = """
        (function () {
          try { localStorage.setItem(${JSONObject.quote(tokenKey)}, 'render-gate-fixture-token') } catch (e) {}
          var tag = function () { var d = document.documentElement; if (d && !d.classList.contains('android-native')) d.classList.add('android-native') };
          tag();
          new MutationObserver(tag).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
        })();
    """.trimIndent()

    private fun eval(web: WebView, js: String): String? {
        var result: String? = null
        val done = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { web.evaluateJavascript(js) { result = it; done.countDown() } }
        done.await(10, TimeUnit.SECONDS)
        return result
    }

    private fun waitFor(ms: Long, what: String, ok: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + ms
        while (!ok()) {
            if (SystemClock.uptimeMillis() > end) throw AssertionError("web reference: no $what after $ms ms; unanswered API calls: $unanswered")
            SystemClock.sleep(100)
        }
    }

    private companion object {
        // As reference.mjs: the API host and same-origin /api/* are data (answered from the HAR); the rest is the live site.
        val DATA = Regex("^https://api\\.boldthin\\.gs/|^https://[^/]+/api/")
    }
}
