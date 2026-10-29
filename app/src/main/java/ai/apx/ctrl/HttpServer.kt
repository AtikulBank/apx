package ai.apx.ctrl

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

class HttpServer(private val port: Int, private val handler: (String, Map<String, String>) -> Resp) {

    data class Resp(val code: Int, val type: String, val body: ByteArray)

    private var ss: ServerSocket? = null
    @Volatile private var running = false

    fun start(): Boolean = try {
        ss = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"))
        running = true
        Thread { loop() }.also { it.isDaemon = true; it.start() }
        true
    } catch (e: Exception) { false }

    fun stop() {
        running = false
        try { ss?.close() } catch (_: Exception) {}
    }

    private fun loop() {
        while (running) {
            val s: Socket? = try { ss?.accept() } catch (e: Exception) {
                if (running) continue else break
            }
            if (s == null) break
            Thread { serve(s) }.also { it.isDaemon = true }.start()
        }
    }

    private fun serve(s: Socket) {
        try {
            s.soTimeout = 8000
            val br = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
            val line = br.readLine() ?: return
            while (true) {
                val h = br.readLine() ?: break
                if (h.isEmpty()) break
            }
            val parts = line.split(" ")
            if (parts.size < 2) return
            val url = parts[1]
            val qi = url.indexOf('?')
            val path = if (qi >= 0) url.substring(0, qi) else url
            val q = HashMap<String, String>()
            if (qi >= 0) for (kv in url.substring(qi + 1).split("&")) {
                val i = kv.indexOf('=')
                if (i > 0) q[kv.substring(0, i)] = URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            }
            val r = try { handler(path, q) }
            catch (e: Exception) { Resp(500, "text/plain", ("ERR " + e.message).toByteArray()) }
            val head = "HTTP/1.1 ${r.code} X\r\nContent-Type: ${r.type}\r\n" +
                       "Content-Length: ${r.body.size}\r\nConnection: close\r\n\r\n"
            val o = s.getOutputStream()
            o.write(head.toByteArray(Charsets.ISO_8859_1))
            o.write(r.body)
            o.flush()
        } catch (_: Exception) {
        } finally { try { s.close() } catch (_: Exception) {} }
    }
}