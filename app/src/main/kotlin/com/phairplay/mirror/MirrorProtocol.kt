package com.phairplay.mirror

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * MirrorProtocol — the wire format spoken between the PhairPlay phone sender
 * and the TV receiver.
 *
 * The p230 has no usable Wi-Fi Display sink (the ROM ships libstagefright_wfd_sink
 * with no process behind it and no way for an app to set the WFD information
 * element), and the incumbent app — 乐播 — does not speak Miracast either: it
 * ships its own Lelink protocol and requires its own sender app. This is that
 * same model, with a format small enough to describe in one page.
 *
 * Handshake (both directions are ASCII lines ending in '\n'):
 *   phone  → TV: "PPM1\n"                      magic, exactly 5 bytes
 *   phone  → TV: "NAME=<url-encoded name>\n"   sender's user-visible name (may be empty)
 *   TV     → phone: "PPM1 1 <width> <height>\n"  protocol version + suggested encode size
 *
 * Then the phone streams frames, each with a 16-byte header:
 *
 *   [1B type][3B reserved=0][4B big-endian payload length][8B big-endian pts µs]
 *
 *   type 1 CONFIG — width, height, rotation, raw SPS, raw PPS (no start codes)
 *   type 2 VIDEO  — one complete access unit, Annex-B (with start codes)
 *   type 3 STOP   — the TV is ending the session; no payload
 *
 * Big-endian on purpose: every field is read by hand on both sides, and both
 * Kotlin and the Python test sender parse it the same way.
 */
object MirrorProtocol {
    const val MAGIC = "PPM1\n"
    const val VERSION = 1

    const val TYPE_CONFIG = 1
    const val TYPE_VIDEO = 2
    const val TYPE_STOP = 3

    const val HEADER_SIZE = 16
    const val MAX_PAYLOAD = 8 * 1024 * 1024
    const val MAX_NAME_LINE = 512

    /** First port tried; the mDNS record advertises whichever one was actually bound. */
    const val DEFAULT_PORT = 7800
    private const val PORT_TRIES = 8

    private const val RESERVED = 3

    data class Header(val type: Int, val length: Int, val ptsUs: Long)

    data class Config(
        val width: Int,
        val height: Int,
        val rotation: Int,
        val sps: ByteArray,
        val pps: ByteArray,
    )

    // ─── Frames ──────────────────────────────────────────────────────────────

    fun encodeHeader(type: Int, length: Int, ptsUs: Long): ByteArray {
        val out = ByteArray(HEADER_SIZE)
        out[0] = type.toByte()
        // out[1..3] stay zero (reserved)
        putInt(out, 4, length)
        putLong(out, 8, ptsUs)
        return out
    }

    /** Returns null for a truncated header or a nonsensical length. */
    fun decodeHeader(buf: ByteArray, offset: Int = 0): Header? {
        if (buf.size - offset < HEADER_SIZE) return null
        val length = getInt(buf, offset + 4)
        if (length < 0 || length > MAX_PAYLOAD) return null
        return Header(
            type = buf[offset].toInt() and 0xFF,
            length = length,
            ptsUs = getLong(buf, offset + 8),
        )
    }

    fun encodeConfig(config: Config): ByteArray {
        require(config.sps.size <= 0xFFFF && config.pps.size <= 0xFFFF) { "SPS/PPS too large" }
        val out = ByteArray(8 + config.sps.size + 2 + config.pps.size)
        putShort(out, 0, config.width)
        putShort(out, 2, config.height)
        putShort(out, 4, config.rotation)
        putShort(out, 6, config.sps.size)
        config.sps.copyInto(out, 8)
        val ppsLenAt = 8 + config.sps.size
        putShort(out, ppsLenAt, config.pps.size)
        config.pps.copyInto(out, ppsLenAt + 2)
        return out
    }

    /** Returns null rather than throwing: a malformed config must not kill the session. */
    fun decodeConfig(payload: ByteArray): Config? {
        if (payload.size < 10) return null
        val width = getShort(payload, 0)
        val height = getShort(payload, 2)
        val rotation = getShort(payload, 4)
        val spsLen = getShort(payload, 6)
        if (spsLen <= 0 || 8 + spsLen + 2 > payload.size) return null
        val sps = payload.copyOfRange(8, 8 + spsLen)
        val ppsLenAt = 8 + spsLen
        val ppsLen = getShort(payload, ppsLenAt)
        if (ppsLen <= 0 || ppsLenAt + 2 + ppsLen > payload.size) return null
        val pps = payload.copyOfRange(ppsLenAt + 2, ppsLenAt + 2 + ppsLen)
        return Config(width, height, rotation, sps, pps)
    }

    // ─── Handshake ───────────────────────────────────────────────────────────

    fun encodeHello(width: Int, height: Int): ByteArray =
        "PPM1 $VERSION $width $height\n".toByteArray(Charsets.US_ASCII)

    fun encodeName(name: String): ByteArray {
        val encoded = URLEncoder.encode(name, "UTF-8")
        return "NAME=$encoded\n".toByteArray(Charsets.US_ASCII)
    }

