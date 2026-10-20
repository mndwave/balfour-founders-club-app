package gs.boldthin.loyalty.screens

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

// Everything the web /history page (balfour-founders-club src/app/(app)/history/page.tsx) decides, as pure functions:
// parsing, which rows exist, their order, labels and colours' meaning. The composable only draws.

data class CashTx(
    val actionDate: String,
    val amountGbp: Double,
    val type: String,
    val externalTransactionId: String?,
    val spendAmountGbp: String?,
    val venueName: String?,
    val isCorrection: Boolean,
)

data class ReceiptItem(val name: String, val quantity: Int, val priceGbp: String, val itemType: String)

data class Receipt(
    /** false when the client has paused receipts: every other field is absent. */
    val active: Boolean,
    val receiptCode: String?,
    val memberRating: Int?,
    val source: String?,
    val items: List<ReceiptItem>,
)

enum class HistoryFilter(val label: String) { ALL("ALL"), ACTIVATED("ACTIVATED"), REDEEMED("REDEEMED") }

enum class RowKind { EARNED, SPENT, CORRECTION }

data class LedgerRow(
    val venue: String,
    val description: String,
    val dateLabel: String,
    val amount: String,
    val kind: RowKind,
    /** Null for a correction (and a row without an id): it has no receipt to open. */
    val orderId: String?,
)

data class MonthGroup(val label: String, val net: Double, val rows: List<LedgerRow>) {
    val netLabel: String get() = BalfourHistoryModel.pound(net)
}

object BalfourHistoryModel {
    private val MONTHS = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val WEEKDAYS = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    const val SUPPORT = "support@mail.founders.balfourwinery.com"

    /** `+£1.50` / `-£1.50`: sign from the value, magnitude to 2dp (the page's POUND). */
    fun pound(n: Double): String = "${if (n < 0) "-" else "+"}£${String.format(Locale.US, "%.2f", abs(n))}"

    private fun parse(iso: String, zone: ZoneId): ZonedDateTime = try {
        OffsetDateTime.parse(iso).atZoneSameInstant(zone)
    } catch (e: Exception) {
        Instant.parse(iso).atZone(zone)
    }

    /** `October 2026` (en-GB month long + year). */
    fun monthLabel(iso: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = parse(iso, zone)
        return "${MONTHS[d.monthValue - 1]} ${d.year}"
    }

    /** `Thu 22 · 14:05` */
    fun rowLabel(iso: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = parse(iso, zone)
        return "${WEEKDAYS[d.dayOfWeek.value - 1]} ${d.dayOfMonth} · ${"%02d".format(d.hour)}:${"%02d".format(d.minute)}"
    }

    /** Member-facing wording from the transaction type; never a raw POS string. */
    fun txDescription(type: String, spendAmountGbp: String?, externalTransactionId: String?, isCorrection: Boolean): String {
        if (isCorrection) return "Balance correction"
        if (externalTransactionId?.startsWith("gc-") == true) return "Gift card received"
        if (externalTransactionId?.startsWith("5l-opening-") == true) return "Balance transferred from old account"
        if (externalTransactionId?.startsWith("hls-") == true) return "Hush Heath stay"
        if (type == "earn") {
            val n = spendAmountGbp?.let { jsParseFloat(it) }
            if (n != null && !n.isNaN() && n > 0) return "£${String.format(Locale.US, "%.2f", n)} spend"
            return "Spend at venue"
        }
        if (type == "burn") return "Redeemed at till"
        if (type == "refund") return "Refund"
        return "Transaction"
    }

    /** JS parseFloat: the leading numeric prefix, NaN when there is none. */
    fun jsParseFloat(s: String): Double {
        val m = Regex("^\\s*[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?").find(s) ?: return Double.NaN
        return m.value.trim().toDouble()
    }

    fun parseTransactions(raw: String): List<CashTx>? = try {
        JSONObject(raw).getJSONObject("transactions").optJSONArray("cash").objects().map { o ->
            CashTx(
                actionDate = o.optString("action_date"),
                amountGbp = jsParseFloat(o.optString("amount_gbp")).let { if (it.isNaN()) 0.0 else it },
                type = o.optString("type"),
                externalTransactionId = o.optNullableString("external_transaction_id"),
                spendAmountGbp = o.optNullableString("spend_amount_gbp"),
                venueName = o.optNullableString("venue_name"),
                isCorrection = o.optBoolean("is_correction", false),
            )
        }
    } catch (e: Exception) {
        null
    }

