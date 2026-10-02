package com.phairplay.dlna

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecList
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import com.phairplay.util.DebugLog
import com.phairplay.util.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Chooses the H.265/HEVC decoder instead of blindly taking the first one the
 * firmware advertises.
 *
 * WHY: the newtv live playlists are direct `H265-5500k-1080P` TS with no H.264
 * variant, and on this box ExoPlayer's stock pick dies with
 * `DecoderInitializationException … Decoder init failed:
 * OMX.amlogic.hevc.decoder.awawesome`. Yet the very same HEVC channel plays on
 * this box in ijkplayer-based players (当贝), which create the decoder through
 * `MediaCodec.createDecoderByType("video/hevc")` instead of asking for one
 * advertised component by name. That path can resolve to a different component
 * (`OMX.amlogic.hevc.decoder`, without the `awesome` suffix) — so the fix is to
 * (1) see the full component list the system really has, (2) try them for
 * real in the order this box prefers, (3) remember the ones that died so a
 * later attempt can never burn another MediaCodec on them.
 *
 * HOW: [MediaCodecSelector.getDecoderInfos] is the list ExoPlayer walks with
 * decoder fallback enabled. It is filled from three sources — media3's own
 * query (format-support checked), every `video/hevc` component
 * `MediaCodecList` knows about (so components media3's query silently drops
 * are still reachable), and the last known-good one — then filtered by what we
 * have already seen fail.
 *
 * The scan runs on a background thread: `MediaCodecList` does binder work and
 * must not sit in front of the first frame.
 */
class HevcCodecSelector(private val appContext: Context) : MediaCodecSelector {

    private val lock = Any()

    /** Last component that created successfully during a probe. */
    @Volatile
    private var workingName: String? = null

    /** Components media3 refuses to use, already known to fail on this box. */
    private val brokenNames = HashSet<String>()

    /** Every `video/hevc` component the system advertises (name + capabilities). */
    @Volatile
    private var rawComponents: List<android.media.MediaCodecInfo> = emptyList()

    /** True once every candidate is known dead — the caller should stop retrying. */
    @Volatile
    private var allBroken = false

    @Volatile
    private var probeStarted = false

