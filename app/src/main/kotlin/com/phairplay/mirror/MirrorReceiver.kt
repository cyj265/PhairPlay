package com.phairplay.mirror

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.view.Surface
import com.phairplay.airplay.StreamStats
import com.phairplay.airplay.VideoDecoder
import com.phairplay.airplay.handshake.MirrorCrypto
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import com.phairplay.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * MirrorReceiver — receives the phone's H.264 screen stream over the PPM1
 * protocol and decodes it onto the TV's Surface.
 *
 * Discovery is mDNS (`_airmirror._tcp`) so the phone only needs the LAN, not
 * Wi-Fi Direct: the p230 ROM offers no usable WFD sink, and this is the same
 * model the app it replaces uses (乐播 also needs its own phone app).
 *
 * Threading mirrors MirrorStreamServer, for the same reason: the socket must
 * always be drained even when the decoder falls behind. A reader thread pushes
 * whole frames onto a bounded queue; a decoder thread is the only thing that
 * touches [VideoDecoder]. Dropping a frame marks the stream as reference-broken
 * and the decoder skips until the next IDR instead of smearing.
 */
class MirrorReceiver(
    private val context: Context,
    private val displayName: String,
    private val surfaceProvider: () -> Surface?,
    private val onStateChanged: (ProtocolState) -> Unit,
    private val onSenderName: (String) -> Unit = {},
) {
    private sealed class Item
    private class Config(
        val width: Int,
        val height: Int,
        val sps: ByteArray,
        val pps: ByteArray,
    ) : Item()
    private class Frame(val annexB: ByteArray, val ptsUs: Long) : Item()

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private val queue = ArrayBlockingQueue<Item>(QUEUE_CAPACITY)

    @Volatile private var running = false
    @Volatile private var client: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    // Owned by the decoder thread.
    private var decoder: VideoDecoder? = null
    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null
    private var lastWidth = 0
    private var lastHeight = 0
    @Volatile private var configuredSurface: Surface? = null
    @Volatile private var awaitingKeyframe = true

    private var framesIn = 0
    private var framesDropped = 0
    private var lastStatMs = 0L

    private val advertisedName: String =
        displayName.trim().ifEmpty { NetworkUtils.getDeviceName(context) }

    fun start() {
        if (running) {
            Logger.w("MirrorReceiver.start() called while already running — ignoring")
            return
        }
        running = true
        try {
            serverSocket = MirrorProtocol.openServerSocket()
        } catch (e: Exception) {
            Logger.e("MirrorReceiver: no usable TCP port", e)
            running = false
            onStateChanged(ProtocolState.ERROR)
            return
        }
        Logger.i("MirrorReceiver listening on port ${serverSocket?.localPort} as '$advertisedName'")
        val discoverable = registerMdns()
        scope.launch { acceptLoop() }
        scope.launch { decoderLoop() }
        // The socket is up either way, but without mDNS the phone cannot find it —
        // so ERROR is the honest state rather than a silent "advertising".
        onStateChanged(if (discoverable) ProtocolState.ADVERTISING else ProtocolState.ERROR)
    }

    fun stop() {
        Logger.i("MirrorReceiver stopping")
        running = false
        closeClient()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        unregisterMdns()
        queue.clear()
        scope.cancel()
        onStateChanged(ProtocolState.DISABLED)
    }

    /**
     * Ends the current session but keeps advertising. Called when the user
     * presses Back on the TV: closing the socket is what tells the phone (its
     * next write fails) that the mirror is no longer wanted.
     */
    fun stopSession() {
        Logger.i("MirrorReceiver: session ended from the UI")
        closeClient()
    }

    private fun closeClient() {
        try { client?.close() } catch (_: Exception) {}
        client = null
    }

    // ─── Discovery ───────────────────────────────────────────────────────────

    /** Returns false when registration could not even be requested. */
    private fun registerMdns(): Boolean {
        try {
            val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            nsdManager = manager
            val info = NsdServiceInfo().apply {
                serviceName = advertisedName
                serviceType = SERVICE_TYPE
                port = serverSocket?.localPort ?: MirrorProtocol.DEFAULT_PORT
                setAttribute("v", MirrorProtocol.VERSION.toString())
                setAttribute("name", advertisedName)
            }
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Logger.i("mDNS registered: $SERVICE_TYPE as '${serviceInfo.serviceName}'")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Logger.e("mDNS registration FAILED for $SERVICE_TYPE, errorCode=$errorCode")
                    // The socket still works with a manual IP, so this is not fatal —
                    // but it must not read as "advertising" either.
                    onStateChanged(ProtocolState.ERROR)
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            return true
        } catch (e: Exception) {
            Logger.e("Unable to advertise $SERVICE_TYPE", e)
            return false
        }
    }

    private fun unregisterMdns() {
        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {
            Logger.w("mDNS unregister failed for $SERVICE_TYPE (non-fatal): ${e.message}")
        } finally {
            registrationListener = null
            nsdManager = null
        }
    }

    // ─── Network ─────────────────────────────────────────────────────────────

    private fun acceptLoop() {
        while (running) {
            val server = serverSocket ?: return
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                if (running) Logger.e("Mirror accept failed", e)
                continue
            }
            client = socket
            try {
                handleSession(socket)
            } catch (e: Exception) {
                Logger.e("Mirror session error", e)
            } finally {
                closeClient()
                queue.clear()
                awaitingKeyframe = true
                Logger.i("Mirror session ended")
                if (running) onStateChanged(ProtocolState.ADVERTISING)
            }
        }
    }

    private fun handleSession(socket: Socket) {
        Logger.i("Mirror connection from ${socket.inetAddress?.hostAddress}")
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        val magic = ByteArray(MirrorProtocol.MAGIC.length)
        if (!MirrorProtocol.readFully(input, magic) ||
            String(magic, Charsets.US_ASCII) != MirrorProtocol.MAGIC
        ) {
            Logger.w("Mirror: bad handshake magic — closing")
            return
        }
        val name = MirrorProtocol.readLine(input)?.let(MirrorProtocol::decodeName)
            ?.takeIf { it.isNotBlank() } ?: "手机投屏"
        onSenderName(name)

        val (width, height) = displaySize()
        if (!MirrorProtocol.writeFully(output, MirrorProtocol.encodeHello(width, height))) {
            Logger.w("Mirror: handshake reply failed")
            return
        }
        // The handshake is over; the frame loop may legitimately block on a silent sender.
        socket.soTimeout = 0

        Logger.i("Mirror session from '$name' — suggested encode ${width}x$height")
        onStateChanged(ProtocolState.CONNECTED)

        val headerBuf = ByteArray(MirrorProtocol.HEADER_SIZE)
        while (running && !socket.isClosed) {
            if (!MirrorProtocol.readFully(input, headerBuf)) break
            val header = MirrorProtocol.decodeHeader(headerBuf)
            if (header == null) {
                Logger.w("Mirror: malformed frame header — ending session")
                break
            }
            if (header.type == MirrorProtocol.TYPE_STOP) break
            val payload = ByteArray(header.length)
            if (!MirrorProtocol.readFully(input, payload)) break
            when (header.type) {
                MirrorProtocol.TYPE_CONFIG -> MirrorProtocol.decodeConfig(payload)?.let {
                    enqueue(Config(it.width, it.height, it.sps, it.pps))
                }
                MirrorProtocol.TYPE_VIDEO -> if (payload.isNotEmpty()) enqueue(Frame(payload, header.ptsUs))
                else -> Logger.v("Mirror: ignoring frame type ${header.type} (${header.length}B)")
            }
        }
    }

    private fun enqueue(item: Item) {
        framesIn++
        if (!queue.offer(item)) {
            queue.poll()
            queue.offer(item)
            framesDropped++
            awaitingKeyframe = true
        }
        StreamStats.videoQueue = queue.size
        if (framesIn % 300 == 0) {
            val now = System.currentTimeMillis()
            if (lastStatMs != 0L) {
                StreamStats.videoFps = (300_000L / (now - lastStatMs).coerceAtLeast(1)).toInt()
            }
            lastStatMs = now
            StreamStats.videoDropPct = framesDropped * 100 / framesIn
            Logger.i(
                "Mirror video: in=$framesIn dropped=$framesDropped " +
                    "(${StreamStats.videoDropPct}%) queue=${queue.size}/$QUEUE_CAPACITY " +
                    "${StreamStats.videoFps}fps"
            )
        }
    }

    // ─── Decoding ────────────────────────────────────────────────────────────

    private fun decoderLoop() {
        try {
            while (scope.isActive && running) {
                val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                when (item) {
                    is Config -> configureDecoder(item)
                    is Frame -> decodeFrame(item)
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Mirror decoder thread error", e)
        } finally {
            decoder?.release()
            decoder = null
        }
    }

    private fun configureDecoder(config: Config) {
        val surface = awaitSurface()
        val unchanged = decoder?.isHealthy == true &&
            lastSps?.contentEquals(config.sps) == true &&
            lastPps?.contentEquals(config.pps) == true &&
            surface === configuredSurface
        if (unchanged) return
        lastSps = config.sps
        lastPps = config.pps
        lastWidth = config.width
        lastHeight = config.height
        rebuildDecoder(surface)
    }

    private fun rebuildDecoder(surface: Surface?) {
        decoder?.release()
        decoder = null
        configuredSurface = surface
        val sps = lastSps ?: return
        val pps = lastPps ?: return
        if (surface == null) return
        val sc = MirrorCrypto.START_CODE
        decoder = VideoDecoder(surface).also {
            it.initialize(sc + sps, sc + pps, lastWidth, lastHeight)
        }
        awaitingKeyframe = true
        StreamStats.videoRes = "${lastWidth}x${lastHeight}"
        Logger.i("Mirror decoder (re)built for surface (sps=${sps.size}B pps=${pps.size}B)")
    }

    private fun decodeFrame(frame: Frame) {
        val liveSurface = surfaceProvider()
        if (liveSurface !== configuredSurface) {
            Logger.i("Mirror: surface ${if (liveSurface == null) "lost" else "changed"} — re-attaching")
            rebuildDecoder(liveSurface)
        }
        val d = decoder ?: return
        if (!d.isHealthy) {
            // The sender sends CONFIG once per session, so there is nothing to wait for:
            // rebuild from the cached SPS/PPS and resync on the next IDR (one second away).
            Logger.w("Mirror: decoder unhealthy — rebuilding from cached config")
            rebuildDecoder(liveSurface)
            return
        }
        if (awaitingKeyframe) {
            if (!MirrorProtocol.isKeyframe(frame.annexB)) return
            awaitingKeyframe = false
            Logger.i("Mirror: synced on keyframe")
        }
        d.decodeNalUnit(frame.annexB, frame.ptsUs)
    }

    private fun awaitSurface(): Surface? {
        repeat(SURFACE_WAIT_TRIES) {
            if (!running) return null
            surfaceProvider()?.let { return it }
            try { Thread.sleep(SURFACE_WAIT_MS) } catch (_: InterruptedException) { return null }
        }
        return surfaceProvider()
    }

    private fun displaySize(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    companion object {
        private const val SERVICE_TYPE = "_airmirror._tcp"
        private const val QUEUE_CAPACITY = 90
        private const val HANDSHAKE_TIMEOUT_MS = 5_000
        private const val SURFACE_WAIT_TRIES = 50
        private const val SURFACE_WAIT_MS = 100L
    }
}
