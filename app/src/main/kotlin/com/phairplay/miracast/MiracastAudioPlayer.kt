// Ported from weekdayjast/MiracastReceiver (MIT). See THIRD_PARTY_NOTICES.md.
package com.phairplay.miracast

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import com.phairplay.util.Logger
import com.phairplay.util.MirrorProbe
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays Miracast audio decoded from [TsDemuxer] PES payloads.
 *
 * WFD carries audio in two possible wrappings:
 * - AAC (stream_type 0x0F): PES payload is ADTS frames; strip the header, decode each frame
 * - LPCM (stream_type 0x83): 4-byte private header + 16-bit big-endian PCM; byte-swap to play
 *
 * Like video, playback is "decode on arrival" with no PTS-based A/V sync: at least it
 * never builds latency. The queue is shallow and older PES are dropped when the source
 * clock runs ahead, so sound stays current instead of drifting seconds late.
 *
 * MediaCodec and AudioTrack are only touched on the playback thread.
 */
class MiracastAudioPlayer {

    private val queue = ArrayBlockingQueue<Pair<TsDemuxer.AudioCodec, ByteArray>>(QUEUE_CAPACITY)

    @Volatile
    private var running = false

    /** After release() the demux thread may still deliver a few PES; never restart for them. */
    @Volatile
    private var released = false
    private var thread: Thread? = null

    /** Data loss: the playback thread should resync on the next ADTS sync word. */
    @Volatile
    private var resyncRequested = false

    // Playback-thread only
    private var codec: MediaCodec? = null
    private var codecConfig = 0                  // AudioSpecificConfig of the current decoder
    private var audioTrack: AudioTrack? = null
    private var trackSampleRate = 0
    private var trackChannels = 0
    private val adts = ByteArrayOutputStream(16 * 1024)
    private var firstPcm = true
    private var droppedPes = 0L

    /** PCM frames handed to the current AudioTrack; [AudioTrack.playbackHeadPosition] lags it. */
    private var framesWritten = 0L

    /** Playback head value that [framesWritten] is measured against (reset on flush). */
    private var trackHeadBase = 0L
    private var lastFlushMs = 0L
    private var lastStatsMs = 0L
    private var lastProbeMs = 0L

    companion object {
        /** One AAC frame is 1024 samples (~21ms at 48kHz); Windows packs one or two per PES. */
        private const val QUEUE_CAPACITY = 64

        /** Undecoded PES held longer than this get dropped (they have not been heard yet). */
        private const val QUEUE_HIGH_MS = 150L

        /** Trim down towards this, leaving enough cushion that trimming does not oscillate. */
        private const val QUEUE_TARGET_MS = 100L

        /**
         * Never discard more than this per trim. Shedding a whole burst in one go is one
         * big hole in the sound; capping it makes each event a short click instead.
         */
        private const val MAX_DROP_PER_EVENT = 3

        /**
         * Audio already handed to AudioTrack that is still unheard. Dropping queue entries
         * cannot fix this; the track has to be flushed. 200ms is well above what a healthy
         * pipe holds, so a value this high means the track really is running behind.
         */
        private const val TRACK_LAG_MS = 200L

        /** One flush is an audible gap; do not let it become a stutter loop. */
        private const val FLUSH_MIN_INTERVAL_MS = 1000L

        private const val SAMPLES_PER_AAC_FRAME = 1024.0

        private val SAMPLE_RATES = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
        )

