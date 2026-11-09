// Ported from weekdayjast/MiracastReceiver (MIT). See THIRD_PARTY_NOTICES.md.
package com.phairplay.miracast

import android.content.Context
import android.view.Surface
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * Wi-Fi Display (Miracast) session initiator.
 *
 * This is **not** an RTSP server: in WFD the Source is the listener. Once the Source
 * joins the P2P group it waits on its own port 7236 and the Sink must dial in, otherwise
 * both sides wait until the ~60s timeout and tear down. This class:
 *
 *   1. finds the Source listening on 7236 inside the P2P subnet,
 *   2. connects and runs M1–M7 through [WfdSessionHandler],
 *   3. receives MPEG-2 TS over RTP via [RtpReceiver] for the duration of the session.
 *
 * Discovery still depends on wpa_supplicant broadcasting the WFD IE, which needs root
 * injection (see [WfdRootHelper]) — the framework refuses `setWFDInfo()` to normal apps.
 *
 * @param port the Source's RTSP port (WFD standard 7236)
 * @param rtpPort local UDP port for RTP
 * @param surfaceProvider current display target; null while the UI has no Surface
 */
class WfdServer(
    private val context: Context,
    private val port: Int = 7236,
    private val rtpPort: Int = 19000,
    private val surfaceProvider: () -> Surface?
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isRunning = false
    @Volatile private var reconnectAfterMs = 0L

    private var sessionHandler: WfdSessionHandler? = null
    private var rtpReceiver: RtpReceiver? = null

    var onConnectionRequested: ((clientName: String, clientAddress: String) -> Unit)? = null
    var onConnectionEstablished: ((sessionId: String) -> Unit)? = null
    var onStreamStarted: ((rtpPort: Int) -> Unit)? = null
    var onStreamStopped: (() -> Unit)? = null

    companion object {
        private const val SCAN_INTERVAL_MS = 3_000L
        private const val CONNECT_TIMEOUT_MS = 400
        private const val SCAN_CHUNK = 32          // addresses probed concurrently per batch
    }

    fun start() {
        if (isRunning) {
            Logger.w("WFD session starter already running")
            return
        }
        isRunning = true
        Logger.i("WFD session starter running (will dial source:$port, RTP on $rtpPort)")

        scope.launch {
            while (isRunning) {
                try {
                    if (android.os.SystemClock.elapsedRealtime() < reconnectAfterMs) {
                        delay(SCAN_INTERVAL_MS)
                        continue
                    }
                    val socket = dialSource()
                    if (socket != null) {
                        runSession(socket)
                    }
                } catch (e: Exception) {
                    if (isRunning) Logger.e("WFD session loop error", e)
                }
                if (isRunning) delay(SCAN_INTERVAL_MS)
            }
        }
    }

    /**
     * Find the Source in the P2P subnet and **keep** that connection.
     *
     * Probing and reconnecting is not allowed: the Source's listen queue accepts the probe
     * as the Sink's real session and then stops listening, so a second connect would get
     * ECONNREFUSED while the Source reports a failed connection.
     */
    private suspend fun dialSource(): Socket? = coroutineScope {
        val prefix = p2pSubnetPrefix() ?: return@coroutineScope null

        for (chunkStart in 2..254 step SCAN_CHUNK) {
            if (!isRunning) return@coroutineScope null
            val range = chunkStart until minOf(chunkStart + SCAN_CHUNK, 255)
            val connected = range.map { host ->
                async { tryConnect("$prefix$host") }
            }.awaitAll().filterNotNull()

            if (connected.isNotEmpty()) {
                val session = connected.first()
                connected.drop(1).forEach { runCatching { it.close() } }
                Logger.i("WFD: connected to source at ${session.inetAddress.hostAddress}:$port")
                return@coroutineScope session
            }
        }
        null
    }

    private fun tryConnect(ip: String): Socket? = try {
        Socket().also { it.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS) }
    } catch (e: Exception) {
        null
    }

    /**
     * The local P2P interface's /24 prefix (usually "192.168.49.").
     * The group owner address is excluded implicitly because it is never dialed by the sink.
     */
    private fun p2pSubnetPrefix(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .firstOrNull { it.name.startsWith("p2p") && it.isUp }
            ?.inetAddresses?.toList()
            ?.firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress
            ?.substringBeforeLast('.')
            ?.plus(".")
    } catch (e: Exception) {
        Logger.e("Failed to resolve P2P subnet", e)
        null
    }

    /** Run one whole session on the connected socket; returns when the session ends. */
    private fun runSession(socket: Socket) {
        val sourceIp = socket.inetAddress.hostAddress ?: "unknown"
        onConnectionRequested?.invoke("Miracast sender", sourceIp)

        // RTP must be listening before M3 advertises the port, and packets can arrive
        // immediately after M7. Frames are dropped until the player Surface exists.
        val receiver = RtpReceiver(rtpPort, surfaceProvider).apply {
            onError = { Logger.e("Miracast RTP error: $it") }
            start()
        }
        rtpReceiver = receiver

        val handler = WfdSessionHandler(context, socket, rtpPort).apply {
            onSessionEstablished = { onConnectionEstablished?.invoke(it) }
            onStreamStart = { onStreamStarted?.invoke(it) }
            onStreamStop = { onStreamStopped?.invoke() }
        }
        sessionHandler = handler

        // Recovery path for a picture frozen by packet loss: the receiver detects the freeze
        // and asks the Source for a fresh IDR over this same RTSP session.
        receiver.onIdrRequest = { handler.requestIdr() }

        try {
            handler.handleSession()          // blocks until the session ends
        } finally {
            receiver.stop()
            rtpReceiver = null
            sessionHandler = null
        }
    }

    fun stop() {
        isRunning = false
        sessionHandler?.close()
        sessionHandler = null
        rtpReceiver?.stop()
        rtpReceiver = null
        scope.cancel()
        Logger.i("WFD session starter stopped")
    }

    fun isRunning(): Boolean = isRunning

    fun disconnectSender() {
        reconnectAfterMs = android.os.SystemClock.elapsedRealtime() + 5000
        val current = sessionHandler
        scope.launch { runCatching { current?.disconnectSender() } }
    }
}
