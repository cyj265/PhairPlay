// Ported from weekdayjast/MiracastReceiver (MIT). See THIRD_PARTY_NOTICES.md.
package com.phairplay.miracast

import android.os.Process
import android.view.Surface
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Miracast RTP receiver.
 *
 * Windows sends MPEG-2 TS over RTP (payload type 33), not bare H.264: each 1328-byte
 * packet is a 12-byte RTP header plus 7 × 188-byte TS packets. This class strips the RTP
 * header, hands the payload to [TsDemuxer], and lets [MiracastVideoRenderer] /
 * [MiracastAudioPlayer] decode the result directly.
 *
 * ExoPlayer is deliberately not used: its buffering adds 10+ seconds of latency to a
 * second-screen feed, while this path decodes on arrival.
 *
 * @param surfaceProvider display target; null while the app has no Surface
 */
class RtpReceiver(
    private val port: Int,
    private val surfaceProvider: () -> Surface?
) {
    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var isRunning = false

    private val renderer = MiracastVideoRenderer(surfaceProvider)
    private val audioPlayer = MiracastAudioPlayer()
    private val demuxer = TsDemuxer(
        onAccessUnit = renderer::onAccessUnit,
        onDiscontinuity = renderer::onDiscontinuity,
        // Match video: stay silent while the player UI has no Surface
        onAudioPes = { codec, data -> if (surfaceProvider() != null) audioPlayer.onAudioPes(codec, data) },
        onAudioDiscontinuity = audioPlayer::onDiscontinuity
    )

    /**
     * Receive/decode boundary. Decoding on the receive thread would back-pressure into
     * the UDP socket and drop packets (which always shows as corruption), so the receive
     * thread only copies payloads into this shallow real-time queue.
     */
    private val payloadQueue = ArrayBlockingQueue<ByteArray>(256)

    @Volatile
    private var packetsLost = 0L

    @Volatile
    private var packetsReceived = 0L

    @Volatile
    private var bytesReceived = 0L

    private val sequenceMonitor = RtpSequenceMonitor()

    var onError: ((String) -> Unit)? = null

    fun start() {
        if (isRunning) {
            Logger.w("RTP Receiver already running")
            return
        }

        scope.launch { runDecoder() }

        scope.launch {
            try {
                socket = DatagramSocket(port).apply {
                    // The default receive buffer is a few tens of KB; a 2.5 Mbps stream
                    // overflows it after a trivial scheduling delay.
                    runCatching { receiveBufferSize = 1024 * 1024 }
                }
                isRunning = true
                Logger.i("RTP Receiver listening on UDP $port (expecting MPEG-2 TS, PT=33), " +
                    "recvBuf=${socket?.receiveBufferSize}")

                // Socket overflow is indistinguishable from Wi-Fi loss once the
                // packet is gone; keep this thread above decoders so a busy CPU
                // doesn't let the 512 KB kernel buffer drain too slowly.
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

                val buffer = ByteArray(65536)
                val packet = DatagramPacket(buffer, buffer.size)
                var loggedFirst = false

                while (isRunning) {
                    try {
                        packet.length = buffer.size    // receive() shrinks it; reset every round
                        socket?.receive(packet)
                        if (packet.length <= 0) continue

                        packetsReceived++
                        bytesReceived += packet.length

                        val payloadOffset = rtpHeaderLength(packet.data, packet.length)
                        if (payloadOffset <= 0 || payloadOffset >= packet.length) continue

                        if (!loggedFirst) {
                            loggedFirst = true
                            val pt = packet.data[1].toInt() and 0x7F
                            Logger.i("First RTP packet: ${packet.length}B payloadType=$pt " +
                                "payload=${packet.length - payloadOffset}B")
                        }

                        // A forward sequence gap means packet loss and the current
                        // frame is corrupt. A *lower* sequence number is a late or
                        // duplicated packet: the gap was already handled when the
                        // newer packet arrived, and feeding it now would put TS
                        // packets out of order. It must not be counted as loss —
                        // `seq - expected` wraps and used to log "lost 65505".
                        val seq = ((packet.data[2].toInt() and 0xFF) shl 8) or
                            (packet.data[3].toInt() and 0xFF)
                        when (val event = sequenceMonitor.onSequence(seq)) {
                            RtpSequenceEvent.IN_ORDER -> Unit
                            RtpSequenceEvent.LOSS -> {
                                val lost = sequenceMonitor.takeLost()
                                packetsLost += lost
                                Logger.w("RTP: lost $lost packets (next seq $seq)")
                                signalDiscontinuity()
                            }
                            RtpSequenceEvent.LATE -> {
                                // Drop the stale payload: the stream has already
                                // moved past it and its TS continuity is broken.
                                continue
                            }
                            RtpSequenceEvent.RESTART -> {
                                Logger.w("RTP: source restarted its stream (seq $seq)")
                                signalDiscontinuity()
                            }
                        }

                        val payload = packet.data.copyOfRange(payloadOffset, packet.length)
                        if (!payloadQueue.offer(payload)) {
                            payloadQueue.poll()
                            payloadQueue.offer(payload)
                            signalDiscontinuity()
                        }

                        if (packetsReceived % 1000L == 0L) {
                            Logger.i("RTP stats: $packetsReceived packets, ${bytesReceived / 1024}KB, " +
                                "lost=$packetsLost, late=${sequenceMonitor.lateCount}, " +
                                "queue=${payloadQueue.size}")
                        }
                    } catch (e: Exception) {
                        if (isRunning) Logger.e("Error receiving RTP packet", e)
                    }
                }
            } catch (e: Exception) {
                Logger.e("Failed to start RTP Receiver", e)
                onError?.invoke("RTP 接收启动失败: ${e.message}")
                isRunning = false
            }
        }
    }

    /**
     * One packet is gone, so the audio decoder may be mid-frame. Video is *not*
     * gated on a keyframe: asking the source for an IDR on every loss made the
     * encoder spend its bit budget on keyframes (Windows honoured each request),
     * which visibly blurred the desktop and added traffic on an already lossy link.
     */
    private fun signalDiscontinuity() {
        audioPlayer.onDiscontinuity()
    }

    /** Decode thread: take TS payloads from the queue and demux them. */
    private suspend fun runDecoder() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        while (scope.isActive) {
            // This coroutine already lives on Dispatchers.IO; the old
            // withContext(Dispatchers.IO) added two dispatches per packet at
            // ~2000 packets/s and pushed receive scheduling behind the load
            // it was supposed to relieve.
            val payload = try {
                payloadQueue.poll(200, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                null
            } ?: continue
            try {
                // 512 KB kernel receive buffer: decoding on this thread must not
                // back-pressure into the socket, but the queue is what absorbs
                // the burst, not this poll.
                demuxer.feed(payload, 0, payload.size)
            } catch (e: Exception) {
                Logger.e("Error demuxing TS payload", e)
                renderer.onDiscontinuity()
            }
        }
    }

    /**
     * RTP header length: fixed 12 bytes + CSRC list + optional extension.
     * @return payload offset, or -1 when the packet is malformed
     */
    private fun rtpHeaderLength(data: ByteArray, length: Int): Int {
        if (length < 12) return -1
        val version = (data[0].toInt() shr 6) and 0x03
        if (version != 2) return -1

        val csrcCount = data[0].toInt() and 0x0F
        val hasExtension = ((data[0].toInt() shr 4) and 0x01) == 1
        var headerLength = 12 + csrcCount * 4

        if (hasExtension) {
            if (length < headerLength + 4) return -1
            val extWords = ((data[headerLength + 2].toInt() and 0xFF) shl 8) or
                (data[headerLength + 3].toInt() and 0xFF)
            headerLength += 4 + extWords * 4
        }
        return if (headerLength < length) headerLength else -1
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        renderer.release()
        audioPlayer.release()
        demuxer.reset()

        try {
            socket?.close()
            socket = null
        } catch (e: Exception) {
            Logger.e("Error closing RTP socket", e)
        }
        Logger.i("RTP Receiver stopped: $packetsReceived packets, ${bytesReceived / 1024}KB")
    }

    fun isRunning(): Boolean = isRunning
}

