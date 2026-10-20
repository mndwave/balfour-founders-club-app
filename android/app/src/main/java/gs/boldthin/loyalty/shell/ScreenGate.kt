package gs.boldthin.loyalty.shell

import org.json.JSONObject

// LOYALTY-NATIVE-SHELL-2026-10-20 (healer:1c19eae5, parent healer:cdefd3a3): the per-screen native/web gate of the
// loyalty shells. A line-for-line port of seq1-native-kit core-rs src/screen_gate.rs (healer:26a87acc) rather than the
// UniFFI library: that .so is built for arm64-v8a + x86_64 only, and the Play build of this app still serves 32-bit
// phones, so linking it would drop them (or crash them). ScreenGateTest runs the kit's shared vectors
// (core-rs/tests/vectors/screen_gate.json, copied into test resources and checked equal to the kit's) so the two
// implementations cannot drift. Rules, in order (contract: boldthings-services definitions/loyalty_screens/README.md):
//  1. not in the build -> hosted page;  2. offline-critical -> native, a live kill switch only degrades it;
//  3. registry never fetched -> native;  4. build older than the registry -> hosted page;
//  5. kill switch live (expiry in the future on the device clock) -> hosted page;  6. otherwise native.

/** One screen the build implements (an entry of the embedded screens.json). */
data class BuiltScreen(val screenId: String, val version: Int, val offlineCritical: Boolean)

/** One registry row as the device holds it. [forceWebUntil] is Unix seconds, 0 = none. */
data class ScreenGateEntry(
    val screenId: String,
    val version: Int,
    val forceWeb: Boolean,
    val forceWebUntil: Long,
    val offlineCritical: Boolean,
)

/** The registry body as last fetched. [fetchedAt] is Unix seconds. */
data class ScreenGateRegistry(val tenant: String, val revision: Long, val fetchedAt: Long, val entries: List<ScreenGateEntry>)

enum class ScreenRoute { NATIVE, WEB }

/** [reason]: not_in_build, offline_critical, no_registry, stale_build, force_web, native (same codes as the kit). */
data class ScreenGateDecision(val route: ScreenRoute, val degraded: Boolean, val reason: String)

object ScreenGate {

    fun decide(screenId: String, build: List<BuiltScreen>, registry: ScreenGateRegistry?, now: Long): ScreenGateDecision {
        val built = build.firstOrNull { it.screenId == screenId }
            ?: return ScreenGateDecision(ScreenRoute.WEB, false, "not_in_build")
        val row = registry?.entries?.firstOrNull { it.screenId == screenId }
        val switchLive = row != null && row.forceWeb && row.forceWebUntil > now
        if (built.offlineCritical || row?.offlineCritical == true) {
            return ScreenGateDecision(ScreenRoute.NATIVE, switchLive, "offline_critical")
        }
        if (registry == null) return ScreenGateDecision(ScreenRoute.NATIVE, false, "no_registry")
        return when {
            row != null && built.version < row.version -> ScreenGateDecision(ScreenRoute.WEB, false, "stale_build")
            row != null && switchLive -> ScreenGateDecision(ScreenRoute.WEB, false, "force_web")
            else -> ScreenGateDecision(ScreenRoute.NATIVE, false, "native")
        }
    }

    /** `{"schema":1,"brand":"…","screens":{"card":{"version":1,"offline_critical":true}}}`; null when malformed. */
    fun parseManifest(json: String): List<BuiltScreen>? = try {
        val screens = JSONObject(json).getJSONObject("screens")
        screens.keys().asSequence().map { id ->
            val s = screens.getJSONObject(id)
            val version = s.get("version")
            require(version is Int && version >= 1) { "screen $id: version must be an integer >= 1" }
            BuiltScreen(id, version, s.optBoolean("offline_critical", false))
        }.toList()
    } catch (e: Exception) {
        null
    }

    /** GET /loyalty/portal/screens (and the /balfour twin). RFC 3339 UTC timestamps; [now] when fetched_at is absent. */
    fun parseRegistry(json: String, now: Long): ScreenGateRegistry? = try {
        val v = JSONObject(json)
        val rows = v.getJSONArray("screens")
        val entries = (0 until rows.length()).map { i ->
            val r = rows.getJSONObject(i)
            val id = r.getString("screen_id")
            val version = r.get("version")
            require(version is Int && version >= 1) { "screen $id: bad version" }
            val until = if (r.isNull("force_web_until") || !r.has("force_web_until")) 0L
            else rfc3339ToUnix(r.getString("force_web_until")) ?: error("screen $id: bad force_web_until")
            ScreenGateEntry(id, version, r.optBoolean("force_web", false), until, r.optBoolean("offline_critical", false))
        }
        ScreenGateRegistry(
            tenant = v.optString("tenant", ""),
            revision = v.optLong("revision", 0),
            fetchedAt = (if (v.isNull("fetched_at")) null else rfc3339ToUnix(v.optString("fetched_at"))) ?: now,
            entries = entries,
        )
    } catch (e: Exception) {
        null
    }

    /** `2026-10-20T08:04:09.137823166Z` / `…+00:00` -> Unix seconds (fraction dropped); no java.time (minSdk 24). */
    fun rfc3339ToUnix(s: String): Long? {
        if (s.length < 20 || s[4] != '-' || s[7] != '-' || s[10] !in "Tt " || s[13] != ':' || s[16] != ':') return null
        fun num(a: Int, b: Int): Long? = s.substring(a, b).takeIf { t -> t.all { it.isDigit() } }?.toLongOrNull()
        val y = num(0, 4) ?: return null
        val mo = num(5, 7) ?: return null
        val d = num(8, 10) ?: return null
        val h = num(11, 13) ?: return null
        val mi = num(14, 16) ?: return null
        val se = num(17, 19) ?: return null
        if (mo !in 1..12 || d !in 1..31 || h > 23 || mi > 59 || se > 60) return null
        var rest = s.substring(19)
        if (rest.startsWith(".")) {
            val n = rest.drop(1).takeWhile { it.isDigit() }.length
            if (n == 0) return null
            rest = rest.substring(1 + n)
        }
        val offset = when {
            rest == "Z" || rest == "z" -> 0L
            rest.length == 6 && (rest[0] == '+' || rest[0] == '-') && rest[3] == ':' -> {
                val hh = rest.substring(1, 3).toLongOrNull() ?: return null
                val mm = rest.substring(4, 6).toLongOrNull() ?: return null
                val mins = hh * 60 + mm
                if (rest[0] == '-') -mins * 60 else mins * 60
            }
            else -> return null
        }
        // Days from civil (Howard Hinnant), as the kit.
        val yy = if (mo <= 2) y - 1 else y
        val era = Math.floorDiv(yy, 400L)
        val yoe = yy - era * 400
        val mp = (mo + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        val days = era * 146_097 + doe - 719_468
        return days * 86_400 + h * 3600 + mi * 60 + se - offset
    }
}
