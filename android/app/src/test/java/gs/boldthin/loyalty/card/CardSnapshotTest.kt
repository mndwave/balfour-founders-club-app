package gs.boldthin.loyalty.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardSnapshotTest {
    private val token = "session-token-abcdefgh"

    // Balfour: wallet_qr_prefix is empty, so qr.value is the bare rotating token or the BF member number.
    private fun payload(value: String, rotating: Boolean, displayName: String? = "Founder") = """
        {"tenant":"balfour","member":{"id":"m1","member_number":"BF100234","first_name":"Ann","last_name":"Le Brun"},
         "tier":{"name":"founder","display_name":${displayName?.let { "\"$it\"" } ?: "null"},"colour_hex":null},
         "stars_balance":12,"cash_balance":4.5,"point_balance":0,
         "qr":{"value":"$value","prefix":"","rotating":$rotating,"fallback_member_number":"BF100234"},
         "fetched_at":"2026-10-20T07:00:00Z"}
    """.trimIndent()

    @Test fun rotatingTokenIsTheScanValue() {
        val c = CardSnapshot.fromCardPayload(payload("48213907", true), token, 42L, "")!!
        assertEquals("48213907", c.qrToken)
        assertEquals("48213907", c.scanValue)
        assertEquals("BF100234", c.memberNumber)
        assertEquals("Ann Le Brun", c.name)
        assertEquals("abcdefgh", c.tag)
        assertEquals(42L, c.syncedAt)
    }

    @Test fun memberNumberScanValueStoresNoToken() {
        val c = CardSnapshot.fromCardPayload(payload("BF100234", false, null), token, 1L, "")!!
        assertNull(c.qrToken)
        assertEquals("BF100234", c.scanValue)
        assertEquals("founder", c.tier)
    }

    @Test fun roundTripsTheSnapshotShape() {
        val saved = """{"v":1,"tag":"abcdefgh","member_number":"BF100234","qr_prefix":"","qr_token":"48213907",
            "name":"Ann","tier":"Founder","cash_balance":0,"stars":3,"synced_at":7}"""
        val c = CardSnapshot.fromJson(saved, "")!!
        assertEquals("48213907", c.scanValue)
        assertEquals(c, CardSnapshot.fromJson(c.toJson(), ""))
        assertNull(CardSnapshot.fromJson("""{"v":2,"member_number":"1"}""", ""))
    }

    @Test fun brandPointsAtBoldthingsServicesOnly() {
        val b = CardBrand.BALFOUR
        assertEquals("https://api.boldthin.gs", b.apiBase)
        assertEquals("/balfour/loyalty/portal/card", b.cardPath)
        assertEquals("balfour_member_token", b.sessionKey)
    }
}