internal enum class RtpSequenceEvent { IN_ORDER, LOSS, LATE, RESTART }

/**
 * Classifies RTP sequence numbers without treating reordering as loss.
 *
 * Wi-Fi Direct delivers bursts slightly out of order; the old arithmetic
 * (`seq - expected & 0xFFFF`) reported every late packet as ~65500 lost
 * packets and forced a keyframe wait for each one.
 */
internal class RtpSequenceMonitor(
    private val lateWindow: Int = 64,
    /** Largest forward gap still treated as a burst loss rather than a stream restart. */
    private val maxLossGap: Int = 1024,
) {

    private var expected = -1
    private var pendingLost = 0

    /** Total packets skipped over by forward gaps. */
    var lost = 0L
        private set

    /** Late/duplicate packets that were dropped. */
    var lateCount = 0L
        private set

    fun onSequence(seq: Int): RtpSequenceEvent {
        pendingLost = 0
        val current = seq and 0xFFFF
        if (expected < 0) {
            expected = (current + 1) and 0xFFFF
            return RtpSequenceEvent.IN_ORDER
        }
        if (current == expected) {
            expected = (current + 1) and 0xFFFF
            return RtpSequenceEvent.IN_ORDER
        }

        val forward = (current - expected + 0x10000) and 0xFFFF
        if (forward <= maxLossGap) {
            pendingLost = forward
            lost += forward
            expected = (current + 1) and 0xFFFF
            return RtpSequenceEvent.LOSS
        }
        if (forward < 0x8000) {
            // Thousands of packets in one jump is minutes of stream, not a burst
            // loss: the source restarted its numbering mid-session.
            expected = (current + 1) and 0xFFFF
            return RtpSequenceEvent.RESTART
        }

        lateCount++
        // A far-backward jump is not reordering: the source began a new stream.
        if (0x10000 - forward > lateWindow) {
            expected = (current + 1) and 0xFFFF
            return RtpSequenceEvent.RESTART
        }
        return RtpSequenceEvent.LATE
    }

    /** Lost-packet count for the last [onSequence] result (0 when it wasn't LOSS). */
    fun takeLost(): Int {
        val value = pendingLost
        pendingLost = 0
        return value
    }
}
