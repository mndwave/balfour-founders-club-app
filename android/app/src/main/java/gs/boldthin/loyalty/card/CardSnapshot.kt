package gs.boldthin.loyalty.card

import org.json.JSONObject

/**
 * The member card as kept on the device. Same JSON shape (v1) as randalls-rewards src/lib/offline-card.ts.
 * Balfour's web app does not write one; this card writes it itself after every live fetch
 * (CardStore, key bf_offline_card_v1) and the app also refreshes it in the background at launch.
 */
data class CardSnapshot(
    /** Tail of the session token: a snapshot that is not this device's member is ignored. */
    val tag: String,
    val memberNumber: String,
    val qrPrefix: String,
    /** Latest rotating scan token; null means the member number is the scan value. */
    val qrToken: String?,
    val name: String,
    val tier: String,
    val cashBalance: Double,
    val stars: Long,
    /** When the card data was last fetched live (epoch ms). */
    val syncedAt: Long,
) {
    /** Exactly what the web card encodes: prefix + rotating token, else prefix + member number. */
    val scanValue: String get() = qrPrefix + (qrToken ?: memberNumber)

    fun toJson(): String = JSONObject()
        .put("v", 1)
        .put("tag", tag)
        .put("member_number", memberNumber)
        .put("qr_prefix", qrPrefix)
        .put("qr_token", qrToken ?: JSONObject.NULL)
        .put("name", name)
        .put("tier", tier)
        .put("cash_balance", cashBalance)
        .put("stars", stars)
        .put("synced_at", syncedAt)
        .toString()

    companion object {
        const val TAG_LENGTH = 8

        fun tagOf(sessionToken: String): String = sessionToken.takeLast(TAG_LENGTH)

        /** Parses a v1 snapshot; null for anything malformed or from another version. */
        fun fromJson(raw: String?, defaultPrefix: String): CardSnapshot? {
            if (raw.isNullOrBlank()) return null
            return try {
                val o = JSONObject(raw)
                if (o.optInt("v") != 1) return null
                val number = o.optString("member_number")
                if (number.isBlank()) return null
                CardSnapshot(
                    tag = o.optString("tag"),
                    memberNumber = number,
                    qrPrefix = o.optString("qr_prefix").ifBlank { defaultPrefix },
                    qrToken = o.optStringOrNull("qr_token"),
                    name = o.optString("name"),
                    tier = o.optString("tier"),
                    cashBalance = o.optDouble("cash_balance", 0.0),
                    stars = o.optLong("stars", 0),
                    syncedAt = o.optLong("synced_at", 0),
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Maps GET /loyalty/portal/card onto the snapshot. `qr.value` is the full scan value the till reads
         * (Balfour: the BF number, never a bare token); when it is not the rotating token it is
         * prefix + member number and qr_token stays null, as the web app stores it.
         */
        fun fromCardPayload(raw: String, sessionToken: String, now: Long, defaultPrefix: String): CardSnapshot? {
            return try {
                val o = JSONObject(raw)
                val member = o.getJSONObject("member")
                val number = member.optString("member_number")
                if (number.isBlank()) return null
                val qr = o.getJSONObject("qr")
                val prefix = qr.optString("prefix").ifBlank { defaultPrefix }
                val value = qr.optString("value")
                val token = if (qr.optBoolean("rotating") && value.startsWith(prefix) && value.length > prefix.length) {
                    value.substring(prefix.length)
                } else null
                val tier = o.optJSONObject("tier")
                val first = member.optStringOrNull("first_name") ?: ""
                val last = member.optStringOrNull("last_name") ?: ""
                CardSnapshot(
                    tag = tagOf(sessionToken),
                    memberNumber = number,
                    qrPrefix = prefix,
                    qrToken = token,
                    name = "$first $last".trim(),
                    tier = tier?.optStringOrNull("display_name") ?: tier?.optStringOrNull("name") ?: "",
                    cashBalance = o.optDouble("cash_balance", 0.0),
                    stars = o.optLong("stars_balance", 0),
                    syncedAt = now,
                )
            } catch (e: Exception) {
                null
            }
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}
