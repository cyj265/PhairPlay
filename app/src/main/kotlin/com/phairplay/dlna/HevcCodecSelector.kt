package com.phairplay.dlna

import android.media.MediaCodec
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.phairplay.util.DebugLog
import com.phairplay.util.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Picks the H.265/HEVC decoder instead of blindly taking the first one the
 * firmware advertises.
 *
 * WHY: the N1 box cannot decode the newtv live channels at all. Their playlist
 * is a direct `H265-5500k-1080P` TS — there is no H.264 variant to fall back
 * to — and ExoPlayer's default pick (`OMX.amlogic.hevc.decoder.awesome`)
 * answers create/configure with a timeout, which surfaces as
 * `DecoderInitializationException … Decoder init failed:
 * OMX.amlogic.hevc.decoder.awesome` and leaves the sender with nothing but
 * audio. Other Amlogic firmwares advertise a second component
 * (`OMX.amlogic.hevc.decoder`) that does initialise fine, so the fix is to
 * remember which ones died and try the next one instead.
 *
 * HOW: [MediaCodecSelector.getDecoderInfos] lets us reorder the list ExoPlayer
 * walks through (with decoder fallback enabled it tries each entry). We drop
 * components already known to fail and put the last known-good one first, so a
 * retry after a failure lands on a fresh decoder. The first failure knows the
 * component name from the exception, and [noteDecoderFailure] additionally
 * starts a background probe ([probe]) that finds, once and for all, which HEVC
 * component on this box can actually be created.
 */
class HevcCodecSelector : MediaCodecSelector {

    @Volatile private var workingName: String? = null
    private val brokenNames = HashSet<String>()

    @Volatile private var probeStarted = false

    override fun getDecoderInfos(
        mime: String,
        requiresSecureDecryption: Boolean,
        requiresTunnelingVideoDecryption: Boolean
    ): List<MediaCodecInfo> {
        val all = try {
            MediaCodecSelector.DEFAULT.getDecoderInfos(
                mime, requiresSecureDecryption, requiresTunnelingVideoDecryption
            )
        } catch (t: Throwable) {
            // Codec querying can fail on odd firmwares; reordering is a bonus,
            // not something worth failing playback over.
            Logger.w("codec query failed: ${t.message}")
            return emptyList()
        }
        // Every other format (H.264, audio) keeps the stock behaviour.
        if (!MimeTypes.VIDEO_H265.equals(mime)) return all

        val working = workingName
        return synchronized(brokenNames) {
            val usable = all.filter { it.name !in brokenNames }
            if (working != null) usable.sortedBy { if (it.name == working) 0 else 1 } else usable
        }
    }

    /** Records a component that refused to initialise so later attempts skip it. */
    fun noteDecoderFailure(name: String?) {
        if (name.isNullOrBlank()) return
        synchronized(brokenNames) { brokenNames.add(name) }
        Logger.w("HEVC decoder unusable on this box: $name")
        DebugLog.log("DECODER", "HEVC解码器不可用: $name")
        startProbe()
    }

    /** Records a component that initialised, and stops probing after that. */
    fun noteDecoderSuccess(name: String?) {
        if (name.isNullOrBlank() || name == workingName) return
        workingName = name
        Logger.i("HEVC decoder usable: $name")
        DebugLog.log("DECODER", "HEVC解码器可用: $name")
    }

    // ─── Background probe ───────────────────────────────────────────────────

    private fun startProbe() {
        synchronized(this) {
            if (probeStarted) return
            probeStarted = true
        }
        Thread(probeRunnable, "phairplay-hevc-probe").apply { isDaemon = true }.start()
    }

    /**
     * Tries to create every HEVC component on the box. Creating is the step
     * that hangs/throws for the broken ones, and each attempt runs behind a
     * timeout so a wedged component can only cost its own attempt — the thread
     * is abandoned (a native call in progress cannot be interrupted) instead of
     * blocking the probe as a whole.
     */
    private val probeRunnable = Runnable {
        val candidates = hevcComponentNames().filter { it != workingName }
        DebugLog.log("DECODER", "探测HEVC解码器: ${candidates.joinToString()}")
        for (name in candidates) {
            var created: MediaCodec? = null
            val done = CountDownLatch(1)
            val worker = Thread({
                try {
                    created = MediaCodec.createByCodecName(name)
                    noteDecoderSuccess(name)
                    DebugLog.log("DECODER", "HEVC解码器创建成功: $name")
                } catch (t: Throwable) {
                    Logger.w("HEVC decoder $name unavailable: ${t.message}")
                    DebugLog.log("DECODER", "HEVC解码器不可用: $name (${t.message})")
                } finally {
                    // Release outside try: a half-constructed codec throws on
                    // release as well, and neither path should stop the probe.
                    runCatching { created?.release() }
                    done.countDown()
                }
            }, "phairplay-hevc-probe-$name").apply { isDaemon = true }
            worker.start()
            done.await(3, TimeUnit.SECONDS)
            if (workingName != null) break
        }
        DebugLog.log(
            "DECODER",
            "HEVC探测结束: 可用=${workingName ?: "无（此设备/固件无法硬解 HEVC）"}"
        )
    }

    /**
     * Names of every decoder component on this device that claims video/hevc.
     * Uses media3's own codec query rather than `android.media.MediaCodecList`
     * (which this module cannot resolve members from) — it is the same source
     * ExoPlayer consults, so the list is guaranteed to match reality.
     */
    private fun hevcComponentNames(): List<String> {
        return try {
            MediaCodecSelector.DEFAULT
                .getDecoderInfos(MimeTypes.VIDEO_H265, false, false)
                .map { it.name }
        } catch (t: Throwable) {
            Logger.w("HEVC codec list unreadable: ${t.message}")
            emptyList()
        }
    }

    /** Diagnostic dump used in the error line, so a screenshot carries the facts. */
    fun describeAvailable(): String {
        val names = runCatching { hevcComponentNames() }.getOrElse { emptyList() }
        val working = workingName
        return buildString {
            append("HEVC解码器[")
            append(names.joinToString(", ").ifEmpty { "无" })
            append("]")
            working?.let { append(" 可用=$it") }
            val broken = synchronized(brokenNames) { brokenNames.joinToString(", ") }
            if (broken.isNotBlank()) append(" 已排除=$broken")
        }
    }
}
