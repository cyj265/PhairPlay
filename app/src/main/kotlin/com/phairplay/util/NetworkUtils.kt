package com.phairplay.util

import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import timber.log.Timber
import java.net.NetworkInterface
import java.util.UUID

/**
 * NetworkUtils — Helper functions for reading network interface information.
 *
 * WHY: The AirPlay protocol requires the receiver to advertise its MAC address
 * and device name via mDNS TXT records. This class provides clean, safe methods
 * to read this information from the Android system.
 *
 * HOW: All methods are static (on the companion object) — no instance needed.
 * Read the device name and MAC address once at startup and pass them to MdnsService.
 *
 * Example:
 *   val name = NetworkUtils.getDeviceName(context)   // "My Android TV"
 *   val mac  = NetworkUtils.getMacAddress()           // "aa:bb:cc:dd:ee:ff"
 *   val uuid = NetworkUtils.getPersistentUuid(context) // stable UUID per device
 */
object NetworkUtils {

    /**
     * Returns the user-visible device name as configured in Android settings.
     *
     * This is the name that will appear in the macOS AirPlay picker, so it's
     * important that it matches what the user set in their TV's settings.
     *
     * Sources tried in order:
     * 1. Settings.Global.DEVICE_NAME (Android 5+, most TVs)
     * 2. Settings.Secure.BLUETOOTH_NAME (Bluetooth device name, often same as device name)
     * 3. Fallback: "PhairPlay" (if neither source is available)
     *
     * SECURITY: The returned value is sanitized — mDNS service names must not contain
     * certain special characters. We strip any character outside [A-Za-z0-9 _-].
     *
     * @param context Android context (needed to read system settings)
     * @return The sanitized device name, never null or empty.
     */
    fun getDeviceName(context: Context): String {
        val rawName = Settings.Global.getString(context.contentResolver, "device_name")
            ?: Settings.Secure.getString(context.contentResolver, "bluetooth_name")
            ?: DEFAULT_DEVICE_NAME

        // Sanitize: keep Unicode letters/digits (Chinese device names included)
        // plus space/underscore/hyphen — these are all valid in mDNS service
        // names (RFC 6763 allows arbitrary UTF-8) and in DLNA device names.
        val sanitized = rawName.replace(Regex("[^\\p{L}\\p{N} _\\-]"), "").trim()

        return sanitized.ifEmpty { DEFAULT_DEVICE_NAME }
    }

    /**
     * Returns the device's Wi-Fi or Ethernet MAC address.
     *
     * The MAC address is used as the `deviceid` in AirPlay mDNS TXT records.
     * It uniquely identifies this receiver to macOS senders.
     *
     * Tries Wi-Fi first (most TVs are Wi-Fi), then falls back to any available
     * non-loopback interface, then uses a fake address as last resort.
     *
     * NOTE: On Android 10+, direct MAC access is restricted. We use NetworkInterface
     * instead of WifiManager.getConnectionInfo() which is deprecated.
     *
     * @return MAC address in "aa:bb:cc:dd:ee:ff" format (lowercase, colon-separated).
     */
    fun getMacAddress(): String {
        return try {
            // Iterate all network interfaces to find the Wi-Fi or Ethernet interface
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()

            val mac = interfaces
                .filter { !it.isLoopback && it.isUp && it.hardwareAddress != null }
                .mapNotNull { iface ->
                    iface.hardwareAddress?.let { hwAddr ->
                        hwAddr.joinToString(":") { byte -> "%02x".format(byte) }
                    }
                }
                .firstOrNull()

            mac ?: FALLBACK_MAC_ADDRESS
        } catch (e: Exception) {
            Timber.w(e, "Could not read MAC address — using fallback")
            FALLBACK_MAC_ADDRESS
        }
    }

