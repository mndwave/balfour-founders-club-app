package gs.boldthin.balfour.foundersclub

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import gs.boldthin.loyalty.card.BalfourMemberCard
import gs.boldthin.loyalty.card.CardSnapshot
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the native member card with real Skia (Robolectric native graphics) at a 412x915 dp / 480 dpi (3x) phone, the render gate size,
 * for the frame diff against the web My ID card. Writes a PNG only when CARD_RENDER_OUT is set.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-port-xxhdpi")
class BalfourCardRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun rendersTheCard() {
        // The render gate passes the GET /balfour/loyalty/portal/card payload its React reference was recorded with.
        val fixture = System.getenv("CARD_FIXTURE")
        val card = fixture?.let { CardSnapshot.fromCardPayload(File(it).readText(), "render-gate-fixture-token", 0, "")!! } ?: CardSnapshot(
            tag = "abcdefgh", memberNumber = "BF100234", qrPrefix = "", qrToken = "48213907",
            name = "Ann Example", tier = "Founder", cashBalance = 0.0, stars = 0, syncedAt = 0,
        )
        compose.setContent { BalfourMemberCard(card, offline = false, art = BalfourCard.ART, onClose = {}, onAddToGoogleWallet = {}) }
        compose.waitForIdle()
        val out = System.getenv("CARD_RENDER_OUT")
        assumeTrue(out != null)
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bmp)) }
        File(out!!).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
