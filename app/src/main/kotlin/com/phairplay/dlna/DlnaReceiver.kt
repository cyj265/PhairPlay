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
    private val onSenderStop: () -> Unit = {},
    /**
     * Answers "the user closed this uri and it must not come back".
     *
     * The service keeps the dismissal (uri + expiry) for the foreground gate;
     * the receiver needs the same answer *before* it parks an item, because
     * the two things a dismissed cast used to do on the next sender poll were
     * both wrong — it showed the picture again, or it started playback and
     * left the box sounding while the user sat on the home screen they
     * deliberately chose.
     */
    private val isCastDismissed: (String?) -> Boolean = { false },
    /**
     * v98: a Play instruction reached the renderer.
     *
     * The sender asking for playback is the one piece of evidence we never
     * have to guess at, so it outranks any earlier "user closed this".
     * Phone apps re-push the *same* SetAVTransportURI + Play every 10-30 s
     * while they poll whether playback really started; treating those
     * re-pushes as a resurrection of something the user dismissed shut the
     * channel for the whole DISMISS window (field log 13:04:31 -> 13:06:13
     * of a douyin cast that only started once the sender changed uri).
     */
    private val onCastPlayIntent: (String) -> Unit = {},
    /**
     * v101-⑧ — the idle sweep gave the hardware slot back while paused, so
     * the UI can say "暂停中，闲置 X 秒后释放" instead of letting the user
     * wonder why the slot went quiet.
     */
    private val onPauseIdleWarning: () -> Unit = {},
    /**
     * v101-⑩ — the far end served something that is not media (anti-scraping
     * page, expired ticket). Worth one line on screen: retrying cannot help,
     * and silence made users blame the app.
     */
    private val onSourceHint: (String) -> Unit = {}
) : DlnaPlayerControl {

    /**
     * v98: set while a cast the user closed is being (re)started.
     *
     * It gates the *presentation* only — the receiver keeps playing, it just
     * must not put the playback layer in front of the user. Keeping the two
     * apart is what the old single dismissal flag got wrong: it stopped the
     * pipeline too, so a user who pressed Back got a box that sounded while
     * the picture stayed behind the home screen, with no way and no reason
     * to come back.
     */
    private var uiGateBlocked = false

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

    /**
     * v99-② — when [pendingStartUri] was parked, so "pending" can expire.
     *
     * v98's F3 dropped [hasPendingCast] from the UI's paint decision, which
     * cured the layer that lies about having a picture but also left the 1-3 s
     * between SetURI and the first real surface with no way back at all. The
     * fix is not to restore that permanently but to restore it with a deadline:
     * long enough to cover the wait, short enough that a genuinely stuck cast
     * stops claiming there is something to watch.
     */
    @Volatile
    private var pendingStartAtMs = 0L

    /** The single place a pending item is recorded, so the clock starts here. */
    private fun holdPending(uri: String?) {
        // Nullable on purpose: two of the callers sit in the player-error
        // handler, where currentUri may already be gone. Parking nothing is
        // the right answer there — the sender's next Play brings the uri back.
        if (uri.isNullOrEmpty()) return
        pendingStartUri = uri
        pendingStartAtMs = System.currentTimeMillis()
    }

    /**
     * The item the UI must reveal even though nothing has started yet.
     *
     * [currentCastUri] is deliberately NOT the answer to "is there something to
     * paint?": it is only written in [runStart], which runs *after* the surface
     * gate. A cast that is still waiting for its layer therefore reported
     * "no uri", the UI refused to show the player, the surface never appeared,
     * the fallback fired and the box played sound behind a home screen — the
     * reported symptom, and a deadlock created by the guard meant to prevent
     * it. Play is a firm promise that an item follows; showing the layer is
     * what makes that promise real.
     */
    val pendingCastUri: String? get() = pendingStartUri

    /** True while an item the sender pushed is still waiting to start. */
    fun hasPendingCast(): Boolean = pendingStartUri != null

    /**
     * v99-② — a pending item that is still young enough to be worth a layer.
     *
     * Both sides agreed (round 5/6 of the coordination doc) that F3 was about
     * a layer claiming to show something for good, not about the seconds while
     * the surface is still being laid out.
     */
    fun hasRecentPendingCast(): Boolean {
        val parked = pendingStartUri ?: return false
        if (parked.isEmpty()) return false
        return System.currentTimeMillis() - pendingStartAtMs <= PENDING_CAST_ENTRY_MS
    }

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
        // Same reasoning as [foregroundTimeoutRunnable]: the layer that owns
        // the Surface is what the Activity must show, and if it is hidden the
        // next poll finds the same state. Ask before giving up; the audio-only
        // start below remains the guarantee that sound happens either way.
        onSurfaceProbeNeeded()
        pendingStartUri = null
        surfaceReady = true
        DebugLog.log(
            "DLNA",
            "等待播放层 ${SURFACE_WAIT_MS / 1000}s 未就绪 → 再请求一次界面，按仅音频起播: $uri"
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
        // v97: READY-but-0x0 is only a decoder problem if the source actually
        // answered. Boxes here resolve public CDN hostnames through a link-local
        // IPv6 resolver first, so a live source can take 20+s to hand over its
        // first SPS — long enough to trip this watchdog and drop the decoder
        // slot, after which the next start pays the same 20+s *again*. Cooling
        // down on a slow source is what turned "one slow channel" into a
        // two-minute black screen. If the probe says the far end is fine, retry
        // the item instead of taking the decoder away.
        val probeAge = System.currentTimeMillis() - lastProbeAtMs
        val probeFresh =
            lastProbeVerdict == PROBE_HEALTHY && probeAge < PROBE_FRESHNESS_MS
        // v99-①: "we could not classify the answer" is not "the source is
        // dead". It retries the item like a slow source does and, like a slow
        // source, never takes the decoder slot away on its own. Only a proven
        // non-media answer (or never having probed at all) may cool down.
        val probeUnproven =
            lastProbeVerdict == PROBE_UNPROVEN && probeAge < PROBE_FRESHNESS_MS
        if ((probeFresh || probeUnproven) && sourceSlowRetries < SOURCE_SLOW_RETRY_LIMIT) {
            sourceSlowRetries++
            DebugLog.log(
                "DLNA",
                "READY 后 ${FIRST_PICTURE_TIMEOUT_MS / 1000}s 仍无画面（视频轨 0x0），" +
                    (if (probeFresh) "但源探测 $lastProbeCode 正常" else "但源探测未确证（不判死）") +
                    " → 判为源侧慢，不交还硬解槽，" +
                    "${SOURCE_SLOW_RETRY_MS / 1000}s 后重试起播（第 $sourceSlowRetries/$SOURCE_SLOW_RETRY_LIMIT 次）"
            )
            surfaceGeneration++
            hevcCodecSelector.resetFailures()
            consecutiveDecoderFailures = 0
            mainHandler.removeCallbacks(foregroundTimeoutRunnable)
            mainHandler.postDelayed(
                { resumeParkedCast(uri) },
                SOURCE_SLOW_RETRY_MS
            )
            onSurfaceProbeNeeded()
            onPlaybackUiNeeded()
            return@Runnable
        }
        if (sourceSlowRetries >= SOURCE_SLOW_RETRY_LIMIT) {
            DebugLog.log(
                "DLNA",
                "同一片源已因慢重试 $SOURCE_SLOW_RETRY_LIMIT 次仍无画面 → 交还硬解槽并冷却"
            )
            sourceSlowRetries = 0
        }
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

    // ── v97: "is the picture late because the source is slow, or because the
    // decoder is dead?" — the question [firstPictureWatchdog] has to answer
    // before it may take the decoder slot away.

    /** Guards against two [runStart] calls for one item inside a single second. */
    private var lastRunStartUri: String? = null
    private var lastRunStartMs = 0L

    /** Last time [probeSourceAsync] saw a healthy answer from the far end. */
    private var lastProbeOkMs = 0L

    private var lastProbeCode = 0

    /**
     * v99-① — what the last probe actually proved.
     *
     * [lastProbeOkMs] cannot carry this on its own any more: "the far end
     * answered with something we do not understand" and "we never got to
     * ask" both leave it at 0, and [firstPictureWatchdog] reads that 0 as
     * "the source is dead, take the decoder slot back". Keeping the verdict
     * separate is what lets an unproven answer retry the item instead of
     * cooling it down.
     */
    private var lastProbeVerdict = PROBE_NONE

    /** When the last 2xx probe landed, whatever its verdict. A verdict older
     *  than [PROBE_FRESHNESS_MS] stops protecting the source from the
     *  cooldown — an hour-old "unproven" is not evidence about today. */
    private var lastProbeAtMs = 0L

    /**
     * v100-③ — which item [lastProbeVerdict] is a verdict about.
     *
     * Without this the verdict outlives the item: a cast that starts in a
     * second never reaches `firstPictureWatchdog`, so it is never probed, and
     * the previous item's `INVALID` would still be sitting there. Reading that
     * as "this new source is proven junk" and skipping its auto-start is how a
     * fix for "a bad source starts too eagerly" turns into "nothing starts".
     */
    @Volatile
    private var lastProbeUri: String? = null

    /** v101-⑨ — guard so one item never has two probes in flight. */
    private val probeLock = Any()

    @Volatile
    private var probeInFlightUri: String? = null

    /**
     * v100-③ — has *this* uri been proven not to be media, recently?
     *
     * Only a fresh, same-item INVALID counts. Anything else (never probed,
     * a verdict about a different uri, an expired one, healthy or unproven)
     * answers false and the auto-start is free to run.
     */
    fun isSourceProvenInvalid(uri: String): Boolean =
        lastProbeVerdict == PROBE_INVALID &&
            lastProbeUri == uri &&
            System.currentTimeMillis() - lastProbeAtMs <= PROBE_FRESHNESS_MS

    private var sourceSlowRetries = 0

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
                                    // v99-④: an audio item never renders a first
                                    // frame, so READY is where its proof of life
                                    // happens. Without this a music cast stayed
                                    // "STOPPED" in the sender's eyes and was
                                    // re-pushed until it gave up.
                                    if (DlnaMediaMeta.audioOnly) {
                                        ManualDlnaHttp.markPlaying()
                                    }
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
                            // v99-④ — pixels on screen is the moment the sender has
                            // been waiting for. Saying PLAYING here (rather than
                            // only when the Play action arrived) is what lets a
                            // polling control point stop re-sending Set/Play, which
                            // is how the same URI used to be re-pushed every 10-30 s
                            // and re-entered every gate on the way.
                            ManualDlnaHttp.markPlaying()
                            // v86: retract an "audio only" verdict once real pixels
                            // land. READY saw videoSize 0x0 *because there was no
                            // surface yet*, not because the item is music, and
                            // nothing ever retracted it — so the decorative music
                            // card stayed stretched over a working picture: the
                            // "music UI on top, video underneath" report. The UI
                            // refreshes at 2 Hz, so one tick drops the card.
                            mainHandler.post {
                                val size = player?.videoSize
                                if (DlnaMediaMeta.audioOnly &&
                                    size != null && size.width > 0 && size.height > 0
                                ) {
                                    DlnaMediaMeta.audioOnly = false
                                    DebugLog.log(
                                        "DLNA",
                                        "首帧已渲染且视频轨有效 → 撤销仅音频标记（收起音乐卡片）"
                                    )
                                }
                            }
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
                            // The frames, not just the summary: a cause-only line is what
                            // made the HLS assertion look like a CDN problem.
                            DebugLog.throwable("DLNA", error)
                            // v101-⑩ — a manifest we cannot parse is not a flaky
                            // network, and it is not our bug either: the far end
                            // answered 200 with an anti-scraping page (field log
                            // 21:11-44, `Input does not start with the #EXTM3U
                            // header`, body `<pre> _oo0oo_`). Retrying will not
                            // help, and the old behaviour - log it and show a
                            // black rectangle - left the user assuming the app
                            // was broken. Say what actually happened, in words
                            // they can act on.
                            if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) {
                                DebugLog.log(
                                    "DLNA",
                                    "判定：源站返回的不是媒体清单（反爬/限流/票据失效）→ 提示用户换源，" +
                                        "不再把它当成可自恢复的网络抖动"
                                )
                                onSourceHint("该点播源返回非媒体内容（可能反爬/限流），请换源或稍后再试")
                            }
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
                                // v88: an IllegalArgumentException from inside
                                // media3's `SampleQueue.commitSample` also lands
                                // in this bucket (errorCode 1000+2, cause
                                // IllegalArgumentException), so the old "source
                                // glitch" wording sent every reader straight to
                                // the CDN. Say which of the two it actually is —
                                // the assertion is a player-internal sample
                                // ordering problem and cannot be fixed upstream.
                                val internal =
                                    error.cause is IllegalArgumentException ||
                                        error.cause?.cause is IllegalArgumentException
                                DebugLog.log(
                                    "DLNA",
                                    if (internal)
                                        "错误根因=播放器内部断言（Media3 SampleQueue.commitSample：样本偏移回退，非网络/源端）" +
                                            "，不向界面报错（此类异常被 catch 不住，靠下一次重建自愈）"
                                    else
                                        "源端瞬时抖动（可自恢复），不向界面报错"
                                )
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
                                holdPending(uri)
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
                                holdPending(uri)
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
        // v101-⑨ — one probe in flight per item.
        //
        // The threshold below dropped from 15 s to 5 s, so the same cast now
        // asks three times as often. Against an anti-scraping source that is
        // not free: the 21:11-21:12 field log already shows 37 probes all
        // coming back as the challenge page, and tripling the rate would be us
        // helping the far end throttle the box. Re-probing the same uri while
        // one is running cannot produce a different answer anyway.
        synchronized(probeLock) {
            if (probeInFlightUri == uri) {
                DebugLog.log("DLNA", "源探测：同一 URI 已有探测在飞 → 跳过本次（避免替源站加倍限流）")
                return
            }
            probeInFlightUri = uri
        }
        Thread {
            var conn: java.net.HttpURLConnection? = null
            val probeStartedAt = System.currentTimeMillis()
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
                // v98 C: the first-byte latency is what separates "the source
                // is slow" from "the player is not playing" in the log, without
                // having to reach a laptop for a curled copy of the stream.
                val probeMs = System.currentTimeMillis() - probeStartedAt
                DebugLog.log("DLNA", "源探测: HTTP $code, Content-Type=$type, 首字节 ${probeMs}ms")
                DebugLog.log("DLNA", "源前200字节: $head")
                // v97: remember that the far end is answering, so the
                // "READY but 0x0" watchdog can tell a slow source from a dead
                // decoder instead of cooling down on every live channel.
                if (code in 200..299) {
                    lastProbeAtMs = System.currentTimeMillis()
                    // v100-③: a verdict has to say which item it is about.
                    lastProbeUri = uri
                    when (classifyProbePayload(type, head)) {
                        PROBE_HEALTHY -> {
                            lastProbeOkMs = System.currentTimeMillis()
                            lastProbeCode = code
                            lastProbeVerdict = PROBE_HEALTHY
                        }
                        PROBE_UNPROVEN -> {
                            // v99-①: the far end answered, but with a type we do
                            // not know. That is not evidence of a dead source, so
                            // it must neither count as healthy nor reach the
                            // cooldown: the first frame gets to decide. v98 wrote
                            // lastProbeOkMs = 0L here, which firstPictureWatchdog
                            // reads as "source is dead" — one unrecognised
                            // Content-Type and the real source froze for a minute.
                            lastProbeVerdict = PROBE_UNPROVEN
                            DebugLog.log(
                                "DLNA",
                                "源探测: HTTP 200 但类型未确证（Content-Type=$type）→ 探测未确证，" +
                                    "不计入源健康，也不作为冷却依据"
                            )
                        }
                        else -> {
                            // The only answer allowed to cool the source down:
                            // text that is not a playlist really is the relay
                            // showing its index page (field log 13:07:58).
                            lastProbeVerdict = PROBE_INVALID
                            DebugLog.log(
                                "DLNA",
                                "源探测: HTTP 200 但确凿非媒体（Content-Type=$type，首字节非 #EXTM3U）" +
                                    "→ 判为确凿非媒体，允许冷却"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLog.log("DLNA", "源探测失败: ${e.javaClass.simpleName} ${e.message}")
            } finally {
                conn?.disconnect()
                synchronized(probeLock) {
                    if (probeInFlightUri == uri) probeInFlightUri = null
                }
            }
        }.apply { isDaemon = true; name = "dlna-source-probe" }.start()
    }

    /**
     * v99-① — what did the probe actually prove? Three answers, not two.
     *
     * v98 asked a yes/no question ("is this media?") and a "no" was treated as
     * a dead source, which is how an unknown Content-Type ended up freezing a
     * real source. The split that matters:
     *
     * - [PROBE_HEALTHY] a playlist, or a container type we recognise
     * - [PROBE_INVALID] **proven** not to be media: text/plain or text/html
     *   whose body does not start with `#EXTM3U`. The field log (13:07:58)
     *   caught exactly this — the app's own relay answering with an ASCII
     *   index page while that same cast took 21 s to its first frame.
     * - [PROBE_UNPROVEN] anything else. A CDN is free to answer `video/MP2T`,
     *   `binary/octet-stream`, or nothing at all; that is a gap in our
     *   knowledge, not a verdict on the source.
     */
    private fun classifyProbePayload(contentType: String, firstBytes: String): Int {
        val type = contentType.trim().lowercase()
        // A textual answer is only acceptable when it really is a playlist.
        if (type.contains("text/plain") || type.contains("text/html")) {
            return if (firstBytes.trimStart().startsWith("#EXTM3U")) {
                PROBE_HEALTHY
            } else {
                PROBE_INVALID
            }
        }
        // No Content-Type at all: plenty of stream endpoints omit it, and the
        // placeholder the probe substitutes for null must land here too.
        if (type.isBlank() || type.startsWith("(无")) return PROBE_HEALTHY
        if (type.contains("mpegurl") ||
            type.contains("x-mpegurl") ||
            type.contains("video/") ||
            type.contains("audio/") ||
            type.contains("application/octet-stream") ||
            type.contains("flv") ||
            type.contains("mp4") ||
            type.contains("mpeg") ||
            type.contains("matroska")
        ) {
            return PROBE_HEALTHY
        }
        return PROBE_UNPROVEN
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
        // v99-①: a verdict belongs to one item. Carrying it into the next
        // cast would let yesterday's "healthy" excuse today's silence.
        lastProbeVerdict = PROBE_NONE
        lastProbeAtMs = 0L
        lastProbeOkMs = 0L
        lastProbeUri = null
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
     * Declares the Activity on its way without cancelling the audio-only
     * fallback.
     *
     * The service pulls the app up from its own thread when a cast arrives
     * while the Activity sits in the background. That launch lands a good
     * second or two *after* the receiver already decided "no foreground,
     * wait and then start audio-only" — the cast we saw spend the whole
     * [FOREGROUND_WAIT_MS] on a launch that was in fact already running.
     * Confirming the foreground early stops that wait, but the fallback must
     * survive: if the Activity is refused (user in another app, low memory)
     * the timeout is the only thing that ever makes a sound.
     */
    fun setUiForegroundPending(foreground: Boolean) {
        mainHandler.post {
            uiForeground = foreground
            if (foreground) {
                DebugLog.log(
                    "DLNA",
                    "服务侧已确认前台（Activity 启动中）→ 不再等满 ${FOREGROUND_WAIT_MS / 1000}s，转等渲染面"
                )
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

        if (realSurfacePresent) {
            pendingStartUri = null
            surfaceReady = true
            DebugLog.log(
                "DLNA",
                "等待前台期间渲染面已就绪 → 直接在真实渲染面上起播（不再等满 ${FOREGROUND_WAIT_MS / 1000}s）"
            )
            if (started) runStart(uri)
            return@Runnable
        }

        // v88: ask for the picture once more BEFORE falling back to sound.
        // The cast-time request was the only one, and if a gate refused it
        // there (a suppressed dismissal, a refused launch) nothing asked
        // again for the whole 10 s — which is exactly the field report
        // "cast while the app is in the background: 10 s of nothing, then it
        // only sounds". The launch may now succeed and the layer appear on
        // its own; the audio-only start below stays as the guarantee that
        // something is audible.
        DebugLog.log("DLNA", "等待前台 ${FOREGROUND_WAIT_MS / 1000}s 未成功 → 再请求一次界面（拉起/显示播放层）")
        onSurfaceProbeNeeded()
        onPlaybackUiNeeded()

        pendingStartUri = null
        surfaceReady = true
        DebugLog.log("DLNA", "再请求界面后仍无渲染面 → 按仅音频起播: $uri")
        if (started) runStart(uri, audioOnly = true)
    }

    /**
     * Drops the foreground wait because the service just refused to raise the
     * UI — the user dismissed this cast.
     *
     * Without this the receiver sits out all [FOREGROUND_WAIT_MS] asking for a
     * picture nobody is going to get, and only then starts on sound. The
     * refusal lands *before* that wait has any chance to pay off, so the
     * fallback fires on the spot instead of ten seconds later.
     */
    fun cancelForegroundWaitAndStartAudio() {
        mainHandler.post {
            val uri = pendingStartUri ?: return@post
            mainHandler.removeCallbacks(foregroundTimeoutRunnable)
            mainHandler.removeCallbacks(surfaceTimeoutRunnable)
            pendingStartUri = null
            surfaceReady = true
            DebugLog.log(
                "DLNA",
                "界面请求被服务拒绝（用户已关闭该投屏）→ 立即仅音频起播（不再空等 ${FOREGROUND_WAIT_MS / 1000}s）"
            )
            if (started) runStart(uri, audioOnly = true)
        }
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
    // ─── v101: Back inside the playback layer means pause, not end ───────────

    /**
     * v101-② — the user pressed Back while the picture was on screen.
     *
     * Until v100 this was `stopPlaybackFromUi()`: the item was cleared, the
     * position gone, and the log cheerfully said "播放照常进行" while the
     * decoder was released — the promise and the behaviour disagreed, and the
     * user had to end the whole cast just to get back to the home screen.
     *
     * Now it pauses. The item, the position and the cast session all stay, the
     * far end is told `PAUSED_PLAYBACK` (so the phone shows a pause button
     * instead of thinking the cast ended), and nothing but the user's own
     * "resume" or "end" changes it. This is a stronger signal than the
     * 20 s dismissal: a dismissal means "do not steal the foreground", a pause
     * means "stop playing until I say so", so the two are kept apart.
     */
    @Volatile
    private var userPaused = false

    @Volatile
    private var userPausedUri: String? = null

    @Volatile
    private var userPausedPositionMs = 0L

    /**
     * v101-⑧ — set when the idle sweep already gave the hardware slot back,
     * so the resume has to rebuild instead of just un-pausing.
     */
    @Volatile
    private var pauseReleasedSlot = false

    /** True while the user has this exact item paused. */
    override fun isUserPaused(uri: String): Boolean = userPaused && uri == userPausedUri

    /**
     * v101-⑧ — the box has one hardware decoder and the pause is holding it.
     *
     * Holding it forever would starve every other app, so an idle pause gives
     * it back — but only the slot: the media, the position and the session stay,
     * so "resume" still lands on the frame the user left. UI says so before it
     * happens ([onPauseIdleWarning]); silence would look like a bug.
     */
    private val pauseIdleRunnable: Runnable = Runnable {
        if (!userPaused) return@Runnable
        val p = player ?: return@Runnable
        val uri = userPausedUri
        runCatching {
            p.clearVideoSurface()
            p.stop()
        }
        lastStopReason = "暂停闲置超时 → 释放硬解槽"
        pauseReleasedSlot = true
        // No removeCallbacks here: this runnable does not reschedule itself, and
        // referencing itself from inside its own initializer is what made the
        // compiler give up on the type ("must be initialized").
        DebugLog.log(
            "DLNA",
            "暂停闲置 ${PAUSE_IDLE_RELEASE_MS / 1000}s → 释放硬解槽（位置 ${userPausedPositionMs / 1000}s 已保留，" +
                "首页仍可继续）: ${uri?.take(64)}…"
        )
        onPauseIdleWarning()
    }

    /**
     * v101-② — pause on a user Back, keeping the cast resumable.
     *
     * Deliberately does **not** stop the player: a stop releases the hardware
     * slot and drops the decoder, which is exactly the cost the 60 s idle sweep
     * is there to pay later, on a budget, rather than on every Back.
     */
    fun pauseForUser() {
        mainHandler.post {
            val p = player ?: return@post
            val uri = currentUri ?: return@post
            if (userPaused) return@post
            userPaused = true
            userPausedUri = uri
            pauseReleasedSlot = false
            userPausedPositionMs = runCatching { p.currentPosition }.getOrDefault(0L)
            runCatching { p.pause() }
            // ParkedMedia is the existing hand-off for "the picture is gone but
            // the item is not"; resumePlaybackFromUi() already knows how to
            // rebuild from it with the position intact.
            parkedForUi = ParkedMedia(uri, userPausedPositionMs)
            ManualDlnaHttp.markPaused()
            mainHandler.removeCallbacks(pauseIdleRunnable)
            mainHandler.postDelayed(pauseIdleRunnable, PAUSE_IDLE_RELEASE_MS.toLong())
            DebugLog.log(
                "DLNA",
                "用户按返回 → 暂停并保留投屏会话（首页可继续，闲置 ${PAUSE_IDLE_RELEASE_MS / 1000}s 后释放解码槽）" +
                    ": ${uri.take(64)}…"
            )
        }
    }

    /**
     * v101-⑥⑦ — a Play for the paused item is the user asking for it back.
     *
     * Checked *before* the duplicate-Play guard, because that guard only fires
     * while playWhenReady is true and a paused player is false by definition.
     *
     * Whether a re-push means "resume" or "the sender is polling" is not
     * decidable from the protocol: both are Set+Play. The field data settles
     * it - during healthy playback the sender only asks for
     * GetTransportInfo/GetPositionInfo and never re-sends Set+Play (measured
     * 21:09-21:12), while the 25 re-pushes all happened while the source was
     * failing. So a Play arriving while we are paused is treated as intent.
     */
    private fun resumeIfPaused(uri: String): Boolean {
        if (!isUserPaused(uri)) return false
        val at = userPausedPositionMs
        userPaused = false
        userPausedUri = null
        userPausedPositionMs = 0
        mainHandler.removeCallbacks(pauseIdleRunnable)
        ManualDlnaHttp.markPlaying()
        if (pauseReleasedSlot) {
            // The idle sweep already stopped the player; rebuild through the
            // existing parked-media path so the position survives.
            pauseReleasedSlot = false
            parkedForUi = ParkedMedia(uri, at)
            DebugLog.log(
                "DLNA",
                "暂停中收到同 URI Play（解码槽已释放）→ 重建并从 ${at / 1000}s 继续: ${uri.take(64)}…"
            )
            resumePlaybackFromUi()
            return true
        }
        pendingSeekMs = at
        DebugLog.log(
            "DLNA",
            "暂停中收到同 URI Play → 从暂停位置 ${at / 1000}s 继续: ${uri.take(64)}…"
        )
        return false
    }

    /** True once the cast has been paused by the user and not resumed. */
    val isCastPausedByUser: Boolean get() = userPaused

    /**
     * v101-⑤ — the user tapped the card (or the pill) to come back.
     *
     * Two paths, because the idle sweep may have taken the slot in between:
     * while the player is merely paused the decoder and the media item are
     * still alive, so this is a `play()` plus a fresh surface — instant. After
     * the sweep it is a rebuild through the parked-media path, which costs a
     * second or two and still lands on the frame the user left.
     */
    fun resumeFromUserPause() {
        mainHandler.post {
            if (!userPaused) return@post
            val p = player ?: return@post
            val uri = userPausedUri ?: currentUri ?: return@post
            val at = userPausedPositionMs
            userPaused = false
            userPausedUri = null
            userPausedPositionMs = 0
            mainHandler.removeCallbacks(pauseIdleRunnable)
            ManualDlnaHttp.markPlaying()
            if (pauseReleasedSlot) {
                pauseReleasedSlot = false
                parkedForUi = ParkedMedia(uri, at)
                DebugLog.log("DLNA", "用户点「继续播放」→ 解码槽已释放，重建并从 ${at / 1000}s 继续")
                resumePlaybackFromUi()
                return@post
            }
            pendingSeekMs = at
            runCatching { p.play() }
            // The playback layer was hidden on Back, so its Surface is new;
            // ask the UI to hand it over again.
            onSurfaceProbeNeeded()
            onPlaybackUiNeeded()
            DebugLog.log("DLNA", "用户点「继续播放」→ 从 ${at / 1000}s 恢复播放（解码器未释放，秒回）")
        }
    }

    fun stopPlaybackFromUi() {
        mainHandler.post {
            // v100-② — the user just closed this item, so every auto-start
            // timer still in flight has to die with it.
            //
            // Field log 14:49:38-39: the user pressed Back, and one second
            // later a timer scheduled at 14:49:36 woke up and pulled the
            // channel back. The exit had already put `transportState` back to
            // STOPPED, which is what disarmed the timer's third guard — the
            // exit did the timer's work for it. Bumping the generation voids
            // every timer in flight in one move, instead of relying on each
            // one happening to notice the dismissal in time.
            ManualDlnaHttp.cancelPendingAutoStart()
            // v101: a real end also ends the pause, whatever it was holding.
            userPaused = false
            userPausedUri = null
            userPausedPositionMs = 0
            pauseReleasedSlot = false
            mainHandler.removeCallbacks(pauseIdleRunnable)
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
                holdPending(parked.uri)
                mainHandler.removeCallbacks(foregroundTimeoutRunnable)
                mainHandler.postDelayed(
                    foregroundTimeoutRunnable, FOREGROUND_WAIT_MS.toLong()
                )
            } else if (surfaceReady) {
                runStart(parked.uri)
            } else {
                holdPending(parked.uri)
                mainHandler.removeCallbacks(surfaceTimeoutRunnable)
                mainHandler.postDelayed(surfaceTimeoutRunnable, SURFACE_WAIT_MS.toLong())
            }
            onSurfaceProbeNeeded()
        }
    }

    // ─── DlnaPlayerControl (called from Cling state-machine threads) ─────

    @OptIn(UnstableApi::class)
    override fun startPlayback(uri: String) {
        startPlaybackInternal(uri, fromAutoStart = false)
    }

    /**
     * v100-① — the auto-start's own entry, and the reason it is a separate
     * function rather than a flag inside the SOAP layer.
     *
     * `scheduleAutoStart` fires three seconds after a SetAVTransportURI that
     * was never followed by a Play. That Play is **our guess**, not the
     * sender's instruction, and the field log of 14:49:36-39 showed what
     * happens when a guess is allowed to behave like the real thing: the user
     * pressed Back at 14:49:38, the exit put `transportState` back to STOPPED,
     * and the timer woke at 14:49:39 with all three of its guards satisfied and
     * pulled the channel back — five exits, five resurrections.
     *
     * So the guess is marked as a guess all the way down: it does not clear
     * the dismissal (that is reserved for a real Play), and it refuses to run
     * at all when the user has just closed this item or the source has already
     * been proven not to be media.
     */
    override fun startPlaybackAuto(uri: String) {
        startPlaybackInternal(uri, fromAutoStart = true)
    }

    @OptIn(UnstableApi::class)
    private fun startPlaybackInternal(uri: String, fromAutoStart: Boolean) {
        mainHandler.post {
            if (!started) return@post

            val p = player ?: return@post

            // v101-⑥⑦ — a Play for the item the user paused is a request to
            // come back. Checked before the duplicate guard below, which only
            // fires while playWhenReady is true and a paused player is false.
            // If the idle sweep already stopped the player this hands off to
            // the parked-media rebuild and the rest of this function is skipped.
            if (!fromAutoStart && resumeIfPaused(uri)) return@post

            // v99-④ — the same URI is already playing: do not build it again.
            //
            // Senders re-push SetAVTransportURI + Play every 10-30 s to find out
            // whether playback began, so this branch runs dozens of times for
            // one cast. Every one of them used to walk the whole gate chain and
            // reach runStart, and one MediaCodec leaked per visit — the failure
            // mode the decoder-slot work spent a week tracking down. Once the
            // item is READY with playWhenReady, the answer is known: say
            // PLAYING so the sender stops asking, and go no further.
            if (uri == currentUri && p.playWhenReady && p.playbackState == Player.STATE_READY) {
                DebugLog.log(
                    "DLNA",
                    "同一 URI 已在播放 → 忽略重复的 Play（幂等，不重建播放器）: ${uri.take(64)}…"
                )
                ManualDlnaHttp.markPlaying()
                report(ProtocolState.CONNECTED)
                return@post
            }

            if (fromAutoStart) {
                // v100-①③ — three seconds of silence from the sender is not a
                // decision to play, and it must not touch anything the user or
                // the probe established.
                if (isCastDismissed(uri)) {
                    DebugLog.log(
                        "DLNA",
                        "用户已关闭该投屏 → 跳过自动起播（不猜、不清禁、不弹层）: ${uri.take(64)}…"
                    )
                    ManualDlnaHttp.markStoppedByUser()
                    return@post
                }
                if (isSourceProvenInvalid(uri)) {
                    DebugLog.log(
                        "DLNA",
                        "该源已判确凿非媒体（探针结论仍有效）→ 跳过自动起播: ${uri.take(64)}…"
                    )
                    return@post
                }
            }

            // v98 F2 — a Play instruction is the sender plainly asking for
            // playback, so it outranks whatever we remembered about this uri.
            // Sender apps re-push the very same SetAVTransportURI + Play every
            // 10-30 s while they poll whether playback really started; the old
            // gate treated those re-pushes as a resurrection of a cast the
            // user had closed, and the channel stayed shut for the whole
            // DISMISS window (field log 13:04:31 -> 13:06:13 of a douyin cast
            // that only started once the sender changed uri).
            //
            // v100-①: an auto-start is not that — it is our own three-second
            // guess — so it does not get to clear the dismissal.
            if (!fromAutoStart) onCastPlayIntent(uri)

            // v98 F1 — a dismissal closes the *foreground* path only. The
            // pipeline keeps running and the audio keeps going, which is what
            // turns "you closed it" into a one-tap recovery instead of a dead
            // channel. Stopping playback as well (what this gate used to do)
            // is what produced the only-sounds-without-picture report.
            uiGateBlocked = isCastDismissed(uri)
            if (uiGateBlocked) {
                DebugLog.log(
                    "DLNA",
                    "该投屏已被用户关闭 → 本次不把播放层摆到前台（播放照常进行，随时可回看）: ${uri.take(64)}…"
                )
                mainHandler.removeCallbacks(foregroundTimeoutRunnable)
                mainHandler.removeCallbacks(surfaceTimeoutRunnable)
                report(ProtocolState.ADVERTISING)
            }

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
                // v99-②: deliberately a plain assignment, not holdPending().
                // This is the same item waiting out a cooldown, not a new
                // promise, and the sender polls here every 10-30 s — renewing
                // the 3 s entry window on every poll would keep the playback
                // layer on the home screen for the whole cooldown, which is
                // the v97 black-rectangle report all over again.
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
                holdPending(uri)
                mainHandler.removeCallbacks(foregroundTimeoutRunnable)
                if (!uiGateBlocked) {
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
                } else {
                    // The user closed this cast: wait for the foreground to come
                    // back on its own (the Activity's own resume path) instead of
                    // dragging the player over the home screen.
                    DebugLog.log(
                        "DLNA",
                        "该投屏已被用户关闭 → 等前台自然恢复，不主动拉起播放器"
                    )
                }
                report(ProtocolState.CONNECTED)
                return@post
            }

            // ── Wait for the render surface ───────────────────────────────────
            // The UI is on screen but the revealed PlayerView may not have a
            // real Surface yet. [SURFACE_WAIT_MS] keeps a hard cap.
            if (!surfaceReady) {
                holdPending(uri)
                mainHandler.removeCallbacks(surfaceTimeoutRunnable)
                if (!uiGateBlocked) {
                    mainHandler.postDelayed(
                        surfaceTimeoutRunnable, SURFACE_WAIT_MS.toLong()
                    )
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
                }
                report(ProtocolState.CONNECTED)
                return@post
            }

            // v98 A — a real render surface is up, so the only thing missing is
            // the intent to play. An earlier pause here (the old "only audio"
            // fallbacks below, or a surface the Activity lost while the user sat
            // in another app) used to leave playWhenReady=false permanently: the
            // data kept flowing but nothing rendered, which is the
            // black-screen-with-sound report. Surface present => play, plainly.
            if (!p.playWhenReady) {
                DebugLog.log(
                    "DLNA",
                    "渲染面已就绪 → 无条件恢复播放 playWhenReady=true"
                )
                p.play()
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

            // v97: two paths can reach [runStart] for the same item inside the
            // same second — the cooldown retry ([onDecoderCooldownElapsed]) and
            // the "foreground ready" callback. The second pass stopped and
            // cleared the item the first one had just built, which the log
            // reported as `播放器被清空 (IDLE) 原因=ExoPlayer 自行清空（未见我方调用）`:
            // the MediaCodec the user had just got was thrown away, and because
            // the whole connect handshake runs again, the picture came back a
            // whole rebuild later. Collapse the duplicates instead.
            val nowMs = System.currentTimeMillis()
            if (uri == lastRunStartUri && nowMs - lastRunStartMs < REPEAT_RUN_START_MS) {
                DebugLog.log(
                    "DLNA",
                    "同一 ${REPEAT_RUN_START_MS}ms 内已为该 uri 起播过 → 跳过重复 runStart（不拆掉刚建好的解码器）"
                )
                return@post
            }
            lastRunStartUri = uri
            lastRunStartMs = nowMs

            currentUri = uri
            retryCount = 0
            consecutiveDecoderFailures = 0
            // A start that got as far as loading an item is a start the source
            // served; the next stall on this item is measured from here.
            sourceSlowRetries = 0
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
            // v88: drop whatever the previous item left behind before loading
            // the new one. This is the path that trips media3's
            // `SampleQueue.commitSample` assertion — the one that used to show
            // up as "source error, self-recovers in 2 s" — because an HLS
            // sample queue still holding samples at high byte offsets meets an
            // item that writes from zero, and the first write after the
            // transition asserts. [clearPlayback] already stops and clears on
            // the UI-exit path, but a recovery retry reaches here straight
            // from the error handler, so clear unconditionally: for a starting
            // player this is a no-op, for a re-entry it is the difference
            // between a picture and another assertion two seconds later.
            p.stop()
            p.clearMediaItems()
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
        // ── v97: "is the picture late because the source is slow, or because
        // the decoder is dead?" — these constant belong here, next to the
        // one [firstPictureWatchdog] has to consult before it takes the
        // hardware decoder slot away.

        /** Two [runStart] calls for one uri closer than this are one start. */
        private const val REPEAT_RUN_START_MS = 1500L

        /** How long a "the source answered 2xx" verdict still applies. */
        private const val PROBE_FRESHNESS_MS = 60_000L

        /** Gap used when re-driving a start after a healthy source probe. */
        private const val SOURCE_SLOW_RETRY_MS = 3000L

        /** Slow-source retries before falling back to the decoder cooldown. */
        private const val SOURCE_SLOW_RETRY_LIMIT = 3

        // ── v99-①: the probe gets four verdicts instead of two. v98 answered
        // "healthy / definitely not media", and the second one fed
        // firstPictureWatchdog's cooldown directly (it zeroes lastProbeOkMs),
        // so a single unknown Content-Type froze the real source for
        // 8 -> 16 -> 32 -> 60 s. "Unproven" is now its own answer: not
        // healthy, not a reason to take the decoder away either.

        /** Never probed. Keeps the v97 behaviour: no answer, no mercy. */
        private const val PROBE_NONE = 0

        /** Proven media: a playlist, or a container type we know. */
        private const val PROBE_HEALTHY = 1

        /** Answered, but the Content-Type is one we do not recognise. */
        private const val PROBE_UNPROVEN = 2

        /** Proven not to be media: text that is not a playlist. */
        private const val PROBE_INVALID = 3

        /**
         * v99-② — how long a parked item still counts as "there is something
         * worth showing". SetURI to first real surface is normally well under a
         * second; 3 s is the slack both sides agreed on, short enough that a
         * cast which never starts stops holding the playback layer open.
         */
        private const val PENDING_CAST_ENTRY_MS = 3_000L

        /** How long [startPlayback] waits for the UI to be on screen. */
        private const val FOREGROUND_WAIT_MS = 10_000

        /** How long an off-main-thread [stop] waits for the main thread to run
         *  the teardown. Only the wait is bounded; the teardown itself is not. */
        private const val STOP_MAIN_THREAD_TIMEOUT_MS = 3_000

        /**
         * v101-⑧ — how long a user pause may hold the one hardware decoder
         * before it is handed back. The item, the position and the session all
         * survive; only the slot goes.
         */
        private const val PAUSE_IDLE_RELEASE_MS = 60_000L

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
        /**
         * v101-⑨ — 5 s, down from 15 s.
         *
         * The probe itself answers in ~43 ms, and it does not interrupt the
         * start (the field log shows the first frame still landing after it
         * fired). So the 15 s wait bought nothing except a slower answer for
         * the user staring at a black rectangle: on a throttled source, five
         * seconds is the difference between "this source is refusing us" and
         * "this app is broken". [firstPictureWatchdog] is a different question
         * (started, but no picture) and keeps its own 12 s.
         */
        private const val STALL_TIMEOUT_MS = 5_000

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
