package gs.boldthin.loyalty.card

import java.net.HttpURLConnection
import java.net.URL

/**
 * GET /loyalty/portal/card from boldthings-services. Blocking; call it off the main thread. Short timeouts:
 * the card is already on screen from the cache, this only freshens it.
 */
class CardClient(private val brand: CardBrand) {

    sealed class Result {
        data class Ok(val card: CardSnapshot) : Result()
        /** The session is no longer valid; the snapshot must not be refreshed with it. */
        object SignedOut : Result()
        /** The server answered with an error: not an offline state (the web page does not say so either). */
        object Failed : Result()
        /** No answer at all (no signal, timeout): the card says it is the saved one. */
        object Unreachable : Result()
    }

    fun fetch(sessionToken: String, now: Long = System.currentTimeMillis()): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(brand.apiBase + brand.cardPath).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                setRequestProperty("Authorization", "Bearer $sessionToken")
                setRequestProperty("Accept", "application/json")
            }
            when (conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    CardSnapshot.fromCardPayload(body, sessionToken, now, brand.defaultQrPrefix)
                        ?.let { Result.Ok(it) } ?: Result.Failed
                }
                401 -> Result.SignedOut
                else -> Result.Failed
            }
        } catch (e: java.io.IOException) {
            Result.Unreachable
        } catch (e: Exception) {
            Result.Failed
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        const val TIMEOUT_MS = 4000
    }
}