    fun parseReceipt(raw: String): Receipt? = try {
        val o = JSONObject(raw)
        Receipt(
            active = !o.has("active") || o.optBoolean("active", true),
            receiptCode = o.optNullableString("receipt_code"),
            memberRating = o.optNullableInt("member_rating"),
            source = o.optNullableString("source"),
            items = o.optJSONArray("items").objects().map {
                ReceiptItem(
                    name = it.optString("name"),
                    quantity = it.optInt("quantity", 1),
                    priceGbp = it.optString("price_gbp"),
                    itemType = it.optNullableString("item_type") ?: "regular",
                )
            },
        )
    } catch (e: Exception) {
        null
    }

    /**
     * The grouped, filtered list, in the order the server sent (the web page does not sort): months in
     * first-seen order, rows in server order.
     */
    fun groups(cash: List<CashTx>, filter: HistoryFilter, zone: ZoneId = ZoneId.systemDefault()): List<MonthGroup> {
        val filtered = cash.filter {
            when (filter) {
                HistoryFilter.ACTIVATED -> it.amountGbp >= 0
                HistoryFilter.REDEEMED -> it.amountGbp < 0
                HistoryFilter.ALL -> true
            }
        }
        val byMonth = LinkedHashMap<String, MutableList<CashTx>>()
        for (tx in filtered) byMonth.getOrPut(monthLabel(tx.actionDate, zone)) { mutableListOf() }.add(tx)
        return byMonth.map { (label, list) ->
            MonthGroup(label, list.fold(0.0) { a, c -> a + c.amountGbp }, list.map { row(it, zone) })
        }
    }

    fun row(tx: CashTx, zone: ZoneId = ZoneId.systemDefault()): LedgerRow {
        val g = tx.amountGbp
        return LedgerRow(
            venue = tx.venueName ?: "Balfour Winery",
            description = txDescription(tx.type, tx.spendAmountGbp, tx.externalTransactionId, tx.isCorrection),
            dateLabel = rowLabel(tx.actionDate, zone),
            amount = pound(g),
            kind = if (tx.isCorrection) RowKind.CORRECTION else if (g >= 0) RowKind.EARNED else RowKind.SPENT,
            // `!!orderId` on the web: null and "" both mean no receipt.
            orderId = if (tx.isCorrection) null else tx.externalTransactionId?.takeIf { it.isNotEmpty() },
        )
    }

    /** encodeURIComponent: spaces as %20 and ! ' ( ) * ~ left bare, unlike URLEncoder. */
    private fun encodeUriComponent(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
            .replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~")

    /** The "Query this transaction" mailto link, exactly as the web page builds it (subject only, no body). */
    fun queryMailto(receipt: Receipt?, orderId: String?): String {
        val subject = if (receipt != null && receipt.items.isNotEmpty()) {
            "Transaction query — REF ${receipt.receiptCode ?: orderId ?: ""}"
        } else {
            "Transaction query — ${orderId ?: "unknown"}"
        }
        return "mailto:$SUPPORT?subject=${encodeUriComponent(subject)}"
    }

    /** Initials for the avatar: first letters of first and last name, "BF" when there is neither. */
    fun avatarInitials(first: String?, last: String?): String {
        val f = (first ?: "").trim().take(1)
        val l = (last ?: "").trim().take(1)
        return "$f$l".uppercase().ifEmpty { "BF" }
    }

    /** lib/avatarStyle.ts: a deterministic pair per member id (hash * 31, JS int32 wrap). */
    fun avatarPairIndex(seed: String?): Int {
        if (seed.isNullOrEmpty()) return 0
        var hash = 0
        for (c in seed) hash = hash * 31 + c.code
        return (abs(hash.toLong()) % 5).toInt()
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

private fun JSONObject.optNullableInt(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)
internal fun JSONObject.optNullableString(key: String): String? = if (isNull(key) || !has(key)) null else optString(key)

/** GET /balfour/loyalty/portal/site-copy: the CMS overrides for page titles and subtitles (SiteCopy on the web). */
class SiteCopy(private val overrides: Map<String, String>) {
    fun get(key: String, fallback: String): String = overrides[key]?.takeIf { it.isNotEmpty() } ?: fallback

    companion object {
        val NONE = SiteCopy(emptyMap())

        fun parse(raw: String): SiteCopy = try {
            val arr = JSONObject(raw).optJSONArray("site_copy")
            SiteCopy(arr.objects().associate { it.optString("key") to it.optString("value") })
        } catch (e: Exception) {
            NONE
        }
    }
}
