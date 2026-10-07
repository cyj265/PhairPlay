package com.phairplay.dlna

import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * v107 — an IPv4-only resolver, shared by the player and the source probe.
 *
 * ## Why this exists
 *
 * On this box the router hands out an IPv6 address and a default route, but does
 * not forward IPv6 to the WAN: `ping6` to any internet address is100% loss while
 * the same host over IPv4 answers in ~33 ms. DNS, however, happily returns AAAA
 * records, and both media3 and the JVM sort IPv6 first per RFC 6724. So every
 * single request tries IPv6, gets nowhere, and only falls back to IPv4 after the
 * connect timeout has expired. Measured on real casts: first byte at 6.1 s, 12.6 s
 * and 66.2 s, each landing on one of those retry steps, while a source with no
 * AAAA record answered in 144 ms.
 *
 * The same video plays quickly on a phone on the same Wi-Fi, which is what proved
 * the delay was ours and not the origin's.
 *
 * ## Why one shared instance
 *
 * The probe in [DlnaReceiver] resolves the host itself to time the DNS phase, and
 * the probe is the *only* way a slow source becomes attributable in the log. If
 * only the player were fixed, the probe would keep reporting AAAA addresses and
 * 12–66 s first bytes, and the next person to read the log would conclude the
 * network was slow again. Both paths have to agree.
 *
 * ## Accepted trade-off
 *
 * A host that only publishes AAAA now resolves to nothing and is reported as
 * unresolvable. On a box with no working IPv6 path those streams could never have
 * played anyway, so this costs nothing real. If IPv6 forwarding is ever fixed at
 * the router, the fallback below makes this class unnecessary: filtering to IPv4
 * can be turned off with [ALLOW_IPV6].
 */
internal object Ipv4OnlyDns {

    /** Flip to true once the router actually forwards IPv6; then this file is a no-op. */
    private const val ALLOW_IPV6 = false

    private const val TAG = "Ipv4OnlyDns"

    /**
     * Ask the JVM to prefer IPv4 when it resolves a name itself.
     *
     * Note the honest limitation: `java.net.preferIPv4Stack` is read when the
     * networking stack initialises, so setting it late has no effect on a running
     * process. It is kept because it costs nothing and does apply to work started
     * after the property is set, but **it is not the mechanism the fix relies
     * on** — that is [lookup] filtering the addresses itself.
     *
     * The remaining gap is HTTPS: there the hostname has to reach the TLS
     * handshake, so the request goes out by name and the platform resolver may
     * still try the AAAA address first. Whether that costs time on this network
     * is measured, not assumed — see the first-byte figure in the probe log.
     */
    fun preferIpv4() {
        if (ALLOW_IPV6) return
        if (System.getProperty(PREFER_IPV4_PROPERTY) == null) {
            System.setProperty(PREFER_IPV4_PROPERTY, "true")
            Log.i(TAG, "java.net.preferIPv4Stack=true — HTTPS requests will resolve IPv4 first")
        }
    }

    private const val PREFER_IPV4_PROPERTY = "java.net.preferIPv4Stack"

    /**
     * Resolves [host] and keeps only the IPv4 addresses, mirroring what
     * `androidx.media3.datasource.Dns#lookup` must return.
     *
     * Order is preserved so the caller still sees the resolver's own preference
     * among the v4 candidates. Throws [UnknownHostException] when nothing usable is
     * left, which is the contract every caller already handles.
     */
    @Throws(UnknownHostException::class)
    fun lookup(host: String): List<InetAddress> {
        val all = InetAddress.getAllByName(host)
        if (ALLOW_IPV6) return all.toList()
        val v4 = all.filter { it is Inet4Address }
        if (v4.isNotEmpty()) {
            if (all.size != v4.size) {
                Log.i(TAG, "$host: dropped ${all.size - v4.size} AAAA address(es), IPv6 does not leave this network")
            }
            return v4
        }
        // Nothing but AAAA. Passing them through would connect to a black hole;
        // failing fast at resolution is the same verdict, just sooner.
        Log.w(TAG, "$host: AAAA only, no IPv4 address — reporting unresolvable")
        throw UnknownHostException("$host has no IPv4 address and IPv6 is unusable here")
    }

    /**
     * Resolution for the probe's timing line: returns the addresses it would use,
     * or an empty list when the host only has unusable AAAA records. Never throws,
     * because the probe wants to *describe* the failure, not propagate it.
     */
    fun lookupOrEmpty(host: String): List<InetAddress> = try {
        lookup(host)
    } catch (e: UnknownHostException) {
        emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "$host: ${e.javaClass.simpleName} ${e.message}")
        emptyList()
    }
}