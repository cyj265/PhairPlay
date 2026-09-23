package com.phairplay.util

import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-app debug log + live DLNA/SSDP diagnostics, shown in the Settings →
 * "调试信息" dialog. Lets us see on-device whether SSDP is announcing, whether
 * M-SEARCH queries arrive, and what the HTTP layer answers — without logcat
 * access on the user's device.
 */
object DebugLog {

    private const val MAX = 140
    private val entries = CopyOnWriteArrayList<String>()

    @Volatile var ssdpStatus: String = "未启动"
    @Volatile var ssdpLocation: String = "—"
    @Volatile var lastNotifyAt: String = "—"
    @Volatile var lastSearchAt: String = "—"
    @Volatile var lastSearchFrom: String = "—"
    @Volatile var lastSearchSt: String = "—"
    @Volatile var lastResponseAt: String = "—"
    @Volatile var registryDiag: String = "—"
    @Volatile var lastError: String = "—"

    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    @JvmStatic
    fun log(tag: String, msg: String) {
        val line = "[${fmt.format(Date())}] $tag: $msg"
        entries.add(line)
        while (entries.size > MAX) {
            entries.removeAt(0)
        }
    }

    @JvmStatic
    fun clear() {
        entries.clear()
    }

    /** Current time as "HH:mm:ss" for the SSDP status fields. */
    @JvmStatic
    fun now(): String = fmt.format(Date())

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