    init {
        scanAsync()
    }

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
            val names = LinkedHashSet(stock.map { it.name })
            // Components the system lists but media3's query dropped: exactly the
            // ones worth a second look when the stock pick is broken.
            rawComponents.map { it.name }.forEach { if (it !in brokenNames) names.add(it) }
            val usable = names.filter { it !in brokenNames }
            if (usable.isEmpty()) {
                allBroken = true
            }
            usable.map { name ->
                stock.firstOrNull { it.name == name } ?: candidateInfo(name)
            }
                .filterNotNull()
                // Known-good first, then anything that is not the `awesome`
                // Amlogic component (the one this box trips over), then the rest.
                .sortedWith(compareBy({ if (it.name == working) 0 else preferenceOf(it.name) }, { it.name }))
        }
    }

    /** True when the component was already recorded as unable to initialise. */
    fun isBroken(name: String?): Boolean {
        if (name == null) return false
        return synchronized(lock) { name in brokenNames }
    }

    /** True once every HEVC component on this box is known to fail. */
    fun isHevcBroken(): Boolean = allBroken && workingName == null

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
                    "该片源为纯 H.265，无法出画面（换 H.264 片源或换固件）"
            )
            DebugLog.lastError = "HEVC解码器不可用: $name"
        }
        startProbe()
    }

    /** Records a component that initialised, and stops probing after that. */
    fun noteDecoderSuccess(name: String?) {
        if (name.isNullOrBlank() || name == workingName) return
        workingName = name
        synchronized(lock) { allBroken = false }
        Logger.i("HEVC decoder usable: $name")
        DebugLog.log("DECODER", "HEVC解码器可用: $name")
    }

    // ─── Background scans ──────────────────────────────────────────────────

    private fun scanAsync() {
        val thread = Thread({
            try {
                val list = MediaCodecList(MediaCodecList.ALL_CODECS)
                // Called on the class on purpose: these are Java *static*
                // methods, and Kotlin does not synthesise properties for them.
                val count = MediaCodecList.getCodecCount()
                val found = ArrayList<android.media.MediaCodecInfo>()
                for (i in 0 until count) {
                    val info = try {
                        MediaCodecList.getCodecInfoAt(i)
                    } catch (t: Throwable) {
                        Logger.w("codecInfoAt($i) failed: ${t.message}")
                        continue
                    }
                    val types: Array<String> =
                        runCatching { info.supportedTypes }.getOrElse { emptyArray<String>() }
                    if (types.any { it.equals(MimeTypes.VIDEO_H265, ignoreCase = true) }) {
                        found.add(info)
                    }
                }
                val names = found.map { it.name }
                synchronized(lock) {
                    rawComponents = found
                }
                DebugLog.log(
                    "DECODER",
                    "HEVC组件(系统枚举, ${found.size} 个): ${names.joinToString(", ").ifEmpty { "无" }}"
                )
            } catch (t: Throwable) {
                Logger.w("HEVC component scan failed: ${t.message}")
            }
        }, "phairplay-codec-scan").apply { isDaemon = true }
        thread.start()
    }

    /**
     * Tries to create every HEVC component on the box. Creating is the step
     * that throws for the broken ones, and each attempt runs behind a timeout
     * so a wedged component can only cost its own attempt — the thread is
     * abandoned (a native call in progress cannot be interrupted) instead of
     * blocking the probe as a whole.
     */
    private fun startProbe() {
        synchronized(this) {
            if (probeStarted) return
            probeStarted = true
        }
        Thread(probeRunnable, "phairplay-hevc-probe").apply { isDaemon = true }.start()
    }

    private val probeRunnable = Runnable {
        // What ijkplayer-based players do — worth a name in the log because it
        // is the difference between "no decoder" and "the other decoder".
        val typeBased = try {
            val codec = MediaCodec.createDecoderByType(MimeTypes.VIDEO_H265)
            runCatching { codec.release() }.isSuccess
        } catch (t: Throwable) {
            DebugLog.log("DECODER", "createDecoderByType(video/hevc) 不可用: ${t.message}")
            false
        }
        DebugLog.log("DECODER", "createDecoderByType(video/hevc) ${if (typeBased) "可用" else "不可用"}")

        val candidates = synchronized(lock) { rawComponents.map { it.name } }
            .filter { it != workingName && it !in brokenNames }
            .sortedWith(compareBy({ preferenceOf(it) }, { it }))
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
        synchronized(lock) {
            if (brokenNames.isNotEmpty() && getDecoderInfos(MimeTypes.VIDEO_H265, false, false).isEmpty()) {
                allBroken = true
            }
        }
        DebugLog.log(
            "DECODER",
            "HEVC探测结束: 可用=${workingName ?: "无（此设备/固件无法硬解 HEVC）"} ${describeAvailable()}"
        )
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

    /** Lower is tried first: known-good, then the plain Amlogic component. */
    private fun preferenceOf(name: String): Int = when {
        name.contains("awesome", true) -> 2
        name.contains("hevc", true) || name.contains("h265", true) -> 1
        else -> 3
    }

    /**
     * Rebuilds a media3 codec entry for a component media3's own query does not
     * return, so we can still offer it as a candidate.
     */
    private fun candidateInfo(name: String): MediaCodecInfo? {
        val raw = rawComponents.firstOrNull { it.name == name } ?: return null
        val caps = runCatching { raw.getCapabilitiesForType(MimeTypes.VIDEO_H265) }.getOrNull()
            ?: return null
        val lower = name.lowercase(java.util.Locale.ROOT)
        try {
            // Positional: this is a Java factory, named arguments are refused.
            MediaCodecInfo.newInstance(
                name,
                MimeTypes.VIDEO_H265,
                MimeTypes.VIDEO_H265,
                caps,
                // On API < 29 (this box ships 25) media3 decides by name alone,
                // so use its rule instead of APIs that do not exist here.
                lower.startsWith("omx.") && !lower.contains(".sw") && !lower.contains("software"),
                lower.startsWith("omx.google") || lower.contains("software"),
                name.startsWith("OMX."),
                caps.isFeatureSupported(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback),
                false
            )
        } catch (t: Throwable) {
            Logger.w("cannot resurrect codec $name: ${t.message}")
            null
        }
        return null
    }

    /** Diagnostic dump used in the error line, so a screenshot carries the facts. */
    fun describeAvailable(): String {
        val names = runCatching {
            synchronized(lock) { rawComponents.map { it.name } }
        }.getOrElse { emptyList() }
        val stock = runCatching {
            MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.VIDEO_H265, false, false).map { it.name }
        }.getOrElse { emptyList() }
        val working = workingName
        return buildString {
            append("HEVC组件[系统=")
            append(names.joinToString(", ").ifEmpty { "无" })
            append("] 媒体框架=")
            append(stock.joinToString(", ").ifEmpty { "无" })
            working?.let { append(" 可用=$it") }
            val broken = synchronized(lock) { brokenNames.joinToString(", ") }
            if (broken.isNotBlank()) append(" 已排除=$broken")
            append(if (allBroken) " 结论=本机无法硬解HEVC" else " 结论=仍有可用候选")
        }
    }
}
