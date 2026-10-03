package com.phairplay.dlna

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.phairplay.util.DebugLog
import com.phairplay.util.Logger

/**
 * Keeps the usable H.265/HEVC decoder at the front of the list ExoPlayer
 * walks, and pushes components that already refused to initialise to the back.
 *
 * WHY: on the N1 the *only* HEVC component is
 * `OMX.amlogic.hevc.decoder.awesome`, and for a long time creating it killed
 * the whole `media.codec` process: the component calls `sendto` during init
 * (reading a system property over a socket) and the box's
 * `mediacodec-seccomp.policy` does not whitelist it, so libminijail blocked
 * the syscall and the service died with SIGABRT. ExoPlayer sees that as
 * `DecoderInitializationException`, retries on the sender's next poll, and
 * kills it again.
 *
 * That is a *firmware* bug and it is fixed on the system side (whitelist
 * `sendto`, then `setprop ctl.restart mediacodec`). What this class does is
 * make sure the app does not make it worse: remember which component failed,
 * lead with the one that worked, and never hand ExoPlayer a component this
 * box has already proven unusable while a working one is still available.
 *
 * WHAT WAS REMOVED HERE, AND WHY: an earlier version also re-listed
 * components that media3's own query dropped, by scanning `MediaCodecList`
 * and rebuilding media3 entries through `MediaCodecInfo.newInstance(...)`.
 * That rested on a guess — "the firmware advertises a second, non-`awesome`
 * component that media3 misses" — which the box disproved: it has exactly one
 * HEVC component. The rebuild call was also the source of two real bugs
 * (a positional-argument polarity inversion on `forceDisableAdaptive`, and a
 * missing `return` that made the whole path a no-op). Deleted rather than
 * debugged: it can only matter on a box that has a second component, and no
 * such box has been observed.
 *
 * Also removed: an ordering rule that pushed the `awesome` component to the
 * *back*. It was written when that component was the one that kept failing;
 * now that it is known to be the only one (and to work), penalising it is
 * pure downside.
 */
class HevcCodecSelector : MediaCodecSelector {

    private val lock = Any()

    /** First component that created successfully — sorted to the front. */
    @Volatile
    private var workingName: String? = null

    /** Components that refused to initialise on this box. */
    private val brokenNames = HashSet<String>()

    /** True once every candidate is known dead — the caller should stop retrying. */
    @Volatile
    private var allBroken = false

    // ─── Decoder list ──────────────────────────────────────────────────────

    override fun getDecoderInfos(
        mime: String,
        requiresSecureDecryption: Boolean,
        requiresTunnelingVideoDecryption: Boolean
    ): List<MediaCodecInfo> {
        val secure = requiresSecureDecryption || requiresTunnelingVideoDecryption
        val stock = safeDefault(mime, requiresSecureDecryption, requiresTunnelingVideoDecryption)
        // Every other format (H.264, audio) keeps the stock behaviour.
        if (secure || !MimeTypes.VIDEO_H265.equals(mime)) return stock
        if (stock.isEmpty()) return emptyList()

        val working = workingName
        return synchronized(lock) {
            val usable = stock.filter { it.name !in brokenNames }
            // Recomputed, never latched: a verdict taken on one render surface
            // must not outlive it (see [resetFailures]).
            allBroken = usable.isEmpty()
            if (usable.isNotEmpty() && workingName == null) {
                noteDecoderSuccess(usable.first().name)
            }
            // Known-good first, then whatever else the framework offered.
            usable.sortedWith(
                compareBy({ if (it.name == working) 0 else 1 }, { it.name })
            )
        }
    }

    /** True when the component was already recorded as unable to initialise. */
    fun isBroken(name: String?): Boolean {
        if (name == null) return false
        return synchronized(lock) { name in brokenNames }
    }

    /** True once every HEVC component on this box is known to fail. */
    fun isHevcBroken(): Boolean = allBroken && workingName == null

