package gs.boldthin.balfour.foundersclub

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import gs.boldthin.loyalty.card.CardPage
import gs.boldthin.loyalty.card.CardSnapshot
import gs.boldthin.loyalty.rendergate.WebReference
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The render gate on the device (scripts/render-gate/render-gate.sh android). Both sides are painted by this phone's
 * WebView at the gate's fixed 412x915 dp at density 3 (1236x2745 px), system bars hidden as the reference has no insets:
 *  - native-card.png: the app's member card exactly as it ships, CardPage loading the bundled offline copy of the My ID card (the /dashboard overlay)
 *    (assets/card/card.html), filled from the recorded card payload, with no network;
 *  - android-<element>-react.png: the live page, with its API calls answered from the recorded HAR (WebReference).
 * A pass means the bundled copy still equals the deployed page; a failure means the site changed: rebuild the copy
 * (scripts/card-page/build-card-page.mjs). Runs only when the gate passes -e renderGateDir <device dir holding fixtures/>.
 */
@RunWith(AndroidJUnit4::class)
class RenderGateDeviceTest {
    @get:Rule val scenario = ActivityScenarioRule(ComponentActivity::class.java)

    private val dir = InstrumentationRegistry.getArguments().getString("renderGateDir")?.let(::File)

    private fun activity(): ComponentActivity {
        lateinit var a: ComponentActivity
        scenario.scenario.onActivity { a = it }
        return a
    }

    private fun out(a: ComponentActivity) = File(a.filesDir, "render-gate").apply { mkdirs() }

    // The reference renders in Europe/London (reference.mjs), as the device test always has.
    init { java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/London")) }

    @Test fun rendersCard() {
        assumeTrue(dir != null)
        val a = activity()
        val card = CardSnapshot.fromCardPayload(File(dir, "fixtures/card.json").readText(), "render-gate-fixture-token", 0, "")!!
        val inst = InstrumentationRegistry.getInstrumentation()
        lateinit var page: CardPage
        inst.runOnMainSync {
            WebView.enableSlowWholeDocumentDraw()
            WindowCompat.setDecorFitsSystemWindows(a.window, false)
            WindowInsetsControllerCompat(a.window, a.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
            page = CardPage(a, onClose = {}, onWallet = {}, onCopy = {}, background = BalfourCard.ART.pageBackground)
            ViewCompat.setOnApplyWindowInsetsListener(page) { _, _ -> WindowInsetsCompat.CONSUMED }
            a.setContentView(
                FrameLayout(a).apply { addView(page, FrameLayout.LayoutParams(W, H)) },
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            page.show(card, offline = false)
        }
        val end = SystemClock.uptimeMillis() + 30_000
        while (!page.ready) {
            if (SystemClock.uptimeMillis() > end) throw AssertionError("card page: not painted after 30 s")
            SystemClock.sleep(100)
        }
        SystemClock.sleep(1500)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        inst.runOnMainSync { page.draw(Canvas(bmp)) }
        File(out(a), "native-card.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        inst.runOnMainSync { page.destroy() }
    }

    /**
     * The card page's controls reach the app (LoyaltyCardPage): close, wallet and copy call back with the scan value and the
     * page shows the page's own "Copied" state; a refreshed snapshot refills the page in place.
     */
    @Test fun cardControlsReachTheApp() {
        assumeTrue(dir != null)
        val a = activity()
        val inst = InstrumentationRegistry.getInstrumentation()
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val card = CardSnapshot("tag", "M1", "P", "TOKEN1", "Ann Example", "Member", 0.0, 0, 0)
        lateinit var page: CardPage
        inst.runOnMainSync {
            page = CardPage(a, onClose = { calls += "close" }, onWallet = { calls += "wallet" }, onCopy = { calls += "copy:$it" }, background = BalfourCard.ART.pageBackground)
            a.setContentView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            page.show(card, offline = false)
        }
        waitUntil("card page painted") { page.ready }
        fun js(code: String): String {
            val done = java.util.concurrent.CountDownLatch(1)
            var out = ""
            inst.runOnMainSync { page.evaluateJavascript(code) { out = it; done.countDown() } }
            done.await(10, java.util.concurrent.TimeUnit.SECONDS)
            return out
        }
        org.junit.Assert.assertEquals("\"PTOKEN1\"", js("document.querySelector('[data-card=number]').textContent"))
        for (slot in listOf("close", "wallet", "number")) js("document.querySelector('[data-card=$slot]').click()")
        waitUntil("three callbacks, got $calls") { calls.size == 3 }
        org.junit.Assert.assertEquals(setOf("close", "wallet", "copy:PTOKEN1"), calls.toSet())
        org.junit.Assert.assertEquals("\"Copied ✓\"", js("document.querySelector('[data-card=number]').textContent"))
        inst.runOnMainSync { page.show(card.copy(qrToken = "TOKEN2"), offline = false) }
        waitUntil("refill after the copied state") { js("document.querySelector('[data-card=number]').textContent") == "\"PTOKEN2\"" }
        inst.runOnMainSync { page.destroy() }
    }

    private fun waitUntil(what: String, ok: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (!ok()) {
            if (SystemClock.uptimeMillis() > end) throw AssertionError("card page: no $what after 15 s")
            SystemClock.sleep(100)
        }
    }

    /**
     * The React references, painted by this device's WebView (WebReference): one per element the gate passes as
     * -e renderGateRefs "element|path|har|click;..." with -e renderGateSite, written as android-<element>-react.png.
     */
    @Test fun rendersReferences() {
        assumeTrue(dir != null)
        val a = activity()
        val args = InstrumentationRegistry.getArguments()
        val site = args.getString("renderGateSite") ?: return
        for (row in args.getString("renderGateRefs").orEmpty().split(';').filter { it.isNotBlank() }) {
            val (element, path, har, click) = (row.split('|') + listOf("", "", "", "")).take(4)
            WebReference(a, File(dir, "fixtures/$har"), TOKEN_KEY)
                .render(site + path, File(out(a), "android-$element-react.png"), W, H, click.ifBlank { null })
        }
    }

    private companion object {
        const val W = 1236
        const val H = 2745
        const val TOKEN_KEY = "balfour_member_token"
    }
}
