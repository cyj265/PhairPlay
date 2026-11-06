// Ported from weekdayjast/MiracastReceiver (MIT). See THIRD_PARTY_NOTICES.md.
package com.phairplay.miracast

import android.content.Context
import android.provider.Settings
import android.view.Surface
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Miracast (Wi-Fi Display) receiver entry point.
 *
 * Orchestrates the three pieces:
 * - [WifiDirectManager] forms the P2P group and tries framework-level WFD advertising,
 * - [WfdRootHelper] injects the WFD IE into wpa_supplicant on rooted devices (the only
 *   way Windows can discover a normal app install),
 * - [WfdServer] dials the Source's RTSP port and receives RTP.
 *
 * Miracast cannot work at all on a non-rooted device whose system is not already
 * advertising WFD; in that case the state goes to ERROR while AirPlay / DLNA / Cast keep
 * running untouched.
 *
 * @param displayName name shown in the sender's cast list (shared with AirPlay / DLNA)
 * @param videoSurfaceProvider display target; null while the player UI has no Surface
 */
class MiracastReceiver(
    private val context: Context,
    private val displayName: String,
    private val videoSurfaceProvider: () -> Surface?,
    private val onStateChanged: (ProtocolState) -> Unit
) {
    companion object {
        const val WFD_RTSP_PORT = 7236
        private const val RTP_PORT = 19000
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var stopped = false
    @Volatile private var streaming = false
    @Volatile private var advertisingInFlight = false
    @Volatile private var rootAdvertisingActive = false
    @Volatile private var startupFailed = false

    private val wfdServer = WfdServer(
        context = context,
        port = WFD_RTSP_PORT,
        rtpPort = RTP_PORT,
        surfaceProvider = { videoSurfaceProvider() }
    )

    private var wifiDirect: WifiDirectManager? = null

    fun start() {
        stopped = false
        streaming = false
        startupFailed = false

        wfdServer.apply {
            onConnectionRequested = { clientName, clientAddress ->
                Logger.i("Miracast connection requested: $clientName from $clientAddress")
            }
            onConnectionEstablished = { sessionId ->
                Logger.i("Miracast session established: $sessionId")
            }
            onStreamStarted = { rtpPort ->
                Logger.i("Miracast stream started on RTP port $rtpPort")
                streaming = true
                onStateChanged(ProtocolState.CONNECTED)
            }
            onStreamStopped = {
                Logger.i("Miracast stream stopped")
                if (streaming) {
                    streaming = false
                    if (!stopped) onStateChanged(ProtocolState.ADVERTISING)
                }
            }
            start()
        }

        wifiDirect = WifiDirectManager(context, displayName).apply {
            onStartupState = stateChanged@{ state ->
                if (stopped) return@stateChanged
                val failure = when (state) {
                    WifiDirectManager.StartupState.STARTING -> null
                    WifiDirectManager.StartupState.UNSUPPORTED -> "Wi-Fi Direct unsupported"
                    WifiDirectManager.StartupState.PERMISSION_REQUIRED ->
                        "Wi-Fi Direct needs the location permission"
                    WifiDirectManager.StartupState.WIFI_DISABLED -> "Wi-Fi is disabled"
                    WifiDirectManager.StartupState.FAILED -> "Wi-Fi Direct setup failed"
                }
                if (failure != null) {
                    Logger.w("Miracast: $failure")
                    startupFailed = true
                    onStateChanged(ProtocolState.ERROR)
                }
            }
            onWfdConfigured = { if (!stopped) onStateChanged(ProtocolState.ADVERTISING) }
            onGroupCreated = { group ->
                Logger.i("Miracast: P2P group ready (${group.networkName})")
                advertiseViaRootIfNeeded()
            }
            onDeviceConnected = { device ->
                Logger.i("Miracast device connected: ${device.deviceName}")
            }
            onDeviceDisconnected = { Logger.i("Miracast device disconnected") }
        }.also { it.start() }

        if (!startupFailed) onStateChanged(ProtocolState.ADVERTISING)
    }

    /**
     * Windows only sees the sink through the WFD IE. The framework refuses that to normal
     * apps, so try root injection once the P2P group exists; without root and without a
     * system-wide WFD advertisement the receiver cannot be discovered.
     */
    private fun advertiseViaRootIfNeeded() {
        if (stopped || advertisingInFlight) return

        if (wifiDirect?.isWfdConfigured == true) {
            Logger.i("Miracast: WFD already advertised by the framework")
            onStateChanged(ProtocolState.ADVERTISING)
            return
        }
        if (systemWfdEnabled()) {
            // A ROM-level WFD sink may work even though this app cannot read its state.
            Logger.i("Miracast: system reports wifi_display_on=1")
            onStateChanged(ProtocolState.ADVERTISING)
            return
        }

        advertisingInFlight = true
        scope.launch {
            val result = runCatching { WfdRootHelper.advertiseSinkResult(context, WFD_RTSP_PORT) }
                .getOrDefault(WfdRootHelper.AdvertiseResult.COMMAND_FAILED)
            advertisingInFlight = false
            rootAdvertisingActive = result == WfdRootHelper.AdvertiseResult.COMMAND_SENT
            if (stopped) return@launch

            when (result) {
                WfdRootHelper.AdvertiseResult.COMMAND_SENT -> {
                    WfdRootHelper.setDeviceName(context, displayName)
                    onStateChanged(ProtocolState.ADVERTISING)
                }
                WfdRootHelper.AdvertiseResult.ROOT_UNAVAILABLE -> {
                    Logger.w("Miracast: root unavailable — Windows cannot discover this device")
                    onStateChanged(ProtocolState.ERROR)
                }
                WfdRootHelper.AdvertiseResult.HELPER_MISSING,
                WfdRootHelper.AdvertiseResult.SOCKET_UNAVAILABLE,
                WfdRootHelper.AdvertiseResult.COMMAND_FAILED -> {
                    Logger.w("Miracast: WFD advertisement failed ($result)")
                    onStateChanged(ProtocolState.ERROR)
                }
            }
        }
    }

    private fun systemWfdEnabled(): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, "wifi_display_on", 0) == 1
    }.getOrDefault(false)

    fun stop() {
        stopped = true
        streaming = false

        runCatching { wfdServer.stop() }
        runCatching { wifiDirect?.stop() }
        wifiDirect = null

        if (rootAdvertisingActive) {
            rootAdvertisingActive = false
            // WFD IE lives in wpa_supplicant and outlives the app; withdraw it even though
            // this scope is about to be cancelled.
            val appContext = context.applicationContext
            Thread({ runCatching { WfdRootHelper.stopAdvertising(appContext) } }, "wfd-stop").start()
        }

        scope.cancel()
        Logger.i("MiracastReceiver stopped")
        onStateChanged(ProtocolState.DISABLED)
    }

    fun isRunning(): Boolean = !stopped

    fun disconnectSender() {
        runCatching { wfdServer.disconnectSender() }
        runCatching { wifiDirect?.disconnectSender() }
    }
}