    fun decodeName(line: String): String? {
        if (!line.startsWith("NAME=")) return null
        return try {
            URLDecoder.decode(line.substring(5).trim(), "UTF-8")
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Reads one '\n'-terminated ASCII line. Returns null on EOF or when the line
     * exceeds [max] bytes (a garbage sender must not grow an unbounded buffer).
     */
    fun readLine(input: InputStream, max: Int = MAX_NAME_LINE): String? {
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) return buf.toString()
            if (buf.length >= max) return null
            if (b != '\r'.code) buf.append(b.toChar())
        }
    }

    fun writeFully(output: OutputStream, data: ByteArray): Boolean = try {
        output.write(data)
        output.flush()
        true
    } catch (e: Exception) {
        false
    }

    /** Reads exactly [len] bytes; false on EOF. InputStream.readNBytes is API 33+, so read by hand. */
    fun readFully(input: InputStream, buf: ByteArray, len: Int = buf.size): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n <= 0) return false
            read += n
        }
        return true
    }

    // ─── Ports ───────────────────────────────────────────────────────────────

    /**
     * Binds the first free port in [first, first + PORT_TRIES), falling back to an
     * OS-assigned port. Same shape as the DLNA HTTP fallback: a port collision must
     * not decide whether the feature exists at all.
     */
    fun openServerSocket(first: Int = DEFAULT_PORT): ServerSocket {
        for (port in first until first + PORT_TRIES) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port), 4)
                }
            } catch (_: Exception) {
                // taken — try the next one
            }
        }
        return ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(0), 4)
        }
    }

    // ─── H.264 helpers ───────────────────────────────────────────────────────

    /** True when the Annex-B access unit contains an IDR slice — the only safe resync point. */
    fun isKeyframe(annexB: ByteArray): Boolean {
        var i = 0
        while (i + 3 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0) {
                val startCodeLen = when {
                    annexB[i + 2].toInt() == 1 -> 3
                    annexB[i + 2].toInt() == 0 && i + 4 < annexB.size && annexB[i + 3].toInt() == 1 -> 4
                    else -> 0
                }
                if (startCodeLen > 0) {
                    if ((annexB[i + startCodeLen].toInt() and 0x1F) == 5) return true
                    i += startCodeLen
                    continue
                }
            }
            i++
        }
        return false
    }

    /** Removes a leading Annex-B start code so SPS/PPS can travel as raw NAL units. */
    fun stripStartCode(nal: ByteArray): ByteArray {
        var start = 0
        if (nal.size >= 4 && nal[0].toInt() == 0 && nal[1].toInt() == 0 &&
            nal[2].toInt() == 0 && nal[3].toInt() == 1
        ) {
            start = 4
        } else if (nal.size >= 3 && nal[0].toInt() == 0 && nal[1].toInt() == 0 &&
            nal[2].toInt() == 1
        ) {
            start = 3
        }
        return if (start == 0) nal else nal.copyOfRange(start, nal.size)
    }

    /** Converts the length-prefixed NAL units MediaCodec emits (AVCC) into Annex-B. */
    fun avccToAnnexB(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(data.size + 16)
        var i = 0
        while (i + 4 <= data.size) {
            val len = getInt(data, i)
            i += 4
            if (len <= 0 || i + len > data.size) break
            out.write(0); out.write(0); out.write(0); out.write(1)
            out.write(data, i, len)
            i += len
        }
        return out.toByteArray()
    }

    /**
     * Normalises one encoder output buffer to Annex-B.
     *
     * Android's H.264 encoders are not consistent: most emit Annex-B (start
     * codes) straight from the codec, some emit length-prefixed AVCC. Converting
     * unconditionally destroys an Annex-B buffer (its first four bytes read as a
     * NAL length of 1), so the format is detected instead of assumed.
     */
    fun toAnnexB(data: ByteArray): ByteArray = when {
        startsWithStartCode(data) -> data
        isAvccChain(data) -> avccToAnnexB(data)
        else -> data
    }

    /** True when [data] begins with a 3- or 4-byte Annex-B start code. */
    fun startsWithStartCode(data: ByteArray): Boolean =
        (data.size >= 4 && data[0].toInt() == 0 && data[1].toInt() == 0 &&
            data[2].toInt() == 0 && data[3].toInt() == 1) ||
            (data.size >= 3 && data[0].toInt() == 0 && data[1].toInt() == 0 &&
                data[2].toInt() == 1)

    /** True when [data] is exactly a chain of 4-byte-length-prefixed NAL units. */
    fun isAvccChain(data: ByteArray): Boolean {
        if (data.size < 5) return false
        var i = 0
        while (i + 4 <= data.size) {
            val len = getInt(data, i)
            if (len <= 0 || i + 4 + len > data.size) return false
            i += 4 + len
        }
        return i == data.size
    }

    // ─── Byte order helpers ──────────────────────────────────────────────────

    private fun putShort(out: ByteArray, off: Int, value: Int) {
        out[off] = (value ushr 8).toByte()
        out[off + 1] = value.toByte()
    }

    private fun getShort(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    private fun putInt(out: ByteArray, off: Int, value: Int) {
        out[off] = (value ushr 24).toByte()
        out[off + 1] = (value ushr 16).toByte()
        out[off + 2] = (value ushr 8).toByte()
        out[off + 3] = value.toByte()
    }

    private fun getInt(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)

    private fun putLong(out: ByteArray, off: Int, value: Long) {
        for (i in 0 until 8) out[off + i] = (value ushr (56 - i * 8)).toByte()
    }

    private fun getLong(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (buf[off + i].toLong() and 0xFF)
        return v
    }
}
