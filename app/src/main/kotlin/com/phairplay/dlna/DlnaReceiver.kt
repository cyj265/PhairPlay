package com.phairplay.dlna

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.phairplay.dlna.renderer.DlnaAudioRenderingControl
import com.phairplay.dlna.renderer.DlnaNoMediaPresent
import com.phairplay.dlna.renderer.DlnaPlayerBridge
import com.phairplay.dlna.renderer.DlnaPlayerControl
import com.phairplay.dlna.renderer.DlnaRendererStateMachine
import com.phairplay.service.ProtocolState
import com.phairplay.util.DebugLog
import com.phairplay.util.Logger
import org.jupnp.UpnpService
import org.jupnp.UpnpServiceConfiguration
import org.jupnp.UpnpServiceImpl
import com.phairplay.dlna.transport.DlnaUpnpServiceConfiguration
import com.phairplay.dlna.transport.ManualDlnaHttp
import com.phairplay.dlna.transport.ManualSsdp
import com.phairplay.util.NetworkUtils
import org.jupnp.binding.annotations.AnnotationLocalServiceBinder
import org.jupnp.model.meta.DeviceDetails
import org.jupnp.model.meta.DeviceIdentity
import org.jupnp.model.meta.LocalDevice
import org.jupnp.model.meta.LocalService
import org.jupnp.model.types.UDADeviceType
import org.jupnp.model.types.UDN
import org.jupnp.protocol.ProtocolFactory
import org.jupnp.registry.Registry
import org.jupnp.support.avtransport.impl.AVTransportService
import org.jupnp.support.avtransport.lastchange.AVTransportLastChangeParser
import org.jupnp.support.connectionmanager.ConnectionManagerService
import org.jupnp.support.lastchange.LastChangeAwareServiceManager
import org.jupnp.support.model.AVTransport
import org.jupnp.support.renderingcontrol.lastchange.RenderingControlLastChangeParser
import org.jupnp.transport.Router
import org.jupnp.transport.RouterImpl
import java.util.UUID

/**
 * DlnaReceiver — DLNA/UPnP MediaRenderer receiver.
 *
 * Advertises this device as a DLNA renderer over SSDP using Cling, and plays
 * received video URIs with ExoPlayer. This is what lets phone video apps
 * (Bilibili, Tencent Video, iQiyi, YouTube…) cast to the TV without needing
 * Google Cast or a mirroring protocol.
 *
 * Threading: [start] and [stop] must be called on the main thread (they create /
 * release the ExoPlayer and Cling instances). Control actions from the UPnP
 * state machine arrive on Cling threads and are re-dispatched to the main
 * thread internally.
 *
 * Lifecycle is driven by PhairPlayService: [start] registers the renderer and
 * starts advertising; [stop] unregisters, releases the multicast lock and the
 * player. [onStateChanged] reports ProtocolState so the home UI card can
 * reflect DISABLED / ADVERTISING / CONNECTED / ERROR.
 */
