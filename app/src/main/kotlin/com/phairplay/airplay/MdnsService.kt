package com.phairplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.phairplay.util.Logger
import com.phairplay.util.NetworkUtils

/**
 * MdnsService — Advertises PhairPlay as an AirPlay 2 receiver on the local network.
 *
 * WHY: For macOS to show PhairPlay in the AirPlay menu, the device must announce
 * itself using mDNS (Multicast DNS, the same protocol as Apple's Bonjour).
 * Without this advertisement, macOS would never know PhairPlay exists.
 *
 * HOW: Registers two mDNS services using Android's [NsdManager]:
 * - "_airplay._tcp": The main AirPlay service with feature flags and device info
 * - "_raop._tcp": The audio streaming service (required even for screen mirroring)
 *
 * Example:
 *   val mdns = MdnsService(context)
 *   mdns.start()    // device appears in macOS AirPlay menu
 *   mdns.stop()     // device disappears from macOS AirPlay menu
 *   mdns.restart()  // stop + start (used after a streaming session ends)
 */
class MdnsService(private val context: Context) {

    // Android's built-in mDNS manager — handles multicast registration
    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    // Listeners track registration state; we hold references to unregister later
    private var airPlayListener: NsdManager.RegistrationListener? = null
    private var raopListener: NsdManager.RegistrationListener? = null

    // Track whether we are currently registered to avoid double-registration
    @Volatile
    private var isRegistered = false

    /**
     * Starts mDNS advertising.
     *
     * Registers both the _airplay._tcp and _raop._tcp services.
     * The device will appear in the macOS AirPlay menu within ~1-3 seconds.
     *
     * This method is idempotent: calling it twice without stop() in between
     * is safe (the second call is a no-op).
     */
    fun start() {
        if (isRegistered) {
            Logger.w("MdnsService.start() called but already registered — ignoring")
            return
        }
        Logger.i("Starting mDNS advertising")
        registerAirPlayService()
        registerRaopService()
        isRegistered = true
    }

    /**
     * Stops mDNS advertising.
     *
     * Unregisters both mDNS services. The device disappears from the macOS
     * AirPlay menu within ~5-10 seconds (mDNS goodbye packet is sent immediately,
     * but macOS caches the entry briefly).
     *
     * This method is safe to call even if [start] was never called.
     */
    fun stop() {
        Logger.i("Stopping mDNS advertising")
        try {
            airPlayListener?.let { nsdManager.unregisterService(it) }
            raopListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            // Unregistration errors are non-fatal — the service will expire naturally
            Logger.e("Error unregistering mDNS services (non-fatal)", e)
        } finally {
            airPlayListener = null
            raopListener = null
            isRegistered = false
        }
    }

    /**
     * Restarts mDNS advertising.
     *
     * Used after a streaming session ends to make the device immediately
     * visible in the macOS AirPlay picker again.
     */
    fun restart() {
        Logger.d("Restarting mDNS advertising")
        stop()
        start()
    }

