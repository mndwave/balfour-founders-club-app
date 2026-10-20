package gs.boldthin.balfour.foundersclub

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import gs.boldthin.loyalty.screens.BalfourHistory
import gs.boldthin.loyalty.screens.BalfourHistoryState
import gs.boldthin.loyalty.screens.BfReceiptPrompt
import gs.boldthin.loyalty.screens.BfReceiptState
import gs.boldthin.loyalty.screens.CashTx
import gs.boldthin.loyalty.screens.Receipt
import gs.boldthin.loyalty.screens.ReceiptItem
import gs.boldthin.loyalty.screens.SiteCopy
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the native history screen at a 411x914 dp phone for the frame diff against the web page. PNG only when SCREEN_RENDER_DIR is set. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class BalfourScreensRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun snap(dir: String, name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { root.draw(Canvas(bmp)) }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun history(expanded: Boolean, prompt: BfReceiptPrompt): BalfourHistoryState {
        val cash = listOf(
            CashTx("2026-10-18T12:05:00Z", 12.5, "earn", "o1", "12.50", "Hush Heath Estate", false),
            CashTx("2026-10-12T18:30:00Z", -4.0, "burn", "o2", null, "The Cellar Door", false),
            CashTx("2026-09-20T09:00:00Z", 3.0, "earn", null, null, null, true),
        )
        val receipt = Receipt(true, "AB12", null, "pos", listOf(
            ReceiptItem("Sparkling", 2, "7.00", "regular"),
            ReceiptItem("Service charge", 1, "0.70", "service_charge"),
        ))
        return BalfourHistoryState(
            cash = cash,
            expanded = if (expanded) setOf("o1") else emptySet(),
            receipts = mapOf("o1" to BfReceiptState.Loaded(receipt)),
            receiptPrompt = prompt,
            avatarInitials = "AB",
            avatarPair = 2,
        )
    }

    @Test fun rendersHistory() {
        val dir = System.getenv("SCREEN_RENDER_DIR")
        assumeTrue(dir != null)
        compose.setContent { BalfourHistory(history(true, BfReceiptPrompt.PROMPT), SiteCopy.NONE, BalfourScreens.ART, {}, {}, { _, _ -> }, {}, {}) }
        snap(dir!!, "balfour-native-history.png")
    }

    @Test fun rendersHistoryWithoutAnyRowOpen() {
        val dir = System.getenv("SCREEN_RENDER_DIR")
        assumeTrue(dir != null)
        compose.setContent { BalfourHistory(history(false, BfReceiptPrompt.HIDDEN), SiteCopy.NONE, BalfourScreens.ART, {}, {}, { _, _ -> }, {}, {}) }
        snap(dir!!, "balfour-native-history-closed.png")
    }
}