    /**
     * True when [name] is the only HEVC component this box has left.
     *
     * WHY this matters: blacklisting is a *permanent* verdict, and on a box
     * with a single component it is self-defeating. Excluding the only decoder
     * does not make the next cast pick a different one — it makes every later
     * HEVC cast fall back to whatever `MediaCodecSelector.DEFAULT` still
     * offers, which in practice means no video track at all: the item reaches
     * READY with `视频轨: 0x0` and plays audio only, for the rest of the
     * session. The caller uses this to cool down and retry instead.
     */
    fun isSoleCandidate(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return synchronized(lock) {
            safeDefaultNames().none { it != name && it !in brokenNames }
        }
    }

    private fun safeDefaultNames(): List<String> = try {
        MediaCodecSelector.DEFAULT
            .getDecoderInfos(MimeTypes.VIDEO_H265, false, false)
            .map { it.name }
    } catch (t: Throwable) {
        emptyList()
    }

    // ─── Failure / success bookkeeping ─────────────────────────────────────

    /** Records a component that refused to initialise so later attempts skip it. */
    fun noteDecoderFailure(name: String?) {
        if (name.isNullOrBlank()) return
        var dead = false
        synchronized(lock) {
            brokenNames.add(name)
            dead = getDecoderInfos(MimeTypes.VIDEO_H265, false, false).isEmpty()
            allBroken = dead
        }
        Logger.w("HEVC decoder unusable on this box: $name")
        DebugLog.log("DECODER", "HEVC解码器不可用: $name")
        if (dead) {
            DebugLog.log(
                "DECODER",
                "本机 HEVC 解码器全部不可用 → ${describeAvailable()}；" +
                    "该片源为纯 H.265 且本机没有软解兜底，无法出画面"
            )
            DebugLog.lastError = "HEVC解码器不可用: $name"
        }
    }

    /**
     * Forgets every component ruled out so far.
     *
     * A verdict is only valid for the render surface it was measured on. A
     * component that hung while the view had just been revealed (still 0×0 in
     * its first layout pass) may work perfectly on the next surface — the N1
     * hardware degrades nothing. Called from [DlnaReceiver.markSurfaceReady].
     */
    fun resetFailures() {
        synchronized(lock) {
            if (brokenNames.isEmpty()) return@synchronized
            brokenNames.clear()
            allBroken = false
        }
        Logger.w("HEVC decoder verdicts cleared — the render surface changed")
        DebugLog.log("DECODER", "HEVC解码器名单已重置（渲染面已更换）")
    }

    /** Records a component that initialised, and keeps it at the front. */
    fun noteDecoderSuccess(name: String?) {
        if (name.isNullOrBlank() || name == workingName) return
        workingName = name
        synchronized(lock) { allBroken = false }
        Logger.i("HEVC decoder usable: $name")
        DebugLog.log("DECODER", "HEVC解码器可用: $name")
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private fun safeDefault(
        mime: String,
        secure: Boolean,
        tunneling: Boolean
    ): List<MediaCodecInfo> = try {
        MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
    } catch (t: Throwable) {
        // Codec querying can fail on odd firmwares; reordering is a bonus,
        // not something worth failing playback over.
        Logger.w("codec query failed: ${t.message}")
        emptyList()
    }

    /** Diagnostic dump used in the error line, so a screenshot carries the facts. */
    fun describeAvailable(): String {
        val stock = runCatching { safeDefaultNames() }.getOrElse { emptyList() }
        val working = workingName
        return buildString {
            append("HEVC组件[媒体框架=")
            append(stock.joinToString(", ").ifEmpty { "无" })
            append("]")
            working?.let { append(" 可用=$it") }
            val broken = synchronized(lock) { brokenNames.joinToString(", ") }
            if (broken.isNotBlank()) append(" 已排除=$broken")
            append(if (allBroken) " 结论=本机无法硬解HEVC" else " 结论=仍有可用候选")
        }
    }
}
