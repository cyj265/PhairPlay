package com.phairplay.sender

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import java.net.InetAddress

/**
 * SenderActivity — pick the TV, grant screen capture, start streaming.
 *
 * Discovery is plain mDNS: the TV advertises `_airmirror._tcp` with the port it
 * actually bound, so the phone never has to guess. A manual IP is kept as a
 * fallback for networks where multicast is blocked (some routers filter it).
 */
class SenderActivity : Activity() {

    private data class Device(val name: String, val host: String, val port: Int) {
        override fun toString(): String = "$name\n$host:$port"
    }

    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var hostInput: EditText

    private val devices = mutableListOf<Device>()
    private lateinit var adapter: ArrayAdapter<Device>

    private var nsd: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolving = mutableSetOf<String>()
    private var pendingDevice: Device? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sender)

        status = findViewById(R.id.text_status)
        list = findViewById(R.id.list_devices)
        hostInput = findViewById(R.id.edit_host)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, devices)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            devices.getOrNull(position)?.let { connect(it) }
        }

        findViewById<Button>(R.id.btn_refresh).setOnClickListener { startDiscovery() }
        findViewById<Button>(R.id.btn_connect).setOnClickListener {
            val raw = hostInput.text.toString().trim()
            if (raw.isEmpty()) {
                status.text = "请输入电视 IP，或从上面的列表选择"
                return@setOnClickListener
            }
            val host = raw.substringBefore(':')
            val port = raw.substringAfter(':', "").toIntOrNull() ?: DEFAULT_PORT
            connect(Device(host, host, port))
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            stopService(Intent(this, MirrorSenderService::class.java))
            status.text = "已停止投屏"
        }

        requestNotificationPermission()
        startDiscovery()
    }

    override fun onDestroy() {
        stopDiscovery()
        super.onDestroy()
    }

    // ─── Discovery ───────────────────────────────────────────────────────────

    private fun startDiscovery() {
        stopDiscovery()
        devices.clear()
        adapter.notifyDataSetChanged()
        status.text = "正在搜索电视…"

        val manager = getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = manager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                runOnUiThread { status.text = "正在搜索电视…（请确保手机和电视在同一 Wi-Fi）" }
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.contains(SERVICE_TYPE)) return
                synchronized(resolving) {
                    if (!resolving.add(info.serviceName)) return
                }
                @Suppress("DEPRECATION")
                manager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        synchronized(resolving) { resolving.remove(serviceInfo.serviceName) }
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        synchronized(resolving) { resolving.remove(serviceInfo.serviceName) }
                        val host: InetAddress = serviceInfo.host ?: return
                        addDevice(
                            Device(serviceInfo.serviceName, host.hostAddress ?: return, serviceInfo.port)
                        )
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(resolving) { resolving.remove(info.serviceName) }
                runOnUiThread {
                    val removed = devices.removeAll { it.name == info.serviceName }
                    if (removed) {
                        adapter.notifyDataSetChanged()
                        updateCount()
                    }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runOnUiThread {
                    status.text = "搜索失败（错误码 $errorCode），可以用下方手动输入电视 IP"
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = listener
        manager.discoverServices(SERVICE_TYPE_WITH_DOT, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun stopDiscovery() {
        val manager = nsd ?: return
        val listener = discoveryListener ?: return
        try { manager.stopServiceDiscovery(listener) } catch (_: Exception) {}
        discoveryListener = null
    }

    private fun addDevice(device: Device) {
        runOnUiThread {
            if (devices.any { it.host == device.host && it.port == device.port }) return@runOnUiThread
            devices.add(device)
            adapter.notifyDataSetChanged()
            updateCount()
        }
    }

    private fun updateCount() {
        status.text = if (devices.isEmpty()) {
            "正在搜索电视…（请确保手机和电视在同一 Wi-Fi）"
        } else {
            "找到 ${devices.size} 台电视，点一下开始投屏"
        }
    }

    // ─── Capture ─────────────────────────────────────────────────────────────

    private fun connect(device: Device) {
        pendingDevice = device
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Activity#onActivityResult — no AndroidX in this module")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_CAPTURE) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val device = pendingDevice ?: return
        if (resultCode != RESULT_OK || data == null) {
            status.text = "已取消投屏授权"
            return
        }
        val intent = Intent(this, MirrorSenderService::class.java).apply {
            action = MirrorSenderService.ACTION_START
            putExtra(MirrorSenderService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MirrorSenderService.EXTRA_PROJECTION_DATA, data)
            putExtra(MirrorSenderService.EXTRA_HOST, device.host)
            putExtra(MirrorSenderService.EXTRA_PORT, device.port)
            putExtra(MirrorSenderService.EXTRA_NAME, Build.MODEL)
        }
        startForegroundService(intent)
        status.text = "正在投屏到 ${device.name}\n可以按 Home 键，投屏会继续"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    companion object {
        private const val SERVICE_TYPE = "_airmirror._tcp"
        private const val SERVICE_TYPE_WITH_DOT = "_airmirror._tcp."
        private const val DEFAULT_PORT = 7800
        private const val REQUEST_CAPTURE = 100
        private const val REQUEST_NOTIFICATIONS = 101
    }
}
