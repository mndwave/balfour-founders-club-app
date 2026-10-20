package gs.boldthin.loyalty.shell

import android.content.Context
import android.content.SharedPreferences
import java.net.HttpURLConnection
import java.net.URL

/**
 * Decides, per hosted path, whether the shell draws the screen natively. Inputs are only the build's embedded
 * screens.json (assets) and the registry last fetched from boldthings-services, cached on the device with the time it
 * was fetched so the decision holds offline (and a cached kill switch still lapses on the device clock).
 */
class ScreenRouter(context: Context, private val brand: ShellBrand) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val build: List<BuiltScreen> = try {
        context.assets.open(MANIFEST_ASSET).bufferedReader().use { it.readText() }.let { ScreenGate.parseManifest(it) }
    } catch (e: Exception) {
        null
    } ?: emptyList()

    @Volatile private var registry: ScreenGateRegistry? = prefs.getString(KEY_REGISTRY, null)?.let {
        ScreenGate.parseRegistry(it, prefs.getLong(KEY_FETCHED_AT, 0))
    }

    /** The screens this build implements (for logs and the shell's tests). */
    val builtScreens: List<BuiltScreen> get() = build

    fun decide(screenId: String, now: Long = System.currentTimeMillis() / 1000): ScreenGateDecision =
        ScreenGate.decide(screenId, build, registry, now)

    /** The native screen id for a hosted path, or null when the hosted page should show. */
    fun nativeScreenFor(path: String): String? {
        val id = brand.screenFor(path) ?: return null
        return id.takeIf { decide(it).route == ScreenRoute.NATIVE }
    }

    /** Hosted paths currently drawn natively (handed to the page's link interceptor). */
    fun nativePaths(): List<String> = brand.screenPaths.filter { decide(it.value).route == ScreenRoute.NATIVE }.keys.toList()

    /** Blocking: refresh the registry from boldthings-services; keeps the cached copy on any failure. */
    fun refresh(): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(brand.registryUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != 200) return false
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val now = System.currentTimeMillis() / 1000
            val parsed = ScreenGate.parseRegistry(body, now) ?: return false
            registry = parsed
            prefs.edit().putString(KEY_REGISTRY, body).putLong(KEY_FETCHED_AT, now).apply()
            true
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        const val MANIFEST_ASSET = "screens.json"
        private const val PREFS = "LoyaltyShell"
        private const val KEY_REGISTRY = "screens_registry_v1"
        private const val KEY_FETCHED_AT = "screens_registry_fetched_at"
        private const val TIMEOUT_MS = 8000
    }
}
