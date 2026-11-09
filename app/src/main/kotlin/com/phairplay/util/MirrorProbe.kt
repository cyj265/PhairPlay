package com.phairplay.util

import android.os.SystemClock
import android.util.Log
import com.phairplay.BuildConfig
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * DEBUG-ONLY playback diagnostics for Miracast mirroring.
 *
 * WHY this exists: the release build emits no per-frame logging, and the mirror picture is
 * drawn into a SurfaceView that shares its layer name with another view in the same Activity,
 * so `dumpsys SurfaceFlinger --latency` cannot address it unambiguously. Without counters there
 * is no way to tell apart the three very different causes of a stutter report:
 *
 *  - [auArrived] — the network/demuxer stopped delivering access units (upstream starved),
 *  - [decodeNanos] / [inputTimeouts] — the decoder fell behind and had to drop input,
 *  - [renderBlockNanos] — `releaseOutputBuffer(render=true)` blocked, i.e. the Surface's
 *    BufferQueue (~3 frames) was full and back-pressured the whole pipeline.
 *
 * v2 (startup-stutter investigation) adds the measurements that separate "queued inside the
 * codec" from "waiting on the Surface":
 *
 *  - [codecLatencyUs] — wall time from `queueInputBuffer(pts)` to `releaseOutputBuffer(pts)`.
 *    This is the honest per-frame residence time inside the codec, including decode, internal
 *    output queueing and the Surface hand-off. A rising value with a flat [decodeNanos] means
 *    the frames are parked in the codec's output pipeline, not being decoded slowly.
 *  - [inflight] — `au - rendered`: how many frames have been fed in but not yet shown. Unlike
 *    the pts-based backlog this is not confused by timestamp discontinuities.
 *  - [inputWaitUs] — how long `dequeueInputBuffer` had to wait for a free input buffer. If this
 *    grows, the codec refuses input because its output stage is stuck.
 *  - block histogram — how often the Surface hand-off exceeded one vsync, two, three.
 *  - [lagDriftUs] — rendered frame's `now - pts` drift above the session's best value. This is
 *    what the viewer perceives as "the picture fell behind".
 *
 * A one-second reporter thread prints the deltas. Every hook is a no-op unless [enabled].
 */
object MirrorProbe {

    private const val TAG = "MirrorProbe"

    /**
     * A frame still waiting inside the codec this long was not queued behind anything — it
     * never came back out. 1.5 s is far beyond the deepest output pipeline on this SoC.
     */
    private const val SILENT_DROP_MS = 1_500L

    /** Debug builds only — keeps release logging and hot paths untouched. */
    private val enabled: Boolean = BuildConfig.DEBUG

    private val auArrived = AtomicLong()
    private val framesRendered = AtomicLong()
    private val inputTimeouts = AtomicLong()
    private val decoderErrors = AtomicLong()
    private val keyframes = AtomicLong()
    private val droppedBeforeConfig = AtomicLong()

    /** Wall-clock time inside decodeNalUnit (input queue + output drain). */
    private val decodeNanos = AtomicLong()
    private val decodeCalls = AtomicLong()
    private val decodeMaxNanos = AtomicLong()

    /** Time spent blocked inside releaseOutputBuffer(..., true). */
    private val renderBlockNanos = AtomicLong()
    private val renderMaxBlockNanos = AtomicLong()

    /** Gap between two consecutively rendered frames, as reported by the bitstream pts. */
    private val ptsGapMaxUs = AtomicLong()
    private val ptsGapSumUs = AtomicLong()
    private val ptsGapCount = AtomicLong()

    private val lastAuPtsUs = AtomicLong(Long.MIN_VALUE)
    private val lastRenderedPtsUs = AtomicLong(Long.MIN_VALUE)
    private val renderedSinceReset = AtomicLong()

    /**
     * Access units whose pts did not advance over the previous one.
     *
     * The stream's media clock is coarser than its frame rate, so a steady share of frames
     * normally repeats the previous timestamp. A *collapse* in the frame rate coincides with
     * this either exploding (the source is re-sending the same instant) or freezing (the clock
     * stopped), which is the difference between a sender problem and a decoder problem.
     */
    private val auPtsNonIncreasing = AtomicLong()

