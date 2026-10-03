package com.phairplay.util

import android.content.Context
import java.io.File
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * In-app debug log + live DLNA/SSDP diagnostics, shown in the Settings →
 * "调试信息" dialog. Lets us see on-device whether SSDP is announcing, whether
 * M-SEARCH queries arrive, and what the HTTP layer answers — without logcat
 * access on the user's device.
 *
 * Two sinks, because they answer different questions:
 *
 * 1. **The in-memory ring** ([MAX] lines) feeds the settings dialog.
 * 2. **A file** (`filesDir/phairplay-debug.log`) survives the process and the
 *    boot, so a failure can be read *after* it happened. This is the one that
 *    matters in the field: the box is three metres away with no adb, and a
 *    cast that failed ten minutes ago has already scrolled out of the ring.
 *    Read it over LAN at `http://<box>:8099/log` — see [DiagnosticsServer].
 *
 * Rotation is one generation (`.1`), size-capped, and the file is appended
 * across boots so a problem that only shows up every few days is still there.
 */
object DebugLog {

    private const val MAX = 250

    /** Rotate once the live file passes this; keeps `.1` as the previous run. */
    private const val MAX_FILE_BYTES = 512 * 1024
    private const val FILE_NAME = "phairplay-debug.log"

    private val entries = CopyOnWriteArrayList<String>()

    @Volatile var ssdpStatus: String = "未启动"
    @Volatile var ssdpLocation: String = "—"
    @Volatile var lastNotifyAt: String = "—"
    @Volatile var lastSearchAt: String = "—"
    @Volatile var lastSearchFrom: String = "—"
    @Volatile var lastSearchSt: String = "—"
    @Volatile var lastResponseAt: String = "—"

    /**
     * Total M-SEARCH probes received since the SSDP layer started.
     *
     * This is the single most useful split in the whole diagnostic: a phone
     * actively looking for renderers sends an M-SEARCH every couple of seconds,
     * so after a minute of searching, a count of 0 proves the probes never reach
     * the box at all (routing, AP isolation, or a deaf face) — whereas a healthy
     * count with the device still missing points at the response/parser side.
     */
    @Volatile var searchTotal: Int = 0

    /**
     * Per-face probe distribution, e.g. "wlan0=12 eth0=0".
     *
     * On a dual-homed box this shows directly which NIC the phone's traffic
     * actually arrives on, and whether any face is silently deaf.
     */
    @Volatile var searchByFace: String = "—"
    @Volatile var registryDiag: String = "—"
    @Volatile var lastError: String = "—"

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val stampFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /**
     * Single-threaded so writes land in the order they were logged, and so a
     * log call never blocks on the filesystem (log() runs on the main thread).
     */
    private val sink = Executors.newSingleThreadExecutor { r ->
        Thread(r, "phairplay-log-sink").apply { isDaemon = true }
    }

    @Volatile private var file: File? = null

    /**
     * Live subscribers — [DiagnosticsServer] tails them to a LAN client.
     * Copy-on-write because writes are rare (one per connected `curl`) and
     * reads happen on every log line.
     */
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /**
     * Receives every future log line until the returned lambda is called.
     *
     * Used by `/tail`, so a diagnosis can be watched live from a laptop
     * instead of reproduced, screenshotted and transcribed.
     */
    @JvmStatic
    fun addListener(listener: (String) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /** Installed once from [com.phairplay.PhairPlayApp]; safe to call twice. */
    @Synchronized
    @JvmStatic
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, FILE_NAME)
        file = f
        val banner = buildString {
            append("──────── PhairPlay 日志启动 ")
            append(stampFmt.format(Date()))
            append(" ────────\n")
            append("设备: ").append(android.os.Build.MODEL)
            append("  Android ").append(android.os.Build.VERSION.RELEASE)
            append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
        }
        sink.execute {
            runCatching { f.appendText(banner) }
                .onFailure { Logger.w("debug log file unavailable: ${it.message}") }
        }
    }

    @Synchronized
    @JvmStatic
    fun log(tag: String, msg: String) {
        val line = "[${fmt.format(Date())}] $tag: $msg"
        entries.add(line)
        while (entries.size > MAX) {
            entries.removeAt(0)
        }
        val target = file
        if (target != null) {
            sink.execute { appendToFile(target, line) }
        }
        for (l in listeners) {
            runCatching { l(line) }
        }
    }

    private fun appendToFile(target: File, line: String) {
        try {
            if (target.length() > MAX_FILE_BYTES) {
                val previous = File(target.parentFile, "$FILE_NAME.1")
                runCatching { if (previous.exists()) previous.delete() }
                runCatching { target.renameTo(previous) }
            }
            target.appendText(line + "\n")
        } catch (t: Throwable) {
            // A log that cannot be written must never take the receiver down.
            Logger.w("debug log write failed: ${t.message}")
        }
    }

    @JvmStatic
    fun clear() {
        entries.clear()
        val target = file
        if (target != null) {
            sink.execute { runCatching { target.writeText("") } }
        }
    }

    /** Current time as "HH:mm:ss" for the SSDP status fields. */
    @JvmStatic
    fun now(): String = fmt.format(Date())

    /** The on-disk log, newest last. Empty string when it is not available. */
    @JvmStatic
    fun readFile(): String = file?.let { f ->
        runCatching {
            if (!f.exists()) "" else f.readText()
        }.getOrDefault("")
    } ?: ""

    /** Full diagnostic text shown in the settings dialog. */
    @JvmStatic
    fun dump(): String {
        val sb = StringBuilder()
        sb.append("SSDP 状态: ").append(ssdpStatus).append('\n')
        sb.append("LOCATION: ").append(ssdpLocation).append('\n')
        sb.append("最近 NOTIFY: ").append(lastNotifyAt).append('\n')
        sb.append("最近 M-SEARCH: ").append(lastSearchAt)
            .append(" 来自 ").append(lastSearchFrom)
            .append(" ST=").append(lastSearchSt).append('\n')
        sb.append("收到 M-SEARCH 总数: ").append(searchTotal)
            .append("  各面分布: ").append(searchByFace).append('\n')
        sb.append("最近响应: ").append(lastResponseAt).append('\n')
        sb.append("registry: ").append(registryDiag).append('\n')
        sb.append("最近错误: ").append(lastError).append('\n')
        sb.append("── 网络接口 ──\n").append(interfacesText()).append('\n')
        sb.append("── 事件日志 ──\n")
        for (e in entries) {
            sb.append(e).append('\n')
        }
        return sb.toString().trim()
    }

    @JvmStatic
    fun interfacesText(): String {
        val sb = StringBuilder()
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return "无"
            for (ni in ifs) {
                if (!ni.isUp) {
                    continue
                }
                val ipv4 = ni.inetAddresses.toList()
                    .filter { it is java.net.Inet4Address && !it.isLoopbackAddress }
                    .map { it.hostAddress }
                if (ipv4.isNotEmpty()) {
                    sb.append(ni.name).append(": ").append(ipv4.joinToString(",")).append('\n')
                }
            }
        } catch (e: Exception) {
            sb.append("错误: ").append(e.message).append('\n')
        }
        return sb.toString().trim()
    }
}
