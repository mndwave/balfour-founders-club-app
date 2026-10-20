package gs.boldthin.loyalty.screens

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BalfourHistoryModelTest {
    private val london = ZoneId.of("Europe/London")

    private fun cash(date: String, amount: Double, id: String? = "ord-1", type: String = if (amount >= 0) "earn" else "burn",
                     spend: String? = null, venue: String? = "Hush Heath Estate", corr: Boolean = false) =
        CashTx(date, amount, type, id, spend, venue, corr)

    @Test fun poundSignsAndRounding() {
        assertEquals("+£1.50", BalfourHistoryModel.pound(1.5))
        assertEquals("-£12.00", BalfourHistoryModel.pound(-12.0))
        assertEquals("+£0.00", BalfourHistoryModel.pound(0.0))
    }

    @Test fun dateLabelsUseTheDeviceZoneAndTheMiddleDot() {
        // 22 Oct 2026 13:05Z is 14:05 in London (BST)
        assertEquals("Thu 22 · 14:05", BalfourHistoryModel.rowLabel("2026-10-22T13:05:00Z", london))
        assertEquals("October 2026", BalfourHistoryModel.monthLabel("2026-10-22T13:05:00Z", london))
        assertEquals("September 2026", BalfourHistoryModel.monthLabel("2026-09-01T00:30:00+01:00", london))
    }

    @Test fun descriptionsNeverShowRawPosText() {
        assertEquals("Balance correction", BalfourHistoryModel.txDescription("burn", null, "x", true))
        assertEquals("Gift card received", BalfourHistoryModel.txDescription("earn", null, "gc-123", false))
        assertEquals("Balance transferred from old account", BalfourHistoryModel.txDescription("earn", null, "5l-opening-9", false))
        assertEquals("Hush Heath stay", BalfourHistoryModel.txDescription("earn", null, "hls-9", false))
        assertEquals("£23.50 spend", BalfourHistoryModel.txDescription("earn", "23.5", "o1", false))
        assertEquals("Spend at venue", BalfourHistoryModel.txDescription("earn", "0", "o1", false))
        assertEquals("Redeemed at till", BalfourHistoryModel.txDescription("burn", null, "o1", false))
        assertEquals("Refund", BalfourHistoryModel.txDescription("refund", null, "o1", false))
        assertEquals("Transaction", BalfourHistoryModel.txDescription("odd", null, "o1", false))
    }

    @Test fun groupsKeepServerOrderAndSumTheMonth() {
        // the web page does not sort: months appear as first seen, rows as sent
        val c = listOf(cash("2026-10-05T10:00:00Z", 10.0), cash("2026-10-02T10:00:00Z", -2.0), cash("2026-09-30T10:00:00Z", 5.0))
        val g = BalfourHistoryModel.groups(c, HistoryFilter.ALL, london)
        assertEquals(listOf("October 2026", "September 2026"), g.map { it.label })
        assertEquals(8.0, g[0].net, 0.0001)
        assertEquals("+£8.00", g[0].netLabel)
        assertEquals(listOf("+£10.00", "-£2.00"), g[0].rows.map { it.amount })
    }

    @Test fun filtersSplitByTheSignOfTheAmount() {
        val c = listOf(cash("2026-10-05T10:00:00Z", 10.0), cash("2026-10-02T10:00:00Z", -2.0), cash("2026-10-01T10:00:00Z", 0.0))
        fun amounts(f: HistoryFilter) = BalfourHistoryModel.groups(c, f, london).flatMap { it.rows }.map { it.amount }
        assertEquals(listOf("+£10.00", "-£2.00", "+£0.00"), amounts(HistoryFilter.ALL))
        assertEquals(listOf("+£10.00", "+£0.00"), amounts(HistoryFilter.ACTIVATED))
        assertEquals(listOf("-£2.00"), amounts(HistoryFilter.REDEEMED))
        assertTrue(BalfourHistoryModel.groups(emptyList(), HistoryFilter.ALL, london).isEmpty())
    }

    @Test fun rowKindVenueAndReceiptIds() {
        val earned = BalfourHistoryModel.row(cash("2026-10-05T10:00:00Z", 10.0, id = "o9", spend = "10"), london)
        assertEquals(RowKind.EARNED, earned.kind)
        assertEquals("Hush Heath Estate", earned.venue)
        assertEquals("o9", earned.orderId)
        assertEquals("£10.00 spend", earned.description)
        val spent = BalfourHistoryModel.row(cash("2026-10-05T10:00:00Z", -4.0, venue = null), london)
        assertEquals(RowKind.SPENT, spent.kind)
        assertEquals("Balfour Winery", spent.venue)
        val corr = BalfourHistoryModel.row(cash("2026-10-05T10:00:00Z", 3.0, id = "o1", corr = true), london)
        assertEquals(RowKind.CORRECTION, corr.kind)
        assertNull(corr.orderId) // a correction has no receipt to open
        assertNull(BalfourHistoryModel.row(cash("2026-10-05T10:00:00Z", 3.0, id = ""), london).orderId)
        assertNull(BalfourHistoryModel.row(cash("2026-10-05T10:00:00Z", 3.0, id = null), london).orderId)
    }

    @Test fun parsesTransactionsAndReceipts() {
        val tx = BalfourHistoryModel.parseTransactions(
            """{"transactions":{"cash":[{"kind":"cash","action_date":"2026-10-05T10:00:00Z","amount_gbp":"-4.50","type":"burn",
               "external_transaction_id":null,"spend_amount_gbp":null,"venue_name":"The Cellar Door","is_correction":false}],"stars":[]}}""",
        )!!
        assertEquals(-4.5, tx[0].amountGbp, 0.0001)
        assertNull(tx[0].externalTransactionId)
        assertEquals("The Cellar Door", tx[0].venueName)
        assertNull(BalfourHistoryModel.parseTransactions("not json"))

        val paused = BalfourHistoryModel.parseReceipt("""{"active":false}""")!!
        assertEquals(false, paused.active)
        val r = BalfourHistoryModel.parseReceipt(
            """{"order_id":"o1","receipt_code":"AB12","member_rating":4,"source":"pos",
               "items":[{"name":"Sparkling","quantity":2,"price_gbp":"18.00"},{"name":"Service charge","quantity":1,"price_gbp":"2.70","item_type":"service_charge"}]}""",
        )!!
        assertEquals(true, r.active)
        assertEquals("AB12", r.receiptCode)
        assertEquals(4, r.memberRating)
        assertEquals(listOf("regular", "service_charge"), r.items.map { it.itemType })
    }

    @Test fun queryMailtoMatchesTheWebSubject() {
        val r = Receipt(true, "AB12", null, "pos", listOf(ReceiptItem("x", 1, "1.00", "regular")))
        assertEquals(
            "mailto:support@mail.founders.balfourwinery.com?subject=Transaction%20query%20%E2%80%94%20REF%20AB12",
            BalfourHistoryModel.queryMailto(r, "o1"),
        )
        // no usable receipt: the order id stands in
        assertEquals(
            "mailto:support@mail.founders.balfourwinery.com?subject=Transaction%20query%20%E2%80%94%20o1",
            BalfourHistoryModel.queryMailto(null, "o1"),
        )
        assertEquals(
            "mailto:support@mail.founders.balfourwinery.com?subject=Transaction%20query%20%E2%80%94%20REF%20o1",
            BalfourHistoryModel.queryMailto(r.copy(receiptCode = null), "o1"),
        )
    }

    @Test fun avatarInitialsAndColourPair() {
        assertEquals("AB", BalfourHistoryModel.avatarInitials(" ada", "Byron"))
        assertEquals("BF", BalfourHistoryModel.avatarInitials(null, ""))
        assertEquals(0, BalfourHistoryModel.avatarPairIndex(null))
        // hashSeed("a") = 97, 97 % 5 = 2 ; same member always the same pair
        assertEquals(2, BalfourHistoryModel.avatarPairIndex("a"))
        assertEquals(BalfourHistoryModel.avatarPairIndex("member-1"), BalfourHistoryModel.avatarPairIndex("member-1"))
    }

    @Test fun siteCopyOverridesOnlyWhenNonEmpty() {
        val c = SiteCopy.parse("""{"site_copy":[{"key":"history.title","value":"Ledger"},{"key":"history.subtitle","value":""}]}""")
        assertEquals("Ledger", c.get("history.title", "Your History"))
        assertEquals("Fallback", c.get("history.subtitle", "Fallback"))
        assertEquals("Fallback", SiteCopy.parse("garbage").get("x", "Fallback"))
    }
}
