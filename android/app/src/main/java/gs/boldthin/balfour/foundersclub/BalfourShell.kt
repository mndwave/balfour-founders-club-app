package gs.boldthin.balfour.foundersclub

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import gs.boldthin.loyalty.card.CardBrand
import gs.boldthin.loyalty.card.CardStore
import gs.boldthin.loyalty.card.MemberCardOverlay
import gs.boldthin.loyalty.screens.BalfourHistoryRoute
import gs.boldthin.loyalty.screens.BalfourScreenContext
import gs.boldthin.loyalty.screens.PortalClient
import gs.boldthin.loyalty.shell.LoyaltyShell
import gs.boldthin.loyalty.shell.ScreenRouter
import gs.boldthin.loyalty.shell.ShellBrand

/**
 * The Balfour native shell (healer:6d37d06c, port of the Randalls shell healer:1c19eae5): native card and history; every
 * other screen the hosted page in the one Capacitor bridge WebView. The web has no bottom tab bar (its navigation is the
 * hamburger drawer and the avatar menu in the top bar), so the native history draws those two controls and hands them to
 * the hosted page underneath: the page's own drawer / account menu opens, exactly as on the web.
 */
object BalfourShell {
    /** The hosted page's account menu button in AppTopBar (UserMenu). */
    private const val AVATAR_BUTTON = ".app-bar button[aria-haspopup='menu']"

    @JvmStatic
    fun create(activity: ComponentActivity, webView: WebView, card: MemberCardOverlay): LoyaltyShell {
        val store = CardStore(activity, CardBrand.BALFOUR)
        val client = PortalClient(CardBrand.BALFOUR.apiBase)
        lateinit var shell: LoyaltyShell
        shell = LoyaltyShell(
            activity = activity,
            brand = ShellBrand.BALFOUR,
            webView = webView,
            router = ScreenRouter(activity, ShellBrand.BALFOUR),
            // The card needs a saved snapshot (else the hosted /my-id page draws it); history needs a session.
            canOpen = { id -> if (id == LoyaltyShell.CARD) store.load() != null else store.sessionToken() != null },
            openCard = { card.show() },
            screens = { id, actions ->
                val token = store.sessionToken()
                if (token == null) {
                    LaunchedEffect(Unit) { actions.signedOut() }
                } else {
                    val ctx = BalfourScreenContext(
                        client = client,
                        sessionToken = token,
                        onBack = actions.close,
                        onMenu = actions.openMenu,
                        onAvatar = { shell.clickOnPage(AVATAR_BUTTON) },
                        onSignedOut = actions.signedOut,
                        onMailto = { mailto ->
                            try { activity.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(mailto))) } catch (e: Exception) { }
                        },
                    )
                    when (id) {
                        "history" -> BalfourHistoryRoute(ctx, BalfourScreens.ART)
                        else -> LaunchedEffect(Unit) { actions.close() }
                    }
                }
            },
        )
        return shell
    }
}
