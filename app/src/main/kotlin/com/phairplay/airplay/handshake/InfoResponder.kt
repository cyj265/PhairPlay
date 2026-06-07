package com.phairplay.airplay.handshake

import android.content.Context
import com.phairplay.util.NetworkUtils

/**
 * InfoResponder — builds the binary-plist body for `GET /info`, the first request a macOS
 * AirPlay sender makes. It advertises the receiver's identity and capability bits so the
 * sender knows to continue with pairing → FairPlay → mirroring.
 *
 * Values are kept consistent with what [com.phairplay.airplay.MdnsService] advertises so the
 * sender sees one coherent device.
 *
 * NOTE: the Ed25519 public key (`pk`) is added in the pairing phase once a persistent
 * identity exists; macOS still proceeds to pair-setup without it.
 */
object InfoResponder {

    fun build(context: Context): ByteArray {
        val mac = NetworkUtils.getMacAddress()
        val info = mapOf(
            "deviceID" to mac,
            "macAddress" to mac,
            "features" to AIRPLAY_FEATURES,
            "statusFlags" to STATUS_FLAGS,
            "model" to MODEL,
            "name" to NetworkUtils.getDeviceName(context),
            "sourceVersion" to SOURCE_VERSION,
            "pi" to NetworkUtils.getPersistentUuid(context),
            "protovers" to "1.1",
            "vv" to 2L
        )
        return PlistCodec.encode(info)
    }

    /** 64-bit features value; mirrors MdnsService's "0x5A7FFFF7,0x1E" (low,high 32-bit halves). */
    private const val AIRPLAY_FEATURES = 0x1E5A7FFFF7L

    /** Bit 2 set = screen-mirroring-capable receiver. */
    private const val STATUS_FLAGS = 0x4L

    private const val MODEL = "AppleTV5,3"
    private const val SOURCE_VERSION = "220.68"
}
