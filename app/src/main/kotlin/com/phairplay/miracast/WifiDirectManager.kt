// Ported from weekdayjast/MiracastReceiver (MIT). See THIRD_PARTY_NOTICES.md.
package com.phairplay.miracast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Looper
import androidx.core.content.ContextCompat
import com.phairplay.util.Logger

/**
 * Wi-Fi Direct (P2P) manager used for Miracast discovery.
 *
 * The device is the group owner and advertising sink. Windows discovers wireless
 * displays through the WFD IE that wpa_supplicant broadcasts; the framework call here
 * is only a best-effort system-signed path, while a normal install gets root advertising
 * through [WfdRootHelper].
 *
 * @param deviceName name shown in the sender's cast list; must match the AirPlay / DLNA name
 */
class WifiDirectManager(
    private val context: Context,
    private var deviceName: String
) {
    /**
     * Some TVs (especially uncertified boxes) have no Wi-Fi Direct at all, and
     * `getSystemService` returns null. Miracast is only one of several protocols and
     * must never take the whole app down.
     */
    private val manager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }

    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var isStarted = false

    enum class StartupState { STARTING, UNSUPPORTED, PERMISSION_REQUIRED, WIFI_DISABLED, FAILED }

    var onStartupState: ((StartupState) -> Unit)? = null
    var onWfdConfigured: (() -> Unit)? = null

    var isWfdConfigured = false
        private set

    var onDeviceConnected: ((WifiP2pDevice) -> Unit)? = null
    var onDeviceDisconnected: (() -> Unit)? = null
    var onGroupCreated: ((WifiP2pGroup) -> Unit)? = null

    fun start() {
        if (isStarted) {
            Logger.w("Wi-Fi Direct already started")
            // Retry group creation after the user grants Wi-Fi Direct permission.
            createGroup()
            return
        }

        val p2p = manager
        if (p2p == null) {
            onStartupState?.invoke(StartupState.UNSUPPORTED)
            Logger.w("本机不支持 Wi-Fi Direct，Miracast 不可用（AirPlay / DLNA 不受影响）")
            return
        }

        try {
            onStartupState?.invoke(StartupState.STARTING)
            channel = p2p.initialize(context, Looper.getMainLooper(), null)

            if (channel == null) {
                onStartupState?.invoke(StartupState.FAILED)
                Logger.e("Failed to initialize Wi-Fi P2P channel")
                return
            }

            registerReceiver()

            // Best-effort framework path; usually SecurityException on a normal install.
            setWfdInfo()

            createGroup()
            registerLocalService()

            isStarted = true
            Logger.i("Wi-Fi Direct started for Miracast")
        } catch (e: SecurityException) {
            onStartupState?.invoke(StartupState.PERMISSION_REQUIRED)
            Logger.w("Wi-Fi Direct permission required")
        } catch (e: Exception) {
            onStartupState?.invoke(StartupState.FAILED)
            Logger.e("Failed to start Wi-Fi Direct", e)
        }
    }

    private fun setWfdInfo() {
        val ch = channel ?: return

        try {
            val wfdInfoClass = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            val wfdInfo = wfdInfoClass.newInstance()

            wfdInfoClass.getMethod("setWfdEnabled", Boolean::class.java)
                .invoke(wfdInfo, true)
            // PRIMARY_SINK = 1, SECONDARY_SINK = 2, SOURCE = 0
            wfdInfoClass.getMethod("setDeviceType", Int::class.java)
                .invoke(wfdInfo, 1)
            wfdInfoClass.getMethod("setSessionAvailable", Boolean::class.java)
                .invoke(wfdInfo, true)
            wfdInfoClass.getMethod("setControlPort", Int::class.java)
                .invoke(wfdInfo, 7236)
            wfdInfoClass.getMethod("setMaxThroughput", Int::class.java)
                .invoke(wfdInfo, 50)

            val setWFDInfoMethod = WifiP2pManager::class.java.getMethod(
                "setWFDInfo",
                WifiP2pManager.Channel::class.java,
                wfdInfoClass,
                WifiP2pManager.ActionListener::class.java
            )

            setWFDInfoMethod.invoke(manager, ch, wfdInfo, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    isWfdConfigured = true
                    onWfdConfigured?.invoke()
                    Logger.i("WFD Info set successfully (system-signed build)")
                }

                override fun onFailure(reason: Int) {
                    Logger.e("Failed to set WFD Info: ${reasonText(reason)} (需要系统权限)")
                }
            })

        } catch (e: SecurityException) {
            Logger.e("setWFDInfo 需要系统权限 (CONFIGURE_WIFI_DISPLAY)", e)
        } catch (e: ClassNotFoundException) {
            Logger.e("WifiP2pWfdInfo class not found", e)
        } catch (e: NoSuchMethodException) {
            Logger.e("setWFDInfo method not found", e)
        } catch (e: Exception) {
            Logger.e("Error setting WFD Info", e)
        }
    }

    private fun createGroup() {
        val ch = channel ?: return

        try {
            manager?.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Logger.i("Wi-Fi Direct group created successfully")
                    manager?.requestGroupInfo(ch) { group ->
                        if (group != null) {
                            Logger.i("Group created - SSID: ${group.networkName}, Owner: ${group.isGroupOwner}")
                            onGroupCreated?.invoke(group)
                            setDeviceName(deviceName)
                        } else {
                            onStartupState?.invoke(StartupState.FAILED)
                        }
                    }
                }

                override fun onFailure(reason: Int) {
                    Logger.e("Failed to create Wi-Fi Direct group: ${reasonText(reason)}")

                    if (reason == WifiP2pManager.BUSY) {
                        // A group may already exist; reuse it.
                        manager?.requestGroupInfo(ch) { group ->
                            if (group != null) {
                                Logger.i("Existing group found: ${group.networkName}")
                                onGroupCreated?.invoke(group)
                                setDeviceName(deviceName)
                            } else {
                                onStartupState?.invoke(StartupState.FAILED)
                            }
                        }
                    } else {
                        onStartupState?.invoke(
                            if (reason == WifiP2pManager.P2P_UNSUPPORTED) StartupState.UNSUPPORTED
                            else StartupState.FAILED
                        )
                    }
                }
            })
        } catch (e: SecurityException) {
            onStartupState?.invoke(StartupState.PERMISSION_REQUIRED)
            Logger.e("Security exception when creating group - missing permissions?", e)
        } catch (e: Exception) {
            onStartupState?.invoke(StartupState.FAILED)
            Logger.e("Exception when creating group", e)
        }
    }

    /**
     * Try to rename the P2P device (shown in phone cast lists).
     *
     * Since Android 11 this requires NETWORK_SETTINGS / NETWORK_STACK / OVERRIDE_WIFI_CONFIG,
     * which a normal app lacks. Old systems or system-signed builds succeed directly;
     * rooted devices get the name through [WfdRootHelper] instead.
     */
    private fun setDeviceName(name: String) {
        val p2pName = P2pDeviceName.fromDisplayName(name) ?: return
        try {
            val setDeviceNameMethod = WifiP2pManager::class.java.getMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java,
                String::class.java,
                WifiP2pManager.ActionListener::class.java
            )

            setDeviceNameMethod.invoke(manager, channel, p2pName, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Logger.i("Device name set to: $name")
                }

                override fun onFailure(reason: Int) {
                    Logger.w("Failed to set device name: $reason")
                }
            })
        } catch (e: Exception) {
            Logger.w("Could not set device name (requires system permissions)")
        }
    }

    fun updateDeviceName(name: String) {
        deviceName = name
        if (channel != null) setDeviceName(name)
    }

    private fun registerLocalService() {
        val ch = channel ?: return

        try {
            val record = mapOf(
                "version" to "1.0",
                "type" to "miracast-sink",
                "rtsp_port" to "7236"
            )

            val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance("_miracast", "_tcp", record)

            manager?.addLocalService(ch, serviceInfo, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Logger.i("Local Miracast service registered")
                }

                override fun onFailure(reason: Int) {
                    Logger.e("Failed to register local service: $reason")
                }
            })

            // No discoverPeers here: a sink only needs to advertise and answer probe
            // requests. Discovery keeps scanning for ~2 minutes, and every off-channel
            // scan stalls the group channel long enough to drop a burst of RTP packets.
        } catch (e: SecurityException) {
            Logger.w("注册 P2P 本地服务被拒绝（缺少定位权限），Miracast 不可用")
        } catch (e: Exception) {
            Logger.e("Error registering local service", e)
        }
    }

    private fun registerReceiver() {
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        if (!enabled) onStartupState?.invoke(StartupState.WIFI_DISABLED)
                        Logger.d("Wi-Fi P2P state changed: ${if (enabled) "ENABLED" else "DISABLED"}")
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        Logger.d("Wi-Fi P2P peers changed")
                        // requestPeers throws SecurityException without location permission;
                        // this runs in a broadcast, so an escaping exception kills the app.
                        channel?.let { ch ->
                            try {
                                manager?.requestPeers(ch) { peerList ->
                                    Logger.d("Peers discovered: ${peerList.deviceList.size}")
                                }
                            } catch (e: SecurityException) {
                                Logger.w("requestPeers 被拒绝（缺少定位权限）")
                            }
                        }
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        Logger.d("Wi-Fi P2P connection changed")
                        channel?.let { ch ->
                            manager?.requestConnectionInfo(ch) { info ->
                                if (info.groupFormed) {
                                    Logger.i("P2P Group formed - Group Owner: ${info.isGroupOwner}")
                                    if (info.isGroupOwner) {
                                        Logger.i("This device is Group Owner, IP: ${info.groupOwnerAddress?.hostAddress}")
                                    }
                                }
                            }
                        }
                    }

                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val device = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        Logger.d("This device changed: ${device?.deviceName}, Status: ${device?.status}")
                    }
                }
            }
        }

        try {
            // Flags overload is API 26; use ContextCompat so minSdk 25 doesn't crash.
            ContextCompat.registerReceiver(
                context, receiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            Logger.i("Wi-Fi P2P broadcast receiver registered")
        } catch (e: Exception) {
            Logger.e("Failed to register Wi-Fi P2P receiver", e)
        }
    }

    fun stop() {
        if (!isStarted) return

        try {
            channel?.let { ch ->
                manager?.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Logger.i("Local services cleared")
                    }

                    override fun onFailure(reason: Int) {
                        Logger.w("Failed to clear local services: $reason")
                    }
                })

                manager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        Logger.i("Wi-Fi Direct group removed")
                    }

                    override fun onFailure(reason: Int) {
                        Logger.w("Failed to remove group: $reason")
                    }
                })
            }

            receiver?.let {
                context.unregisterReceiver(it)
                receiver = null
            }

            channel = null
            isStarted = false
            Logger.i("Wi-Fi Direct stopped")
        } catch (e: Exception) {
            Logger.e("Error stopping Wi-Fi Direct", e)
        }
    }

    fun isRunning(): Boolean = isStarted

    /** Drop the current sender's P2P link, then prepare a fresh receiving group. */
    fun disconnectSender() {
        val ch = channel ?: return
        manager?.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (isStarted && channel === ch) createGroup()
            }

            override fun onFailure(reason: Int) {
                Logger.w("Miracast disconnect group failed: $reason")
            }
        })
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.ERROR -> "ERROR"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY -> "BUSY"
        else -> "UNKNOWN($reason)"
    }
}
