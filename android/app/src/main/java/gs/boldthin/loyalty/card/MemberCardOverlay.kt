package gs.boldthin.loyalty.card

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
 * Puts the native member card over the activity, from the device snapshot, with no network wait, then
 * freshens it from GET /balfour/loyalty/portal/card. Keeps the screen awake and at full brightness while shown,
 * as the web /qr page does through the keep-awake and screen-brightness plugins.
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
    private val card = mutableStateOf<CardSnapshot?>(null)
    private val offline = mutableStateOf(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var host: FrameLayout? = null
    private var savedBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
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
        card.value = saved
        offline.value = !hasNetwork()
        val compose = ComposeView(activity).apply {
            setContent {
                card.value?.let {
                    BalfourMemberCard(it, offline.value, art, onClose = ::close, onAddToGoogleWallet = ::addToGoogleWallet)
                }
            }
        }
        // Clickable host: touches that the card does not use never reach the WebView underneath.
        host = FrameLayout(activity).apply {
            isClickable = true
            addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        activity.addContentView(host, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
                        card.value = result.card
                        offline.value = false
                    }
                    // A signed-out session keeps showing the saved card; the web app signs the member out.
                    CardClient.Result.SignedOut -> Unit
                    CardClient.Result.Failed -> Unit
                    CardClient.Result.Unreachable ->
                        if (retry) main.postDelayed({ if (host != null) refresh(retry = false) }, 1500) else offline.value = true
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