@OptIn(UnstableApi::class)
class DlnaReceiver(
    private val context: Context,
    private val displayName: String,
    private val onStateChanged: (ProtocolState) -> Unit,
    private val onError: (String) -> Unit = {},
    /**
     * User-facing hint, fired (throttled) when a real-surface decoder init
     * failure means this box cannot decode the item.
     *
     * Distinct from [onError]: this one is not an app fault to flash red — it
     * describes a box-level limitation ("this box's HEVC decoder refused to
     * initialise"), and the receiver keeps cooling down and retrying on its
     * own either way.
     *
     * WHAT IT USED TO SAY, AND WHY THAT WAS WRONG: it used to blame another
     * app ("关闭当贝投屏 / IPTV"). Root取证 (2026-10-03) proved there is no
     * other app: `dumpsys media.resource_manager` named only PhairPlay, and the
     * failure was `media.codec` being killed by its own seccomp filter
     * (`blocked syscall: sendto`) while creating the HEVC component. Telling
     * the user to close an innocent app sent them hunting for something that
     * was never there.
     */
    private val onDecoderHint: (String) -> Unit = {},
    /**
     * Takes the hint back down once a picture actually arrives.
     *
     * Without this the hint is a sticky value: the UI keeps it (deliberately,
     * so the user finds it on the screen they return to), but then a later
     * rebind replays a hint about a failure that is long over — the user gets
     * told the decoder is broken while a picture is playing.
     */
    private val onDecoderHintCleared: () -> Unit = {},
    /**
     * Asks the UI to look again for a render surface. Fired when an item is
     * parked because a decoder init failed with no real surface behind it —
     * only the Activity can say when a Surface appears.
     */
    private val onSurfaceProbeNeeded: () -> Unit = {},
    /**
     * Asks the service to bring the playback UI back and show the player.
     *
     * Distinct from [onSurfaceProbeNeeded]: probing is pointless while the
     * playback layer is hidden, and the layer is only shown on a CONNECTED
     * state — so a parked item (which sits in ADVERTISING) would never get a
     * Surface again without this.
     */
    private val onPlaybackUiNeeded: () -> Unit = {},
    /**
     * Ticks whenever the player's own state changes (IDLE / BUFFERING / READY)
     * or a first frame lands.
     *
     * WHY A SEPARATE CHANNEL: "should the UI show the picture?" is a judgement
     * that changes over time, while `ProtocolState.CONNECTED` is emitted once,
     * early, and then never again (StateFlow dedupes equal values). Deciding
     * at that single instant is exactly what broke v82: CONNECTED arrives
     * while the player is still idle, the answer was "nothing is playing", the
     * player view was hidden, and no later event ever reconsidered it — the
     * cast ran with sound behind the home screen. This pulse is the
     * reconsideration.
     */
    private val onPlaybackActivityChanged: () -> Unit = {},
    /**
     * The control point sent a real Stop (SOAP), as opposed to this app
     * dropping the item because the user left the UI.
     *
     * The service uses it to forget which uri it has already reacted to: a
     * sender Stop is the only thing that turns "the same uri again" back into
     * a *new* cast. Without this distinction a control point that polls
     * SetAVTransportURI + Play every 10-30 s re-raises a cast the user just
     * closed — see [PhairPlayService] for the bug it caused.
     */
    private val onSenderStop: () -> Unit = {}
) : DlnaPlayerControl {

    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        installCrashGuard { throwable ->
            val where = throwable.stackTrace.firstOrNull()
                ?.let { "${it.className}.${it.methodName}" }
            onError(
                "${throwable.javaClass.simpleName}: ${throwable.message}" +
                    (where?.let { " @ $it" } ?: "")
            )
            mainHandler.post { report(ProtocolState.ERROR) }
        }
    }

    /**
     * The ExoPlayer instance, created lazily on the main thread by [start].
     * Exposed so the UI can attach a SurfaceView via [attachSurface].
     */
    @Volatile
    private var player: ExoPlayer? = null

    /** Thread-safe accessor for the UI layer. */
    val playerOrNull: ExoPlayer?
        get() = player

    @Volatile
    private var started = false

    @Volatile
    private var currentUri: String? = null

    /**
     * The item the renderer is holding, for callers that must tell a *new* cast
     * from a sender re-polling the same one (a polling control point re-sends
     * SetAVTransportURI + Play every 10-30 s with an identical URI).
     */
    val currentCastUri: String? get() = currentUri

    /**
     * Who emptied the player last.
     *
     * The field log's hardest case is a bare `播放器状态: IDLE` with no error
     * and no stop message anywhere near it — the picture just stops and nothing
     * says why. Every path that stops the player now names itself, and the IDLE
     * transition prints the name, so "who killed it" is always in the log.
     */
    @Volatile
    private var lastStopReason: String? = null

    /**
     * True while the renderer is really running something: playing, or
     * buffering because the user asked it to.
     *
     * `ProtocolState.CONNECTED` is not enough — it survives a sender pause and
     * a dead pipeline, and acting on it is what made the app open straight into
     * a black player instead of the home screen.
     */
    fun isPlaybackLive(): Boolean {
        val p = player ?: return pendingStartUri != null
        if (p.isReleased) return false
        return p.playbackState == Player.STATE_READY ||
            (p.playbackState == Player.STATE_BUFFERING && p.playWhenReady)
    }

    /** Retry counter for playback errors, reset on new media and on READY. */
    @Volatile
    private var retryCount = 0

    /** Consecutive decoder-init failures for the current item (see the error handler). */
    @Volatile
    private var consecutiveDecoderFailures = 0

    /** Throttle for "this item's decoder is dead" notices (see [startPlayback]). */
    @Volatile
    private var lastHevcDeadNoticeMs = 0L

    /** Throttle for the user-facing "decoder may be occupied" hint (see [onDecoderHint]). */
    @Volatile
    private var lastDecoderHintMs = 0L

    /**
     * When a real-surface decoder init failed, and until when.
     *
     * WHY: every failed `MediaCodec.create/configure` leaves an instance behind
     * that the Amlogic HAL does not always unwind, and this box has a single
     * HEVC component. A control point polls SetAVTransportURI + Play every
     * 10-30 s, so an ungated receiver builds (and leaks) a MediaCodec per poll
     * — the field log shows eight in twenty seconds, after which nothing on the
     * box would play. Cooling down caps the damage to a couple of patient
     * retries; the cast resumes by itself afterwards, or immediately when the
     * UI comes back.
     *
     * WHAT THIS IS NOT: it is not a wait for another app to release the slot.
     * That theory was disproven on 2026-10-03 (see [onDecoderHint]) — the
     * failures were `media.codec` dying inside its own seccomp filter, and no
     * other app was involved.
     */
    @Volatile
    private var decoderCooldownUntilMs = 0L

    /** Current backoff, doubled on every consecutive failure. */
    @Volatile
    private var decoderCooldownMs = DECODER_COOLDOWN_MS

    /** Consecutive cooldowns since the last picture that actually appeared. */
    @Volatile
    private var consecutiveCooldowns = 0

    private fun decoderCooldownActive(): Boolean =
        System.currentTimeMillis() < decoderCooldownUntilMs

    /**
     * Runs [DECODER_COOLDOWN_MS] after it was posted.
     *
     * Scheduled only after a real-surface decoder failure or a silent
     * no-picture start, so the box gets its hardware decoder back before this
     * app takes it again. Without it a failed cast would either stay dead or,
     * worse, be retried by the sender's poll every few seconds and take the
     * slot with it.
     *
     * The body lives in [onDecoderCooldownElapsed] because a lambda cannot
     * reference its own field during initialisation.
     */
    /**
     * Fails loudly and early if the buffer constants violate the contract
     * [DefaultLoadControl] enforces in its constructor.
     *
     * WHY this exists: getting the order wrong (minBuffer 10 s vs rebuffer
     * 12 s) throws `IllegalArgumentException` from inside `start()`, which the
     * receiver catches as a startup failure — the whole DLNA stack then never
     * comes up, so the box advertises nothing and even the /debug page is
     * unreachable. That is a very expensive way to learn about four constants.
     * A named check here points straight at the cause in the log.
     */
    private fun checkBufferDurations() {
        val problems = buildList {
            if (BUFFER_FOR_PLAYBACK_MS < 0) add("bufferForPlaybackMs < 0")
            if (MIN_BUFFER_MS < BUFFER_FOR_PLAYBACK_MS) {
                add("minBufferMs($MIN_BUFFER_MS) < bufferForPlaybackMs($BUFFER_FOR_PLAYBACK_MS)")
            }
            if (MAX_BUFFER_MS < MIN_BUFFER_MS) {
                add("maxBufferMs($MAX_BUFFER_MS) < minBufferMs($MIN_BUFFER_MS)")
            }
            if (BUFFER_FOR_REBUFFER_MS < BUFFER_FOR_PLAYBACK_MS) {
                add("bufferForPlaybackAfterRebufferMs($BUFFER_FOR_REBUFFER_MS) " +
                    "< bufferForPlaybackMs($BUFFER_FOR_PLAYBACK_MS)")
            }
            if (MIN_BUFFER_MS < BUFFER_FOR_REBUFFER_MS) {
                add("minBufferMs($MIN_BUFFER_MS) " +
                    "< bufferForPlaybackAfterRebufferMs($BUFFER_FOR_REBUFFER_MS)")
            }
        }
        if (problems.isNotEmpty()) {
            throw IllegalArgumentException(
                "DefaultLoadControl 缓冲参数非法（会导致 DLNA 启动失败）: " + problems.joinToString("; ")
            )
        }
    }

    private val decoderCooldownRetry = Runnable { onDecoderCooldownElapsed() }

    private fun onDecoderCooldownElapsed() {
        val uri = pendingStartUri ?: return
        if (!started) return
        if (decoderCooldownActive()) {
            mainHandler.postDelayed(decoderCooldownRetry, DECODER_COOLDOWN_MS.toLong())
            return
        }
        DebugLog.log("DLNA", "硬解冷却结束 → 重新起播挂起的投屏")
        resumeParkedCast(uri)
    }

    private fun resumeParkedCast(uri: String) {
        surfaceGeneration++
        hevcCodecSelector.resetFailures()
        consecutiveDecoderFailures = 0
        retryCount = 0
        if (surfaceReady) {
            runStart(uri)
        } else {
            // Ask the UI to *show* the player, not merely to re-probe. Ringing
            // the probe alone deadlocks: the probe is a no-op while the playback
            // layer is hidden, and nothing else would ever show it again —
            // showDlnaPlayer() only runs on a CONNECTED state, while a failed
            // start sits in ADVERTISING. The field log shows exactly that, four
            // "cooldown elapsed" notices with no start after any of them.
            onSurfaceProbeNeeded()
            onPlaybackUiNeeded()
        }
    }

    /**
     * Gives the hardware decoder slot back after a failed init: stop the
     * pipeline, drop the surface and the item, and cool down.
     *
     * Called on the main thread from the error handler. `stop()` is what
     * releases the renderer; `clearVideoSurface()` makes sure the codec is not
     * holding a surface it may never see again.
     */
    private fun enterDecoderCooldown(reason: String) {
        // Back off harder on each consecutive failure. The field log shows eight
        // attempts in twenty seconds, every one failing identically: this HAL
        // needs longer than a couple of seconds to hand the instance back, and a
        // fixed 8 s just guarantees a slow loop instead of a real pause. Doubling
        // up to [DECODER_COOLDOWN_MAX_MS] turns that loop into a couple of
        // patient retries — and the box gets its decoder back for much longer,
        // which is the whole point of cooling down at all.
        val backoff = (decoderCooldownMs * 2).coerceAtMost(DECODER_COOLDOWN_MAX_MS)
        decoderCooldownMs = backoff
        consecutiveCooldowns++
        decoderCooldownUntilMs = System.currentTimeMillis() + backoff
        mainHandler.removeCallbacks(stallWatchdog)
        mainHandler.removeCallbacks(firstPictureWatchdog)
        // Deliberately NOT calling clearVideoSurface() here.
        //
        // PlayerView sets the player's video surface exactly once, when
        // showDlnaPlayer() assigns `pv.player = p`. Detaching the surface
        // therefore leaves the player permanently without an output, while the
        // TextureView stays available — so the UI probe keeps reporting "surface
        // ready", the item starts, fails the same way, and the loop repeats. The
        // field log shows exactly eight attempts in twenty seconds, every one of
        // them "视频轨: 0x0 + DecoderInitializationException" on a surface the
        // probe had just declared ready.
        //
        // `stop()` is what actually returns the hardware decoder; the surface
        // itself is not a scarce resource, and keeping it attached is what lets
        // the next attempt succeed.
        player?.let { p ->
            lastStopReason = "硬解冷却: $reason"
            runCatching { p.stop() }
            runCatching { p.clearMediaItems() }
        }
        currentUri = null
        DebugLog.log(
            "DLNA",
            "$reason → 停止并冷却 ${backoff / 1000}s" +
                "（第 $consecutiveCooldowns 次，连续失败会继续加长）；" +
                "冷却期间不再建 MediaCodec，否则发送端每轮 poll 都会泄漏一个实例"
        )
    }

    /** Cleared whenever an item actually reaches a picture. */
    private fun notePlaybackHealthy() {
        if (consecutiveCooldowns != 0) {
            DebugLog.log("DLNA", "画面恢复正常 → 硬解冷却策略重置")
        }
        decoderCooldownMs = DECODER_COOLDOWN_MS
        consecutiveCooldowns = 0
        // A picture is on screen, so any "the decoder failed" advice is now
        // stale. Retract it: the UI holds the hint until told otherwise, and a
        // hint that outlives the problem is worse than none (the user reads a
        // failure notice over a perfectly good picture).
        if (lastDecoderHintMs != 0L) {
            lastDecoderHintMs = 0L
            DebugLog.log("DLNA", "画面恢复 → 撤销解码器占用提示")
            onDecoderHintCleared()
        }
    }

    /**
     * One user-facing notice that this box's HEVC decoder refused to start,
     * throttled to [DECODER_HINT_MIN_INTERVAL_MS] so the sender's 2-3 s poll
     * cannot spam it into a Toast storm.
     *
     * The wording is deliberately about *this box*, not about other apps.
     * Root-level取证 on the N1 (2026-10-03) showed the HEVC component dying
     * inside `media.codec` (seccomp blocked `sendto` → SIGABRT) with the codec
     * instance pool empty (`mNum=0, mMaxNum=9`) and no other app registered in
     * `media.resource_manager`. Blaming a sibling player was not just
     * inaccurate, it was unactionable.
     */
    private fun maybeHintDecoderProblem(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastDecoderHintMs < DECODER_HINT_MIN_INTERVAL_MS) return
        lastDecoderHintMs = now
        DebugLog.log("DLNA", "用户提示: $text")
        onDecoderHint(text)
    }

    /**
     * True once the playback UI reports a render target that exists *and* has
     * a real size (see [markSurfaceReady]).
     *
     * The renderer used to prepare the player the instant Play arrived. When
     * the UI had to be pulled to the front first (auto-foreground, v65) that
     * meant configuring the Amlogic HEVC decoder against the TextureView
     * surface of a view that had only just been revealed — still 0×0 in its
     * first layout pass — and the HAL blocks in configure() waiting for that
     * surface to become usable. It never does, so every poll reported
     * "Decoder init failed … 超时" while the box sat there decoding audio.
     * [startPlayback] now waits for this flag and [surfaceTimeoutRunnable]
     * keeps a hard cap.
     */
    @Volatile
    private var surfaceReady = false

    /** Bumped for every new render surface so codec verdicts never leak
     *  across surfaces: a fresh surface deserves a fresh chance. */
    @Volatile
    private var surfaceGeneration = 0

    /**
     * True when the current start attempt was gated on a surface the PlayerView
     * had really handed to the player (MainActivity waits for
     * `player.videoSurface != null`, not for the View to have a size).
     *
     * A decoder init failure is only evidence about the *component* when a real
     * surface was there. Configure against a placeholder/just-created surface
     * and the Amlogic HAL blocks regardless of how good the codec is — field
     * evidence: three consecutive "init failed … 超时" on a surface that had
     * just been revealed, then the very same component playing 1920x1080 on
     * the first try 40 s later on that same surface. So failures recorded
     * before this flag are counted separately and never write a component off.
     */
    @Volatile
    private var startedOnRealSurface = false

    /**
     * Whether a render surface exists right now, regardless of any start
     * attempt.
     *
     * Distinct from [startedOnRealSurface], which describes the attempt that
     * produced the current item. This one answers "could we start on a real
     * surface if we wanted to?", which is what lets a stale foreground flag
     * (see the gate in [startPlayback]) stop costing 10 seconds per cast.
     */
    @Volatile
    private var realSurfacePresent = false

    /** Play received but not started yet because no render surface existed. */
    @Volatile
    private var pendingStartUri: String? = null

    /** True while the Activity really is in the foreground (see [setUiForeground]). */
    @Volatile
    private var uiForeground = false

    /**
     * An item that was playing when the UI went to the background, kept so it
     * can be rebuilt on the way back.
     *
     * The player is emptied on the way out (that is the only way to hand the
     * single hardware decoder back — see [pausePlaybackFromUi]), so returning
     * to the app means re-preparing from scratch. Holding the URI and position
     * is what makes that invisible to the user.
     */
    private data class ParkedMedia(val uri: String, val positionMs: Long)

    @Volatile
    private var parkedForUi: ParkedMedia? = null

    /** Gives up waiting for the UI after [SURFACE_WAIT_MS] and starts the item
     *  without a picture — exactly what receivers did before auto-foreground,
     *  so a cast that never opens the UI still makes sound.
     *
     *  Note this path can be reached with NO surface at all, so a decoder
     *  failure after it says nothing about the component (see
     *  [startedOnRealSurface]). */
    private val surfaceTimeoutRunnable = Runnable {
        val uri = pendingStartUri ?: return@Runnable
        pendingStartUri = null
        surfaceReady = true
        DebugLog.log(
            "DLNA",
            "等待播放层 ${SURFACE_WAIT_MS / 1000}s 未就绪 → 按仅音频起播: $uri"
        )
        if (started) runStart(uri, audioOnly = true)
    }

    /**
     * A Seek that arrived before the pipeline reached READY. Senders poll and
     * re-issue Seek(≈resume position) right after Play; executing it while the
     * HLS loader is still fetching the first segments aborts those in-flight
     * loads (manifest/keys/segments) and can leave the item stuck in
     * BUFFERING. Parked here and applied by the STATE_READY handler.
     */
    @Volatile
    private var pendingSeekMs: Long = -1L

    /**
     * HTTP factory shared with the media source factory. Request headers
     * carried by the sender's relay URL (see [applyRelayHeaders]) are applied
     * here before every new media item, because HLS playlists resolve their
     * segments to absolute CDN URLs that ExoPlayer fetches directly.
     */
    private val httpFactory: DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent(DEFAULT_HTTP_USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(10_000)
        .setReadTimeoutMs(20_000)

    /**
     * Fires when an item starts but never reaches READY — i.e. a black screen
     * with *no* error callbacks. Nothing else in the player reports that case,
     * and it is the one that looks like a broken app rather than a broken
     * source. Probes the URL directly and records what the far end answered,
     * turning "black screen" into either "source dead" or "still buffering".
     *
     * The companion case — READY but zero video size, i.e. audio with no
     * picture — is [firstPictureWatchdog]'s job, because this one is cancelled
     * on READY.
     */
    private val stallWatchdog = Runnable {
        val p = player
        val uri = currentUri
        if (p == null || uri == null) return@Runnable
        if (p.playbackState != Player.STATE_READY) {
            DebugLog.log(
                "DLNA",
                "起播 ${STALL_TIMEOUT_MS / 1000}s 未就绪: state=${stateNameOf(p.playbackState)} → 主动探测源"
            )
            probeSourceAsync(uri)
        }
    }

    /**
     * Deadline for "a video item that reached READY must have produced frames
     * by now".
     *
     * The field log's nastiest state: `播放器状态: READY … 视频轨: 0x0` — audio
     * playing, no picture, and *no error callback of any kind*, so the sender
     * sees a healthy session and never re-sends anything. Cancelling the
     * regular stall watchdog on READY is right for buffering but blind to this,
     * hence a separate timer that a real first frame disarms.
     */
    private val firstPictureWatchdog: Runnable = Runnable {
        val p = player
        val uri = currentUri
        if (p == null || uri == null) return@Runnable
        if (p.videoSize.width > 0 && p.videoSize.height > 0) return@Runnable
        DebugLog.log(
            "DLNA",
            "READY 后 ${FIRST_PICTURE_TIMEOUT_MS / 1000}s 仍无画面（视频轨 0x0）" +
                "→ 交还硬解槽，等渲染面稳定后重试"
        )
        enterDecoderCooldown("READY 后无画面")
        pendingStartUri = uri
        surfaceReady = false
        realSurfacePresent = false
        surfaceGeneration++
        hevcCodecSelector.resetFailures()
        consecutiveDecoderFailures = 0
        mainHandler.removeCallbacks(foregroundTimeoutRunnable)
        mainHandler.postDelayed(decoderCooldownRetry, DECODER_COOLDOWN_MS.toLong())
        onSurfaceProbeNeeded()
        onPlaybackUiNeeded()
    }

    /** Prefers a HEVC decoder that actually initialises (see [HevcCodecSelector]). */
    private val hevcCodecSelector = HevcCodecSelector()

    private var upnpService: UpnpService? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var manualSsdp: ManualSsdp? = null
    /** Set while waiting for the box to be handed a LAN IPv4 (see [waitForIpAndStartSsdp]). */
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Registers the UPnP renderer and starts advertising. Must be called on the main thread. */
    fun start() {
        if (started) return
        started = true
        DlnaPlayerBridge.setControl(this)

        try {
            // SSDP discovery runs over UDP multicast; Android drops multicast
            // packets unless the app holds a MulticastLock.
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("phairplay_dlna").apply {
                setReferenceCounted(false)
                acquire()
            }

            // Manual SSDP: announce NOTIFY alive + answer M-SEARCH ourselves,
            // so discovery does not depend on jUPnP's runtime registry lookup
            // (which misbehaved for HTTP and could equally break discovery).
            try {
                val ip = NetworkUtils.getLocalIpv4()
                if (!ip.isNullOrBlank()) {
                    manualSsdp = ManualSsdp().apply { start(ip) }
                    Logger.i("Manual SSDP started on $ip:1900")
                } else {
                    // Do NOT stay silent here: a blank debug card on the box
                    // is exactly this case. Surface the reason so a remote
                    // helper can report it back instead of "nothing at all".
                    DebugLog.ssdpStatus = "等待局域网IP下发…"
                    DebugLog.log("SSDP", "未获取到局域网IP，Manual SSDP 暂未启动（等待联网）")
                    Logger.w("Manual SSDP skipped: no LAN IPv4 found — waiting for the box to get an address")
                    // The box may still be booting / re-connecting: DHCP had not
                    // handed out an address at start() time. Without this wait
                    // the SSDP layer stays dead until the user restarts the app,
                    // i.e. the phone never sees the device on a freshly booted
                    // N1/box. Watch for the address and start then.
                    waitForIpAndStartSsdp()
                }
            } catch (t: Throwable) {
                Logger.i("Manual SSDP start failed: ${t.message}")
            }

            // H.265 is the format this box actually trips over (the newtv live
            // playlists are direct H.265 TS with no H.264 variant), so the
            // decoder choice for it is ours.
            val renderersFactory = DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(hevcCodecSelector)

            // A live HLS window on these boxes is only a few segments deep, and
            // ExoPlayer's defaults (2.5 s / 5 s) are thinner than one segment
            // round-trip to the CDN. Every hiccup there drains the buffer to zero
            // and the picture freezes — the field log shows a clean pattern of
            // "READY 1920x1080" then a repeatable
            // `ERROR_CODE_IO_UNSPECIFIED … IllegalArgumentException` roughly
            // every 13 s, each one recovering only because the retry re-prepared.
            // A deeper buffer absorbs those, which is the difference between
            // "briefly stalls" and "not really stalling".
            checkBufferDurations()
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    MIN_BUFFER_MS,
                    MAX_BUFFER_MS,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_FOR_REBUFFER_MS
                )
                // Cap by wall time, not by byte count: bitrate is unknown for
                // these relay URLs and a byte cap starves a 1080p stream.
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val exoPlayer = ExoPlayer.Builder(context, renderersFactory)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory)
                )
                .build()
                .also { p ->
                    p.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            val stateName = stateNameOf(playbackState)
                            Logger.d("DLNA player state: $stateName uri=${currentUri}")
                            // Written to the in-app debug log deliberately: a black
                            // picture with no visible error means ExoPlayer never
                            // said anything at all, and on this box we have no
                            // logcat access. State transitions are the cheapest
                            // evidence of whether bytes ever arrived.
                            DebugLog.log(
                                "DLNA",
                                "播放器状态: $stateName playWhenReady=${p.playWhenReady} pos=${p.currentPosition / 1000}s"
                            )
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    mainHandler.removeCallbacks(stallWatchdog)
                                    DebugLog.log(
                                        "DLNA",
                                        "准备完成: ${p.videoSize.width}x${p.videoSize.height} 时长=${p.duration}"
                                    )
                                    // The pipeline decoded something: whatever was
                                    // tried worked, so the counter starts over for
                                    // the next item instead of carrying a verdict
                                    // that only ever applied to the one that hung.
                                    consecutiveDecoderFailures = 0
                                    // READY only means the *audio* pipeline is
                                    // healthy. With a video item and no frames
                                    // (视频轨 0x0) it is still a black screen, and
                                    // nothing else would ever say so — the
                                    // stall watchdog is cancelled right here, so
                                    // this case needs its own deadline.
                                    mainHandler.removeCallbacks(firstPictureWatchdog)
                                    if (p.videoSize.width > 0 && p.videoSize.height > 0) {
                                        mainHandler.postDelayed(
                                            firstPictureWatchdog,
                                            FIRST_PICTURE_TIMEOUT_MS.toLong()
                                        )
                                    }
                                    // No video track (music / audio-only cast):
                                    // a video surface would just be a black
                                    // rectangle, so the UI shows a music card.
                                    DlnaMediaMeta.audioOnly =
                                        p.videoSize.width == 0 || p.videoSize.height == 0
                                    val pending = pendingSeekMs
                                    if (pending >= 0L) {
                                        pendingSeekMs = -1L
                                        DebugLog.log("DLNA", "执行暂存的 Seek → ${pending / 1000}s")
                                        p.seekTo(pending)
                                    }
                                    if (currentUri != null) {
                                        retryCount = 0
                                        report(ProtocolState.CONNECTED)
                                    }
                                }
                                Player.STATE_IDLE -> {
                                    // A player that had an item and no longer
                                    // does: the picture is gone, and until now
                                    // nothing said who took it. `lastStopReason`
                                    // is null only when ExoPlayer emptied itself
                                    // (e.g. a fatal internal error) — which is
                                    // itself the answer.
                                    DebugLog.log(
                                        "DLNA",
                                        "播放器被清空 (IDLE) 原因=${lastStopReason ?: "ExoPlayer 自行清空（未见我方调用）"}"
                                    )
                                    lastStopReason = null
                                }
                                Player.STATE_ENDED -> {
                                    mainHandler.removeCallbacks(stallWatchdog)
                                    mainHandler.removeCallbacks(firstPictureWatchdog)
                                    // The stream finished naturally — return to idle.
                                    if (currentUri != null) {
                                        // Say so. A live HLS window ends on its own
                                        // every few minutes, and the resulting IDLE
                                        // used to appear in the log with no
                                        // explanation, which is indistinguishable
                                        // from a decoder dying.
                                        DebugLog.log(
                                            "DLNA",
                                            "媒体流自然结束 (STATE_ENDED) → 回到空闲，pos=${p.currentPosition / 1000}s"
                                        )
                                        clearPlayback("媒体流自然结束 STATE_ENDED")
                                        // Let the control point see STOPPED instead
                                        // of a stuck PLAYING after the media ends.
                                        ManualDlnaHttp.notifyPlaybackEnded()
                                        report(ProtocolState.ADVERTISING)
                                    }
                                }
                                else -> {
                                    // STATE_IDLE / STATE_BUFFERING: transient, no UI change needed.
                                }
                            }
                            // Anything that can flip "is there a picture?"
                            // must be able to flip the answer in the UI.
                            onPlaybackActivityChanged()
                        }

                        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                            DebugLog.log("DLNA", "视频轨: ${videoSize.width}x${videoSize.height}")
                        }

                        /** Proof that pixels actually reached the screen (vs. a black overlay). */
                        override fun onRenderedFirstFrame() {
                            mainHandler.removeCallbacks(stallWatchdog)
                            mainHandler.removeCallbacks(firstPictureWatchdog)
                            // The box can hand its decoder back: reset the backoff so
                            // the next hiccup starts from the short delay again.
                            notePlaybackHealthy()
                            DebugLog.log("DLNA", "首帧已渲染（画面已上屏）")
                            // The strongest possible "there is a picture now".
                            onPlaybackActivityChanged()
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            mainHandler.removeCallbacks(stallWatchdog)
                            mainHandler.removeCallbacks(firstPictureWatchdog)
                            val cause = error.cause?.let { "${it.javaClass.simpleName}: ${it.message}" }
                            val msg = "DLNA播放失败: ${error.errorCodeName ?: error.errorCode} ${error.message}"
                            Logger.e("DLNA playback error: $msg", error)
                            DebugLog.log("DLNA", msg)
                            DebugLog.log(
                                "DLNA",
                                "错误根因: ${cause ?: "(无 cause)"} 超时/网络=${error.errorCode / 1000}"
                            )
                            // A parsing failure nearly always means the SOURCE lied, not
                            // the player: it answered HTTP 200 with an HTML error page or
                            // plain text. Nothing in errorCodeName says so, so probe the
                            // URL ourselves and record what actually came back.
                            if (error.errorCode / 1000 == 3) {
                                currentUri?.let { probeSourceAsync(it) }
                            }
                            // A decoder that refuses to start will refuse again on
                            // every single Play the sender polls with — each attempt
                            // also leaks a MediaCodec, and enough of those is what
                            // turns "one bad channel" into "nothing plays any more".
                            // Remember the component and stop retrying once it is clear
                            // this is a codec problem rather than a network hiccup.
                            val failedCodec = failingCodecName(error)
                            if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
                                // Count only components we have not already
                                // ruled out. Senders alternate between the two
                                // URLs they proxy the channel through, which
                                // used to reset the counter per URL and turn
                                // one broken decoder into a MediaCodec leak
                                // every few seconds.
                                val alreadyKnown = hevcCodecSelector.isBroken(failedCodec)
                                if (!alreadyKnown) consecutiveDecoderFailures++
                                // Only after [HEVC_ATTEMPT_LIMIT] failed
                                // attempts on one component: the first create of
                                // a session regularly times out on this HAL and
                                // the very next one succeeds, so writing the
                                // component off on the first failure is what
                                // left the first cast of a session dead.
                                //
                                // AND only when this attempt actually had a
                                // surface. Configure against a placeholder and
                                // the Amlogic HAL blocks no matter how good the
                                // codec is — the field log shows three "init
                                // failed" on a surface revealed that very
                                // second, then that same component at
                                // 1920x1080 on the first try once the surface
                                // had settled. A component must never be judged
                                // on an attempt that had nothing to decode into.
                                if (consecutiveDecoderFailures >= HEVC_ATTEMPT_LIMIT) {
                                    if (startedOnRealSurface) {
                                        // A blacklist is a *permanent* verdict, so
                                        // it is only safe when something else is
                                        // left to fall back to. This box advertises
                                        // exactly one HEVC component: ruling it
                                        // out does not make the next cast use a
                                        // different decoder, it just turns every
                                        // later HEVC cast into silent audio-only
                                        // playback for the rest of the session —
                                        // which is exactly what the field log
                                        // showed (READY with 视频轨 0x0, twice).
                                        // So hand the slot back and cool down
                                        // instead; the next cast gets a fresh try.
                                        if (hevcCodecSelector.isSoleCandidate(failedCodec)) {
                                            DebugLog.log(
                                                "DLNA",
                                                "本机只有这一个 HEVC 组件 → 不拉黑，改为冷却后重试（否则本次会话之后所有 HEVC 都只有声音）"
                                            )
                                        } else {
                                            hevcCodecSelector.noteDecoderFailure(failedCodec)
                                        }
                                    } else {
                                        DebugLog.log(
                                            "DLNA",
                                            "本次起播没有真实渲染面 → 不判定解码器不可用，等待新渲染面再试"
                                        )
                                    }
                                }
                                Logger.w(
                                    "Decoder init failed ($failedCodec) " +
                                        "— 第 $consecutiveDecoderFailures/$HEVC_ATTEMPT_LIMIT 次" +
                                        (if (alreadyKnown) "(已列入黑名单)" else "")
                                )
                            } else {
                                consecutiveDecoderFailures = 0
                            }
                            // A source-side hiccup (a segment that failed to load,
                            // a playlist that rolled mid-fetch) is not a receiver
                            // fault: the very next retry in the field log came back
                            // with a picture, twice in a row. Surfacing it as an
                            // error flashes the home card red at the user for
                            // something they never caused and cannot see.
                            // Anything decoder-related still reports, because that
                            // one is real and needs the card to say so.
                            val sourceGlitch = error.errorCode / 1000 == 2
                            if (sourceGlitch) {
                                DebugLog.log("DLNA", "源端瞬时抖动（可自恢复），不向界面报错")
                            } else {
                                onError(msg)
                            }
                            // Reflect STOPPED immediately so the control point's
                            // UI doesn't stay stuck on PLAYING while we retry.
                            ManualDlnaHttp.notifyPlaybackEnded()
                            // Transient failures (Surface recreation across a
                            // background/foreground switch, momentary network
                            // stalls) recover with a single retry — capped so a
                            // dead URL can't retry forever.
                            val uri = currentUri
                            // Retrying makes sense for a Surface or network blip; a
                            // dead decoder is permanent until the sender picks a
                            // different item, so don't burn a MediaCodec per poll.
                            val decoderDead =
                                error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED &&
                                (hevcCodecSelector.isHevcBroken() ||
                                    hevcCodecSelector.isBroken(failedCodec))
                            if (decoderDead) {
                                DebugLog.log("DLNA", "${hevcCodecSelector.describeAvailable()} → 放弃自动重试")
                                // The debug card must say why the picture is
                                // black: a silent box looks like an app bug.
                                if (failedCodec != null) {
                                    DebugLog.lastError = "HEVC解码器不可用: $failedCodec"
                                }
                            }

                            // A decoder init failure with no real surface behind it is
                            // not a decoder problem, it is a surface problem — and
                            // retrying straight away just builds the next doomed
                            // MediaCodec (the field log shows three of them, 2 s
                            // apart, all on a surface revealed that same second).
                            // Park the item again and let the UI's surface probe
                            // release it once the PlayerView really has a Surface.
                            val surfaceLessFailure =
                                uri != null &&
                                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED &&
                                    !startedOnRealSurface
                            val realSurfaceFailure =
                                uri != null &&
                                    error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED &&
                                    startedOnRealSurface
                            if (surfaceLessFailure) {
                                DebugLog.log(
                                    "DLNA",
                                    "无渲染面导致的解码失败 → 重新等待播放层，不再盲目重试: ${uri?.take(72)}…"
                                )
                                enterDecoderCooldown("本次起播没有真实渲染面")
                                pendingStartUri = uri
                                surfaceReady = false
                                realSurfacePresent = false
                                surfaceGeneration++
                                hevcCodecSelector.resetFailures()
                                consecutiveDecoderFailures = 0
                                mainHandler.removeCallbacks(surfaceTimeoutRunnable)
                                // No timeout runnable here on purpose: the item must
                                // wait for a real surface rather than fall back to a
                                // surface-less start that is known to fail. The
                                // sender's next Play re-enters the same gate, and
                                // the UI is asked to keep watching for a Surface.
                                onSurfaceProbeNeeded()
                                onPlaybackUiNeeded()
                            } else if (realSurfaceFailure) {
                                // A genuine decoder failure on a surface that was
                                // really there. Hand the slot back and stay off it
                                // for a while: hammering a possibly wedged HAL
                                // instance is what takes the hardware decoder away
                                // from every other app on the box until reboot.
                                enterDecoderCooldown("解码器初始化失败(${failedCodec ?: "?"})")
                                pendingStartUri = uri
                                mainHandler.removeCallbacks(surfaceTimeoutRunnable)
                                mainHandler.removeCallbacks(foregroundTimeoutRunnable)
                                onPlaybackUiNeeded()
                                if (decoderDead) {
                                    // The component is on the broken list, so
                                    // cooling down and retrying only builds the
                                    // next doomed MediaCodec. Say so outright —
                                    // this is the one case the user has to act
                                    // on, because nothing will fix itself.
                                    mainHandler.removeCallbacks(decoderCooldownRetry)
                                    maybeHintDecoderProblem(
                                        "本盒 HEVC 硬解初始化失败（${failedCodec ?: "?"}），已停止自动重试。" +
                                            "该片源为纯 H.265 且本机没有软解兜底，请换 H.264 片源或重投一次。"
                                    )
                                } else {
                                    mainHandler.postDelayed(decoderCooldownRetry, DECODER_COOLDOWN_MS.toLong())
                                    // A real-surface init failure is a box-level
                                    // condition, not a contest for the decoder:
                                    // the component either comes back on the next
                                    // attempt or it does not. Say what is
                                    // happening instead of leaving a black
                                    // screen with no explanation.
                                    maybeHintDecoderProblem(
                                        "本盒 HEVC 硬解初始化失败，正在冷却后自动重试。" +
                                            "若反复出现，该片源为纯 H.265，本机无法解码。"
                                    )
                                }
                            } else if (uri != null && retryCount < 2 && !decoderDead &&
                                !decoderCooldownActive()
                            ) {
                                retryCount++
                                mainHandler.postDelayed({
                                    if (started && currentUri == uri) {
                                    player?.let { p ->
                                        // Retry without a declared type: if the
                                        // inference above was wrong, byte sniffing
                                        // is the other chance at playing it.
                                        p.setMediaItem(MediaItem.fromUri(uri))
                                            p.prepare()
                                            p.play()
                                        }
                                    }
                                }, 1500)
                            }
                        }
                    })
                }
            player = exoPlayer

            // Build the UPnP stack in-process instead of binding the stock
            // AndroidUpnpServiceImpl: the stock AndroidRouter registers a
            // ConnectivityBroadcastReceiver without the RECEIVER_EXPORTED/
            // RECEIVER_NOT_EXPORTED flag that Android 13+ requires, which makes
            // the bound service crash on modern phones (whole-process crash).
            // Our router keeps the same stack but skips that receiver; failures
            // are caught below instead of killing the app.
            val service = object : UpnpServiceImpl(DlnaUpnpServiceConfiguration()) {
                override fun createRouter(
                    protocolFactory: ProtocolFactory,
                    registry: Registry
                ): Router {
                    return SimpleAndroidRouter(configuration, protocolFactory)
                }
            }
            // UpnpServiceImpl's constructor only stores the configuration — the
            // registry/router are created by startup(). Without it, registry is
            // null and addDevice() below would NPE.
            service.startup()
            upnpService = service
            service.registry.addDevice(createRendererDevice())
            // Diagnostics: how many devices/resources actually landed in the
            // registry, and the first resource path. Helps debug 404 on desc.
            try {
                val devCount = service.registry.localDevices.size
                val resCount = service.registry.resources.size
                val sample = service.registry.resources.firstOrNull()?.pathQuery
                val diag = "dev=$devCount res=$resCount $sample"
                lastDiagnostic = diag
                DebugLog.registryDiag = diag
                Logger.i("DLNA registry diag: $diag")
            } catch (t: Throwable) {
                Logger.w("DLNA diag failed: ${t.message}")
            }
            Logger.i("DLNA renderer advertising as: $displayName")
            report(ProtocolState.ADVERTISING)
        } catch (t: Throwable) {
            // Catch Throwable, not just Exception: on modern Android the old
            // jUPnP stack can fail with Error subclasses (NoSuchMethodError,
            // ExceptionInInitializerError, …) which would otherwise crash the
            // process. Surface the failure on the card instead.
            Logger.e("DLNA startup failed", t)
            val where = t.stackTrace.firstOrNull()
                ?.let { "${it.className}.${it.methodName}" }
            onError(
                "${t.javaClass.simpleName}: ${t.message}" +
                    (where?.let { " @ $it" } ?: "")
            )
            DebugLog.lastError = "${t.javaClass.simpleName}: ${t.message} @ ${where ?: "?"}"
            DebugLog.log("DLNA", "启动失败: ${t.javaClass.simpleName}: ${t.message}")
            releaseResources()
            started = false
            DlnaPlayerBridge.setControl(null)
            report(ProtocolState.ERROR)
        }
    }

    /**
     * Unregisters the renderer, releases the multicast lock and the player.
     *
     * Safe to call from **any** thread. Everything released here is main-thread
     * only — most importantly ExoPlayer, whose stop/release throw
     * IllegalStateException when touched off the main thread. That failure used
     * to be swallowed by the per-step `catch`, which then dropped the only
     * reference to a still-playing player: the audio kept going with nothing
     * left holding it, so no button could ever stop it. Callers therefore get
     * bounced onto the main thread and waited for.
     */
    fun stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val done = java.util.concurrent.CountDownLatch(1)
            mainHandler.post {
                try {
                    stopOnMainThread()
                } finally {
                    done.countDown()
                }
            }
            // Bounded so a wedged main thread cannot hang the caller's thread
            // (the service runs this from Dispatchers.IO during ACTION_STOP).
            if (!done.await(STOP_MAIN_THREAD_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                Logger.w("DLNA stop: main thread did not answer in ${STOP_MAIN_THREAD_TIMEOUT_MS}ms")
            }
            return
        }
        stopOnMainThread()
    }

    /** Main thread only. See [stop]. */
    private fun stopOnMainThread() {
        if (!started) return
        started = false
        DlnaPlayerBridge.setControl(null)
        // A queued Play has nothing to attach to any more, and the surface gate
        // must not resurrect a start after teardown.
        pendingStartUri = null
        mainHandler.removeCallbacks(foregroundTimeoutRunnable)
        mainHandler.removeCallbacks(surfaceTimeoutRunnable)
        mainHandler.removeCallbacks(stallWatchdog)
        surfaceReady = false
        realSurfacePresent = false
        uiForeground = false
        releaseResources()
        // Proof in the debug card that Stop really reached the player — the
        // report this fixes was "I pressed stop and the sound never stopped",
        // which was invisible from the UI.
        DebugLog.log("DLNA", "接收器已停止：播放器已释放（音频应立即停止）")
        report(ProtocolState.DISABLED)
    }

    /**
     * Waits for the box to be handed a LAN IPv4, then starts Manual SSDP.
     *
     * On a freshly booted TV box, [start] runs before DHCP answers, and the old
     * code left SSDP dead until the user restarted the app — the phone never saw
     * the device. The callback re-reads the address through the same selection
     * rules as the normal path, so the LOCATION stays on an interface the sender
     * can actually reach.
     */
    private fun waitForIpAndStartSsdp() {
        try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                    val addresses = lp.linkAddresses
                    val ip = addresses?.firstOrNull { ia ->
                        val a = ia.address
                        a is java.net.Inet4Address
                                && !a.isLoopbackAddress
                                && !a.isLinkLocalAddress
                    }?.address?.hostAddress
                    if (ip.isNullOrBlank()) return
                    try {
                        cm.unregisterNetworkCallback(this)
                    } catch (ignored: Exception) {
                    }
                    networkCallback = null
                    mainHandler.post {
                        if (!started) return@post
                        val fresh = NetworkUtils.getLocalIpv4()
                        if (fresh.isNullOrBlank()) return@post
                        try {
                            manualSsdp = ManualSsdp().apply { start(fresh) }
                            Logger.i("Manual SSDP started on $fresh:1900（IP 就绪后补启动）")
                            DebugLog.ssdpStatus = "运行中 (端口 1900)"
                        } catch (t: Throwable) {
                            Logger.i("Manual SSDP 补启动失败: ${t.message}")
                            DebugLog.ssdpStatus = "未启动: ${t.message}"
                        }
                    }
                }
            }
            networkCallback = callback
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .build(),
                callback
            )
            DebugLog.log("SSDP", "已监听局域网IP下发")
        } catch (e: Exception) {
            Logger.w("等待局域网IP失败: ${e.message}")
            DebugLog.ssdpStatus = "未启动: 未获取到局域网IP"
        }
    }

    /**
     * Records the real HTTP response behind a playback failure.
     *
     * `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED` reads like a player bug, but in
     * practice it is the far end: resolver/"parsing" services answer HTTP 200
     * with a text error page, and live-updating relay URLs expire. One GET here
     * turns an unactionable error into a one-line verdict - Content-Type and the
     * first bytes of the body are enough to tell those cases apart.
     */
    private fun probeSourceAsync(uri: String) {
        Thread {
            var conn: java.net.HttpURLConnection? = null
            try {
                conn = java.net.URL(uri).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 15; PhairPlay) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
                )
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                val code = conn.responseCode
                val type = conn.contentType ?: "(无 Content-Type)"
                val head = try {
                    conn.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(200)
                        val n = r.read(buf)
                        if (n <= 0) "(空响应体)" else String(buf, 0, n).replace('\n', ' ').replace('\r', ' ')
                    }
                } catch (e: Exception) {
                    "(读取响应体失败: ${e.message})"
                }
                DebugLog.log("DLNA", "源探测: HTTP $code, Content-Type=$type")
                DebugLog.log("DLNA", "源前200字节: $head")
            } catch (e: Exception) {
                DebugLog.log("DLNA", "源探测失败: ${e.javaClass.simpleName} ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }.apply { isDaemon = true; name = "dlna-source-probe" }.start()
    }

    // ───────────────────────── MediaItem construction ─────────────────────────

    /**
     * Declares the container type instead of trusting the far end.
     *
     * ExoPlayer chooses its extractor from the HTTP Content-Type, and relay or
     * resolver endpoints routinely answer with something unusable (text/plain,
     * application/octet-stream) while still serving a perfectly valid playlist.
     * The renderer then reports ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED and the
     * same URL plays fine elsewhere, because other players work the type out
     * from the address. Note the extension is not always in the path: app-local
     * relays bury it inside a query parameter (proxy?do=m3u8&url=...index.m3u8),
     * so both are inspected. Returns null when nothing can be inferred, leaving
     * ExoPlayer to sniff the bytes.
     */
    private fun mimeTypeForUri(uri: String): String? {
        val lower = uri.lowercase()
        val path = lower.substringBefore('?')
        val query = lower.substringAfter('?', "")
        return when {
            path.endsWith(".m3u8") || query.contains(".m3u8") || query.contains("do=m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") || query.contains(".mpd") -> MimeTypes.APPLICATION_MPD
            path.endsWith(".ism") || path.endsWith("/manifest") || query.contains(".ism") -> MimeTypes.APPLICATION_SS
            path.endsWith(".mp4") || query.contains(".mp4") -> MimeTypes.VIDEO_MP4
            else -> null
        }
    }

    /** First attempt: type inferred from the URL when possible. */
    private fun buildMediaItem(uri: String): MediaItem {
        val builder = MediaItem.Builder().setUri(uri)
        val mime = mimeTypeForUri(uri)
        if (mime != null) {
            builder.setMimeType(mime)
            DebugLog.log("DLNA", "按URL推断容器: $mime")
        }
        return builder.build()
    }

    /** Releases everything owned by the receiver. Main thread only. */
    private fun releaseResources() {
        // A full teardown is the one moment where grabbing the hardware
        // decoder slot again is impossible: the box may be running something
        // else by now, and this receiver is going away.
        decoderCooldownUntilMs = 0L
        decoderCooldownMs = DECODER_COOLDOWN_MS
        consecutiveCooldowns = 0
        parkedForUi = null
        mainHandler.removeCallbacks(decoderCooldownRetry)
        mainHandler.removeCallbacks(firstPictureWatchdog)
        lastStopReason = "接收器释放 releaseResources"
        // Drop the pending "wait for IP" watcher first: it fires once and
        // checks `started`, so leaving it registered can only resurrect a
        // socket after this receiver has been torn down.
        networkCallback?.let { cb ->
            try {
                (context.applicationContext
                    .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .unregisterNetworkCallback(cb)
            } catch (ignored: Exception) {
            }
        }
        networkCallback = null
        try {
            manualSsdp?.stop()
        } catch (t: Throwable) {
            Logger.w("Manual SSDP stop warning: ${t.message}")
        }
        manualSsdp = null

        try {
            upnpService?.shutdown()
        } catch (t: Throwable) {
            Logger.w("DLNA shutdown warning: ${t.message}")
        }
        upnpService = null

        try {
            multicastLock?.release()
        } catch (e: Exception) {
            Logger.w("MulticastLock release warning: ${e.message}")
        }
        multicastLock = null

        // ExoPlayer is the one resource whose failure is user-visible (a leaked
        // player keeps playing audio nobody can reach), so it gets a defence in
        // depth: silence it first, then release, and drop the reference either
        // way so a half-released instance is never left behind.
        val p = player
        player = null
        if (p != null) {
            // runCatching per step: one failure must not skip the next. Volume
            // first — it stops the sound even if stop()/release() throws.
            runCatching { p.volume = 0f }
            lastStopReason = "接收器释放 releaseResources"
            runCatching { p.stop() }
            runCatching { p.clearMediaItems() }
            runCatching { p.release() }.onFailure {
                Logger.w("ExoPlayer release warning: ${it.message}")
            }
        }
        currentUri = null
    }

    /** Binds the player output to a SurfaceView (call when the UI shows DLNA playback). */
    @OptIn(UnstableApi::class)
    fun attachSurface(surfaceView: SurfaceView) {
        mainHandler.post {
            val p = player?.takeIf { !it.isReleased } ?: return@post
            // PlayerView path: it manages the Surface lifecycle (including
            // surface recreation across background/foreground) and renders the
            // built-in controller. This is the full-screen DLNA playback UI.
            if (surfaceView is PlayerView) {
                surfaceView.player = p
                return@post
            }
            // Legacy bare-SurfaceView path: wait for the holder callback so we
            // never bind a stale/zero-size Surface.
            val holder = surfaceView.holder
            if (holder.surface.isValid) {
                p.setVideoSurface(holder.surface)
            } else {
                var cb: SurfaceHolder.Callback? = null
                cb = object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        p.setVideoSurface(h.surface)
                    }
                    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
                    }
                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        p.clearVideoSurface()
                    }
                }
                holder.addCallback(cb)
            }
        }
    }

    /**
     * Called by the UI once the playback view really exists: visible, laid out
     * with a non-zero size, and the PlayerView surface already attached.
     *
     * This is the gate that keeps ExoPlayer from creating a MediaCodec against
     * a half-built TextureView surface (see [surfaceReady]).
     */
    fun markSurfaceReady() {
        mainHandler.post {
            realSurfacePresent = true
            if (!surfaceReady) {
                surfaceReady = true
                surfaceGeneration++
                // Verdicts belong to the surface they were taken on.
                hevcCodecSelector.resetFailures()
                consecutiveDecoderFailures = 0
                mainHandler.removeCallbacks(surfaceTimeoutRunnable)
                // Only on the transition: this arrives on every probe tick
                // while the UI is up, and a log line per tick buried the one
                // line that mattered.
                DebugLog.log("DLNA", "渲染面已可用（generation=$surfaceGeneration）")
            }
            flushPendingStart()
        }
    }

    /**
     * Tells the receiver whether the Activity is actually on screen.
     *
     * This is the "cast arrives → bring the player up first → play once it is
     * really in the foreground" rule. The previous version pulled the UI and
     * prepared the player in the same breath, which let ExoPlayer configure the
     * Amlogic HEVC decoder against a view that was still getting its first
     * layout; MediaCodec then hung in configure() and the sender's next poll
     * built a second one. Nothing is prepared until the UI confirms it is there.
     */
    fun setUiForeground(foreground: Boolean) {
        mainHandler.post {
            uiForeground = foreground
            if (foreground) {
                mainHandler.removeCallbacks(foregroundTimeoutRunnable)
                if (surfaceReady) {
                    flushPendingStart()
                } else {
                    DebugLog.log("DLNA", "前台已确认 → 等播放层就绪")
                }
            }
        }
    }

    /**
     * Safety net for a cast that must make *some* sound: if the Activity never
     * reaches the foreground (launch refused, user in another app) the item is
     * started without a picture instead of staying silent forever.
     *
     * This is the path that produced the one good playback in the field log
     * (1920x1080 first try, 40 s after the surface appeared) — so "no
     * foreground" is not the problem it was assumed to be. It stays as a
     * fallback, but a decoder failure here must not blacklist a component.
     *
     * If a real surface *did* appear while we waited (the flag was just stale),
     * start on it rather than throwing the picture away — that is the whole
     * difference between 10 wasted seconds and an instant start.
     */
    private val foregroundTimeoutRunnable = Runnable {
        val uri = pendingStartUri ?: return@Runnable
        pendingStartUri = null
        surfaceReady = true
        if (realSurfacePresent) {
            DebugLog.log(
                "DLNA",
                "等待前台期间渲染面已就绪 → 直接在真实渲染面上起播（不再等满 ${FOREGROUND_WAIT_MS / 1000}s）"
            )
            if (started) runStart(uri)
            return@Runnable
        }
        DebugLog.log(
            "DLNA",
            "等待前台 ${FOREGROUND_WAIT_MS / 1000}s 未成功 → 按仅音频起播: $uri"
        )
        if (started) runStart(uri, audioOnly = true)
    }

    /** Starts the parked item, if the render surface is there by now. */
    private fun flushPendingStart() {
        val uri = pendingStartUri ?: return
        if (!surfaceReady) return
        // The decoder cooldown binds here too, not just in startPlayback.
        // markSurfaceReady keeps arriving while the UI is up (the surface probe
        // re-reports on every tick), so without this check every cooldown was
        // cancelled one second in by the very next surface report: the field log
        // shows eight attempts in twenty seconds, each one failing exactly like
        // the last, which is worse than not cooling down at all.
        if (decoderCooldownActive()) {
            mainHandler.removeCallbacks(decoderCooldownRetry)
            mainHandler.postDelayed(decoderCooldownRetry, COOLDOWN_RECHECK_MS.toLong())
            DebugLog.log(
                "DLNA",
                "硬解冷却中 → 渲染面就绪也不起播（约 " +
                    "${(decoderCooldownUntilMs - System.currentTimeMillis()) / 1000}s 后重试）"
            )
            return
        }
        pendingStartUri = null
        mainHandler.removeCallbacks(surfaceTimeoutRunnable)
        DebugLog.log("DLNA", "前台已就绪 → 起播")
        runStart(uri)
    }

    /** Called by the UI when the playback view is hidden or destroyed, so the
     *  next Play is never armed against a surface that no longer exists. */
    fun markSurfaceGone() {
        mainHandler.post {
            surfaceReady = false
            realSurfacePresent = false
            mainHandler.removeCallbacks(surfaceTimeoutRunnable)
        }
    }

    /** Detaches the player output from any SurfaceView (call when the UI hides playback). */
    @OptIn(UnstableApi::class)
    fun detachSurface() {
        mainHandler.post {
            val p = player?.takeIf { !it.isReleased } ?: return@post
            p.clearVideoSurface()
        }
    }

    /**
     * Called when the UI leaves the foreground.
     *
     * This does far more than `pause()`. ExoPlayer **keeps the MediaCodec
     * allocated while paused**, and on these boxes there is exactly one HEVC
     * hardware decoder to go round: a receiver that merely pauses keeps the
     * slot forever, and the next app to ask for hardware decoding (the user's
     * IPTV player) is refused until the box is rebooted. So stop and drop the
     * media item — that is what actually returns the decoder — and remember
     * the URI so [resumePlaybackFromUi] can rebuild the item on the way back.
     */
    fun pausePlaybackFromUi() {
        mainHandler.post {
            val p = player?.takeIf { started && !it.isReleased } ?: return@post
            val resumeUri = currentUri
            val resumePos = runCatching { p.currentPosition }.getOrDefault(0L)
            p.pause()
            p.clearVideoSurface()
            p.clearMediaItems()
            p.stop()
            lastStopReason = "界面退到后台 pausePlaybackFromUi"
            if (resumeUri != null) {
                parkedForUi = ParkedMedia(resumeUri, resumePos)
                DebugLog.log(
                    "DLNA",
                    "界面退到后台 → 释放硬解槽（仅 pause 不会归还解码器）"
                )
            }
        }
    }

    /**
     * Ends playback because the user left the app (Back out of the UI).
     * The receiver itself keeps running — a renderer that stops advertising
     * the moment its own UI closes can never be found by the next cast — but
     * the media must not keep sounding from an app the user closed, and the
     * sender has to see STOPPED instead of a stuck PLAYING.
     */
    @OptIn(UnstableApi::class)
    fun stopPlaybackFromUi() {
        mainHandler.post {
            clearPlayback("用户退出界面 stopPlaybackFromUi")
            ManualDlnaHttp.notifyPlaybackEnded()
            report(ProtocolState.ADVERTISING)
        }
    }

    /**
     * Resumes DLNA playback when the app returns to the foreground.
     *
     * The media item was dropped on the way out to free the decoder, so this
     * rebuilds it through the normal gated path — the render surface also has
     * to be handed back before preparing, for the same reason as a fresh cast.
     */
    fun resumePlaybackFromUi() {
        mainHandler.post {
            val parked = parkedForUi ?: return@post
            if (!started || player?.isReleased != false) return@post
            // The sender's Play may have re-armed the item already; do not
            // stomp on a start that is already in flight.
            if (pendingStartUri != null) return@post
            parkedForUi = null
            DebugLog.log("DLNA", "回到前台 → 重建媒体项并等待渲染面: ${parked.uri.take(72)}…")
            pendingSeekMs = parked.positionMs
            currentUri = parked.uri
            // Deliberately routed through the surface gate: preparing here
            // without a real Surface is what wedged the decoder in the first place.
            if (!uiForeground && !realSurfacePresent) {
                pendingStartUri = parked.uri
                mainHandler.removeCallbacks(foregroundTimeoutRunnable)
                mainHandler.postDelayed(
                    foregroundTimeoutRunnable, FOREGROUND_WAIT_MS.toLong()
                )
            } else if (surfaceReady) {
                runStart(parked.uri)
            } else {
                pendingStartUri = parked.uri
                mainHandler.removeCallbacks(surfaceTimeoutRunnable)
                mainHandler.postDelayed(surfaceTimeoutRunnable, SURFACE_WAIT_MS.toLong())
            }
            onSurfaceProbeNeeded()
        }
    }

    // ─── DlnaPlayerControl (called from Cling state-machine threads) ─────

    @OptIn(UnstableApi::class)
    override fun startPlayback(uri: String) {
        mainHandler.post {
            if (!started) return@post

            val p = player ?: return@post

            // Something is already queued behind a missing render surface: the
            // sender is polling the very item we could not start yet, so stay
            // armed instead of replacing the pending attempt.
            if (pendingStartUri == uri && !surfaceReady) {
                report(ProtocolState.CONNECTED)
                return@post
            }

            // ── Respect the decoder cooldown ───────────────────────────────────
            // A brand new SetAVTransportURI + Play arrives while the box is still
            // cooling down from a failed init must NOT start immediately. That is
            // the exact path that used to walk straight into the same failure:
            // the cooldown only governed our own retries, while every sender poll
            // (they come every 2–3 s) went straight through the gate. Park it and
            // let [decoderCooldownRetry] pick it up — a different item also gets
            // the same treatment, because the slot is a box-wide resource, not
            // per-channel.
            if (decoderCooldownActive()) {
                pendingStartUri = uri
                mainHandler.removeCallbacks(decoderCooldownRetry)
                mainHandler.postDelayed(decoderCooldownRetry, COOLDOWN_RECHECK_MS.toLong())
                DebugLog.log(
                    "DLNA",
                    "硬解冷却中 → 暂缓起播（约 ${(decoderCooldownUntilMs - System.currentTimeMillis()) / 1000}s 后重试）"
                )
                report(ProtocolState.CONNECTED)
                return@post
            }

            // ── Wait for the foreground ───────────────────────────────────────
            // The order a receiver must keep: the sender pushes media → the UI
            // comes up → the UI confirms "I am on screen" → only then is the
            // player prepared. Preparing earlier is what produced the
            // "Decoder init failed … 超时" loop on this box: ExoPlayer configured
            // the HEVC decoder while the Activity was still being started, and
            // every poll the sender made while that hung built another decoder.
            // Nothing is prepared before [setUiForeground] reports true.
            //
            // Unless the surface is already there. "The Activity is resumed" and
            // "there is a Surface to decode into" are different questions, and
            // only the second one is what actually broke the decoder. The field
            // log shows a cast that waited the full 10 s for a foreground report
            // and then played 1920x1080 on the first try with no surface error
            // at all — the PlayerView had been up the whole time. So a live
            // surface overrides the flag; [uiForeground] only decides whether we
            // need to *wait* for one.
            if (!uiForeground && !realSurfacePresent) {
                pendingStartUri = uri
                mainHandler.removeCallbacks(foregroundTimeoutRunnable)
                mainHandler.postDelayed(
                    foregroundTimeoutRunnable, FOREGROUND_WAIT_MS.toLong()
                )
                DebugLog.log(
                    "DLNA",
                    "界面不在前台 → 先拉起播放器，确认前台后再起播（${FOREGROUND_WAIT_MS / 1000}s 兜底仅音频）"
                )
                // Ask for the layer now instead of letting the timeout be the
                // thing that finally reveals it. Field log: the cast sat on the
                // home screen for the full 10 s and then started on the very
                // same frame the fallback fired — nothing was actually slow but
                // the request to show the player.
                onSurfaceProbeNeeded()
                onPlaybackUiNeeded()
                report(ProtocolState.CONNECTED)
                return@post
            }

            // ── Wait for the render surface ───────────────────────────────────
            // The UI is on screen but the revealed PlayerView may not have a
            // real Surface yet. [SURFACE_WAIT_MS] keeps a hard cap.
            if (!surfaceReady) {
                pendingStartUri = uri
                mainHandler.removeCallbacks(surfaceTimeoutRunnable)
                mainHandler.postDelayed(surfaceTimeoutRunnable, SURFACE_WAIT_MS.toLong())
                DebugLog.log(
                    "DLNA",
                    "播放层尚未就绪 → 暂缓起播（${SURFACE_WAIT_MS / 1000}s 内无界面则以仅音频起播）"
                )
                // Same reason as the foreground branch above: the layer is what
                // makes the surface real, so request it immediately. Waiting for
                // the fallback timer is what produced the 3 s "nothing happens"
                // gap on a cast that started while the app was already open.
                onSurfaceProbeNeeded()
                onPlaybackUiNeeded()
                report(ProtocolState.CONNECTED)
                return@post
            }

            // Past the surface gate, so `surfaceReady` can only have been set by
            // [markSurfaceReady] (the two bounded fallbacks call [runStart]
            // directly, never through here) — a real surface. [runStart] records
            // that in [startedOnRealSurface] so a decoder failure is judged
            // against the component instead of the surface.

            // ── Idempotent replay ─────────────────────────────────────────────
            // This is the black-screen-with-sound fix. Phone apps re-send the
            // same SetAVTransportURI + Play every 10–30 s while polling whether
            // playback really started (we saw four identical pushes inside a
            // minute). Re-running setMediaItem + prepare on each one tears the
            // pipeline back to square one *just before* the first frame lands,
            // so it can never get past the black frame → looks dead, while the
            // same URL plays elsewhere because those renderers treat Play as
            // "make sure it plays". Only a *different* URI is a real source
            // change.
            if (uri == currentUri && p.mediaItemCount > 0 &&
                p.playbackState != Player.STATE_IDLE
            ) {
                DebugLog.log(
                    "DLNA",
                    "重复 Play(同一 URI) → 仅续播 state=${stateNameOf(p.playbackState)}"
                )
                if (!p.playWhenReady) p.play()
                report(ProtocolState.CONNECTED)
                return@post
            }

            // A decoder that never initialises will never initialise. The
            // sender keeps polling the same URI, so every one of those polls
            // used to build a fresh MediaCodec (and leak it) — refuse the ones
            // for the item we already ruled out instead.
            // Give up on an item whose decoder is genuinely ruled out. On a
            // single-component box this can no longer be reached (the sole
            // candidate is never blacklisted — see the error handler), which is
            // deliberate: this branch used to fire for the rest of the session
            // and turned every later cast into audio-only playback with
            // `视频轨: 0x0`.
            if (uri == currentUri && hevcCodecSelector.isHevcBroken()) {
                val now = System.currentTimeMillis()
                if (now - lastHevcDeadNoticeMs > HEVC_DEAD_NOTICE_MS) {
                    lastHevcDeadNoticeMs = now
                    DebugLog.log(
                        "DLNA",
                        "跳过同一片源的重试（HEVC 解码器已排除）: $uri"
                    )
                    DebugLog.log("DLNA", hevcCodecSelector.describeAvailable())
                }
                ManualDlnaHttp.notifyPlaybackEnded()
                report(ProtocolState.ADVERTISING)
                return@post
            }

            runStart(uri)
        }
    }

    /**
     * Loads, prepares and plays one item. Reached either straight from
     * [startPlayback] (surface already there) or once the UI reports
     * [markSurfaceReady].
     *
     * @param audioOnly True when one of the bounded fallbacks fired because no
     *   usable surface ever appeared. Preparing without a surface is what makes
     *   the Amlogic HAL hang, so this is a last resort, and a decoder failure
     *   recorded on such an attempt says nothing about the component.
     */
    @OptIn(UnstableApi::class)
    private fun runStart(uri: String, audioOnly: Boolean = false) {
        mainHandler.post {
            if (!started) return@post
            val p = player ?: return@post

            currentUri = uri
            retryCount = 0
            consecutiveDecoderFailures = 0
            startedOnRealSurface = !audioOnly
            pendingSeekMs = -1L
            mainHandler.removeCallbacks(surfaceTimeoutRunnable)
            // An item is loaded from here on: the UI may offer "back to
            // playback" and (once READY) know whether it is audio-only.
            DlnaMediaMeta.setActive(true)
            DlnaMediaMeta.audioOnly = false
            Logger.i("DLNA playback start: $uri")
            DebugLog.log("DLNA", "开始播放: $uri")
            // Anti-leech CDNs: install whatever headers the sender embedded in
            // the relay URL BEFORE the first request leaves (see helper docs).
            applyRelayHeaders(uri)
            mainHandler.removeCallbacks(stallWatchdog)
            mainHandler.postDelayed(stallWatchdog, STALL_TIMEOUT_MS.toLong())
            p.setMediaItem(buildMediaItem(uri))
            p.volume = (DlnaAudioRenderingControl.getVolumeValue() / 100f)
                .coerceIn(0f, 1f)
            p.prepare()
            p.play()
            report(ProtocolState.CONNECTED)
        }
    }

    @OptIn(UnstableApi::class)
    override fun pausePlayback() {
        mainHandler.post {
            player?.takeIf { started && !it.isReleased }?.pause()
        }
    }

    @OptIn(UnstableApi::class)
    override fun resumePlayback() {
        mainHandler.post {
            player?.takeIf { started && !it.isReleased && currentUri != null }?.play()
        }
    }

    @OptIn(UnstableApi::class)
    override fun stopPlayback() {
        mainHandler.post {
            if (!started) return@post
            clearPlayback("发送端 Stop (SOAP)")
            // A control point Stop is the one event that makes "the same uri
            // again" a genuinely new cast. The service forgets the uri here and
            // nowhere else; forgetting it on our own UI stop is what let a
            // polling sender resurrect a cast the user had just closed.
            onSenderStop()
            report(ProtocolState.ADVERTISING)
        }
    }

    @OptIn(UnstableApi::class)
    override fun seekTo(positionSeconds: Long) {
        mainHandler.post {
            val p = player?.takeIf { started && !it.isReleased } ?: return@post
            if (p.playbackState != Player.STATE_READY) {
                // Still preparing: park the seek instead of aborting the
                // in-flight HLS loads. The STATE_READY handler applies it.
                pendingSeekMs = positionSeconds * 1000L
                DebugLog.log("DLNA", "起播未就绪，暂存 Seek → ${positionSeconds}s")
                return@post
            }
            p.seekTo(positionSeconds * 1000L)
        }
    }

    override fun getPositionSeconds(): Long =
        queryPlayerLong { it.currentPosition.coerceAtLeast(0L) / 1000L }

    override fun getDurationSeconds(): Long =
        queryPlayerLong { it.duration.takeIf { d -> d > 0L }?.div(1000L) ?: 0L }

    override fun setVolumePercent(percent: Int) {
        mainHandler.post {
            player?.takeIf { started && !it.isReleased }?.volume = percent.coerceIn(0, 100) / 100f
        }
    }

    /**
     * Reads a value off the player. ExoPlayer is main-thread-only; SOAP
     * threads block briefly (200 ms cap) on a main-thread round-trip.
     */
    private fun queryPlayerLong(query: (ExoPlayer) -> Long): Long {
        val p = player ?: return 0L
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return if (p.isReleased) 0L else query(p)
        }
        var out = 0L
        val latch = java.util.concurrent.CountDownLatch(1)
        mainHandler.post {
            val cur = player
            out = if (cur != null && !cur.isReleased) query(cur) else 0L
            latch.countDown()
        }
        try {
            latch.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return out
    }

    // ─── Private helpers ──────────────────────────────────────────────────

    /**
     * Applies HTTP headers the sender embedded in a relay URL:
     * `...&headers=<base64 of "K:V" lines>`. Anti-leech CDNs (vd.wmvbo.com,
     * observed 2026-10-02) 302-redirect every request whose User-Agent is not
     * the one the original downloader used. The manifest survives because the
     * phone's local proxy injects the header, but an HLS playlist resolves its
     * segments to absolute CDN URLs which ExoPlayer then fetches directly —
     * with the wrong UA every segment bounces to a 302 and the item sits in
     * BUFFERING until it dies with PARSING_CONTAINER_MALFORMED. So the header
     * must be installed at player (DataSource.Factory) level, not just for the
     * manifest request. This is also why ijkplayer-based senders (当贝) play
     * the same URL: their ffmpeg default UA is exactly `Lavf/…`.
     */
    private fun applyRelayHeaders(uri: String) {
        var ua: String? = null
        val map = LinkedHashMap<String, String>()
        try {
            val raw = android.net.Uri.parse(uri).getQueryParameter("headers")
            if (!raw.isNullOrBlank()) {
                // Base64 payloads may be standard or URL-safe and unpadded;
                // accept both, then percent-decode the inner value encoding.
                val b64 = raw.trim().let {
                    it + "=".repeat((4 - it.length % 4) % 4)
                }
                val decoded = try {
                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                } catch (_: IllegalArgumentException) {
                    android.util.Base64.decode(b64, android.util.Base64.URL_SAFE)
                }.toString(Charsets.UTF_8)
                val lines = java.net.URLDecoder.decode(decoded, "UTF-8")
                    .split("\n", "&&")
                for (line in lines) {
                    val idx = line.indexOf(':')
                    if (idx <= 0) continue
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    if (key.isEmpty() || value.isEmpty()) continue
                    if (key.equals("User-Agent", ignoreCase = true)) ua = value else map[key] = value
                }
            }
        } catch (t: Throwable) {
            DebugLog.log("DLNA", "解析投屏请求头失败: ${t.message}")
        }
        httpFactory.setUserAgent(ua ?: DEFAULT_HTTP_USER_AGENT)
        httpFactory.setDefaultRequestProperties(map)
        val shown = buildString {
            if (ua != null) append("User-Agent=$ua")
            for ((k, v) in map) {
                if (isNotEmpty()) append(", ")
                append("$k=$v")
            }
        }
        DebugLog.log("DLNA", if (shown.isEmpty()) "投屏无附加请求头（默认浏览器UA）" else "应用投屏请求头: $shown")
    }

    /**
     * Drops the item and frees the decoder.
     *
     * @param reason written into [lastStopReason] so the IDLE transition that
     *   follows can be attributed. Pass something a human can act on.
     */
    @OptIn(UnstableApi::class)
    private fun clearPlayback(reason: String) {
        currentUri = null
        pendingSeekMs = -1L
        lastStopReason = reason
        DlnaMediaMeta.setActive(false)
        player?.let { p ->
            p.stop()
            p.clearMediaItems()
        }
    }

    /**
     * Extracts the MediaCodec component name from a decode failure, e.g. the
     * `OMX.amlogic.hevc.decoder.awesome` of
     * "DecoderInitializationException: Decoder init failed: OMX.amlogic.hevc.decoder.awesome, Format(…)".
     * Returns null when the message uses another shape.
     */
    private fun failingCodecName(error: PlaybackException): String? {
        val text = buildString {
            append(error.message ?: "")
            error.cause?.let { append(it.message ?: "") }
        }
        // Two shapes: "Decoder init failed: <component>, Format(…)" from
        // media3, or "Video framework suggested decoder <component> (…)" once
        // decoder fallback has walked past several candidates. Fall back to
        // any component name that shows up in the message.
        val match = Regex("Decoder init failed:\\s*([^,\\s]+)")
            .find(text)
            ?: Regex("(OMX\\.[A-Za-z0-9_.]+)").find(text)
        return match?.groupValues?.getOrNull(1)
    }

    private fun report(state: ProtocolState) {
        mainHandler.post { onStateChanged(state) }
    }

    private fun createRendererDevice(): LocalDevice {
        // AVTransport with the DLNA state machine (NoMediaPresent → Stopped → Playing).
        val avService: LocalService<AVTransportService<AVTransport>> =
            AnnotationLocalServiceBinder().read(AVTransportService::class.java)
                as LocalService<AVTransportService<AVTransport>>
        val lastChangeParser = AVTransportLastChangeParser()
        avService.setManager(
            object : LastChangeAwareServiceManager<AVTransportService<AVTransport>>(avService, lastChangeParser) {
                @Throws(Exception::class)
                override fun createServiceInstance(): AVTransportService<AVTransport> {
                    return AVTransportService<AVTransport>(
                        DlnaRendererStateMachine::class.java,
                        DlnaNoMediaPresent::class.java
                    )
                }
            }
        )

        // RenderingControl (volume).
        val renderService: LocalService<DlnaAudioRenderingControl> =
            AnnotationLocalServiceBinder().read(DlnaAudioRenderingControl::class.java)
                as LocalService<DlnaAudioRenderingControl>
        renderService.setManager(
            LastChangeAwareServiceManager<DlnaAudioRenderingControl>(
                renderService,
                DlnaAudioRenderingControl::class.java,
                RenderingControlLastChangeParser()
            )
        )

        // ConnectionManager (protocol info handshake).
        val connService: LocalService<ConnectionManagerService> =
            AnnotationLocalServiceBinder().read(ConnectionManagerService::class.java)
                as LocalService<ConnectionManagerService>

        return LocalDevice(
            // Fixed UDN (standard UUID format — Windows/VLC reject non-UUID
            // UDNs) so the device-description URL is predictable:
            // http://<ip>:8899/upnp/dev/6f61c845-1dd2-11b2-8f7b-001185123456/desc
            DeviceIdentity(UDN("uuid:6f61c845-1dd2-11b2-8f7b-001185123456")),
            UDADeviceType("MediaRenderer"),
            DeviceDetails(displayName.ifBlank { "PhairPlay" }),
            arrayOf<LocalService<*>>(avService, renderService, connService)
        )
    }

    companion object {
        /** How long [startPlayback] waits for the UI to be on screen. */
        private const val FOREGROUND_WAIT_MS = 10_000

        /** How long an off-main-thread [stop] waits for the main thread to run
         *  the teardown. Only the wait is bounded; the teardown itself is not. */
        private const val STOP_MAIN_THREAD_TIMEOUT_MS = 3_000

        /** How long [startPlayback] may wait for the playback UI before it
         *  accepts "sound only". The view only reports a real surface after
         *  the next layout; that is a fraction of a second in practice, so
         *  this cap only matters when the UI never shows up at all. */
        private const val SURFACE_WAIT_MS = 3_000

        /**
         * Decoder init attempts before a component is written off.
         *
         * The Amlogic HEVC component on these boxes refuses the *first*
         * create after a while (the HAL is warming up) and then works: in the
         * field the first cast failed and the next three played at once. So a
         * single failure is not proof anything is broken — it gets a few
         * attempts and only then lands on the skip list, which is what used
         * to make "one bad channel" permanently unrunnable.
         */
        private const val HEVC_ATTEMPT_LIMIT = 3

        /** How long a source may stay silent before we probe it ourselves. */
        private const val STALL_TIMEOUT_MS = 15_000

        /**
         * How long the receiver stays off the hardware decoder after a failed
         * init.
         *
         * The box has one HEVC decoder and every app shares it. A failed
         * `configure()` can leave the Amlogic HAL holding that slot, and a
         * receiver that keeps retrying robs the user's other players (their
         * IPTV app then reports a hardware decode error) until the box is
         * rebooted. Backing off for a few seconds is what keeps PhairPlay from
         * breaking the rest of the system.
         */
        private const val DECODER_COOLDOWN_MS = 8_000

        /**
         * Ceiling for the doubling backoff.
         *
         * Long enough that the box really gets its single hardware decoder back
         * for a while, which is the entire point: a receiver that keeps taking
         * the slot is what breaks every other player on the device.
         */
        private const val DECODER_COOLDOWN_MAX_MS = 60_000

        /**
         * How often a Play that arrived during the cooldown re-checks it.
         *
         * Senders poll every 2–3 s, so this only decides how promptly the item
         * is picked up once the slot is free again.
         */
        private const val COOLDOWN_RECHECK_MS = 1_000

        /**
         * Minimum gap between user-facing "decoder may be occupied" hints.
         *
         * The sender re-polls every 2-3 s and every poll can end in the same
         * real-surface decoder failure — without this throttle the hint would
         * fire a Toast every few seconds until the cooldown grew long enough to
         * stop the retries.
         */
        private const val DECODER_HINT_MIN_INTERVAL_MS = 30_000

        // ── Buffering ──────────────────────────────────────────────────────
        // A live HLS window here holds only a few segments, and ExoPlayer's
        // defaults are thinner than one segment round-trip. Anything that makes
        // a segment fetch slow (CDN hiccup, a redirect, a slow TCP window)
        // drains the buffer to zero and the picture freezes. Four times the
        // default depth absorbs those without adding perceptible latency on a
        // live stream, which is what the 13-second `IO_UNSPECIFIED` pattern in
        // the field log is really measuring.
        //
        // ORDERING IS AN ASSERTION, NOT A PREFERENCE. DefaultLoadControl checks
        //     minBufferMs >= bufferForPlaybackAfterRebufferMs
        // and throws IllegalArgumentException otherwise — inside start(), which
        // takes the whole DLNA stack down with it (no 8899 listener, so not even
        // /debug is reachable). These four values violated it once already
        // (min 10 s vs rebuffer 12 s) and took the receiver down on the box.
        // Keep minBuffer >= rebuffer, and keep maxBuffer >= minBuffer.
        private const val MIN_BUFFER_MS = 15_000
        private const val MAX_BUFFER_MS = 60_000

        /** Start playing once this much is buffered — near-instant on a live
         *  stream that is already buffering in the background. */
        private const val BUFFER_FOR_PLAYBACK_MS = 500

        /** How much must be buffered after a stall before resuming. A shallow
         *  value here is what turns one failed segment into a visible freeze.
         *  MUST stay <= [MIN_BUFFER_MS] — see the assertion note above. */
        private const val BUFFER_FOR_REBUFFER_MS = 10_000

        /**
         * How long a video item may sit at READY without a single frame before
         * we call it a failed start.
         *
         * Generous, because a live HLS stream legitimately takes a moment to
         * deliver its first segment — this is not a latency budget, it only has
         * to outlast "the decoder came up but is writing into a surface that
         * never becomes usable".
         */
        private const val FIRST_PICTURE_TIMEOUT_MS = 12_000

        /** Gap between "this decoder is dead" notices in the debug log. */
        private const val HEVC_DEAD_NOTICE_MS = 30_000

        /** Fallback UA when the sender does not declare one. */
        private const val DEFAULT_HTTP_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; PhairPlay) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"

        private fun stateNameOf(state: Int): String = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN"
        }

        @Volatile
        private var crashGuardInstalled = false

        /** Last registry diagnostics, shown on the DLNA card for debugging. */
        @Volatile
        var lastDiagnostic: String? = null

        /**
         * Installs a default uncaught-exception handler that intercepts crashes
         * originating from the (old) jUPnP stack — its background threads can
         * throw on modern Android and would otherwise take down the process.
         * Crashes from any other code keep the platform default behaviour.
         */
        private fun installCrashGuard(instanceHandler: (Throwable) -> Unit) {
            if (crashGuardInstalled) return
            synchronized(this) {
                if (crashGuardInstalled) return
                val default = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                    val fromJupnp = throwable.stackTrace.any {
                        it.className.startsWith("org.jupnp")
                    }
                    if (fromJupnp) {
                        Logger.e("jUPnP thread crashed (${thread.name})", throwable)
                        Handler(Looper.getMainLooper()).post {
                            instanceHandler(throwable)
                        }
                    } else {
                        default?.uncaughtException(thread, throwable)
                    }
                }
                crashGuardInstalled = true
            }
        }
    }
}

/**
 * Router for the DLNA renderer's UPnP stack.
 *
 * Deliberately does NOT register a ConnectivityBroadcastReceiver: the stock
 * jUPnP [org.jupnp.android.AndroidRouter] does so without the
 * RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED flag that Android 13+ (API 33+)
 * requires on dynamically registered receivers — that crashes the process on
 * modern devices. It also skips NetworkUtils.getConnectedNetworkInfo(), a
 * deprecated API that can return null on recent Android versions.
 *
 * The [DlnaReceiver] owns its own MulticastLock, so the router does not need
 * the Wi-Fi lock management either; it still handles all SSDP/UDP socket
 * binding and protocol dispatch via [RouterImpl].
 */
private class SimpleAndroidRouter(
    configuration: UpnpServiceConfiguration,
    protocolFactory: ProtocolFactory
) : RouterImpl(configuration, protocolFactory)
