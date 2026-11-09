// Ported from weekdayjast/MiracastReceiver (MIT). See THIRD_PARTY_NOTICES.md.
package com.phairplay.miracast

import android.os.SystemClock
import android.view.Surface
import com.phairplay.airplay.VideoDecoder
import com.phairplay.util.Logger
import com.phairplay.util.MirrorProbe

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
     * Last timestamp handed to the codec.
     *
     * Defensive guard against a non-increasing media clock. Measured on this source the pts is
     * in fact clean (+33 ms per frame, `dupPts=0`), so this is a no-op in normal sessions; it
     * only protects senders that repeat or rewind timestamps. Rendering is immediate here —
     * the pts never drives a render schedule — so re-basing duplicates costs nothing.
     */
    private var lastQueuedPtsUs = Long.MIN_VALUE

    /**
     * Set by the freeze watchdog when the decoder is swallowing frames.
     *
     * MediaCodec must only be released/recreated on the decode thread, so the watchdog flips
     * this flag and [onAccessUnit] performs the rebuild on its next call.
     */
    @Volatile
    private var resetRequested = false

    /** Ask the decode thread to build a fresh MediaCodec instance; see [resetRequested]. */
    fun requestDecoderReset() {
        resetRequested = true
    }

    /**
     * Last time a **decoded frame reached the Surface** (`elapsedRealtime` ms), 0 before the
     * first picture. This is the only honest freeze signal: access units keep arriving after
     * a lost GOP and the codec keeps accepting them, so arrival and decode counters all look
     * healthy while the screen is frozen.
     */
    @Volatile
    var lastFrameRenderedAt = 0L
        private set

    /** Last time a complete access unit was actually fed to the codec (`elapsedRealtime` ms). */
    @Volatile
    var lastAccessUnitAt = 0L
        private set

    /** Access units handed to the codec since [release]; the freeze watchdog compares rates. */
    @Volatile
    var fedFrames = 0L
        private set

    /** Frames that actually reached the Surface since [release]. */
    @Volatile
    var shownFrames = 0L
        private set

    /** Keyframes received after the first one — evidence that the source honours IDR requests. */
    @Volatile
    var idrFrames = 0L
        private set

    /** Time of the last keyframe (`elapsedRealtime` ms), 0 while none has been seen. */
    @Volatile
    var lastIdrAt = 0L
        private set

    /**
     * Called on data loss, but playback is deliberately *not* paused: gating on a keyframe
     * froze the screen for seconds on every lost packet. The demuxer already discards the
     * truncated access unit, so the next complete P-frame is fed as-is. This source emits an
     * IDR about once a second (measured), so concealment is short-lived anyway.
     */
    fun onDiscontinuity() = Unit

    /** [TsDemuxer] callback. */
    fun onAccessUnit(unit: ByteArray, ptsUs: Long) {
        // A decoder stuck in its "accepts input, returns nothing" state cannot be fixed with an
        // IDR (verified: extra IDRs arrived within 100 ms of each request and the frame rate did
        // not recover). Rebuilding MediaCodec is the only working recovery, and it must happen
        // on this thread — the codec is not thread-safe.
        if (resetRequested) {
            resetRequested = false
            releaseDecoder()
            Logger.w("Miracast: rebuilding decoder after sustained frame loss")
        }
        // One pass over the NALs answers three questions that used to need three walks:
        // which parameter sets are present, whether this unit can produce a picture, and
        // whether it is an IDR. "Can produce a picture" matters diagnostically: MediaCodec
        // accepts SPS/PPS/SEI-only units and emits nothing for them, so counting them as
        // arrived makes the decoder look like it is losing frames when it is not.
        var hasPicture = false
        var hasIdr = false
        forEachNal(unit) { start, end ->
            when (unit[start].toInt() and 0x1F) {
                7 -> if (sps == null) {
                    sps = unit.copyOfRange(start, end)
                    Logger.i("Miracast: SPS captured (${end - start} bytes)")
                }
                8 -> if (pps == null) {
                    pps = unit.copyOfRange(start, end)
                    Logger.i("Miracast: PPS captured (${end - start} bytes)")
                }
                5 -> { hasPicture = true; hasIdr = true }
                1 -> hasPicture = true
            }
        }
        MirrorProbe.onAccessUnit(ptsUs, unit.size, hasPicture)

        // Every IDR after the first is logged: it is how we proved the source answers
        // wfd_idr_request with a real keyframe (200 OK + extra IDR within ~100 ms).
        if (hasIdr && !awaitingKeyframe) {
            idrFrames++
            lastIdrAt = SystemClock.elapsedRealtime()
            Logger.i("Miracast: IDR received (#$idrFrames)")
        }

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
                MirrorProbe.onDroppedBeforeConfig()
                if (droppedBeforeConfig % 60 == 0) {
                    Logger.d("Miracast: waiting for SPS/PPS, dropped $droppedBeforeConfig units")
                }
                return
            }
        }

        // Only a fresh decoder needs an IDR; after that, losses are concealed in-band.
        if (awaitingKeyframe) {
            if (!hasIdr) return
            awaitingKeyframe = false
            MirrorProbe.onKeyframe()
            Logger.i("Miracast: keyframe found, decoding started")
        }

        decoder?.decodeNalUnit(unit, monotonicPts(ptsUs))
        fedFrames++
        lastAccessUnitAt = SystemClock.elapsedRealtime()
    }

    /**
     * Returns a strictly increasing pts for the codec, advancing by at least 1 ms when the
     * source repeats or rewinds its own timestamp. The absolute value is irrelevant here: the
     * decoder is driven by input, not by a render schedule (see [lastQueuedPtsUs]).
     */
    private fun monotonicPts(ptsUs: Long): Long {
        val previous = lastQueuedPtsUs
        val next = if (previous == Long.MIN_VALUE || ptsUs > previous) ptsUs else previous + 1_000L
        lastQueuedPtsUs = next
        return next
    }

    private fun rebuildDecoder(surface: Surface): Boolean {
        val spsBytes = sps ?: return false
        val ppsBytes = pps ?: return false

        releaseDecoder()
        val (width, height) = VideoDecoder.parseSpsResolution(spsBytes) ?: (1920 to 1080)
        decoder = VideoDecoder(surface).also {
            // Only used for freeze detection; AirPlay leaves it null.
            it.onFrameRendered = {
                lastFrameRenderedAt = SystemClock.elapsedRealtime()
                shownFrames++
            }
            it.initialize(spsBytes, ppsBytes, width, height)
        }
        configuredSurface = surface
        awaitingKeyframe = true
        lastQueuedPtsUs = Long.MIN_VALUE
        // Deliberately keep fedFrames/shownFrames/lastFrameRenderedAt: they are cumulative
        // counters read by the watchdog. Resetting them in the middle of a session would make
        // its deltas negative and, worse, make it think no picture has ever been shown — which
        // would disable the very recovery that just rebuilt the decoder.
        Logger.i("Miracast decoder initialized: ${width}x$height")
        return true
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
        lastQueuedPtsUs = Long.MIN_VALUE
        lastFrameRenderedAt = 0
        lastAccessUnitAt = 0
        fedFrames = 0
        shownFrames = 0
        MirrorProbe.resetSession()
    }
}
