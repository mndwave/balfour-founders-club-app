package gs.boldthin.balfour.foundersclub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import gs.boldthin.loyalty.card.BalfourMemberCard
import gs.boldthin.loyalty.card.CardSnapshot
import gs.boldthin.loyalty.rendergate.HarServer
import gs.boldthin.loyalty.rendergate.WebReference
import gs.boldthin.loyalty.screens.BalfourHistoryRoute
import gs.boldthin.loyalty.screens.BalfourScreenContext
import gs.boldthin.loyalty.screens.PortalClient
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The render gate's native side on a real Android runtime (scripts/render-gate/render-gate.sh android): the device's own
 * text stack, fonts and variable-font axes, which Robolectric does not reproduce. Each element is laid out at the gate's
 * fixed 412x915 dp at density 3 (1236x2745 px, the React reference's size), system bars hidden as the reference has no
 * insets, fed the recorded data, and drawn to a PNG in the app's files dir for the gate to pull. Runs only when the gate
 * passes -e renderGateDir <device dir holding fixtures/>.
 */
@RunWith(AndroidJUnit4::class)
class RenderGateDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val dir = InstrumentationRegistry.getArguments().getString("renderGateDir")?.let(::File)
    private val out by lazy { File(compose.activity.filesDir, "render-gate").apply { mkdirs() } }

    // The reference renders in Europe/London (reference.mjs); the ledger formats dates in the device zone.
    init { java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/London")) }

    // A screen that never becomes ready fails with what the recorded-API server saw and what a direct call to it returns,
    // so a harness fault (network policy, server, path) is named, never a bare 15 s timeout (GATE-HARDEN, healer:4b3d7c53).
    private fun capture(name: String, ready: () -> Boolean = { true }, why: () -> String = { "" }, content: @Composable () -> Unit) {
        lateinit var view: ComposeView
        compose.runOnUiThread {
            val window = compose.activity.window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            view = ComposeView(compose.activity).apply {
                setContent { CompositionLocalProvider(LocalDensity provides Density(3f, 1f)) { content() } }
            }
            // Larger than the screen is fine: the view is drawn to a bitmap, never to the display.
            compose.activity.setContentView(
                FrameLayout(compose.activity).apply { addView(view, FrameLayout.LayoutParams(W, H)) },
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        try {
            compose.waitUntil(15_000) { ready() }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("$name: not ready after 15 s; ${why()}", e)
        }
        Thread.sleep(800)
        compose.waitForIdle()
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread {
            view.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, W, H)
            view.draw(Canvas(bmp))
        }
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun rendersCard() {
        assumeTrue(dir != null)
        val card = CardSnapshot.fromCardPayload(File(dir, "fixtures/card.json").readText(), "render-gate-fixture-token", 0, "")!!
        capture("native-card.png") {
            BalfourMemberCard(card, offline = false, art = BalfourCard.ART, onClose = {}, onAddToGoogleWallet = {})
        }
    }

    /**
     * The React references, painted by this device's WebView (WebReference): one per element the gate passes as
     * -e renderGateRefs "element|path|har|click;..." with -e renderGateSite, written as android-<element>-react.png.
     */
    @Test fun rendersReferences() {
        assumeTrue(dir != null)
        val args = InstrumentationRegistry.getArguments()
        val site = args.getString("renderGateSite") ?: return
        for (row in args.getString("renderGateRefs").orEmpty().split(';').filter { it.isNotBlank() }) {
            val (element, path, har, click) = (row.split('|') + listOf("", "", "", "")).take(4)
            WebReference(compose.activity, File(dir, "fixtures/$har"), TOKEN_KEY)
                .render(site + path, File(out, "android-$element-react.png"), W, H, click.ifBlank { null })
        }
    }

    @Test fun rendersHistory() {
        assumeTrue(dir != null)
        HarServer(
            File(dir, "fixtures/history.har"),
            aliases = mapOf("/balfour/loyalty/portal/site-copy" to "/api/admin/loyalty/site-copy"),
        ).use { server ->
            val ctx = BalfourScreenContext(
                PortalClient(server.base), "render-gate-fixture-token",
                onBack = {}, onMenu = {}, onAvatar = {}, onSignedOut = {}, onMailto = {},
            )
            val expect = setOf(
                "/balfour/loyalty/portal/transactions?limit=100&offset=0", "/balfour/loyalty/portal/me", "/balfour/loyalty/portal/site-copy",
            )
            capture("native-history.png", ready = { server.served.containsAll(expect) }, why = { diagnose(server, expect) }) { BalfourHistoryRoute(ctx, BalfourScreens.ART) }
        }
    }

    private fun diagnose(server: HarServer, expect: Set<String>): String {
        val missing = expect - server.served
        val probe = try {
            PortalClient(server.base).get(missing.firstOrNull() ?: expect.first(), "render-gate-fixture-token").toString().take(160)
        } catch (t: Throwable) {
            t.toString()
        }
        return "served=${server.served} missing=$missing directCall=$probe"
    }

    private companion object {
        const val W = 1236
        const val H = 2745
        const val TOKEN_KEY = "balfour_member_token"
    }
}
