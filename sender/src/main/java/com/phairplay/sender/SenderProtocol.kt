package com.phairplay.sender

import java.io.InputStream

/**
 * The sender half of the PPM1 wire format.
 *
 * This is intentionally a small copy of the receiver's `MirrorProtocol`
 * (PhairPlay/app/.../mirror/MirrorProtocol.kt): the two APKs ship separately
 * and the phone build must stay free of the TV's dependencies. If the format
 * ever changes, both files change together — the framing tests on the receiver
 * side pin the byte layout.
 */
object SenderProtocol {
    const val MAGIC = "PPM1\n"
    const val VERSION = 1

    const val TYPE_CONFIG = 1
    const val TYPE_VIDEO = 2
    const val TYPE_STOP = 3

    const val HEADER_SIZE = 16
    const val MAX_PAYLOAD = 8 * 1024 * 1024

    const val SERVICE_TYPE = "_airmirror._tcp"

    fun encodeHeader(type: Int, length: Int, ptsUs: Long): ByteArray {
        val out = ByteArray(HEADER_SIZE)
        out[0] = type.toByte()
        putInt(out, 4, length)
        putLong(out, 8, ptsUs)
        return out
    }

    fun encodeConfig(width: Int, height: Int, rotation: Int, sps: ByteArray, pps: ByteArray): ByteArray {
        val out = ByteArray(8 + sps.size + 2 + pps.size)
        putShort(out, 0, width)
        putShort(out, 2, height)
        putShort(out, 4, rotation)
        putShort(out, 6, sps.size)
        sps.copyInto(out, 8)
        val ppsLenAt = 8 + sps.size
        putShort(out, ppsLenAt, pps.size)
        pps.copyInto(out, ppsLenAt + 2)
        return out
    }

    fun encodeName(name: String): ByteArray =
        "NAME=${java.net.URLEncoder.encode(name, "UTF-8")}\n".toByteArray(Charsets.US_ASCII)

    /** Reads the receiver's "PPM1 <version> <w> <h>" reply, or null on EOF/timeout. */
    fun readLine(input: InputStream, max: Int = 256): String? {
        val buf = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) return buf.toString()
            if (buf.length >= max) return null
            if (b != '\r'.code) buf.append(b.toChar())
        }
    }

    /** MediaCodec emits length-prefixed NAL units; the receiver expects Annex-B. */
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
     * Normalises one encoder output buffer to Annex-B. Most Android AVC encoders
     * already emit Annex-B; some emit length-prefixed AVCC. Converting blindly
     * would destroy an Annex-B buffer, so detect first. Kept identical to
     * MirrorProtocol.toAnnexB.
     */
    fun toAnnexB(data: ByteArray): ByteArray = when {
        startsWithStartCode(data) -> data
        isAvccChain(data) -> avccToAnnexB(data)
        else -> data
    }

    fun startsWithStartCode(data: ByteArray): Boolean =
        (data.size >= 4 && data[0].toInt() == 0 && data[1].toInt() == 0 &&
            data[2].toInt() == 0 && data[3].toInt() == 1) ||
            (data.size >= 3 && data[0].toInt() == 0 && data[1].toInt() == 0 &&
                data[2].toInt() == 1)

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

    /** csd-0/csd-1 usually carry a start code; the receiver adds its own. */
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

    private fun putShort(out: ByteArray, off: Int, value: Int) {
        out[off] = (value ushr 8).toByte()
        out[off + 1] = value.toByte()
    }

    private fun putInt(out: ByteArray, off: Int, value: Int) {
        out[off] = (value ushr 24).toByte()
        out[off + 1] = (value ushr 16).toByte()
        out[off + 2] = (value ushr 8).toByte()
        out[off + 3] = value.toByte()
    }

    private fun putLong(out: ByteArray, off: Int, value: Long) {
        for (i in 0 until 8) out[off + i] = (value ushr (56 - i * 8)).toByte()
    }

    private fun getInt(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)
}