    /**
     * Registers the _airplay._tcp mDNS service.
     *
     * The TXT record attributes tell macOS what features PhairPlay supports.
     * Key fields:
     * - deviceid: The device's MAC address (uniquely identifies this receiver)
     * - features:  Bitmask of supported AirPlay features (see TECHNICAL_SPEC.md §8)
     * - model:     Pretend to be an Apple TV so macOS uses the mirroring protocol
     * - srcvers:   AirPlay server version string
     */
    private fun registerAirPlayService() {
        val serviceInfo = NsdServiceInfo().apply {
            // The service name is what users see in the AirPlay picker on macOS.
            // We use the device's Android device name (set by the user in system settings).
            serviceName = NetworkUtils.getDeviceName(context)

            // Service type for AirPlay — this is the standard type macOS looks for
            serviceType = SERVICE_TYPE_AIRPLAY

            // Port 7000 is the standard AirPlay RTSP port
            port = AIRPLAY_PORT

            // TXT records: metadata that macOS reads to understand this receiver's capabilities
            // Each setAttribute call adds one key=value pair to the mDNS TXT record
            setAttribute("deviceid", NetworkUtils.getMacAddress())
            setAttribute("features", AIRPLAY_FEATURES)
            setAttribute("model", AIRPLAY_MODEL)
            setAttribute("srcvers", AIRPLAY_SERVER_VERSION)
            setAttribute("vv", "2")   // AirPlay protocol version 2
            setAttribute("pi", NetworkUtils.getPersistentUuid(context))
            setAttribute("flags", "0x4")  // Indicates this is a screen mirroring receiver
        }

        airPlayListener = createRegistrationListener("_airplay._tcp")
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, airPlayListener!!)
    }

    /**
     * Registers the _raop._tcp mDNS service.
     *
     * RAOP (Remote Audio Output Protocol) is the audio streaming component of AirPlay.
     * macOS requires this service to be present even for screen mirroring (not just audio).
     *
     * The service name for RAOP follows a specific format required by the AirPlay protocol:
     * "<MAC_ADDRESS_NO_COLONS>@<DEVICE_NAME>"
     * Example: "AABBCCDDEEFF@MyAndroidTV"
     */
    private fun registerRaopService() {
        val macAddress = NetworkUtils.getMacAddress().replace(":", "").uppercase()
        val deviceName = NetworkUtils.getDeviceName(context)

        val serviceInfo = NsdServiceInfo().apply {
            // RAOP service name format: MAC@DeviceName (required by AirPlay protocol)
            serviceName = "$macAddress@$deviceName"
            serviceType = SERVICE_TYPE_RAOP
            port = AIRPLAY_PORT

            setAttribute("cn", "0,1,2,3")    // Cipher numbers (audio encryption types)
            setAttribute("da", "true")         // Digest authentication
            setAttribute("et", "0,3,5")        // Encryption types
            setAttribute("md", "0,1,2")        // Metadata types
            setAttribute("sv", "false")        // Software volume
            setAttribute("tp", "UDP")          // Transport protocol for audio
            setAttribute("vn", "65537")        // Version number
            setAttribute("vs", AIRPLAY_SERVER_VERSION)
            setAttribute("am", AIRPLAY_MODEL)
        }

        raopListener = createRegistrationListener("_raop._tcp")
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, raopListener!!)
    }

    /**
     * Creates an [NsdManager.RegistrationListener] that logs registration events.
     *
     * The listener handles four events:
     * - onServiceRegistered: registration succeeded (device is now discoverable)
     * - onRegistrationFailed: registration failed (log the error code for debugging)
     * - onServiceUnregistered: unregistration succeeded
     * - onUnregistrationFailed: unregistration failed (non-fatal, service will expire)
     *
     * @param serviceType The service type string, used only for logging.
     */
    private fun createRegistrationListener(serviceType: String): NsdManager.RegistrationListener {
        return object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                // Note: NsdManager may change the service name to resolve conflicts
                // (e.g., "PhairPlay (2)" if another device has the same name).
                // We log the actual registered name for debugging.
                Logger.i("mDNS registered: $serviceType as '${serviceInfo.serviceName}'")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // FAILURE_ALREADY_ACTIVE (3): already registered — treat as success
                // FAILURE_MAX_LIMIT (4): too many services registered — should not happen
                Logger.e("mDNS registration FAILED for $serviceType, errorCode=$errorCode")
                isRegistered = false
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Logger.d("mDNS unregistered: $serviceType")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Non-fatal: the service will expire naturally via mDNS TTL
                Logger.w("mDNS unregistration failed for $serviceType, errorCode=$errorCode (non-fatal)")
            }
        }
    }

    companion object {
        // Standard mDNS service type for AirPlay receivers
        private const val SERVICE_TYPE_AIRPLAY = "_airplay._tcp"

        // Standard mDNS service type for RAOP (audio) — required alongside AirPlay
        private const val SERVICE_TYPE_RAOP = "_raop._tcp"

        // AirPlay uses TCP port 7000 for RTSP session control
        const val AIRPLAY_PORT = 7000

        // AirPlay feature bitmask: advertise support for screen mirroring, video, and audio.
        // This value is derived from the openairplay protocol spec.
        // See TECHNICAL_SPEC.md §8 for the full breakdown of each bit.
        private const val AIRPLAY_FEATURES = "0x5A7FFFF7,0x1E"

        // Pretend to be an Apple TV 4K so macOS uses the screen mirroring protocol
        private const val AIRPLAY_MODEL = "AppleTV5,3"

        // AirPlay server version string — matches a real Apple TV for compatibility
        private const val AIRPLAY_SERVER_VERSION = "220.68"
    }
}
