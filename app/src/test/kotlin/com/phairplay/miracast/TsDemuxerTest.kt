package com.phairplay.miracast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feeds hand-built MPEG-TS packets through [TsDemuxer] — no device or sender needed.
 *
 * Packet layout follows ISO/IEC 13818-1: a 4-byte header, an optional pointer_field
 * on payload-unit start, then the PSI section or PES packet.
 */
class TsDemuxerTest {

    private val videoUnits = mutableListOf<Pair<ByteArray, Long>>()
    private val audioUnits = mutableListOf<Pair<TsDemuxer.AudioCodec, ByteArray>>()
    private var videoLosses = 0
    private var audioLosses = 0

    private fun demuxer() = TsDemuxer(
        onAccessUnit = { data, pts -> videoUnits += data to pts },
        onDiscontinuity = { videoLosses++ },
        onAudioPes = { codec, data -> audioUnits += codec to data },
        onAudioDiscontinuity = { audioLosses++ }
    )

    @Test
    fun `extracts H264 access unit with PTS`() {
        val d = demuxer()
        val annexB = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0x1E) +
            byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22, 0x33)
        d.feed(pat(), 0, pat().size)
        d.feed(pmt(0x1B), 0, pmt(0x1B).size)
        d.feed(videoPes(cc = 0, pts90k = 90_000, payload = annexB), 0, videoPes(0, 90_000, annexB).size)
        // The next PES start terminates the first access unit.
        val second = byteArrayOf(0, 0, 0, 1, 0x41, 0x55)
        d.feed(videoPes(cc = 1, pts90k = 180_000, payload = second), 0, videoPes(1, 180_000, second).size)

        assertEquals(1, videoUnits.size)
        assertArrayEquals(annexB, videoUnits[0].first)
        assertEquals(1_000_000L, videoUnits[0].second)
        assertEquals(0, videoLosses)
    }

    @Test
    fun `emits bounded AAC PES immediately`() {
        val d = demuxer()
        val adts = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 0x01, 0x7F, 0xFC.toByte(), 0x01)
        d.feed(pat(), 0, pat().size)
        d.feed(pmt(0x0F), 0, pmt(0x0F).size)
        val audio = audioPes(cc = 0, pts90k = 0, payload = adts)
        d.feed(audio, 0, audio.size)

        assertEquals(1, audioUnits.size)
        assertEquals(TsDemuxer.AudioCodec.AAC_ADTS, audioUnits[0].first)
        assertArrayEquals(adts, audioUnits[0].second)
    }

    @Test
    fun `private stream 0x83 is reported as LPCM`() {
        val d = demuxer()
        d.feed(pat(), 0, pat().size)
        d.feed(pmt(0x83), 0, pmt(0x83).size)
        val audio = audioPes(cc = 0, pts90k = 0, payload = ByteArray(8) { it.toByte() })
        d.feed(audio, 0, audio.size)

        assertEquals(TsDemuxer.AudioCodec.LPCM, audioUnits.single().first)
    }

    @Test
    fun `continuity gap reports discontinuity and drops the partial unit`() {
        val d = demuxer()
        d.feed(pat(), 0, pat().size)
        d.feed(pmt(0x1B), 0, pmt(0x1B).size)
        val first = videoPes(cc = 0, pts90k = 0, payload = byteArrayOf(0, 0, 0, 1, 0x65))
        d.feed(first, 0, first.size)
        // The unit should have continued at CC 1; this packet jumps to CC 4.
        val loss = videoPes(cc = 4, pts90k = 0, payload = byteArrayOf(0x77))
        d.feed(loss, 0, loss.size)

        assertEquals(1, videoLosses)
        assertTrue(videoUnits.isEmpty())
    }

    @Test
    fun `garbage between packets is skipped by resync`() {
        val d = demuxer()
        val patBytes = pat()
        val mixed = byteArrayOf(0x00, 0x11) + patBytes + byteArrayOf(0x22)
        d.feed(mixed, 0, mixed.size)

        val pmtBytes = pmt(0x1B)
        d.feed(pmtBytes, 0, pmtBytes.size)
        val pes = videoPes(cc = 0, pts90k = 0, payload = byteArrayOf(0, 0, 0, 1, 0x41))
        d.feed(pes, 0, pes.size)
        val second = videoPes(cc = 1, pts90k = 0, payload = byteArrayOf(0, 0, 0, 1, 0x42))
        d.feed(second, 0, second.size)
        assertEquals(1, videoUnits.size)
    }


    // ---- packet builders ----

    /**
     * Pads with the adaptation field, as a real muxer does: stuffing must never sit
     * inside the PES payload, or the demuxer cannot tell it from media data.
     */
    private fun packet(pid: Int, payloadUnitStart: Boolean, continuity: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 183) { "payload must leave room for the adaptation field" }
        val adaptationLength = 183 - payload.size
        val out = ByteArray(188)
        out[0] = 0x47
        out[1] = ((if (payloadUnitStart) 0x40 else 0) or ((pid shr 8) and 0x1F)).toByte()
        out[2] = (pid and 0xFF).toByte()
        out[3] = (0x30 or (continuity and 0x0F)).toByte()   // adaptation + payload
        out[4] = adaptationLength.toByte()
        if (adaptationLength > 0) {
            out[5] = 0x00                                   // flags: no PCR, no OPCR
            for (i in 6 until 5 + adaptationLength) out[i] = 0xFF.toByte()
        }
        payload.copyInto(out, 5 + adaptationLength)
        return out
    }

    private fun section(tableId: Int, body: ByteArray): ByteArray {
        val sectionLength = body.size + 4                   // body + CRC
        return byteArrayOf(
            tableId.toByte(),
            (0xB0 or ((sectionLength shr 8) and 0x0F)).toByte(),
            (sectionLength and 0xFF).toByte()
        ) + body + byteArrayOf(0, 0, 0, 0)
    }

    private fun pat(): ByteArray {
        val body = byteArrayOf(
            0x00, 0x01,                                     // transport_stream_id
            0xC1.toByte(), 0x00, 0x00,                      // version, section 0 of 0
            0x00, 0x01,                                     // program 1
            0x10, 0x00                                      // PMT PID 0x1000
        )
        val payload = byteArrayOf(0) + section(0x00, body)
        return packet(pid = 0, payloadUnitStart = true, continuity = 0, payload = payload)
    }

    private fun pmt(streamType: Int): ByteArray {
        val audioPid = 0x0101
        val body = byteArrayOf(
            0x00, 0x01,                                     // program number
            0xC1.toByte(), 0x00, 0x00,                      // version, section 0 of 0
            0xE1.toByte(), 0x00,                            // PCR PID 0x100
            0xF0.toByte(), 0x00,                            // program_info_length 0
            0x1B, 0xE1.toByte(), 0x00, 0xF0.toByte(), 0x00, // H.264, PID 0x100
            streamType.toByte(), (0xE0 or (audioPid shr 8)).toByte(), (audioPid and 0xFF).toByte(),
            0xF0.toByte(), 0x00
        )
        val payload = byteArrayOf(0) + section(0x02, body)
        return packet(pid = 0x1000, payloadUnitStart = true, continuity = 0, payload = payload)
    }

    /** Video PES: unbounded packet_length (0) so a unit only ends at the next PES start. */
    private fun videoPes(cc: Int, pts90k: Long, payload: ByteArray): ByteArray {
        val header = pesHeader(0xE0, pts90k, packetLength = 0) + payload
        return packet(pid = 0x0100, payloadUnitStart = true, continuity = cc, payload = header)
    }

    /** Audio PES: payload shorter than one TS packet, so the unit is emitted immediately. */
    private fun audioPes(cc: Int, pts90k: Long, payload: ByteArray): ByteArray {
        val header = pesHeader(0xC0, pts90k, packetLength = payload.size + 8)
        return packet(pid = 0x0101, payloadUnitStart = true, continuity = cc, payload = header + payload)
    }

    private fun pesHeader(streamId: Int, pts90k: Long, packetLength: Int): ByteArray = byteArrayOf(
        0x00, 0x00, 0x01, streamId.toByte(),                // PES start code + stream id
        (packetLength shr 8).toByte(), (packetLength and 0xFF).toByte(),
        0x80.toByte(), 0x80.toByte(),                       // marker bits, PTS present
        0x05,                                               // header_data_length
        0x21,
        (pts90k shr 22).toByte(),
        (0x01L or ((pts90k shr 14) and 0xFEL)).toByte(),
        (pts90k shr 7).toByte(),
        (0x01L or ((pts90k shl 1) and 0xFEL)).toByte()
    )
}
