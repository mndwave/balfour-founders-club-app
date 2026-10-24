package gs.boldthin.loyalty.shell

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The kit's shared gate vectors (seq1-native-kit core-rs/tests/vectors/screen_gate.json), run against the Kotlin port. */
class ScreenGateTest {
    private val vectors = javaClass.classLoader!!.getResource("loyalty/screen_gate_vectors.json")!!.readText()

    @Test fun vectorsAreTheKitsOwn() {
        // The copy must be the kit's file byte for byte, so the port is held to the same cases as core-rs.
        val kit = File(System.getProperty("user.home"), "seq1-native-kit/core-rs/tests/vectors/screen_gate.json")
        if (kit.exists()) assertEquals("test vectors drifted from the kit: copy core-rs/tests/vectors/screen_gate.json again", kit.readText(), vectors)
    }

    @Test fun everySharedCase() {
        val root = JSONObject(vectors)
        val now = root.getLong("now")
        val cases = root.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val b = c.getJSONArray("build")
            val build = (0 until b.length()).map { j ->
                val o = b.getJSONObject(j)
                BuiltScreen(o.getString("screen_id"), o.getInt("version"), o.getBoolean("offline_critical"))
            }
            val registry = if (c.isNull("registry")) null else c.getJSONObject("registry").getJSONArray("entries").let { e ->
                ScreenGateRegistry("t", 0, now, (0 until e.length()).map { j ->
                    val o = e.getJSONObject(j)
                    ScreenGateEntry(o.getString("screen_id"), o.getInt("version"), o.getBoolean("force_web"), o.getLong("force_web_until"), o.getBoolean("offline_critical"))
                })
            }
            val got = ScreenGate.decide(c.getString("screen"), build, registry, now)
            val want = c.getJSONObject("expect")
            val name = c.getString("name")
            assertEquals(name, want.getString("route"), got.route.name.lowercase())
            assertEquals(name, want.getBoolean("degraded"), got.degraded)
            assertEquals(name, want.getString("reason"), got.reason)
        }
        assertEquals(14, cases.length())
    }

    @Test fun rfc3339AsTheKit() {
        assertEquals(0L, ScreenGate.rfc3339ToUnix("1970-01-01T00:00:00Z"))
        assertEquals(1_792_483_449L, ScreenGate.rfc3339ToUnix("2026-10-20T08:04:09.137823166Z"))
        assertEquals(1_792_483_449L, ScreenGate.rfc3339ToUnix("2026-10-20T09:04:09+01:00"))
        assertEquals(951_782_400L, ScreenGate.rfc3339ToUnix("2000-02-29T00:00:00Z"))
        assertNull(ScreenGate.rfc3339ToUnix("2026-10-20"))
        assertNull(ScreenGate.rfc3339ToUnix("2026-13-20T00:00:00Z"))
    }

    @Test fun parsesTheLiveRegistryShape() {
        val body = """{"fetched_at":"2026-10-20T08:04:09.137823166Z","revision":1792480750,"screens":[
            {"degraded":false,"force_web":false,"force_web_until":null,"offline_critical":true,"screen_id":"card","version":1},
            {"degraded":false,"force_web":true,"force_web_until":"2026-10-21T00:00:00Z","offline_critical":false,"screen_id":"tiers","version":2}],
            "tenant":"randalls"}"""
        val r = ScreenGate.parseRegistry(body, 5)!!
        assertEquals("randalls", r.tenant)
        assertEquals(1_792_483_449L, r.fetchedAt)
        assertEquals(2, r.entries.size)
        assertEquals(1_792_540_800L, r.entries[1].forceWebUntil)
        assertEquals(0L, r.entries[0].forceWebUntil)
        assertNull(ScreenGate.parseRegistry("{}", 5))
    }

    @Test fun manifestAsTheEmitterWritesIt() {
        assertNull(ScreenGate.parseManifest("""{"screens":{"card":{"version":0}}}"""))
        val m = ScreenGate.parseManifest(
            """{"schema":1,"brand":"randalls","screens":{"card":{"version":1,"offline_critical":true},"tiers":{"version":1,"offline_critical":false}}}""",
        )!!
        assertEquals(listOf(BuiltScreen("card", 1, true), BuiltScreen("tiers", 1, false)), m.sortedBy { it.screenId })
    }

    @Test fun brandPathsAreTheContracts() {
        // Each ShellBrand.screenPaths entry must be one of screens.toml's [screens.<id>.web] paths for that brand, with the same
        // id. A subset: on Android only the card opens from the device; tiers and history are the live pages
        // (CARD-PAGE-WEBVIEW-2026-10-24, healer:4b3d7c53), though the contract still lists them.
        val toml = File(System.getProperty("user.home"), "boldthings-services/definitions/loyalty_screens/screens.toml")
        if (!toml.exists()) return
        for ((name, brand) in mapOf("randalls" to ShellBrand.RANDALLS, "balfour" to ShellBrand.BALFOUR)) {
            val want = mutableMapOf<String, String>()
            var web: String? = null
            toml.readLines().forEach { line ->
                Regex("""^\[screens\.([a-z0-9_]+)\.web\]""").find(line)?.let { web = it.groupValues[1]; return@forEach }
                if (line.startsWith("[")) web = null
                val id = web ?: return@forEach
                Regex("^" + name + """\s*=\s*"([^"]+)"""").find(line)?.let { want[it.groupValues[1]] = id }
            }
            for ((path, id) in brand.screenPaths) assertEquals("$name $path", want[path], id)
            assertEquals(name, "card", brand.screenPaths.values.single())
        }
    }

    @Test fun balfourPathsFollowTheContract() {
        val b = ShellBrand.BALFOUR
        assertEquals("card", b.screenFor("/my-id"))
        assertEquals("card", b.screenFor("/my-id/?x=1"))
        assertNull(b.screenFor("/history#top"))
        assertNull(b.screenFor("/qr"))
        assertNull(b.screenFor("/tiers"))
        assertNull(b.screenFor("/dashboard"))
    }

    @Test fun brandPathsFollowTheContract() {
        val b = ShellBrand.RANDALLS
        assertNull(b.screenFor("/tiers"))
        assertEquals("card", b.screenFor("/qr"))
        assertEquals("card", b.screenFor("/qr/?embed=1#x"))
        assertNull(b.screenFor("/history"))
        assertNull(b.screenFor("/rewards"))
        assertNull(b.screenFor("/"))
    }
}
