package com.phairplay.airplay

import com.phairplay.util.Logger
import java.io.InputStream

/**
 * RtpInterleaved — Reads binary RTP/RTCP frames from the RTSP TCP connection.
 *
 * WHY: After the RTSP RECORD response, the AirPlay sender switches the same
 * TCP connection from text-based RTSP to binary RTP interleaved framing
 * (RFC 2326 §10.12). Each frame looks like:
 *
 * ```
 * ┌──────┬────────────┬───────────────┬──────────────────────────────┐
 * │ '$'  │ channel(1) │ length(2, BE)  │ RTP/RTCP payload (N bytes)  │
 * │  1B  │     1B     │      2B        │            N bytes            │
 * └──────┴────────────┴───────────────┴──────────────────────────────┘
 * ```
 *
 * Channel assignments (negotiated in SETUP responses):
 * - 0 = video RTP
 * - 1 = video RTCP (control, we ignore)
 * - 2 = audio RTP  (if audio is also interleaved over TCP — rarely the case in AirPlay)
 *
 * In AirPlay screen mirroring, video RTP is always interleaved on the RTSP TCP
 * connection (channel 0). Audio typically goes over a separate UDP socket.
 *
 * HOW: Call [readLoop] from a background coroutine after RECORD is acknowledged.
 * It reads frames indefinitely until the stream ends or an error occurs.
 *
 * Example:
 *   // After RECORD response sent:
 *   RtpInterleaved.readLoop(
 *       inputStream = socket.getInputStream(),
 *       session = currentSession,
 *       onVideoNalUnit = { nalUnit, pts -> videoDecoder.decodeNalUnit(nalUnit, pts) },
 *       onStreamEnded = { /* handle disconnect */ }
 *   )
 */
object RtpInterleaved {

    /**
     * Reads RTP interleaved frames from [inputStream] until the connection ends.
     *
     * SECURITY: Frame length is capped at [MAX_RTP_FRAME_BYTES]. Oversized frames
     * are rejected to prevent heap exhaustion via malicious AirPlay senders.
     *
     * @param inputStream   The raw TCP InputStream from the RTSP socket, positioned
     *                      immediately after the 200 OK response to RECORD was sent.
     * @param onVideoNalUnit Called with each H.264 NAL unit extracted from a video RTP frame.
     *                      @param nalUnit  Raw NAL unit bytes (RTP header stripped).
     *                      @param ptsUs    Presentation timestamp in microseconds.
     * @param onStreamEnded Called when the stream ends cleanly (EOF) or with an error.
     */
    fun readLoop(
        inputStream: InputStream,
        onVideoNalUnit: (nalUnit: ByteArray, ptsUs: Long) -> Unit,
        onStreamEnded: () -> Unit
    ) {
        try {
            while (true) {
                // Read the 4-byte interleaved frame header: $ channel(1) length(2)
                val marker = inputStream.read()
                if (marker == -1) break  // clean EOF — sender disconnected

                // RTSP keeps-alive may send OPTIONS between RTP frames.
                // If the byte is not '$', skip until we find one.
                if (marker != INTERLEAVED_MARKER) {
                    Logger.v("RtpInterleaved: skipping non-$ byte 0x${marker.toString(16)}")
                    continue
                }

                val channel = inputStream.read()
                if (channel == -1) break

                val lenHigh = inputStream.read()
                val lenLow = inputStream.read()
                if (lenHigh == -1 || lenLow == -1) break

                val frameLength = (lenHigh shl 8) or lenLow

                // SECURITY: Reject unreasonably large frames
                if (frameLength <= 0 || frameLength > MAX_RTP_FRAME_BYTES) {
                    Logger.w("RtpInterleaved: invalid frame length $frameLength — stopping")
                    break
                }

                val frameData = ByteArray(frameLength)
                var bytesRead = 0
                while (bytesRead < frameLength) {
                    val n = inputStream.read(frameData, bytesRead, frameLength - bytesRead)
                    if (n == -1) {
                        Logger.w("RtpInterleaved: EOF mid-frame")
                        onStreamEnded()
                        return
                    }
                    bytesRead += n
                }

                // Only process video RTP frames (channel 0); ignore RTCP (channel 1)
                if (channel == CHANNEL_VIDEO_RTP) {
                    processVideoRtpFrame(frameData, onVideoNalUnit)
                }
                // Note: audio over interleaved TCP is rare in AirPlay; UDP is used instead.
            }
        } catch (e: Exception) {
            Logger.e("RtpInterleaved: read error — stream ended", e)
        }

        onStreamEnded()
    }