        /**
         * How many queued PES to discard to bring [backlogMs] down to [targetMs].
         *
         * @param queued PES currently waiting in the queue (never drop more than that)
         */
        internal fun trimCount(
            backlogMs: Long,
            targetMs: Long,
            msPerPes: Double,
            maxPerEvent: Int,
            queued: Int
        ): Int {
            if (queued <= 0 || msPerPes <= 0.0 || backlogMs <= targetMs) return 0
            val needed = kotlin.math.ceil((backlogMs - targetMs) / msPerPes).toInt()
            return needed.coerceIn(1, minOf(maxPerEvent, queued).coerceAtLeast(1))
        }
    }

    /** [TsDemuxer] callback on the demux thread: enqueue only. */
    fun onAudioPes(codecType: TsDemuxer.AudioCodec, data: ByteArray) {
        if (released) return
        MirrorProbe.onAudioPes()
        if (!running) start()
        val item = codecType to data
        if (!queue.offer(item)) {
            queue.poll()
            queue.offer(item)
            droppedPes++
        }
    }

    fun onDiscontinuity() {
        resyncRequested = true
    }

    @Synchronized
    private fun start() {
        if (running || released) return
        running = true
        thread = Thread(::runPlayback, "MiracastAudio").apply { start() }
    }

    @Synchronized
    fun release() {
        released = true
        running = false
        thread?.interrupt()
        thread = null
        queue.clear()
    }

    private fun runPlayback() {
        Logger.i("Miracast audio: playback thread started")
        try {
            while (running) {
                val item = try {
                    queue.poll(200, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    null
                }
                if (item == null) {
                    // Queue drained: still report track lag so a starved pipe is visible.
                    probeAudioState(0L, pendingMs())
                    continue
                }
                val (type, data) = item

                // Video renders on arrival, so audio still unheard means the sound trails the
                // picture. Two different lags need two different fixes: undecoded PES are
                // dropped, while frames already inside AudioTrack can only be discarded by
                // flushing the track. Trimming the queue for track lag never converges, so
                // it just chews through the stream — that used to run for minutes on end.
                val queueMs = (queue.size * msPerPes()).toLong()
                val pendingMs = pendingMs()
                if (queueMs > QUEUE_HIGH_MS) {
                    val dropped = trimCount(
                        queueMs, QUEUE_TARGET_MS, msPerPes(), MAX_DROP_PER_EVENT, queue.size
                    )
                    if (dropped > 0) {
                        repeat(dropped) { queue.poll() }
                        droppedPes += dropped
                        resyncRequested = true
                        Logger.w(
                            "Miracast audio: queue ${queueMs}ms, dropped $dropped PES " +
                                "(total $droppedPes)"
                        )
                    }
                } else if (pendingMs > TRACK_LAG_MS) {
                    flushTrack(pendingMs)
                }
                logStats(queueMs, pendingMs)
                probeAudioState(queueMs, pendingMs)

                try {
                    when (type) {
                        TsDemuxer.AudioCodec.AAC_ADTS -> handleAac(data)
                        TsDemuxer.AudioCodec.LPCM -> handleLpcm(data)
                    }
                } catch (e: Exception) {
                    if (running) Logger.e("Miracast audio: decode error", e)
                    releaseCodec()                       // decoder state is suspect; rebuild next frame
                }
            }
        } finally {
            releaseCodec()
            releaseTrack()
            Logger.i("Miracast audio: playback thread stopped, dropped $droppedPes PES")
        }
    }

    // ─── AAC ────────────────────────────────────────────────────────────────

    /** Append the PES payload and decode every complete ADTS frame (frames may span PES). */
    private fun handleAac(data: ByteArray) {
        if (resyncRequested) {
            resyncRequested = false
            adts.reset()
        }
        adts.write(data)
        val buf = adts.toByteArray()
        var pos = 0

        while (pos + 7 <= buf.size) {
            // ADTS sync word 0xFFF
            if ((buf[pos].toInt() and 0xFF) != 0xFF || (buf[pos + 1].toInt() and 0xF0) != 0xF0) {
                pos++
                continue
            }
            val b1 = buf[pos + 1].toInt() and 0xFF
            val b2 = buf[pos + 2].toInt() and 0xFF
            val b3 = buf[pos + 3].toInt() and 0xFF
            val b4 = buf[pos + 4].toInt() and 0xFF
            val b5 = buf[pos + 5].toInt() and 0xFF

            val headerLength = if ((b1 and 0x01) != 0) 7 else 9      // protection_absent=0 → 2-byte CRC
            val objectType = ((b2 shr 6) and 0x03) + 1               // ADTS profile + 1 = AudioObjectType
            val srIndex = (b2 shr 2) and 0x0F
            val channelConfig = ((b2 and 0x01) shl 2) or ((b3 shr 6) and 0x03)
            val frameLength = ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 shr 5) and 0x07)

            if (srIndex >= SAMPLE_RATES.size || channelConfig == 0 || frameLength <= headerLength) {
                pos++                                                // false sync word
                continue
            }
            if (pos + frameLength > buf.size) break                  // incomplete; wait for the next PES

            ensureAacDecoder(objectType, srIndex, channelConfig)
            decodeAacFrame(buf, pos + headerLength, frameLength - headerLength)
            pos += frameLength
        }

        adts.reset()
        if (pos < buf.size) adts.write(buf, pos, buf.size - pos)
    }

    private fun ensureAacDecoder(objectType: Int, srIndex: Int, channelConfig: Int) {
        // AudioSpecificConfig: 5-bit objectType + 4-bit rate index + 4-bit channels + 3 zero bits
        val asc = (objectType shl 11) or (srIndex shl 7) or (channelConfig shl 3)
        if (codec != null && asc == codecConfig) return

        releaseCodec()
        val sampleRate = SAMPLE_RATES[srIndex]
        val channels = if (channelConfig == 7) 8 else channelConfig
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            if (objectType == 2) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf((asc shr 8).toByte(), asc.toByte())))
        }
        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }
        codecConfig = asc
        ensureTrack(sampleRate, channels)
        Logger.i("Miracast audio: AAC decoder ready, objectType=$objectType ${sampleRate}Hz x$channels")
    }

    private fun decodeAacFrame(buf: ByteArray, offset: Int, length: Int) {
        val mc = codec ?: return
        val inIdx = mc.dequeueInputBuffer(10_000)
        if (inIdx >= 0) {
            val inBuf = mc.getInputBuffer(inIdx)!!
            inBuf.clear()
            inBuf.put(buf, offset, length)
            mc.queueInputBuffer(inIdx, 0, length, 0, 0)
        }

        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIdx = mc.dequeueOutputBuffer(info, 0)
            when {
                outIdx >= 0 -> {
                    val outBuf = mc.getOutputBuffer(outIdx)!!
                    val pcm = ByteArray(info.size)
                    outBuf.position(info.offset)
                    outBuf.get(pcm)
                    mc.releaseOutputBuffer(outIdx, false)
                    writePcm(pcm, pcm.size)
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // HE-AAC may output a different rate/channel count than the ADTS header says.
                    val fmt = mc.outputFormat
                    ensureTrack(
                        fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    )
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                // INFO_OUTPUT_BUFFERS_CHANGED and friends: keep draining
            }
        }
    }

    // ─── LPCM ───────────────────────────────────────────────────────────────

    /**
     * WFD LPCM PES payload: 4-byte private header (0xA0, frame count, reserved,
     * quantization/rate/channels) followed by 16-bit big-endian PCM. AudioTrack wants
     * little-endian, so swap each sample pair.
     */
    private fun handleLpcm(data: ByteArray) {
        var offset = 0
        var sampleRate = 48000
        var channels = 2
        if (data.size >= 4 && (data[0].toInt() and 0xFF) == 0xA0) {
            val info = data[3].toInt() and 0xFF
            sampleRate = if (((info shr 3) and 0x07) == 1) 44100 else 48000
            channels = if ((info and 0x07) == 0) 1 else 2
            offset = 4
        }
        val length = (data.size - offset) and 1.inv()               // align to 16-bit samples
        if (length <= 0) return

        val pcm = ByteArray(length)
        var i = 0
        while (i < length) {
            pcm[i] = data[offset + i + 1]
            pcm[i + 1] = data[offset + i]
            i += 2
        }
        ensureTrack(sampleRate, channels)
        writePcm(pcm, length)
    }

    // ─── AudioTrack ─────────────────────────────────────────────────────────

    /** PCM frames in the track that the speaker has not played yet. */
    private fun pendingMs(): Long {
        val track = audioTrack ?: return 0L
        val head = (track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL) - trackHeadBase
        val bufferedFrames = (framesWritten - head.coerceAtLeast(0L)).coerceAtLeast(0L)
        val rate = if (trackSampleRate > 0) trackSampleRate else 48_000
        return bufferedFrames * 1000 / rate
    }

    /**
     * Discard everything already written to the track. [AudioTrack.playbackHeadPosition]
     * does not reliably reset to zero on this SoC, so re-base the counter at whatever the
     * head reads after the flush instead of assuming success.
     */
    private fun flushTrack(pendingMs: Long) {
        val track = audioTrack ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFlushMs < FLUSH_MIN_INTERVAL_MS) return
        MirrorProbe.onAudioFlush()
        val capacity = track.bufferSizeInFrames
        runCatching {
            track.pause()
            track.flush()
        }
        framesWritten = 0
        trackHeadBase = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        runCatching { track.play() }
        lastFlushMs = now
        Logger.w("Miracast audio: track ${pendingMs}ms behind, flushed (capacity=${capacity}f)")
    }

    /** Low-rate visibility into where the audio actually is. */
    private fun logStats(queueMs: Long, pendingMs: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastStatsMs < 5000) return
        lastStatsMs = now
        if (queueMs == 0L && pendingMs == 0L) return
        val capacity = audioTrack?.bufferSizeInFrames ?: -1
        Logger.i("Miracast audio: queue=${queueMs}ms pending=${pendingMs}ms buffer=${capacity}f")
    }

    private fun msPerPes(): Double {
        val rate = if (trackSampleRate > 0) trackSampleRate else 48_000
        return SAMPLES_PER_AAC_FRAME * 1000.0 / rate
    }

    private fun ensureTrack(sampleRate: Int, channels: Int) {
        if (audioTrack != null && sampleRate == trackSampleRate && channels == trackChannels) return
        releaseTrack()

        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        // Smallest buffer: video renders on arrival, so any extra audio buffering just
        // pushes sound further behind the picture.
        audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
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
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf,
                AudioTrack.MODE_STREAM
            )
        }.also { it.play() }
        trackSampleRate = sampleRate
        trackChannels = channels
        framesWritten = 0
        trackHeadBase = 0
        lastFlushMs = 0
        Logger.i("Miracast audio: AudioTrack ${sampleRate}Hz x$channels, buffer=${minBuf}B/" +
            "${audioTrack?.bufferSizeInFrames}f " +
            "(~${minBuf * 1000 / (sampleRate * channels * 2)}ms requested)")
    }

    /** Blocking write: AudioTrack's consumption rate paces the playback thread. */
    private fun writePcm(pcm: ByteArray, length: Int) {
        val track = audioTrack ?: return
        if (firstPcm) {
            firstPcm = false
            Logger.i("Miracast audio: first PCM (${length}B) → AudioTrack")
        }
        if (trackChannels > 0) framesWritten += length / (2 * trackChannels)
        // Debug probe: WRITE_BLOCKING stalls when the track is full — a stall here is sound
        // that has nowhere to go, and it is measured in the same 1 Hz report as the video.
        val writeStartNanos = System.nanoTime()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            track.write(pcm, 0, length, AudioTrack.WRITE_BLOCKING)
        } else {
            @Suppress("DEPRECATION")
            track.write(pcm, 0, length)
        }
        MirrorProbe.onAudioWrite(System.nanoTime() - writeStartNanos)
        MirrorProbe.onAudioFrames(trackSampleRate, trackChannels)
    }

    /** Feeds the 1 Hz mirror probe, at most once per second. */
    private fun probeAudioState(queueMs: Long, pendingMs: Long) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastProbeMs < 1000) return
        lastProbeMs = now
        val underruns = runCatching { audioTrack?.underrunCount ?: 0 }.getOrDefault(0)
        MirrorProbe.onAudioState(queueMs, pendingMs, underruns)
    }

    private fun releaseCodec() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        codecConfig = 0
    }

    private fun releaseTrack() {
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
        trackSampleRate = 0
        trackChannels = 0
    }
}