    // ---- v2: codec-residency instrumentation ------------------------------------------
    //
    // Frames are matched input→output by pts, but pts is NOT unique on this stream: the
    // demuxer hands out repeated timestamps, so a map keyed by pts silently matched a frame
    // that had just been enqueued microseconds earlier (measured "residence" of 0.2 ms, which
    // is physically impossible). The queue below keeps every enqueue event in order and pops
    // the oldest entry with the same pts, which is correct even when timestamps repeat.
    private val inflightQ = ArrayDeque<LongArray>(128)   // {pts, enqueueNanos}
    private val inputQueueDepth = AtomicLong()

    private val codecLatencySumUs = AtomicLong()
    private val codecLatencyMaxUs = AtomicLong()
    private val codecLatencyCount = AtomicLong()
    private val codecLatencyMisses = AtomicLong()

    /** Access units actually accepted by the codec (vs [auArrived], which counts offers). */
    private val auQueued = AtomicLong()

    /**
     * Access units that carry no picture at all — only SPS/PPS/SEI/AUD.
     *
     * MediaCodec accepts these happily and emits nothing for them, so they inflate the
     * "arrived" count without ever producing a frame. If this is a large share of the
     * stream, the stutter is a demuxer problem, not a decoder problem.
     */
    private val auNoPicture = AtomicLong()
    private val auBytes = AtomicLong()

    /** First-AU → first-rendered-frame, i.e. how long the decoder takes to show anything. */
    private val firstAuNanos = AtomicLong(0)
    private val warmupMs = AtomicLong(-1)

    private val inputWaitSumUs = AtomicLong()
    private val inputWaitMaxUs = AtomicLong()
    private val inputWaitCount = AtomicLong()

    /** Surface hand-off durations bucketed by vsync multiples. */
    private val blockOver8 = AtomicLong()
    private val blockOver16 = AtomicLong()
    private val blockOver33 = AtomicLong()
    private val blockOver50 = AtomicLong()

    /** `now - pts` at render time, in µs; drift is measured against [lagMinUs]. */
    private val lagMinUs = AtomicLong(Long.MAX_VALUE)
    private val lagNowUs = AtomicLong(Long.MIN_VALUE)
    private val lagDriftMaxUs = AtomicLong()

    private val peakBacklogMs = AtomicLong(Long.MIN_VALUE)
    private val peakLoggedAt = AtomicLong()

    // ---- audio side of the same session ------------------------------------------------
    //
    // Audio is decoded on its own thread, so it can drag the whole SoC down without ever
    // showing up in the video counters. The three suspects are logged separately:
    // [audioWriteNanos] (blocking AudioTrack writes), [audioFlush] (pause→flush→play, which
    // reconfigures the audio HAL on this SoC) and [audioUnderrun] (the speaker starved).
    private val audioPes = AtomicLong()
    private val audioFrames = AtomicLong()
    private val audioWriteNanos = AtomicLong()
    private val audioWriteMaxNanos = AtomicLong()
    private val audioFlush = AtomicLong()
    private val audioUnderrun = AtomicLong()
    private val audioQueueMs = AtomicLong(-1)
    private val audioPendingMs = AtomicLong(-1)
    private val audioRate = AtomicLong(0)
    private val audioChannels = AtomicLong(0)

    /** Frames the codec consumed but never returned — silently lost, not "slow". */
    private val codecSwallowed = AtomicLong()

    @Volatile
    private var reporterStarted = false

    // ---------------------------------------------------------------- producer hooks

    /** A complete H.264 access unit was delivered to the renderer. */
    fun onAccessUnit(ptsUs: Long, bytes: Int = 0, hasPicture: Boolean = true) {
        if (!enabled) return
        if (auArrived.getAndIncrement() == 0L) {
            firstAuNanos.set(SystemClock.elapsedRealtimeNanos())
            warmupMs.set(-1)
        }
        auBytes.addAndGet(bytes.toLong())
        if (!hasPicture) auNoPicture.incrementAndGet()
        val previous = lastAuPtsUs.getAndSet(ptsUs)
        if (previous != Long.MIN_VALUE && ptsUs <= previous) auPtsNonIncreasing.incrementAndGet()
        ensureReporter()
    }

