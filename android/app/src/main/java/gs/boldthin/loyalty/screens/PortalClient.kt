package gs.boldthin.loyalty.screens

import java.net.HttpURLConnection
import java.net.URL

// LOYALTY-NATIVE-TIERS-HISTORY-2026-10-20 (healer:4d9ec5cb, parent healer:6c9a3aed): the native tiers and
// history screens. Same rules as the member card: a faithful copy of the web page, no new visible element,
// data only from boldthings-services, no Capacitor dependency (moves unchanged into the native shell).

/**
 * Bearer GET/POST against the loyalty portal on boldthings-services. Blocking; call off the main thread.
 * The web app's own calls (src/lib/api.ts) are the contract: same paths, same bodies.
 */
class PortalClient(private val apiBase: String) {

    sealed class Result {
        data class Ok(val body: String) : Result()
        /** 401: the session is no longer valid. */
        object SignedOut : Result()
        /** The server answered with an error. */
        object Failed : Result()
        /** No answer (no signal, timeout). */
        object Unreachable : Result()
    }

    fun get(path: String, sessionToken: String): Result = call("GET", path, sessionToken, null)

    fun post(path: String, sessionToken: String, jsonBody: String): Result = call("POST", path, sessionToken, jsonBody)

    fun patch(path: String, sessionToken: String, jsonBody: String): Result = call("PATCH", path, sessionToken, jsonBody)

    private fun call(method: String, path: String, sessionToken: String, body: String?): Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(apiBase + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                // Android's HttpURLConnection accepts PATCH (Capacitor's own HTTP plugin relies on it).
                requestMethod = method
                setRequestProperty("Authorization", "Bearer $sessionToken")
                setRequestProperty("Accept", "application/json")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
            when (conn.responseCode) {
                in 200..299 -> Result.Ok(conn.inputStream.bufferedReader().use { it.readText() })
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
        const val TIMEOUT_MS = 8000
    }
}
