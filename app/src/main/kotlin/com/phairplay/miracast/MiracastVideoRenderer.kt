// Ported from weekdayjast/MiracastReceiver (MIT). See THIRD_PARTY_NOTICES.md.
package com.phairplay.miracast

import android.view.Surface
import com.phairplay.airplay.VideoDecoder
import com.phairplay.util.Logger

/**
 * Renders Miracast video by feeding H.264 access units from [TsDemuxer] straight into
 * MediaCodec — same "decode on arrival" approach as AirPlay mirroring, without any
 * player buffering.
 *
 * SPS/PPS travel in-band in the TS stream (repeated before every IDR), so parameter
 * sets are captured first; frames before that are dropped. The decoder is rebuilt when
 * the display Surface changes (the Activity creates a new one after going background).
 *
 * @param surfaceProvider current Surface; null while the app has no display target
 */
class MiracastVideoRenderer(private val surfaceProvider: () -> Surface?) {

    private var decoder: VideoDecoder? = null
    private var configuredSurface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    @Volatile
    private var awaitingKeyframe = true

    private var droppedBeforeConfig = 0

    /**
     * Called on data loss, but playback is deliberately *not* paused: the source only
     * emits an IDR every few seconds and rejects `wfd-idr-request`, so gating on one
     * froze the screen for seconds on every lost packet. The demuxer already discards
     * the truncated access unit, so the next complete P-frame is fed as-is and simply
     * conceals the missing references until the source's next natural IDR.
     */
    fun onDiscontinuity() = Unit

    /** [TsDemuxer] callback. */
    fun onAccessUnit(unit: ByteArray, ptsUs: Long) {
        captureParameterSets(unit)

        val surface = surfaceProvider()
        if (surface == null || !surface.isValid) {
            releaseDecoder()
            return
        }

        // A new Surface (background → foreground) needs a fresh decoder, otherwise the
        // picture stays black forever.
        if (decoder == null || surface !== configuredSurface || decoder?.isHealthy == false) {
            if (!rebuildDecoder(surface)) {
                droppedBeforeConfig++
                if (droppedBeforeConfig % 60 == 0) {
                    Logger.d("Miracast: waiting for SPS/PPS, dropped $droppedBeforeConfig units")
                }
                return
            }
        }

        // Only a fresh decoder needs an IDR; after that, losses are concealed in-band.
        if (awaitingKeyframe) {
            if (!containsIdr(unit)) return
            awaitingKeyframe = false
            Logger.i("Miracast: keyframe found, decoding started")
        }

        decoder?.decodeNalUnit(unit, ptsUs)
    }

    private fun rebuildDecoder(surface: Surface): Boolean {
        val spsBytes = sps ?: return false
        val ppsBytes = pps ?: return false

        releaseDecoder()
        val (width, height) = VideoDecoder.parseSpsResolution(spsBytes) ?: (1920 to 1080)
        decoder = VideoDecoder(surface).also { it.initialize(spsBytes, ppsBytes, width, height) }
        configuredSurface = surface
        awaitingKeyframe = true
        Logger.i("Miracast decoder initialized: ${width}x$height")
        return true
    }

    /** Capture SPS (type 7) / PPS (type 8) without start codes, as VideoDecoder expects. */
    private fun captureParameterSets(unit: ByteArray) {
        if (sps != null && pps != null) return
        forEachNal(unit) { start, end ->
            val nalType = unit[start].toInt() and 0x1F
            if (nalType == 7 && sps == null) {
                sps = unit.copyOfRange(start, end)
                Logger.i("Miracast: SPS captured (${end - start} bytes)")
            } else if (nalType == 8 && pps == null) {
                pps = unit.copyOfRange(start, end)
                Logger.i("Miracast: PPS captured (${end - start} bytes)")
            }
        }
    }

    private fun containsIdr(unit: ByteArray): Boolean {
        var found = false
        forEachNal(unit) { start, _ ->
            if ((unit[start].toInt() and 0x1F) == 5) found = true
        }
        return found
    }

    /** Walk every NAL in the Annex B stream (callback receives [start, end) without start code). */
    private inline fun forEachNal(data: ByteArray, action: (start: Int, end: Int) -> Unit) {
        var i = 0
        var nalStart = -1
        while (i + 3 <= data.size) {
            val isStartCode3 = data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 1
            val isStartCode4 = i + 4 <= data.size && data[i].toInt() == 0 &&
                data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1
            if (isStartCode3 || isStartCode4) {
                if (nalStart >= 0) action(nalStart, i)
                i += if (isStartCode4) 4 else 3
                nalStart = i
            } else {
                i++
            }
        }
        if (nalStart in 0 until data.size) action(nalStart, data.size)
    }

    private fun releaseDecoder() {
        decoder?.release()
        decoder = null
        configuredSurface = null
    }

    fun release() {
        releaseDecoder()
        sps = null
        pps = null
        awaitingKeyframe = true
    }
}
