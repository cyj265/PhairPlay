package com.phairplay.mirror

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket

/**
 * MirrorProtocolTest — the wire format the phone sender and the TV receiver
 * both implement. These are the parts a bad build can get wrong silently
 * (byte order, lengths, handshake), so they are pinned here rather than
 * discovered as a black screen on the TV.
 */
class MirrorProtocolTest {

    @Test
    fun `video header round-trips type length and pts`() {
        val encoded = MirrorProtocol.encodeHeader(MirrorProtocol.TYPE_VIDEO, 1234, 5_000_000L)
        assertEquals(MirrorProtocol.HEADER_SIZE, encoded.size)

        val header = MirrorProtocol.decodeHeader(encoded)
        assertNotNull(header)
        assertEquals(MirrorProtocol.TYPE_VIDEO, header!!.type)
        assertEquals(1234, header.length)
        assertEquals(5_000_000L, header.ptsUs)
    }

    @Test
    fun `header is big-endian on the wire`() {
        val encoded = MirrorProtocol.encodeHeader(MirrorProtocol.TYPE_VIDEO, 0x01020304, 0)
        assertArrayEquals(
            byteArrayOf(2, 0, 0, 0, 1, 2, 3, 4, 0, 0, 0, 0, 0, 0, 0, 0),
            encoded
        )
    }

    @Test
    fun `decoder rejects a short header`() {
        assertNull(MirrorProtocol.decodeHeader(ByteArray(MirrorProtocol.HEADER_SIZE - 1)))
    }

    @Test
    fun `decoder rejects an impossible length`() {
        val bad = MirrorProtocol.encodeHeader(MirrorProtocol.TYPE_VIDEO, MirrorProtocol.MAX_PAYLOAD + 1, 0)
        assertNull(MirrorProtocol.decodeHeader(bad))
    }

