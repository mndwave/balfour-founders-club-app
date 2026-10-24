package gs.boldthin.balfour.foundersclub

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import gs.boldthin.loyalty.rendergate.HarServer
import gs.boldthin.loyalty.screens.BalfourHistoryRoute
import gs.boldthin.loyalty.screens.BalfourScreenContext
import gs.boldthin.loyalty.screens.PortalClient
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Render gate (scripts/render-gate): the native history route, fed through its real PortalClient calls from the HAR the
 * React reference was recorded with, rendered at the reference's 412x915 dp / 480 dpi (3x). Only runs when the gate sets
 * RENDER_GATE_DIR (fixtures in) and RENDER_GATE_OUT (PNGs out).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-xxhdpi")
class RenderGateScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // The reference renders in Europe/London (reference.mjs timezoneId), as RenderGateDeviceTest does on the device.
    init { java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/London")) }

    @Test fun rendersHistory() {
        val dir = System.getenv("RENDER_GATE_DIR")?.let(::File)
        assumeTrue(dir != null)
        HarServer(
            File(dir, "fixtures/history.har"),
            aliases = mapOf("/balfour/loyalty/portal/site-copy" to "/api/admin/loyalty/site-copy"),
        ).use { server ->
            val ctx = BalfourScreenContext(
                PortalClient(server.base), "render-gate-fixture-token",
                onBack = {}, onMenu = {}, onAvatar = {}, onSignedOut = {}, onMailto = {},
            )
            compose.setContent { BalfourHistoryRoute(ctx, BalfourScreens.ART) }
            val expect = setOf(
                "/balfour/loyalty/portal/transactions?limit=100&offset=0", "/balfour/loyalty/portal/me", "/balfour/loyalty/portal/site-copy",
            )
            compose.waitUntil(15_000) { server.served.containsAll(expect) }
            Thread.sleep(500)
            compose.waitForIdle()
            val root = compose.activity.window.decorView
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            compose.runOnUiThread { root.draw(Canvas(bmp)) }
            File(System.getenv("RENDER_GATE_OUT"), "native-history.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
