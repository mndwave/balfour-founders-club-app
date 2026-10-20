package gs.boldthin.loyalty.card

import android.content.Context
import android.content.SharedPreferences

/**
 * The card snapshot and session token on the device. Both live in the SharedPreferences file Capacitor's
 * Preferences plugin uses ("CapacitorStorage"), where the web app mirrors the session token
 * (balfour-founders-club src/lib/native.ts mirrorSessionToken); the snapshot is written here.
 */
class CardStore(context: Context, private val brand: CardBrand) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun sessionToken(): String? = prefs.getString(brand.sessionKey, null)?.takeIf { it.isNotBlank() }

    /** The snapshot, only if it belongs to the member signed in on this device. */
    fun load(): CardSnapshot? {
        val token = sessionToken() ?: return null
        val card = CardSnapshot.fromJson(prefs.getString(brand.cacheKey, null), brand.defaultQrPrefix) ?: return null
        return card.takeIf { it.tag == CardSnapshot.tagOf(token) }
    }

    fun save(card: CardSnapshot) {
        prefs.edit().putString(brand.cacheKey, card.toJson()).apply()
    }

    companion object {
        const val PREFS_FILE = "CapacitorStorage"
    }
}