    @Test
    fun `config round-trips including both parameter sets`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00.toByte(), 0x1F)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val payload = MirrorProtocol.encodeConfig(
            MirrorProtocol.Config(1920, 1080, 90, sps, pps)
        )

        val decoded = MirrorProtocol.decodeConfig(payload)
        assertNotNull(decoded)
        assertEquals(1920, decoded!!.width)
        assertEquals(1080, decoded.height)
        assertEquals(90, decoded.rotation)
        assertArrayEquals(sps, decoded.sps)
        assertArrayEquals(pps, decoded.pps)
    }

    @Test
    fun `config rejects a truncated payload`() {
        val sps = byteArrayOf(0x67, 0x42)
        val pps = byteArrayOf(0x68)
        val payload = MirrorProtocol.encodeConfig(MirrorProtocol.Config(640, 480, 0, sps, pps))
        assertNull(MirrorProtocol.decodeConfig(payload.copyOf(payload.size - 2)))
        assertNull(MirrorProtocol.decodeConfig(ByteArray(4)))
    }

    @Test
    fun `hello announces the version and suggested size`() {
        assertEquals(
            "PPM1 1 1920 1080\n",
            String(MirrorProtocol.encodeHello(1920, 1080), Charsets.US_ASCII)
        )
    }

    @Test
    fun `sender name survives spaces and non-ascii`() {
        val line = String(MirrorProtocol.encodeName("小明的 Pixel 8"), Charsets.US_ASCII)
        assertEquals("小明的 Pixel 8", MirrorProtocol.decodeName(line.trimEnd('\n')))
    }

    @Test
    fun `empty sender name decodes to empty string`() {
        assertEquals("", MirrorProtocol.decodeName("NAME="))
    }

    @Test
    fun `readLine stops at newline and tolerates CRLF`() {
        val input = ByteArrayInputStream("NAME=phone\r\nNAME=second\n".toByteArray())
        assertEquals("NAME=phone", MirrorProtocol.readLine(input))
        assertEquals("NAME=second", MirrorProtocol.readLine(input))
        assertNull(MirrorProtocol.readLine(input))
    }

    @Test
    fun `readLine refuses unbounded input`() {
        val flood = ByteArrayInputStream(ByteArray(64) { 'A'.code.toByte() })
        assertNull(MirrorProtocol.readLine(flood, max = 16))
    }

    @Test
    fun `keyframe detection finds an IDR after a 4-byte start code`() {
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)
        assertTrue(MirrorProtocol.isKeyframe(idr))
    }

    @Test
    fun `keyframe detection finds an IDR after a 3-byte start code`() {
        val idr = byteArrayOf(0, 0, 1, 0x65, 0x11, 0x22)
        assertTrue(MirrorProtocol.isKeyframe(idr))
    }

    @Test
    fun `non-IDR frames are not keyframes`() {
        val pFrame = byteArrayOf(0, 0, 0, 1, 0x41, 0x9A.toByte())
        assertFalse(MirrorProtocol.isKeyframe(pFrame))
        assertFalse(MirrorProtocol.isKeyframe(ByteArray(0)))
    }

    @Test
    fun `stripStartCode handles both prefix lengths`() {
        assertArrayEquals(
            byteArrayOf(0x67, 0x42),
            MirrorProtocol.stripStartCode(byteArrayOf(0, 0, 0, 1, 0x67, 0x42))
        )
        assertArrayEquals(
            byteArrayOf(0x67, 0x42),
            MirrorProtocol.stripStartCode(byteArrayOf(0, 0, 1, 0x67, 0x42))
        )
    }

    @Test
    fun `avcc is converted to annex-b for the decoder`() {
        val avcc = byteArrayOf(
            0, 0, 0, 2, 0x65, 0x01,
            0, 0, 0, 3, 0x41, 0x02, 0x03
        )
        assertArrayEquals(
            byteArrayOf(
                0, 0, 0, 1, 0x65, 0x01,
                0, 0, 0, 1, 0x41, 0x02, 0x03
            ),
            MirrorProtocol.avccToAnnexB(avcc)
        )
    }

    @Test
    fun `avcc stops at a malformed length instead of overrunning`() {
        val truncated = byteArrayOf(0, 0, 0, 9, 0x65, 0x01)
        assertEquals(0, MirrorProtocol.avccToAnnexB(truncated).size)
    }

    @Test
    fun `annex-b from the encoder is passed through untouched`() {
        // Most Android AVC encoders emit this shape. Treating its first four
        // bytes as an AVCC length would turn the whole frame into garbage.
        val annexB = byteArrayOf(0, 0, 0, 1, 0x65, 0x01, 0x02, 0, 0, 1, 0x41, 0x03)
        assertArrayEquals(annexB, MirrorProtocol.toAnnexB(annexB))
    }

    @Test
    fun `short annex-b start code is passed through untouched`() {
        val annexB = byteArrayOf(0, 0, 1, 0x65, 0x01)
        assertArrayEquals(annexB, MirrorProtocol.toAnnexB(annexB))
    }

    @Test
    fun `length-prefixed buffer is detected and converted`() {
        val avcc = byteArrayOf(0, 0, 0, 2, 0x65, 0x01, 0, 0, 0, 1, 0x41)
        assertTrue(MirrorProtocol.isAvccChain(avcc))
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x65, 0x01, 0, 0, 0, 1, 0x41),
            MirrorProtocol.toAnnexB(avcc)
        )
    }

    @Test
    fun `a buffer that is neither format is left alone`() {
        val junk = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x0A)
        assertFalse(MirrorProtocol.isAvccChain(junk))
        assertArrayEquals(junk, MirrorProtocol.toAnnexB(junk))
    }

    @Test
    fun `port fallback skips the port already in use`() {
        val taken = ServerSocket(0)
        try {
            val fallback = MirrorProtocol.openServerSocket(taken.localPort)
            try {
                assertTrue(fallback.localPort != taken.localPort)
                val first = taken.localPort
                assertTrue(fallback.localPort in (first + 1) until (first + 8))
            } finally {
                fallback.close()
            }
        } finally {
            taken.close()
        }
    }
}
