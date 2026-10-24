package gs.boldthin.loyalty.rendergate

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Answers the native screens' API calls with the responses recorded in the render gate's HAR, the same data the React
 * reference was rendered from (scripts/render-gate). Lookup is by path + query whatever the host, so a native call to
 * the API and the web page's same-origin proxy of it (e.g. /api/admin/loyalty/site-copy) read the same recording.
 * A bare HTTP/1.0 responder on java.net (the JDK's com.sun.net.httpserver is not on the Android unit-test classpath).
 */
class HarServer(har: File, private val aliases: Map<String, String> = emptyMap(), extra: Map<String, String> = emptyMap()) : AutoCloseable {
    private val bodies = HashMap<String, String>()
    val served: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // IPv4 127.0.0.1 explicitly, the host in base and in the debug cleartext rule: Android's getLoopbackAddress() is ::1,
    // so on a device the server listened on ::1 while the client dialled 127.0.0.1 (refused; the JVM returns 127.0.0.1).
    private val socket = ServerSocket(0, 16, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))

    init {
        val entries = JSONObject(har.readText()).getJSONObject("log").getJSONArray("entries")
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            if (e.getJSONObject("request").getString("method") != "GET") continue
            val content = e.getJSONObject("response").getJSONObject("content")
            val text = content.optString("text", "")
            val body = if (content.optString("encoding") == "base64") String(Base64.getDecoder().decode(text)) else text
            bodies[key(URI(e.getJSONObject("request").getString("url")))] = body
        }
        bodies.putAll(extra)
        thread(isDaemon = true, name = "har-server") {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: Exception) { break }
                thread(isDaemon = true) {
                    client.use { c ->
                        val reader = c.getInputStream().bufferedReader()
                        val target = reader.readLine()?.split(" ")?.getOrNull(1) ?: return@use
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        val k = key(URI(target))
                        val body = bodies[k] ?: aliases[k]?.let { bodies[it] }
                        val bytes = (body ?: "{}").toByteArray()
                        served += k
                        val head = "HTTP/1.0 ${if (body == null) "404 Not Found" else "200 OK"}\r\n" +
                            "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        c.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
                    }
                }
            }
        }
    }

    val base: String get() = "http://127.0.0.1:${socket.localPort}"

    override fun close() = socket.close()

    private fun key(u: URI) = u.rawPath + (u.rawQuery?.let { "?$it" } ?: "")
}
