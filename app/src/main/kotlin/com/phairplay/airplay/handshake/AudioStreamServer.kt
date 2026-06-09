package com.phairplay.airplay.handshake

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.phairplay.airplay.StreamStats
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AudioStreamServer — receives and plays the AirPlay mirroring/realtime audio stream (type 96).
 *
 * macOS sends AES-128-CBC-encrypted AAC-ELD audio as RTP/UDP. We decrypt each packet (whole
 * 16-byte blocks; the trailing partial block is cleartext — the RAOP scheme), decode AAC-ELD via
 * MediaCodec, and play the PCM through AudioTrack.
 *
 * Architecture — the receiver and the player run on SEPARATE threads, decoupled by a bounded
 * queue (same pattern as [MirrorStreamServer] for video):
 *
 *   • Receive thread: socket.receive → dedup by RTP sequence → enqueue. Never blocks on playback,
 *     so the UDP socket is always drained promptly. (A blocking AudioTrack.write on the receive
 *     thread stalls the socket drain, which destabilises the whole mirror session.)
 *   • Playback thread: dequeue → decrypt → decode → AudioTrack.write(BLOCKING). Blocking here only
 *     paces playback to the audio clock and drops no PCM; it cannot stall the network.
 *
 * Reference: RPiPlay lib/raop_rtp.c + lib/raop_buffer.c (audio key = SHA-512(aesKey‖ecdh)[:16],
 * IV = SETUP eiv, AES-128-CBC per packet).
 */
