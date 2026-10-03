package com.phairplay.util

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Serves the in-app diagnostics over LAN on [PORT].
 *
 * WHY THIS EXISTS: the box sits under a TV with no adb attached nine days out
 * of ten, and `adb connect` is refused often enough that reproducing a fault
 * to capture it is slower than reading what the box already wrote down. With
 * this running, diagnosing a failed cast is:
 *
 * ```
 * curl http://192.168.10.12:8099/        # full status + recent events
 * curl http://192.168.10.12:8099/log     # the whole file, across reboots
 * curl http://192.168.10.12:8099/tail    # live, streams new lines as they happen
 * curl http://192.168.10.12:8099/clear   # start a clean capture
 * ```
 *
 * ROUTES
 * - `/`, `/dump`  — [DebugLog.dump] (status block + the in-memory ring).
 * - `/log`        — the on-disk file, including earlier boots.
 * - `/tail`       — chunked HTTP that stays open and pushes every new line.
 * - `/clear`      — empties both sinks, so a capture has a defined start.
 * - anything else — 404 with the route list.
 *
 * SECURITY, STATED PLAINLY: no authentication, and it answers anything the LAN
 * can reach. That is the trade: the alternative is no diagnostics at all. The
 * payload is this app's own debug text (IPs, SSDP counters, playback URLs) —
 * no media, no credentials, no files from outside `filesDir`. It listens only
 * while the receiver service is running, which is also the only time there is
 * anything to diagnose.
 */
object DiagnosticsServer {

    const val PORT = 8099

    @Volatile private var server: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    private val clients = CopyOnWriteArrayList<Socket>()

    /** True once [start] has a socket bound. */
    fun isRunning(): Boolean = server != null

    /**
     * Binds the port. Idempotent, and a busy port is not fatal — two PhairPlay
     * processes can coexist briefly during a restart, and dying over a debug
     * port would be absurd.
     */
    @Synchronized
    fun start() {
        if (server != null) return
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
        } catch (t: Throwable) {
            Logger.w("diagnostics port $PORT unavailable: ${t.message}")
            return
        }
        server = socket
        acceptThread = Thread({
            while (server === socket) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                clients.add(client)
                Thread({ serve(client) }, "phairplay-diag-client").apply { isDaemon = true }.start()
            }
        }, "phairplay-diag").apply { isDaemon = true }
        acceptThread?.start()
        DebugLog.log("DIAG", "诊断端口已开启 http://<本机IP>:$PORT/ （/ /log /tail /clear）")
    }

    @Synchronized
    fun stop() {
        val s = server ?: return
        server = null
        runCatching { s.close() }
        for (c in clients) runCatching { c.close() }
        clients.clear()
        acceptThread = null
        DebugLog.log("DIAG", "诊断端口已关闭")
    }

    // ─── Request handling ──────────────────────────────────────────────────

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 0
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            // Drain the rest of the request headers so the client does not see
            // a connection reset before our response arrives.
            while (true) {
                val l = reader.readLine() ?: break
                if (l.isEmpty()) break
            }
            val path = requestLine.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"
            val out = client.getOutputStream()
            when (path) {
                "/", "/dump" -> plain(out, DebugLog.dump())
                "/log" -> plain(out, DebugLog.readFile().ifBlank { "(日志为空)" })
                "/tail" -> tail(out)
                "/clear" -> {
                    DebugLog.clear()
                    plain(out, "已清空诊断日志\n")
                }
                else -> plain(
                    out,
                    "未知路径 $path\n可用: /  /log  /tail  /clear\n",
                    status = "404 Not Found"
                )
            }
            out.flush()
        } catch (t: Throwable) {
            Logger.d("diagnostics client ended: ${t.javaClass.simpleName}")
        } finally {
            clients.remove(client)
            runCatching { client.close() }
        }
    }

    private fun plain(out: OutputStream, body: String, status: String = "200 OK") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.write(
            ("HTTP/1.1 $status\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        out.write(bytes)
    }

    /**
     * Streams new lines forever using chunked transfer-encoding.
     *
     * A periodic ": keep-alive" chunk (a legal comment in chunked framing)
     * keeps the socket from being reaped by anything in between, and is also
     * how a disconnect is noticed: the write throws, and the subscription is
     * released in the finally block.
     */
    private fun tail(out: OutputStream) {
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Connection: keep-alive\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        out.flush()
        val lock = Object()
        var unsubscribe: (() -> Unit)? = null
        try {
            unsubscribe = DebugLog.addListener { line ->
                synchronized(lock) { chunk(out, line + "\n") }
            }
            chunk(out, "── tail 已连接，等待新日志 ──\n")
            while (server != null) {
                Thread.sleep(10_000)
                synchronized(lock) { chunk(out, ": keep-alive\n") }
            }
        } catch (t: Throwable) {
            Logger.d("diagnostics tail ended: ${t.javaClass.simpleName}")
        } finally {
            unsubscribe?.invoke()
        }
    }

    private fun chunk(out: OutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        out.write((Integer.toHexString(bytes.size) + "\r\n").toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
    }
}
