package gs.boldthin.loyalty.card

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Puts the member card over the activity, from the device snapshot, with no network wait, then freshens it from
 * GET /balfour/loyalty/portal/card. The card itself is the React My ID card painted by a WebView from the bundled offline
 * copy of the page (CardPage, CARD-PAGE-WEBVIEW-2026-10-24); this overlay keeps the screen awake and at full brightness
 * while it shows, as the web page does through the keep-awake and screen-brightness plugins.
 */
class MemberCardOverlay(
    private val activity: ComponentActivity,
    private val brand: CardBrand,
    private val art: CardArt,
    /** Called after the member closes the card (the host decides what to load behind it). */
    private val onClosed: Runnable,
) {
    private val store = CardStore(activity, brand)
    private val client = CardClient(brand)
    private val main = Handler(Looper.getMainLooper())
    private var card: CardSnapshot? = null
    private var offline = false
    private var page: CardPage? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var host: FrameLayout? = null
    private var savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private val splash = mutableStateOf(false)
    /** The splash plays once per process; a card shown again later goes straight to the card. */
    private var splashPlayed = false
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = close()
    }

    init {
        activity.onBackPressedDispatcher.addCallback(activity, back)
    }

    val isShowing: Boolean get() = host != null

    /** Shows the saved card. False when this member has none yet: the caller keeps its own offline page. */
    fun show(): Boolean {
        if (host != null) return true
        val saved = store.load() ?: return false
        card = saved
        offline = !hasNetwork()
        splash.value = !splashPlayed
        splashPlayed = true
        val web = CardPage(activity, onClose = ::close, onWallet = ::addToGoogleWallet, onCopy = ::copy, background = art.pageBackground)
        web.show(saved, offline)
        page = web
        // Cold launch only: the logo holds on cream, then one fade into the card.
        val compose = ComposeView(activity).apply {
            setContent { if (splash.value) CardLaunchSplash(art) { splash.value = false } }
        }
        // Clickable host: touches that the card does not use never reach the WebView underneath.
        host = FrameLayout(activity).apply {
            isClickable = true
            addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        activity.addContentView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Last callback added is asked first; Capacitor's WebView-back callback is added when the bridge loads, after this one.
        back.remove()
        activity.onBackPressedDispatcher.addCallback(activity, back)
        activity.window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            savedBrightness = attributes.screenBrightness
            attributes = attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        }
        back.isEnabled = true
        host?.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        refresh(retry = true)
        watchForNetwork()
        return true
    }

    fun close() {
        val h = host ?: return
        (h.parent as? ViewGroup)?.removeView(h)
        host = null
        page?.destroy()
        page = null
        back.isEnabled = false
        unwatchNetwork()
        activity.window.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            attributes = attributes.apply { screenBrightness = savedBrightness }
        }
        onClosed.run()
    }

    // Same as the web page: one retry after 1.5 s, then say the card is the saved one.
    private fun refresh(retry: Boolean) {
        val token = store.sessionToken() ?: return
        Thread {
            val result = client.fetch(token)
            main.post {
                if (host == null) return@post
                when (result) {
                    is CardClient.Result.Ok -> {
                        store.save(result.card)
                        card = result.card
                        offline = false
                        page?.show(result.card, false)
                    }
                    // A signed-out session keeps showing the saved card; the web app signs the member out.
                    CardClient.Result.SignedOut -> Unit
                    CardClient.Result.Failed -> Unit
                    CardClient.Result.Unreachable ->
                        if (retry) main.postDelayed({ if (host != null) refresh(retry = false) }, 1500) else {
                            offline = true
                            card?.let { page?.show(it, true) }
                        }
                }
            }
        }.start()
    }

    // On reconnect the card refreshes itself in place and the saved-card line simply goes: it reads as the
    // card having loaded, never as a no-connection screen. (The web page has no equivalent: it reloads.)
    private fun watchForNetwork() {
        if (networkCallback != null) return
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                main.post { if (host != null) refresh(retry = true) }
            }
        }
        networkCallback = cb
        try { cm.registerDefaultNetworkCallback(cb) } catch (e: Exception) { networkCallback = null }
    }

    private fun unwatchNetwork() {
        val cb = networkCallback ?: return
        networkCallback = null
        try { (activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) } catch (e: Exception) { }
    }

    private fun addToGoogleWallet() {
        val token = store.sessionToken() ?: return
        Thread {
            val saveUrl = try {
                val conn = (URL(brand.apiBase + brand.googleWalletPath).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CardClient.TIMEOUT_MS
                    readTimeout = CardClient.TIMEOUT_MS
                    setRequestProperty("Authorization", "Bearer $token")
                }
                try {
                    if (conn.responseCode == 200) JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optString("saveUrl") else null
                } finally { conn.disconnect() }
            } catch (e: Exception) { null }
            main.post {
                if (saveUrl.isNullOrBlank()) return@post
                host?.performHapticFeedback(
                    if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK,
                )
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(saveUrl)))
            }
        }.start()
    }

    /** CopyText on the page: the scan value to the clipboard (the page shows its own "Copied" state). */
    private fun copy(text: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        try { cm.setPrimaryClip(ClipData.newPlainText("Member number", text)) } catch (e: Exception) { }
    }

    private fun hasNetwork(): Boolean {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        /** Fetches the card and keeps the device snapshot current (background, no UI); a no-op when signed out. */
        @JvmStatic
        fun prewarm(context: Context, brand: CardBrand) {
            val store = CardStore(context, brand)
            val token = store.sessionToken() ?: return
            Thread {
                val r = CardClient(brand).fetch(token)
                if (r is CardClient.Result.Ok) store.save(r.card)
            }.start()
        }
    }
}