class AudioStreamServer(
    aesKey: ByteArray,
    ecdhSecret: ByteArray,
    aesIv: ByteArray,
    private val sampleRate: Int,
    private val channels: Int,
) {
    private val key = SecretKeySpec(MirrorCrypto.audioKey(aesKey, ecdhSecret), "AES")
    private val iv = IvParameterSpec(aesIv.copyOf(16))

    // Reused across packets: decryptPacket runs only on the playback thread, so one Cipher
    // instance is safe and avoids a Cipher.getInstance allocation on every packet (~92/s).
    private val cbcCipher = Cipher.getInstance("AES/CBC/NoPadding")

    // Bind to the IPv6 wildcard (dual-stack) — macOS sends the audio RTP over the session's
    // IPv6 link-local address; a default DatagramSocket binds IPv4-only and never receives it.
    private val socket = ipv6Socket()
    private val controlSocket = ipv6Socket()   // realtime-audio control channel (drained)

    @Volatile private var running = false
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var firstPcm = true

    // Decoded-audio jitter buffer: raw (post-dedup) RTP payloads handed from the receive thread to
    // the playback thread. Bounded so a stalled player can't grow latency unboundedly — if it fills
    // we drop the oldest frame (a brief glitch is better than ever-growing audio lag).
    private val frameQueue = ArrayBlockingQueue<ByteArray>(AUDIO_QUEUE_CAPACITY)

    // RTP duplicate suppression. macOS sends each realtime-audio packet 2–3× for redundancy
    // (same 16-bit sequence number). Decoding every copy feeds the AAC decoder duplicate frames
    // and pushes 2–3× real-time data into AudioTrack — the buffer overflows, chunks get dropped,
    // and playback both glitches and lags video. We process each sequence number exactly once,
    // remembering a sliding window of recent seqs (well under the 65536 wrap and ~11 s deep).
    private val seenSeqs = java.util.ArrayDeque<Int>()
    private val seenSeqSet = HashSet<Int>()

    /** UDP port macOS sends the audio RTP stream to (returned in the SETUP response). */
    val dataPort: Int get() = socket.localPort

    /** UDP control port (returned in the SETUP response; macOS won't send audio without it). */
    val controlPort: Int get() = controlSocket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        StreamStats.audioActive = true
        scope.launch(Dispatchers.IO) { runPlayback() }   // decode + play (may block on AudioTrack)
        scope.launch(Dispatchers.IO) { runReceive() }    // drain socket fast (never blocks on audio)
        // Drain the control channel (retransmit/timing requests) — we don't act on it for v1.
        scope.launch(Dispatchers.IO) {
            val buf = ByteArray(2048)
            val pkt = DatagramPacket(buf, buf.size)
            var ctrlCount = 0
            try {
                while (running) {
                    pkt.length = buf.size     // reset capacity before each receive (see runReceive)
                    controlSocket.receive(pkt)
                    if (ctrlCount < 6) {
                        Logger.i("Audio CTRL[$ctrlCount] ${pkt.length}B: ${hex(pkt.data, pkt.length)}")
                        ctrlCount++
                    }
                }
            } catch (_: Exception) { /* closed */ }
        }
    }

    fun stop() {
        running = false
        StreamStats.audioActive = false
        runCatching { socket.close() }
        runCatching { controlSocket.close() }
        frameQueue.clear()
        // NOTE: codec + audioTrack are deliberately NOT released here. They are owned and released
        // exclusively by the playback thread (see runPlayback's finally). Releasing MediaCodec from
        // this thread races decodeFrame on the playback thread and crashes the whole process with a
        // native SIGABRT ("pthread_mutex_destroy called on a destroyed mutex" inside libstagefright).
        // Flipping `running` makes the playback loop exit within one poll timeout and clean up safely.
    }

    /** Receive thread: pull RTP packets, drop duplicates, hand unique frames to the player. */
    private fun runReceive() {
        try {
            Logger.i("AudioStreamServer listening on UDP $dataPort (AAC-ELD ${sampleRate}Hz x$channels)")
            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)
            var rtpCount = 0
            var recv = 0; var dup = 0; var qDrop = 0
            while (running) {
                packet.length = buf.size      // reset capacity — receive() shrinks length to the last datagram
                socket.receive(packet)
                recv++
                if (rtpCount < 6) {
                    Logger.i("Audio RTP[$rtpCount] ${packet.length}B hdr: ${hex(packet.data, minOf(20, packet.length))}")
                    rtpCount++
                }
                if (packet.length <= RTP_HEADER) continue
                // RTP sequence number lives in bytes 2–3 (big-endian). Skip copies we've queued.
                val seq = ((packet.data[2].toInt() and 0xFF) shl 8) or (packet.data[3].toInt() and 0xFF)
                if (isDuplicateSeq(seq)) { dup++; continue }
                // RAOP RTP: 12-byte header, then AES-128-CBC-encrypted AAC payload.
                val payload = packet.data.copyOfRange(RTP_HEADER, packet.length)
                if (!frameQueue.offer(payload)) {     // player fell behind → drop oldest, bound latency
                    frameQueue.poll(); frameQueue.offer(payload); qDrop++
                }
                StreamStats.audioQueue = frameQueue.size
                if (recv % 500 == 0) {
                    StreamStats.audioDupPct = dup * 100 / recv
                    Logger.i("Audio stats: recv=$recv dup=$dup (${StreamStats.audioDupPct}% dup) qDrop=$qDrop queue=${frameQueue.size}")
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Audio stream error", e)
        }
    }

    /**
     * Playback thread: decrypt + decode queued frames and write PCM to AudioTrack. This thread is
     * the SOLE owner of [codec] and [audioTrack] — it creates them here and releases them in the
     * finally block, so no other thread ever touches the codec concurrently (see [stop]).
     */
    private fun runPlayback() {
        try {
            initDecoder()
            initAudioTrack()
            while (running) {
                val payload = frameQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                try {
                    decodeFrame(decryptPacket(payload))
                } catch (e: Exception) {
                    if (running) Logger.e("Audio: frame decode error", e)
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Audio playback error", e)
        } finally {
            // Release on the same thread that used the codec — never cross-thread (avoids SIGABRT).
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { audioTrack?.stop() }
            runCatching { audioTrack?.release() }
            codec = null
            audioTrack = null
            Logger.i("AudioStreamServer stopped")
        }
    }

    /** True if this RTP sequence was already processed (a redundant retransmission). */
    private fun isDuplicateSeq(seq: Int): Boolean {
        if (!seenSeqSet.add(seq)) return true          // add() returns false when already present
        seenSeqs.addLast(seq)
        if (seenSeqs.size > SEQ_WINDOW) seenSeqSet.remove(seenSeqs.removeFirst())
        return false
    }

    /** AES-128-CBC decrypt the whole-block portion; the trailing < 16 bytes stay cleartext. */
    private fun decryptPacket(payload: ByteArray): ByteArray {
        val encryptedLen = (payload.size / 16) * 16
        if (encryptedLen == 0) return payload
        cbcCipher.init(Cipher.DECRYPT_MODE, key, iv)   // fresh IV per packet (RAOP)
        val out = payload.copyOf()
        cbcCipher.doFinal(payload, 0, encryptedLen, out, 0)
        return out
    }

    private fun decodeFrame(aac: ByteArray) {
        val mc = codec ?: return
        val inIdx = mc.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            mc.getInputBuffer(inIdx)!!.apply { clear(); put(aac) }
            mc.queueInputBuffer(inIdx, 0, aac.size, 0, 0)
        }
        val info = MediaCodec.BufferInfo()
        var outIdx = mc.dequeueOutputBuffer(info, 0)
        while (outIdx >= 0) {
            val outBuf: ByteBuffer = mc.getOutputBuffer(outIdx)!!
            val pcm = ByteArray(info.size)
            outBuf.position(info.offset); outBuf.get(pcm)
            if (firstPcm) { Logger.i("Audio: first decoded PCM (${pcm.size}B) → AudioTrack"); firstPcm = false }
            // Blocking write paces playback to the audio clock and drops no PCM. Safe here because
            // this runs on the dedicated playback thread, not the socket-receive thread.
            audioTrack?.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            mc.releaseOutputBuffer(outIdx, false)
            outIdx = mc.dequeueOutputBuffer(info, 0)
        }
    }

    private fun initDecoder() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
            // AudioSpecificConfig for AAC-ELD, derived from the negotiated rate + channels.
            setByteBuffer("csd-0", ByteBuffer.wrap(buildAacEldAsc(sampleRate, channels)))
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    private fun initAudioTrack() {
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val bytesPerSec = sampleRate * channels * 2
        Logger.i("AudioTrack: minBuf=${minBuf}B (~${minBuf * 1000 / bytesPerSec}ms), " +
            "buffer=${minBuf * 2}B (~${minBuf * 2 * 1000 / bytesPerSec}ms latency)")
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            // Minimum buffer for LOW LATENCY so audio lines up with the (immediately-rendered)
            // video. The upstream dedup jitter queue absorbs network jitter, so AudioTrack itself
            // only needs the floor. (If this underruns/crackles on load, raise toward minBuf*2.)
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    companion object {
        private const val RTP_HEADER = 12

        // Jitter buffer depth between the receive and playback threads (~1 s at 92 frames/s).
        private const val AUDIO_QUEUE_CAPACITY = 96

        // Sliding window of recently-played RTP sequence numbers for duplicate suppression.
        // ~11 s at 92 packets/s — far longer than any retransmit gap, far shorter than the
        // 65536-packet (~12 min) sequence-number wrap, so no false positives from wraparound.
        private const val SEQ_WINDOW = 1024

        private fun hex(b: ByteArray, len: Int): String =
            (0 until minOf(len, b.size)).joinToString(" ") { "%02x".format(b[it]) }

        /** A UDP socket bound to the IPv6 wildcard (dual-stack), OS-assigned port. */
        private fun ipv6Socket(): DatagramSocket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("::"), 0))
        }

        @Suppress("unused")
        private val AUDIO_MANAGER_HINT = AudioManager.STREAM_MUSIC

        /**
         * Builds the AAC-ELD AudioSpecificConfig (csd-0) for the negotiated [sampleRate] and
         * [channels], instead of hardcoding 44.1 kHz/stereo. Layout: AOT escape(5)=31 + ext(6)=7
         * (AOT 39 = ELD), samplingFrequencyIndex(4), channelConfiguration(4), then the fixed
         * ELDSpecificConfig tail (frameLengthFlag=1 for 480 samples; resilience/SBR flags 0;
         * ELDEXT_TERM). For 44.1 kHz stereo this yields the canonical bytes F8 E8 50 00.
         */
        fun buildAacEldAsc(sampleRate: Int, channels: Int): ByteArray {
            val freqIndex = when (sampleRate) {
                96000 -> 0
                88200 -> 1
                64000 -> 2
                48000 -> 3
                44100 -> 4
                32000 -> 5
                24000 -> 6
                22050 -> 7
                16000 -> 8
                12000 -> 9
                11025 -> 10
                8000 -> 11
                7350 -> 12
                else -> 4   // default to 44.1 kHz
            }
            var bits = 0L
            var n = 0
            fun put(value: Int, width: Int) {
                bits = (bits shl width) or (value.toLong() and ((1L shl width) - 1))
                n += width
            }
            put(31, 5); put(7, 6)                 // AOT escape → 39 (ELD)
            put(freqIndex, 4); put(channels, 4)
            put(1, 1); put(0, 4); put(0, 4)       // frameLengthFlag=1, resilience/SBR=0, ELDEXT_TERM=0
            bits = bits shl (32 - n)              // left-align into 4 bytes
            return byteArrayOf(
                (bits ushr 24).toByte(),
                (bits ushr 16).toByte(),
                (bits ushr 8).toByte(),
                bits.toByte()
            )
        }
    }
}
