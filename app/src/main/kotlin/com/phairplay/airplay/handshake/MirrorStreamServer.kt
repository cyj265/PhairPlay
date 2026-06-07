package com.phairplay.airplay.handshake

import android.view.Surface
import com.phairplay.airplay.VideoDecoder
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * MirrorStreamServer — receives and decodes the AirPlay 2 mirroring video stream.
 *
 * macOS connects to [dataPort] and sends a sequence of [128-byte header][payload] packets:
 *  - payload_size = little-endian int at header offset 0
 *  - payload_type = little-endian short at header offset 4, low byte
 *      • type 1: unencrypted avcC (SPS/PPS) → initialize the decoder
 *      • type 0: AES-CTR-encrypted H.264 (AVCC) → decrypt, convert to Annex-B, decode
 *
 * Reference: RPiPlay lib/raop_rtp_mirror.c (raop_rtp_mirror_thread).
 */
class MirrorStreamServer(
    aesKey: ByteArray,
    ecdhSecret: ByteArray,
    streamConnectionId: Long,
    private val surfaceProvider: () -> Surface?,
) {
    private val cipher = MirrorCrypto.streamCipher(aesKey, ecdhSecret, streamConnectionId)
    private val serverSocket = ServerSocket(0)            // OS-assigned free port
    @Volatile private var running = false
    @Volatile private var client: Socket? = null
    @Volatile private var decoder: VideoDecoder? = null
    private var framePtsUs = 0L

    /** The OS-assigned TCP port macOS should connect to (returned in the SETUP response). */
    val dataPort: Int get() = serverSocket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) { runAccept() }
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        runCatching { serverSocket.close() }
        decoder?.release()
        decoder = null
        Logger.i("MirrorStreamServer stopped")
    }

    private fun runAccept() {
        try {
            Logger.i("MirrorStreamServer listening on data port $dataPort")
            val socket = serverSocket.accept().also { client = it }
            Logger.i("Mirror data connection from ${socket.inetAddress.hostAddress}")
            val input = socket.getInputStream()
            val header = ByteArray(128)
            while (running && !socket.isClosed) {
                if (!readFully(input, header, 128)) break
                val payloadSize = leInt(header, 0)
                val payloadType = leShort(header, 4) and 0xFF
                if (payloadSize <= 0 || payloadSize > MAX_PAYLOAD) {
                    Logger.w("Mirror: bad payloadSize=$payloadSize type=$payloadType — stopping")
                    break
                }
                val payload = ByteArray(payloadSize)
                if (!readFully(input, payload, payloadSize)) break
                when (payloadType) {
                    0 -> handleVideo(payload)
                    1 -> handleCodec(payload)
                    else -> Logger.v("Mirror: ignoring payload type $payloadType ($payloadSize B)")
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Mirror stream error", e)
        } finally {
            Logger.i("Mirror data connection ended")
        }
    }

    /** type 1 — unencrypted avcC carrying SPS + PPS; initializes the decoder once. */
    private fun handleCodec(payload: ByteArray) {
        if (decoder != null) return
        try {
            val spsSize = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
            val sps = payload.copyOfRange(8, 8 + spsSize)
            val numPpsOffset = 8 + spsSize                       // 1 byte: number of PPS
            val ppsLenOffset = numPpsOffset + 1
            val ppsSize = ((payload[ppsLenOffset].toInt() and 0xFF) shl 8) or
                (payload[ppsLenOffset + 1].toInt() and 0xFF)
            val pps = payload.copyOfRange(ppsLenOffset + 2, ppsLenOffset + 2 + ppsSize)

            val surface = awaitSurface() ?: run {
                Logger.e("Mirror: no surface available — cannot start decoder")
                return
            }
            // csd-0/csd-1 as Annex-B (start-code prefixed) SPS/PPS.
            decoder = VideoDecoder(surface).also {
                it.initialize(MirrorCrypto.START_CODE + sps, MirrorCrypto.START_CODE + pps, 1920, 1080)
            }
            Logger.i("Mirror decoder initialized (sps=${spsSize}B pps=${ppsSize}B)")
        } catch (e: Exception) {
            Logger.e("Mirror: failed to parse SPS/PPS", e)
        }
    }

    /** type 0 — AES-CTR-encrypted H.264 (AVCC); decrypt → Annex-B → decode. */
    private fun handleVideo(payload: ByteArray) {
        val d = decoder ?: return                               // wait for SPS/PPS first
        val decrypted = cipher.update(payload)
        val annexB = MirrorCrypto.avccToAnnexB(decrypted)
        if (annexB.isNotEmpty()) {
            d.decodeNalUnit(annexB, framePtsUs)
            framePtsUs += FRAME_INTERVAL_US
        }
    }

    /** The streaming Surface appears shortly after CONNECTED is emitted; poll briefly. */
    private fun awaitSurface(): Surface? {
        repeat(SURFACE_WAIT_TRIES) {
            surfaceProvider()?.let { return it }
            try { Thread.sleep(SURFACE_WAIT_MS) } catch (_: InterruptedException) { return null }
        }
        return surfaceProvider()
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n == -1) return false
            read += n
        }
        return true
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    companion object {
        private const val MAX_PAYLOAD = 8 * 1024 * 1024        // 8 MB sanity cap per frame
        private const val FRAME_INTERVAL_US = 1_000_000L / 60  // monotonic PTS hint (~60fps)
        private const val SURFACE_WAIT_TRIES = 50
        private const val SURFACE_WAIT_MS = 100L
    }
}
