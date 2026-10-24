package gs.boldthin.loyalty.screens

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// LOYALTY-NATIVE-SHELL-2026-10-20 (healer:6d37d06c, port of healer:1c19eae5): the native Balfour history screen with its
// data, as the shell opens it. Same calls, same order, same optimistic rules as the web page
// (balfour-founders-club src/app/(app)/history/page.tsx); all on boldthings-services through PortalClient.

/** What a screen needs from the shell: the API, the member's session and how to leave. */
class BalfourScreenContext(
    val client: PortalClient,
    val sessionToken: String,
    val onBack: () -> Unit,
    /** The hamburger: the hosted page underneath opens its own menu drawer. */
    val onMenu: () -> Unit,
    /** The avatar button: the hosted page underneath opens its own account menu. */
    val onAvatar: () -> Unit,
    /** The API rejected the session: the shell hands over to the hosted page, which signs the member in. */
    val onSignedOut: () -> Unit,
    val onMailto: (String) -> Unit,
)

private suspend fun BalfourScreenContext.get(path: String) = withContext(Dispatchers.IO) { client.get(path, sessionToken) }

private fun photoOf(url: String) = try {
    BitmapFactory.decodeStream(URL(url).openStream())?.asImageBitmap()
} catch (e: Exception) {
    null
}

@Composable
fun BalfourHistoryRoute(ctx: BalfourScreenContext, art: BalfourArt) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(BalfourHistoryState(cash = null)) }
    var copy by remember { mutableStateOf(SiteCopy.NONE) }
    LaunchedEffect(Unit) {
        launch { (ctx.get("/balfour/loyalty/portal/site-copy") as? PortalClient.Result.Ok)?.let { copy = SiteCopy.parse(it.body) } }
        launch {
            val me = ctx.get("/balfour/loyalty/portal/me")
            if (me is PortalClient.Result.SignedOut) return@launch ctx.onSignedOut()
            val body = (me as? PortalClient.Result.Ok)?.body ?: return@launch
            val o = try { JSONObject(body) } catch (e: Exception) { return@launch }
            val enabled = o.optBoolean("receipt_emails_enabled", false)
            // Never clobber an in-flight/just-completed activation with a stale fetch (as the web page).
            state = state.copy(
                avatarInitials = BalfourHistoryModel.avatarInitials(o.optNullableString("first_name"), o.optNullableString("last_name")),
                avatarPair = BalfourHistoryModel.avatarPairIndex(o.optNullableString("id")),
                receiptPrompt = if (state.receiptPrompt == BfReceiptPrompt.SAVED) state.receiptPrompt
                else if (enabled) BfReceiptPrompt.HIDDEN else BfReceiptPrompt.PROMPT,
            )
            o.optNullableString("avatar_url")?.let { url ->
                withContext(Dispatchers.IO) { photoOf(url) }?.let { state = state.copy(avatarPhoto = it) }
            }
        }
        when (val r = ctx.get("/balfour/loyalty/portal/transactions?limit=100&offset=0")) {
            // Parse first, then copy: copying first held a snapshot of state across the parse, and a /me result landing
            // meanwhile (initials, receipt prompt) was written back over with the stale copy.
            is PortalClient.Result.Ok -> {
                val cash = BalfourHistoryModel.parseTransactions(r.body) ?: emptyList()
                state = state.copy(cash = cash)
            }
            PortalClient.Result.SignedOut -> ctx.onSignedOut()
            // The web page shows its empty state when the fetch fails.
            else -> state = state.copy(cash = emptyList())
        }
    }
    BalfourHistory(
        state = state,
        copy = copy,
        art = art,
        onFilter = { state = state.copy(filter = it) },
        onToggleReceipt = { orderId ->
            val open = orderId in state.expanded
            state = state.copy(expanded = if (open) state.expanded - orderId else state.expanded + orderId)
            if (!open && state.receipts[orderId] !is BfReceiptState.Loaded) {
                state = state.copy(receipts = state.receipts + (orderId to BfReceiptState.Loading))
                scope.launch {
                    val r = ctx.get("/balfour/loyalty/portal/transactions/${java.net.URLEncoder.encode(orderId, "UTF-8").replace("+", "%20")}/receipt")
                    val receipt = (r as? PortalClient.Result.Ok)?.let { BalfourHistoryModel.parseReceipt(it.body) }
                    state = state.copy(
                        receipts = state.receipts + (orderId to (receipt?.let { BfReceiptState.Loaded(it) } ?: BfReceiptState.Failed)),
                    )
                }
            }
        },
        onRate = { orderId, stars ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    ctx.client.post(
                        "/balfour/loyalty/portal/transactions/${java.net.URLEncoder.encode(orderId, "UTF-8").replace("+", "%20")}/rate",
                        ctx.sessionToken,
                        JSONObject().put("stars", stars).toString(),
                    )
                }
            }
        },
        onTurnOnReceipts = {
            // Optimistic, as the web page: saved at once, back to the prompt if the PATCH fails, hidden after 2.2 s.
            state = state.copy(receiptPrompt = BfReceiptPrompt.SAVED)
            scope.launch {
                val r = withContext(Dispatchers.IO) {
                    ctx.client.patch("/balfour/loyalty/portal/me", ctx.sessionToken, JSONObject().put("receipt_emails_enabled", true).toString())
                }
                if (r !is PortalClient.Result.Ok) {
                    state = state.copy(receiptPrompt = BfReceiptPrompt.PROMPT)
                    return@launch
                }
                delay(2200)
                if (state.receiptPrompt == BfReceiptPrompt.SAVED) state = state.copy(receiptPrompt = BfReceiptPrompt.HIDDEN)
            }
        },
        onQuery = ctx.onMailto,
        onMenu = ctx.onMenu,
        onAvatar = ctx.onAvatar,
    )
}
