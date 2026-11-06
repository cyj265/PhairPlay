// Ported from weekdayjast/MiracastReceiver (MIT). See THIRD_PARTY_NOTICES.md.
package com.phairplay.miracast

import com.phairplay.util.Logger
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Minimal MPEG-2 TS demuxer for Miracast RTP payloads.
 *
 * Miracast wraps A/V in TS (RTP payload type 33), 7 × 188-byte packets per RTP packet:
 *
 * ```
 * PID 0   → PAT → find the PMT PID
 * PMT     → stream_type 0x1B (H.264) video PID, 0x0F (AAC) / 0x83 (LPCM) audio PID
 * video   → PES → payload is Annex B H.264, fed straight into MediaCodec
 * audio   → PES → payload handed to MiracastAudioPlayer
 * ```
 *
 * @param onAccessUnit complete video access unit (Annex B + PTS in microseconds)
 * @param onDiscontinuity packet loss detected; the receiver must wait for the next keyframe
 * @param onAudioPes complete audio PES payload (codec + bytes)
 * @param onAudioDiscontinuity audio packet loss detected; resync required
 */
class TsDemuxer(
    private val onAccessUnit: (data: ByteArray, ptsUs: Long) -> Unit,
    private val onDiscontinuity: () -> Unit = {},
    private val onAudioPes: (codec: AudioCodec, data: ByteArray) -> Unit = { _, _ -> },
    private val onAudioDiscontinuity: () -> Unit = {}
) {

    enum class AudioCodec { AAC_ADTS, LPCM }

    private var pmtPid = -1
    private var videoPid = -1
    private var audioPid = -1
    private var audioCodec = AudioCodec.AAC_ADTS

    private val videoPes = PesAssembler("video") { data, pts -> onAccessUnit(data, pts) }
    private val audioPes = PesAssembler("audio") { data, _ -> onAudioPes(audioCodec, data) }

    companion object {
        private const val TS_PACKET_SIZE = 188
        private const val SYNC_BYTE = 0x47.toByte()
        private const val STREAM_TYPE_H264 = 0x1B
        private const val STREAM_TYPE_HEVC = 0x24
        private const val STREAM_TYPE_AAC_ADTS = 0x0F
        private const val STREAM_TYPE_LPCM = 0x83    // WFD LPCM is private stream 0x83
    }

    /** Feed a chunk of TS data (188-byte aligned; RTP payloads always are). */
    fun feed(data: ByteArray, offset: Int, length: Int) {
        var pos = offset
        val end = offset + length
        while (pos + TS_PACKET_SIZE <= end) {
            if (data[pos] != SYNC_BYTE) {
                pos++            // lost sync, scan byte by byte
                continue
            }
            handlePacket(data, pos)
            pos += TS_PACKET_SIZE
        }
    }

    private fun handlePacket(data: ByteArray, start: Int) {
        val b1 = data[start + 1].toInt() and 0xFF
        val b2 = data[start + 2].toInt() and 0xFF
        val b3 = data[start + 3].toInt() and 0xFF

        if ((b1 and 0x80) != 0) return                       // transport_error_indicator
        val payloadUnitStart = (b1 and 0x40) != 0
        val pid = ((b1 and 0x1F) shl 8) or b2
        val adaptationControl = (b3 shr 4) and 0x03

        if (adaptationControl == 0 || adaptationControl == 2) return   // no payload

        var payloadStart = start + 4
        if (adaptationControl == 3) {
            val adaptationLength = data[start + 4].toInt() and 0xFF
            payloadStart = start + 5 + adaptationLength
        }
        val payloadEnd = start + TS_PACKET_SIZE
        if (payloadStart >= payloadEnd) return

        val continuity = b3 and 0x0F
        when (pid) {
            0 -> parsePat(data, payloadStart, payloadEnd, payloadUnitStart)
            pmtPid -> parsePmt(data, payloadStart, payloadEnd, payloadUnitStart)
            videoPid -> {
                // A frame that lost packets is corrupt and would smear across the GOP.
                if (videoPes.checkContinuity(continuity)) onDiscontinuity()
                videoPes.onPacket(data, payloadStart, payloadEnd, payloadUnitStart)
            }
            audioPid -> {
                if (audioPes.checkContinuity(continuity)) onAudioDiscontinuity()
                audioPes.onPacket(data, payloadStart, payloadEnd, payloadUnitStart)
            }
        }
    }

    /** PAT: take the PMT PID of the first valid program. */
    private fun parsePat(data: ByteArray, start: Int, end: Int, payloadUnitStart: Boolean) {
        if (pmtPid >= 0) return
        var pos = start
        if (payloadUnitStart) pos += 1 + (data[pos].toInt() and 0xFF)   // pointer_field
        if (pos + 12 > end) return
        if ((data[pos].toInt() and 0xFF) != 0x00) return                // table_id must be PAT

        val sectionLength = ((data[pos + 1].toInt() and 0x0F) shl 8) or (data[pos + 2].toInt() and 0xFF)
        // Program loop starts after the section header; last 4 bytes are CRC.
        var entry = pos + 8
        val loopEnd = minOf(pos + 3 + sectionLength - 4, end)
        while (entry + 4 <= loopEnd) {
            val programNumber = ((data[entry].toInt() and 0xFF) shl 8) or (data[entry + 1].toInt() and 0xFF)
            val pid = ((data[entry + 2].toInt() and 0x1F) shl 8) or (data[entry + 3].toInt() and 0xFF)
            if (programNumber != 0) {
                pmtPid = pid
                Logger.i("TS: PMT pid = $pmtPid (program $programNumber)")
                return
            }
            entry += 4
        }
    }

    /** PMT: pick the H.264 (or HEVC) video PID and the AAC / LPCM audio PID. */
    private fun parsePmt(data: ByteArray, start: Int, end: Int, payloadUnitStart: Boolean) {
        if (videoPid >= 0) return
        var pos = start
        if (payloadUnitStart) pos += 1 + (data[pos].toInt() and 0xFF)
        if (pos + 12 > end) return
        if ((data[pos].toInt() and 0xFF) != 0x02) return                // table_id must be PMT

        val sectionLength = ((data[pos + 1].toInt() and 0x0F) shl 8) or (data[pos + 2].toInt() and 0xFF)
        val programInfoLength = ((data[pos + 10].toInt() and 0x0F) shl 8) or (data[pos + 11].toInt() and 0xFF)

        var entry = pos + 12 + programInfoLength
        val loopEnd = minOf(pos + 3 + sectionLength - 4, end)
        while (entry + 5 <= loopEnd) {
            val streamType = data[entry].toInt() and 0xFF
            val pid = ((data[entry + 1].toInt() and 0x1F) shl 8) or (data[entry + 2].toInt() and 0xFF)
            val esInfoLength = ((data[entry + 3].toInt() and 0x0F) shl 8) or (data[entry + 4].toInt() and 0xFF)

            when (streamType) {
                STREAM_TYPE_H264, STREAM_TYPE_HEVC -> if (videoPid < 0) {
                    videoPid = pid
                    Logger.i("TS: video pid = $videoPid (stream_type 0x%02X)".format(Locale.ROOT, streamType))
                }
                STREAM_TYPE_AAC_ADTS, STREAM_TYPE_LPCM -> if (audioPid < 0) {
                    audioPid = pid
                    audioCodec = if (streamType == STREAM_TYPE_LPCM) AudioCodec.LPCM else AudioCodec.AAC_ADTS
                    Logger.i("TS: audio pid = $audioPid (stream_type 0x%02X, $audioCodec)".format(Locale.ROOT, streamType))
                }
            }
            entry += 5 + esInfoLength
        }
        if (audioPid < 0) Logger.w("TS: no audio stream in PMT")
    }

    fun reset() {
        pmtPid = -1
        videoPid = -1
        audioPid = -1
        videoPes.reset()
        audioPes.reset()
    }

    /**
     * Reassembles PES payloads from TS packets of one PID.
     *
     * Video PES packet_length is usually 0 (unbounded), so a frame only ends at the next
     * payload_unit_start — one frame of inherent latency. Audio PES is bounded and is
     * emitted as soon as it is complete.
     */
    private class PesAssembler(
        private val name: String,
        private val onUnit: (data: ByteArray, ptsUs: Long) -> Unit
    ) {
        private val buffer = ByteArrayOutputStream(64 * 1024)
        private var pendingPts = 0L
        private var hasPending = false
        /** Expected payload bytes; 0 means unbounded. */
        private var expectedLength = 0

        private var lastContinuity = -1
        private var lossCount = 0

        /**
         * Continuity counter increments per packet (mod 16); a jump means TS-level loss.
         * @return true when loss was detected and the partial unit was discarded
         */
        fun checkContinuity(continuity: Int): Boolean {
            val gap = lastContinuity >= 0 && continuity != (lastContinuity + 1) % 16
            lastContinuity = continuity
            if (!gap) return false
            lossCount++
            Logger.w("TS: $name continuity gap, total $lossCount")
            discardPending()
            return true
        }

        fun onPacket(data: ByteArray, start: Int, end: Int, payloadUnitStart: Boolean) {
            var pos = start

            if (payloadUnitStart) {
                flush()                                      // previous PES ends here

                if (pos + 9 > end) return
                if (data[pos].toInt() != 0x00 || data[pos + 1].toInt() != 0x00 ||
                    (data[pos + 2].toInt() and 0xFF) != 0x01
                ) return

                val packetLength = ((data[pos + 4].toInt() and 0xFF) shl 8) or (data[pos + 5].toInt() and 0xFF)
                val flags2 = data[pos + 7].toInt() and 0xFF
                val headerDataLength = data[pos + 8].toInt() and 0xFF
                val ptsPresent = (flags2 and 0x80) != 0

                if (ptsPresent && pos + 14 <= end) {
                    pendingPts = readPts(data, pos + 9)
                }
                // packet_length counts from byte 6 onward, minus the 3-byte optional header
                // and any header extension.
                expectedLength = if (packetLength > 0) packetLength - 3 - headerDataLength else 0
                hasPending = true
                pos += 9 + headerDataLength
                if (pos >= end) return
            }

            if (!hasPending) return
            val available = end - pos
            // A bounded PES may end before the packet does; anything after it is TS
            // stuffing, not payload, and must not reach the decoder.
            val take = if (expectedLength > 0) {
                minOf(available, (expectedLength - buffer.size()).coerceAtLeast(0))
            } else {
                available
            }
            buffer.write(data, pos, take)
            if (expectedLength > 0 && buffer.size() >= expectedLength) flush()
        }

        /** 33-bit PTS from the PES header, in 90kHz units, converted to microseconds. */
        private fun readPts(data: ByteArray, p: Int): Long {
            val b0 = data[p].toLong() and 0xFF
            val b1 = data[p + 1].toLong() and 0xFF
            val b2 = data[p + 2].toLong() and 0xFF
            val b3 = data[p + 3].toLong() and 0xFF
            val b4 = data[p + 4].toLong() and 0xFF
            val pts90k = ((b0 and 0x0E) shl 29) or
                (b1 shl 22) or ((b2 and 0xFE) shl 14) or
                (b3 shl 7) or ((b4 and 0xFE) shr 1)
            return pts90k * 1000 / 90
        }

        private fun flush() {
            if (!hasPending || buffer.size() == 0) {
                discardPending()
                return
            }
            val unit = buffer.toByteArray()
            discardPending()
            onUnit(unit, pendingPts)
        }

        private fun discardPending() {
            buffer.reset()
            hasPending = false
        }

        fun reset() {
            lastContinuity = -1
            discardPending()
        }
    }
}