    /** An access unit was accepted by the codec's input queue. */
    fun onFrameQueued(ptsUs: Long) {
        if (!enabled) return
        auQueued.incrementAndGet()
        val now = SystemClock.elapsedRealtimeNanos()
        // Only the decode thread touches this queue (enqueue and render both happen there),
        // so no locking is needed beyond bounding it.
        if (inflightQ.size > 300) inflightQ.clear()
        // Anything that has sat here longer than the network could possibly hold is a frame
        // the codec swallowed without ever handing it back. Nothing else observes that: it is
        // invisible to dropInput (input was accepted) and to renderBlock (the Surface never
        // pushed back). On this SoC that is the difference between "slow" and "frames lost".
        while (inflightQ.isNotEmpty()) {
            val head = inflightQ.first()
            if ((now - head[1]) / 1_000_000 < SILENT_DROP_MS) break
            inflightQ.removeFirst()
            inputQueueDepth.decrementAndGet()
            codecSwallowed.incrementAndGet()
        }
        inflightQ.addLast(longArrayOf(ptsUs, now))
        inputQueueDepth.incrementAndGet()
    }

    /** `dequeueInputBuffer` returned, successfully or not, after waiting [nanos]. */
    fun onInputWait(nanos: Long, gotBuffer: Boolean) {
        if (!enabled) return
        val us = nanos / 1_000
        inputWaitSumUs.addAndGet(us)
        inputWaitCount.incrementAndGet()
        inputWaitMaxUs.accumulateAndGet(us) { a, b -> if (b > a) b else a }
        if (!gotBuffer) inputTimeouts.incrementAndGet()
    }