    /**
     * Parses a video RTP frame and extracts the H.264 NAL unit payload.
     *
     * RTP header structure (RFC 3550, fixed 12 bytes):
     * ```
     * ┌──┬──┬──┬──┬──────────┬───┬──────────────┬─────────────────────────────┐
     * │V │P │X │CC│    M     │PT │    Seq#(16)   │        Timestamp(32)        │
     * │(2)│(1)│(1)│(4)│(1)   │(7)│               │                             │
     * ├──────────────────────────────────────────┤─────────────────────────────┤
     * │             SSRC (32 bits)                │  [CSRC list, 0–15 entries]  │
     * └──────────────────────────────────────────┴─────────────────────────────┘
     * ```
     * After the 12-byte header (+ optional 4*CC CSRC bytes): the H.264 payload.
     *
     * RTP timestamp is in 90kHz clock units. Convert to µs: ts * 1_000_000 / 90_000.
     *
     * AirPlay uses Single NAL Unit mode and FU-A (Fragmentation Unit) packetization.
     * For now we pass the entire RTP payload as a NAL unit — the VideoDecoder's
     * MediaCodec handles FU-A reassembly internally for most chipsets.
     *
     * @param rtpFrame  Full RTP frame bytes (header + payload).
     * @param callback  Called with (nalUnit, presentationTimeUs).
     */
    private fun processVideoRtpFrame(
        rtpFrame: ByteArray,
        callback: (ByteArray, Long) -> Unit
    ) {
        if (rtpFrame.size < RTP_FIXED_HEADER_BYTES) {
            Logger.w("RtpInterleaved: RTP frame too small (${rtpFrame.size} bytes)")
            return
        }

        // CSRC count (CC) is in the lower 4 bits of byte 0
        val csrcCount = rtpFrame[0].toInt() and 0x0F
        val headerSize = RTP_FIXED_HEADER_BYTES + csrcCount * 4

        // Check for RTP header extension (X bit = bit 4 of byte 0)
        val hasExtension = (rtpFrame[0].toInt() and 0x10) != 0
        var payloadOffset = headerSize
        if (hasExtension && rtpFrame.size >= payloadOffset + 4) {
            // Extension header: 2-byte profile + 2-byte length (in 32-bit words)
            val extLengthWords = ((rtpFrame[payloadOffset + 2].toInt() and 0xFF) shl 8) or
                                  (rtpFrame[payloadOffset + 3].toInt() and 0xFF)
            payloadOffset += 4 + extLengthWords * 4
        }

        if (payloadOffset >= rtpFrame.size) {
            Logger.w("RtpInterleaved: no payload after RTP header")
            return
        }

        // Extract the 32-bit timestamp from bytes 4–7 (big-endian)
        val rtpTimestamp = ((rtpFrame[4].toLong() and 0xFF) shl 24) or
                           ((rtpFrame[5].toLong() and 0xFF) shl 16) or
                           ((rtpFrame[6].toLong() and 0xFF) shl 8) or
                            (rtpFrame[7].toLong() and 0xFF)

        // Convert from 90kHz RTP clock to microseconds
        val ptsUs = rtpTimestamp * 1_000_000L / RTP_VIDEO_CLOCK_HZ

        val nalUnit = rtpFrame.copyOfRange(payloadOffset, rtpFrame.size)
        callback(nalUnit, ptsUs)
    }

    companion object {
        /** The `$` byte that marks the start of an interleaved RTP frame. */
        private const val INTERLEAVED_MARKER = 0x24  // '$'

        /** RTP channel 0 = video RTP data. */
        private const val CHANNEL_VIDEO_RTP = 0

        /** Fixed RTP header size (no CSRC, no extension). */
        private const val RTP_FIXED_HEADER_BYTES = 12

        /**
         * H.264 video uses a 90 kHz RTP clock (standard for video, per RFC 6184).
         * Used to convert RTP timestamps to microsecond presentation timestamps.
         */
        private const val RTP_VIDEO_CLOCK_HZ = 90_000L

        /**
         * Maximum acceptable RTP frame size.
         * Real AirPlay video frames are typically 1–200 KB.
         * Cap at 2 MB to reject clearly malformed/malicious frames.
         */
        private const val MAX_RTP_FRAME_BYTES = 2 * 1024 * 1024  // 2 MB
    }
}
