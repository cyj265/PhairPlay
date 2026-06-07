package com.phairplay.airplay.handshake

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
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

    // Bind to the IPv6 wildcard (dual-stack) — macOS sends the audio RTP over the session's
    // IPv6 link-local address; a default DatagramSocket binds IPv4-only and never receives it.
    private val socket = ipv6Socket()
    private val controlSocket = ipv6Socket()   // realtime-audio control channel (drained)

    @Volatile private var running = false
    private var codec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var firstPcm = true

    /** UDP port macOS sends the audio RTP stream to (returned in the SETUP response). */
    val dataPort: Int get() = socket.localPort

    /** UDP control port (returned in the SETUP response; macOS won't send audio without it). */
    val controlPort: Int get() = controlSocket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) { runReceive() }
        // Drain the control channel (retransmit/timing requests) — we don't act on it for v1.
        scope.launch(Dispatchers.IO) {
            val buf = ByteArray(2048)
            val pkt = DatagramPacket(buf, buf.size)
            try { while (running) controlSocket.receive(pkt) } catch (_: Exception) { /* closed */ }
        }
    }

    fun stop() {
        running = false
        runCatching { socket.close() }
        runCatching { controlSocket.close() }
        runCatching { codec?.stop(); codec?.release() }
        runCatching { audioTrack?.stop(); audioTrack?.release() }
        codec = null
        audioTrack = null
        Logger.i("AudioStreamServer stopped")
    }

    private fun runReceive() {
        try {
            initDecoder()
            initAudioTrack()
            Logger.i("AudioStreamServer listening on UDP $dataPort (AAC-ELD ${sampleRate}Hz x$channels)")
            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)
            var first = true
            while (running) {
                socket.receive(packet)
                if (first) {
                    Logger.i("Audio: first RTP packet from ${packet.address?.hostAddress} (${packet.length}B)")
                    first = false
                }
                if (packet.length <= RTP_HEADER) continue
                // RAOP RTP: 12-byte header, then AES-128-CBC-encrypted AAC payload.
                val payload = packet.data.copyOfRange(RTP_HEADER, packet.length)
                try {
                    decodeFrame(decryptPacket(payload))
                } catch (e: Exception) {
                    Logger.e("Audio: frame decode error", e)
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Audio stream error", e)
        }
    }

    /** AES-128-CBC decrypt the whole-block portion; the trailing < 16 bytes stay cleartext. */
    private fun decryptPacket(payload: ByteArray): ByteArray {
        val encryptedLen = (payload.size / 16) * 16
        if (encryptedLen == 0) return payload
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)   // fresh IV per packet (RAOP)
        val out = payload.copyOf()
        cipher.doFinal(payload, 0, encryptedLen, out, 0)
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
            audioTrack?.write(pcm, 0, pcm.size, AudioTrack.WRITE_NON_BLOCKING)
            mc.releaseOutputBuffer(outIdx, false)
            outIdx = mc.dequeueOutputBuffer(info, 0)
        }
    }

    private fun initDecoder() {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
            // AudioSpecificConfig for AAC-ELD, 44.1 kHz, stereo.
            setByteBuffer("csd-0", ByteBuffer.wrap(AAC_ELD_ASC))
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }
    }

    private fun initAudioTrack() {
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
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
            .setBufferSizeInBytes(maxOf(minBuf, minBuf * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    companion object {
        private const val RTP_HEADER = 12

        /** A UDP socket bound to the IPv6 wildcard (dual-stack), OS-assigned port. */
        private fun ipv6Socket(): DatagramSocket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("::"), 0))
        }

        @Suppress("unused")
        private val AUDIO_MANAGER_HINT = AudioManager.STREAM_MUSIC

        // AudioSpecificConfig: AAC-ELD (AOT 39), 44.1 kHz (index 4), stereo (config 2).
        private val AAC_ELD_ASC = byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50.toByte(), 0x00.toByte())
    }
}
