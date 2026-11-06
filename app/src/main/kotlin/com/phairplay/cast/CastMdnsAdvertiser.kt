// Ported from weekdayjast/MiracastReceiver (MIT). See THIRD_PARTY_NOTICES.md.
package com.phairplay.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** IPv4 DNS-SD responder scoped to the same LAN interface as the TLS listener. */
class CastMdnsAdvertiser(
    context: Context,
    deviceId: String,
    name: String,
    private val ip: String,
    private val onFailure: () -> Unit
) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val records = CastMdnsPacket(deviceId, name, ip)
    private val group = InetSocketAddress("224.0.0.251", 5353)
    private val lock = Any()
    private var scope: CoroutineScope? = null
    private var socket: MulticastSocket? = null

    fun start() {
        if (scope != null) return
        val active = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = active
        active.launch {
            val multicast = try {
                MulticastSocket(null)
            } catch (e: Exception) {
                Logger.w("Cast mDNS: cannot create socket: ${e.message}")
                onFailure(); active.cancel(); return@launch
            }
            var multicastLock: WifiManager.MulticastLock? = null
            try {
                val network = requireNotNull(NetworkInterface.getByInetAddress(InetAddress.getByName(ip)))
                multicast.reuseAddress = true
                multicast.bind(InetSocketAddress(5353))
                multicast.networkInterface = network
                multicast.timeToLive = 255
                multicast.soTimeout = 1_000
                multicast.joinGroup(group, network)
                multicastLock = wifi?.createMulticastLock("phairplay-cast-mdns")?.apply {
                    setReferenceCounted(false); acquire()
                }
                synchronized(lock) {
                    if (scope !== active) { multicast.close(); return@launch }
                    socket = multicast
                }
                fun announce(ttl: Int = 120) {
                    val packet = records.response(ttl)
                    multicast.send(DatagramPacket(packet, packet.size, group))
                }
                announce()
                Logger.i("Cast mDNS advertised on $ip as ${records.instance}")
                launch {
                    try {
                        delay(1_000); announce()
                        while (isActive) { delay(30_000); announce() }
                    } catch (e: Exception) {
                        if (isActive) {
                            Logger.w("Cast mDNS announcement failed: ${e.message}")
                            onFailure()
                        }
                        multicast.close()
                    }
                }
                val buffer = ByteArray(9000)
                while (isActive) {
                    val request = DatagramPacket(buffer, buffer.size)
                    try {
                        multicast.receive(request)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val query = records.query(buffer.copyOf(request.length), request.port) ?: continue
                    val response = records.response(query = query)
                    val destination = if (query.unicast) request.socketAddress else group
                    multicast.send(DatagramPacket(response, response.size, destination))
                }
            } catch (e: Exception) {
                if (isActive) {
                    Logger.w("Cast mDNS failed: ${e.message}")
                    onFailure()
                }
            } finally {
                multicast.close()
                multicastLock?.let { if (it.isHeld) it.release() }
                active.cancel()
            }
        }
    }

    fun stop() {
        val active: CoroutineScope?
        val bound: MulticastSocket?
        synchronized(lock) {
            active = scope; scope = null
            bound = socket; socket = null
        }
        if (bound == null) { active?.cancel(); return }
        // Datagram I/O must not run on Android's main thread. Closing wakes receive().
        active?.launch(NonCancellable + Dispatchers.IO) {
            runCatching {
                val goodbye = records.response(ttl = 0)
                bound.send(DatagramPacket(goodbye, goodbye.size, group))
            }
            bound.close()
            active.cancel()
        }
    }
}
