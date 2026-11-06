// Ported from weekdayjast/MiracastReceiver (MIT). See THIRD_PARTY_NOTICES.md.
package com.phairplay.miracast

import android.content.Context
import com.phairplay.util.Logger
import java.io.File

/**
 * Injects the Wi-Fi Display Sink WFD IE into wpa_supplicant through root.
 *
 * WHY root: Windows discovers wireless displays by scanning the WFD IE in
 * beacon/probe responses, and only wpa_supplicant can broadcast it. The
 * application-level `WifiP2pManager.setWFDInfo()` needs the signature-level
 * `CONFIGURE_WIFI_DISPLAY` permission, so a normal install always gets
 * SecurityException. The remaining path is wpa_supplicant's control socket,
 * which requires root + the right SELinux context, hence the bundled
 * `libwfdctl.so` executed via su.
 *
 * Without root every method fails safely and returns false — Miracast cannot
 * be discovered, the other protocols are unaffected.
 */
object WfdRootHelper {
    enum class AdvertiseResult { COMMAND_SENT, ROOT_UNAVAILABLE, HELPER_MISSING, SOCKET_UNAVAILABLE, COMMAND_FAILED }

    private const val BINARY_NAME = "libwfdctl.so"

    /** wpa_supplicant control sockets, newest layouts first. */
    private val CTRL_SOCKET_PATHS = listOf(
        "/data/vendor/wifi/wpa/sockets/p2p0",
        "/data/misc/wifi/sockets/p2p0",
        "/data/vendor/wifi/wpa/sockets/wlan0",
        "/data/misc/wifi/sockets/wlan0"
    )

    /**
     * WFD device-information subelement (id 0), six bytes of payload:
     *   0011 → device type primary sink (01) + session available (bits 4-5 = 01)
     *   1c44 → RTSP session-management control port 7236
     *   0032 → maximum throughput 50 Mbps
     * The leading 0006 is the subelement length; wpa_supplicant expects it.
     */
    private fun subelemHex(controlPort: Int, maxThroughputMbps: Int = 50): String =
        "0006" + "0011" + "%04x".format(controlPort) + "%04x".format(maxThroughputMbps)

    fun advertiseSink(context: Context, controlPort: Int = 7236): Boolean =
        advertiseSinkResult(context, controlPort) == AdvertiseResult.COMMAND_SENT

    fun advertiseSinkResult(context: Context, controlPort: Int = 7236): AdvertiseResult {
        if (!isRootAvailable()) return AdvertiseResult.ROOT_UNAVAILABLE
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) {
            Logger.w("WFD: $BINARY_NAME missing from nativeLibraryDir")
            return AdvertiseResult.HELPER_MISSING
        }

        val socketPath = CTRL_SOCKET_PATHS.firstOrNull { runAsRoot("ls $it") }
        if (socketPath == null) {
            Logger.w("WFD: no accessible wpa_supplicant control socket found")
            return AdvertiseResult.SOCKET_UNAVAILABLE
        }

        val cmd = "${binary.absolutePath} $socketPath " +
            "\"SET wifi_display 1\" " +
            "\"WFD_SUBELEM_SET 0 ${subelemHex(controlPort)}\""

        return if (runAsRoot(cmd)) {
            Logger.i("WFD: sink IE injected via $socketPath (control port $controlPort)")
            AdvertiseResult.COMMAND_SENT
        } else {
            Logger.w("WFD: sink IE command failed or timed out")
            AdvertiseResult.COMMAND_FAILED
        }
    }

    /** Renames the native P2P device after this service has enabled WFD via root. */
    fun setDeviceName(context: Context, displayName: String): Boolean {
        val name = P2pDeviceName.fromDisplayName(displayName) ?: return false
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) return false
        val socket = CTRL_SOCKET_PATHS.firstOrNull { runAsRoot("ls $it") } ?: return false
        val command = P2pDeviceName.command(binary.absolutePath, socket, name) ?: return false
        if (!runAsRoot(command)) return false
        // Keep Android's stored P2P name in sync, so framework reinitialization keeps it.
        if (!runAsRoot("settings put global wifi_p2p_device_name ${P2pDeviceName.quote(name)}")) {
            Logger.w("WFD: P2P name sent, but system name persistence failed")
        }
        Logger.i("WFD: P2P device name command sent")
        return true
    }

    /** Withdraws the WFD advertisement (called when Miracast is switched off). */
    fun stopAdvertising(context: Context): Boolean {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_NAME)
        if (!binary.exists()) return false
        val socketPath = CTRL_SOCKET_PATHS.firstOrNull { runAsRoot("ls $it") } ?: return false
        return runAsRoot("${binary.absolutePath} $socketPath \"SET wifi_display 0\"")
    }

    fun isRootAvailable(): Boolean = runAsRoot("test \"$(id -u)\" = 0")

    private fun runAsRoot(command: String): Boolean = try {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        waitForRootCommand(process)
    } catch (e: Exception) {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        Logger.d("WFD root unavailable: ${e.message}")
        false
    }
}
