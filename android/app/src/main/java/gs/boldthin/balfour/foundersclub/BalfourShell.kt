package gs.boldthin.balfour.foundersclub

import android.webkit.WebView
import androidx.activity.ComponentActivity
import gs.boldthin.loyalty.card.CardBrand
import gs.boldthin.loyalty.card.CardStore
import gs.boldthin.loyalty.card.MemberCardOverlay
import gs.boldthin.loyalty.shell.LoyaltyShell
import gs.boldthin.loyalty.shell.ScreenRouter
import gs.boldthin.loyalty.shell.ShellBrand

/**
 * The Balfour shell (healer:6d37d06c, port of the Randalls shell healer:1c19eae5): the My ID card opens from the device
 * (MemberCardOverlay, the bundled React card); every other screen, history included, is the hosted page in the one
 * Capacitor bridge WebView (CARD-PAGE-WEBVIEW-2026-10-24, healer:4b3d7c53).
 */
object BalfourShell {
    @JvmStatic
    fun create(activity: ComponentActivity, webView: WebView, card: MemberCardOverlay): LoyaltyShell {
        val store = CardStore(activity, CardBrand.BALFOUR)
        return LoyaltyShell(
            activity = activity,
            brand = ShellBrand.BALFOUR,
            webView = webView,
            router = ScreenRouter(activity, ShellBrand.BALFOUR),
            // The card needs a saved snapshot (else the hosted /my-id page draws it).
            canOpen = { id -> id == LoyaltyShell.CARD && store.load() != null },
            openCard = { card.show() },
        )
    }
}