    /** A decoded frame was handed to the Surface (i.e. actually displayed). */
    fun onFrameRendered(ptsUs: Long) {
        if (!enabled) return
        framesRendered.incrementAndGet()
        renderedSinceReset.incrementAndGet()

        val nowNanos = SystemClock.elapsedRealtimeNanos()

        // Codec residence: how long this exact frame lived between input and display.
        // Pop the oldest enqueue event carrying the same pts — pts repeats on this stream,
        // so matching by key alone reported absurd sub-millisecond residence times.
        var matched = false
        val it = inflightQ.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry[0] == ptsUs) {
                it.remove()
                val latUs = (nowNanos - entry[1]) / 1_000
                if (latUs in 0..5_000_000L) {
                    codecLatencySumUs.addAndGet(latUs)
                    codecLatencyCount.incrementAndGet()
                    codecLatencyMaxUs.accumulateAndGet(latUs) { a, b -> if (b > a) b else a }
                }
                matched = true
                break
            }
        }
        if (!matched) codecLatencyMisses.incrementAndGet()
        inputQueueDepth.decrementAndGet()

        if (warmupMs.get() < 0) {
            val first = firstAuNanos.get()
            if (first != 0L) warmupMs.set((nowNanos - first) / 1_000_000)
        }

        // Perceived lag: how far the displayed frame's timestamp is behind the wall clock.
        // The absolute value carries the sender's media-clock baseline, so only drift above
        // the session minimum is meaningful.
        val lagUs = (nowNanos / 1_000) - ptsUs
        if (lagUs in -5_000_000L..60_000_000L) {
            lagNowUs.set(lagUs)
            val min = lagMinUs.accumulateAndGet(lagUs) { a, b -> if (b < a) b else a }
            if (min != Long.MAX_VALUE) {
                lagDriftMaxUs.accumulateAndGet(lagUs - min) { a, b -> if (b > a) b else a }
            }
        }

        val previous = lastRenderedPtsUs.getAndSet(ptsUs)
        if (previous != Long.MIN_VALUE && ptsUs > previous) {
            val gap = ptsUs - previous
            // Ignore gaps that are reorder noise or a pts discontinuity (receiver restart).
            if (gap in 1..2_000_000L) {
                ptsGapSumUs.addAndGet(gap)
                ptsGapCount.incrementAndGet()
                ptsGapMaxUs.accumulateAndGet(gap) { a, b -> if (b > a) b else a }
            }
        }
    }

    /** Milliseconds `releaseOutputBuffer(render = true)` blocked on the Surface queue. */
    fun onRenderBlock(nanos: Long) {
        if (!enabled) return
        renderBlockNanos.addAndGet(nanos)
        renderMaxBlockNanos.accumulateAndGet(nanos) { a, b -> if (b > a) b else a }

        val ms = nanos / 1_000_000.0
        if (ms > 50) blockOver50.incrementAndGet()
        if (ms > 33) blockOver33.incrementAndGet()
        if (ms > 16) blockOver16.incrementAndGet()
        if (ms > 8) blockOver8.incrementAndGet()
    }

    /** Duration of one full decodeNalUnit call (synchronized on the codec lock). */
    fun onDecodeCall(nanos: Long) {
        if (!enabled) return
        decodeCalls.incrementAndGet()
        decodeNanos.addAndGet(nanos)
        decodeMaxNanos.accumulateAndGet(nanos) { a, b -> if (b > a) b else a }
    }

    /** `dequeueInputBuffer` timed out — the decoder could not keep up and a unit was dropped. */
    fun onInputTimeout() {
        if (!enabled) return
        inputTimeouts.incrementAndGet()
    }

    /** MediaCodec threw and had to be recreated. */
    fun onDecoderError() {
        if (!enabled) return
        decoderErrors.incrementAndGet()
    }

    /** First IDR after (re)configuration — decoding resumes here. */
    fun onKeyframe() {
        if (!enabled) return
        keyframes.incrementAndGet()
    }

    /** Access units discarded because SPS/PPS had not been captured yet. */
    fun onDroppedBeforeConfig() {
        if (!enabled) return
        droppedBeforeConfig.incrementAndGet()
    }

    // ---------------------------------------------------------------- audio hooks

    /** An audio PES arrived from the demuxer (before any queueing/dropping). */
    fun onAudioPes() {
        if (!enabled) return
        audioPes.incrementAndGet()
        ensureReporter()
    }

    /** One AAC/PCM payload was decoded into PCM. */
    fun onAudioFrames(rate: Int, channels: Int) {
        if (!enabled) return
        audioFrames.incrementAndGet()
        audioRate.set(rate.toLong())
        audioChannels.set(channels.toLong())
    }

    /** Duration of one blocking `AudioTrack.write`. */
    fun onAudioWrite(nanos: Long) {
        if (!enabled) return
        audioWriteNanos.addAndGet(nanos)
        audioWriteMaxNanos.accumulateAndGet(nanos) { a, b -> if (b > a) b else a }
    }

    /** `flushTrack`: pause → flush → play, i.e. an audible gap and an audio HAL reset. */
    fun onAudioFlush() {
        if (!enabled) return
        audioFlush.incrementAndGet()
    }

    /** Sampled once per second from the playback thread. */
    fun onAudioState(queueMs: Long, pendingMs: Long, underruns: Int) {
        if (!enabled) return
        audioQueueMs.set(queueMs)
        audioPendingMs.set(pendingMs)
        audioUnderrun.set(underruns.toLong())
    }

    /**
     * Marks an intentional change of test conditions (e.g. audio switched on/off), so the
     * log carries an unambiguous timestamp for the transition.
     */
    fun mark(label: String) {
        if (!enabled) return
        Log.i(TAG, "mark: $label")
    }

    /** Records the external sampler's timestamp so logcat and shell captures can be lined up. */
    fun markShell(label: String) {
        if (!enabled) return
        Log.i(TAG, "shell: $label")
    }

    /** Clears per-session accumulators so a new mirror session starts from a known state. */
    fun resetSession() {
        if (!enabled) return
        lastAuPtsUs.set(Long.MIN_VALUE)
        lastRenderedPtsUs.set(Long.MIN_VALUE)
        ptsGapMaxUs.set(0)
        ptsGapSumUs.set(0)
        ptsGapCount.set(0)
        renderedSinceReset.set(0)
        decodeMaxNanos.set(0)
        renderMaxBlockNanos.set(0)
        inflightQ.clear()
        inputQueueDepth.set(0)
        auQueued.set(0)
        codecSwallowed.set(0)
        firstAuNanos.set(0)
        warmupMs.set(-1)
        codecLatencyMisses.set(0)
        codecLatencySumUs.set(0)
        codecLatencyMaxUs.set(0)
        codecLatencyCount.set(0)
        inputWaitSumUs.set(0)
        inputWaitMaxUs.set(0)
        inputWaitCount.set(0)
        blockOver8.set(0)
        blockOver16.set(0)
        blockOver33.set(0)
        blockOver50.set(0)
        lagMinUs.set(Long.MAX_VALUE)
        lagNowUs.set(Long.MIN_VALUE)
        lagDriftMaxUs.set(0)
        peakBacklogMs.set(Long.MIN_VALUE)
        peakLoggedAt.set(0)
    }

    // ---------------------------------------------------------------- reporter

    private fun ensureReporter() {
        if (reporterStarted) return
        synchronized(this) {
            if (reporterStarted) return
            reporterStarted = true
            Thread({
                var prevAu = 0L
                var prevNoPic = 0L
                var prevAuBytes = 0L
                var prevFrames = 0L
                var prevTimeouts = 0L
                var prevErrors = 0L
                var prevRenderNanos = 0L
                var prevDecodeNanos = 0L
                var prevDecodeCalls = 0L
                var prevAudioPes = 0L
                var prevAudioFrames = 0L
                var prevAudioWriteNanos = 0L
                var prevAudioFlush = 0L
                var prevTick = SystemClock.elapsedRealtime()

                while (true) {
                    try {
                        Thread.sleep(1_000)
                    } catch (_: InterruptedException) {
                        break
                    }
                    val now = SystemClock.elapsedRealtime()
                    val seconds = (now - prevTick) / 1000.0
                    if (seconds <= 0.0) continue
                    prevTick = now

                    // Stay quiet until the first access unit arrives, so idle time (no mirror
                    // session) does not flood logcat.
                    if (auArrived.get() == 0L && framesRendered.get() == 0L &&
                        audioPes.get() == 0L
                    ) {
                        continue
                    }

                    val au = auArrived.get()
                    val noPic = auNoPicture.get()
                    val auBytesNow = auBytes.get()
                    val frames = framesRendered.get()
                    val timeouts = inputTimeouts.get()
                    val errors = decoderErrors.get()
                    val renderNanos = renderBlockNanos.get()
                    val decodeNanosNow = decodeNanos.get()
                    val decodeCallsNow = decodeCalls.get()
                    val aPes = audioPes.get()
                    val aFrames = audioFrames.get()
                    val aWriteNanos = audioWriteNanos.get()
                    val aFlush = audioFlush.get()

                    val auRate = (au - prevAu) / seconds
                    val noPicRate = (noPic - prevNoPic) / seconds
                    val auKbAvg = if (au - prevAu > 0) {
                        (auBytesNow - prevAuBytes) / 1024.0 / (au - prevAu)
                    } else {
                        0.0
                    }
                    val renderRate = (frames - prevFrames) / seconds
                    val timeoutDelta = timeouts - prevTimeouts
                    val errorDelta = errors - prevErrors
                    val renderBlockMs = (renderNanos - prevRenderNanos) / 1_000_000.0
                    val decodeAvgMs = if (decodeCallsNow > prevDecodeCalls) {
                        (decodeNanosNow - prevDecodeNanos) / 1_000_000.0 /
                            (decodeCallsNow - prevDecodeCalls)
                    } else {
                        0.0
                    }
                    val audioPesRate = (aPes - prevAudioPes) / seconds
                    val audioFrameRate = (aFrames - prevAudioFrames) / seconds
                    val audioWriteMs = (aWriteNanos - prevAudioWriteNanos) / 1_000_000.0
                    val audioWriteMaxMs = audioWriteMaxNanos.get() / 1e6
                    val audioFlushDelta = aFlush - prevAudioFlush

                    prevAu = au
                    prevNoPic = noPic
                    prevAuBytes = auBytesNow
                    prevFrames = frames
                    prevTimeouts = timeouts
                    prevErrors = errors
                    prevRenderNanos = renderNanos
                    prevDecodeNanos = decodeNanosNow
                    prevDecodeCalls = decodeCallsNow
                    prevAudioPes = aPes
                    prevAudioFrames = aFrames
                    prevAudioWriteNanos = aWriteNanos
                    prevAudioFlush = aFlush

                    val newestAu = lastAuPtsUs.get()
                    val newestRendered = lastRenderedPtsUs.get()
                    val backlogMs = if (newestAu != Long.MIN_VALUE && newestRendered != Long.MIN_VALUE) {
                        (newestAu - newestRendered) / 1_000.0
                    } else {
                        -1.0
                    }

                    val gapAvgMs = if (ptsGapCount.get() > 0) {
                        ptsGapSumUs.get() / 1_000.0 / ptsGapCount.get()
                    } else {
                        0.0
                    }
                    val gapMaxMs = ptsGapMaxUs.get() / 1_000.0

                    // In codec: fed but not yet displayed. The pts-based backlog above is skewed
                    // by sender timestamp jumps; this count never is.
                    val inflight = au - frames
                    val inflightHeld = auQueued.get() - frames - codecSwallowed.get()

                    val latCount = codecLatencyCount.get()
                    val latAvgMs = if (latCount > 0) {
                        codecLatencySumUs.get() / 1_000.0 / latCount
                    } else {
                        -1.0
                    }
                    val inWaitAvgMs = if (inputWaitCount.get() > 0) {
                        inputWaitSumUs.get() / 1_000.0 / inputWaitCount.get()
                    } else {
                        -1.0
                    }
                    val lagDriftMs = run {
                        val lag = lagNowUs.get()
                        val min = lagMinUs.get()
                        if (lag == Long.MIN_VALUE || min == Long.MAX_VALUE) -1.0
                        else (lag - min) / 1_000.0
                    }

                    Log.i(
                        TAG,
                        "1s au=${fmt(auRate)}/s render=${fmt(renderRate)}/s" +
                            " noPic=${fmt(noPicRate)}/s auKB=${fmt(auKbAvg)}" +
                            " dropInput=$timeoutDelta err=$errorDelta" +
                            " decodeAvg=${fmt(decodeAvgMs)}ms max=${fmt(decodeMaxNanos.get() / 1e6)}ms" +
                            " renderBlock=${fmt(renderBlockMs)}ms max=${fmt(renderMaxBlockNanos.get() / 1e6)}ms" +
                            " backlog=${fmt(backlogMs)}ms gapAvg=${fmt(gapAvgMs)}ms gapMax=${fmt(gapMaxMs)}ms" +
                            " gapSamples=${ptsGapCount.get()}" +
                            " pts=${newestAu / 1_000},${newestRendered / 1_000}" +
                            " dupPts=${auPtsNonIncreasing.get()}" +
                            " key=${keyframes.get()} droppedPre=${droppedBeforeConfig.get()}" +
                            " | inflight=$inflight" +
                            " codecLat=${fmt(latAvgMs)}/${fmt(codecLatencyMaxUs.get() / 1e6)}ms" +
                            " latN=$latCount miss=${codecLatencyMisses.get()}" +
                            " inWait=${fmt(inWaitAvgMs)}/${fmt(inputWaitMaxUs.get() / 1e6)}ms" +
                            " block=>8:${blockOver8.get()},>16:${blockOver16.get()}," +
                            ">33:${blockOver33.get()},>50:${blockOver50.get()}" +
                            " warm=${warmupMs.get()}ms held=$inflightHeld" +
                            " swallowed=${codecSwallowed.get()}" +
                            " lagDrift=${fmt(lagDriftMs)}ms" +
                            " | A pes=${fmt(audioPesRate)}/s pcm=${fmt(audioFrameRate)}/s" +
                            " wrBlock=${fmt(audioWriteMs)}ms wmax=${fmt(audioWriteMaxMs)}ms" +
                            " flush=$audioFlushDelta underrun=${audioUnderrun.get()}" +
                            " q=${audioQueueMs.get()}ms pend=${audioPendingMs.get()}ms" +
                            " ${audioRate.get()}Hz x${audioChannels.get()}"
                    )

                    // One snapshot at every new backlog high-water mark: the second-by-second
                    // line is an average, the peak is where the mechanism actually shows.
                    if (backlogMs > peakBacklogMs.get() && backlogMs > 100.0 && now - peakLoggedAt.get() > 700) {
                        peakBacklogMs.set(backlogMs.toLong())
                        peakLoggedAt.set(now)
                        Log.i(
                            TAG,
                            "PEAK backlog=${fmt(backlogMs)}ms inflight=$inflight" +
                                " codecLat=${fmt(latAvgMs)}/${fmt(codecLatencyMaxUs.get() / 1e6)}ms" +
                                " inWait=${fmt(inWaitAvgMs)}ms renderRate=${fmt(renderRate)}/s" +
                                " block=>16:${blockOver16.get()},>33:${blockOver33.get()}," +
                                ">50:${blockOver50.get()}" +
                                " lagDrift=${fmt(lagDriftMs)}ms"
                        )
                    }

                    // Reset per-interval accumulators so each line describes only its own second.
                    // A cumulative codec-residence average would smear the startup spike across
                    // the whole session and hide exactly what we are looking for.
                    decodeMaxNanos.set(0)
                    renderMaxBlockNanos.set(0)
                    audioWriteMaxNanos.set(0)
                    codecLatencyMaxUs.set(0)
                    codecLatencySumUs.set(0)
                    codecLatencyCount.set(0)
                    inputWaitMaxUs.set(0)
                    inputWaitSumUs.set(0)
                    inputWaitCount.set(0)
                }
            }, "MirrorProbe").apply { isDaemon = true }.start()
        }
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
}