    /**
     * Returns a stable, device-specific UUID for use in AirPlay's `pi` TXT record.
     *
     * WHY: macOS uses the `pi` (persistent identifier) to recognize a receiver
     * across app restarts. If we generate a new UUID every time, macOS may show
     * duplicate entries in the AirPlay menu.
     *
     * This UUID is generated once and stored in Android's secure settings,
     * so it persists across app restarts and even reinstalls (as long as the
     * app's data is not cleared).
     *
     * SECURITY: This UUID is not a secret — it's transmitted in plaintext via mDNS.
     * It does not contain any sensitive device information.
     *
     * @param context Android context (needed to read/write secure settings)
     * @return A stable UUID string in standard "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" format.
     */
    fun getPersistentUuid(context: Context): String {
        // Persist the UUID in the app's own private SharedPreferences.
        // (Originally this used Settings.Secure, which requires the privileged
        // WRITE_SECURE_SETTINGS permission and threw a SecurityException on normal
        // installs, aborting AirPlay receiver startup. App-private storage needs no
        // permission and still persists across restarts.)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedUuid = prefs.getString(PREF_KEY_DEVICE_UUID, null)
        if (!storedUuid.isNullOrBlank()) {
            return storedUuid
        }

        // Generate a new UUID and store it for future use
        val newUuid = UUID.randomUUID().toString()
        prefs.edit().putString(PREF_KEY_DEVICE_UUID, newUuid).apply()
        return newUuid
    }

    /**
     * Returns the device's current IPv4 address on the active (non-loopback)
     * network interface, or null if unavailable. Used to show the DLNA
     * renderer's address (http://ip:8080) on the home screen so it can be
     * reached manually without SSDP discovery.
     *
     * WiFi (wlan*) / Ethernet (eth*, en*) interfaces are preferred. Hotspot /
     * cellular / tunnel interfaces are skipped because their addresses are not
     * reachable from the cast source on the LAN.
     *
     * NOTE: we deliberately do NOT filter on NetworkInterface.isVirtual() /
     * isPointToPoint(). Many TV-box ROMs (N1 官改系, Amlogic/RK boxes) mark
     * their real Wi-Fi/Ethernet interfaces as virtual (bridge/aggregated
     * adapters such as br0, mac80211 vif, etc.). Filtering them out returns
     * null here, which silently stops the whole SSDP service and leaves the
     * DLNA debug card completely blank. Interface-name blacklisting is the
     * reliable way to exclude hotspot/cellular/tunnel adapters.
     */
    fun getLocalIpv4(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
                ?: return null
            var fallback: String? = null
            for (ni in interfaces) {
                if (ni.isLoopback || !ni.isUp) continue
                val name = ni.name.lowercase()
                // Skip hotspot, cellular, tunnel and other non-LAN interfaces
                // by NAME only (not by isVirtual()/isPointToPoint() — see above).
                if (name.contains("softap") || name.startsWith("ap")
                    || name.contains("rmnet") || name.contains("ccmni")
                    || name.contains("wwan") || name.contains("tun")
                    || name.contains("ppp") || name.contains("bluetooth")
                    || name.contains("usb") || name.contains("v4-radio")
                    || name.contains("dummy") || name.contains("sit")
                    || name.contains("ip6tnl") || name.contains("wpan")
                ) {
                    continue
                }
                for (addr in ni.inetAddresses) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress
                        if (name.contains("wlan") || name.contains("wifi")
                            || name.contains("eth") || name.contains("en")
                        ) {
                            return ip
                        }
                        if (fallback == null) {
                            fallback = ip
                        }
                    }
                }
            }
            return fallback
        } catch (e: Exception) {
            Timber.w(e, "getLocalIpv4 failed")
        }
        return null
    }

    // Constants
    private const val DEFAULT_DEVICE_NAME = "PhairPlay"
    private const val FALLBACK_MAC_ADDRESS = "aa:bb:cc:dd:ee:ff"
    private const val PREFS_NAME = "phairplay_prefs"
    private const val PREF_KEY_DEVICE_UUID = "phairplay_device_uuid"
}
