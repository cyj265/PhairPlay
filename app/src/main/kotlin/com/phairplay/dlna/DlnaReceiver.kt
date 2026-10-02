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
     * Asks the UI to look again for a render surface. Fired when an item is
     * parked because a decoder init failed with no real surface behind it —
     * only the Activity can say when a Surface appears.
     */
    private val onSurfaceProbeNeeded: () -> Unit = {}
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

    /** Retry counter for playback errors, reset on new media and on READY. */
    @Volatile
    private var retryCount = 0

    /** Consecutive decoder-init failures for the current item (see the error handler). */
    @Volatile
    private var consecutiveDecoderFailures = 0

    /** Throttle for "this item's decoder is dead" notices (see [startPlayback]). */
    @Volatile
    private var lastHevcDeadNoticeMs = 0L

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

    /** Play received but not started yet because no render surface existed. */
    @Volatile
    private var pendingStartUri: String? = null

    /** True while the Activity really is in the foreground (see [setUiForeground]). */
    @Volatile
    private var uiForeground = false

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
     * Fires when an starts but never reaches READY — i.e. a black screen with
     * *no* error callbacks. Nothing else in the player reports that case, and
     * it is the one that looks like a broken app rather than a broken source.
     * Probes the URL directly and records what the far end answered, turning
     * "black screen" into either "source dead" or "still buffering".
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

    /** Prefers a HEVC decoder that actually initialises (see [HevcCodecSelector]). */
    private val hevcCodecSelector =
        HevcCodecSelector(context.applicationContext)

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

            val exoPlayer = ExoPlayer.Builder(context, renderersFactory)
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
                                Player.STATE_ENDED -> {
                                    mainHandler.removeCallbacks(stallWatchdog)
                                    // The stream finished naturally — return to idle.
                                    if (currentUri != null) {
                                        clearPlayback()
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
                        }

                        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                            DebugLog.log("DLNA", "视频轨: ${videoSize.width}x${videoSize.height}")
                        }

                        /** Proof that pixels actually reached the screen (vs. a black overlay). */
                        override fun onRenderedFirstFrame() {
                            mainHandler.removeCallbacks(stallWatchdog)
                            DebugLog.log("DLNA", "首帧已渲染（画面已上屏）")
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            mainHandler.removeCallbacks(stallWatchdog)
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
                                        hevcCodecSelector.noteDecoderFailure(failedCodec)
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
                            onError(msg)
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
                            if (surfaceLessFailure) {
                                DebugLog.log(
                                    "DLNA",
                                    "无渲染面导致的解码失败 → 重新等待播放层，不再盲目重试: ${uri?.take(72)}…"
                                )
                                player?.let { p ->
                                    runCatching { p.stop() }
                                    runCatching { p.clearMediaItems() }
                                }
                                currentUri = null
                                pendingStartUri = uri
                                surfaceReady = false
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
                            } else if (uri != null && retryCount < 2 && !decoderDead) {
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
            if (!surfaceReady) {
                surfaceReady = true
                surfaceGeneration++
                // Verdicts belong to the surface they were taken on.
                hevcCodecSelector.resetFailures()
                consecutiveDecoderFailures = 0
                mainHandler.removeCallbacks(surfaceTimeoutRunnable)
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
     */
    private val foregroundTimeoutRunnable = Runnable {
        val uri = pendingStartUri ?: return@Runnable
        pendingStartUri = null
        surfaceReady = true
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

    /** Pauses the DLNA player when the app goes to the background so the
     *  MediaCodec renderer never writes into a destroyed Surface. */
    fun pausePlaybackFromUi() {
        mainHandler.post {
            player?.takeIf { started && !it.isReleased }?.pause()
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
            clearPlayback()
            ManualDlnaHttp.notifyPlaybackEnded()
            report(ProtocolState.ADVERTISING)
        }
    }

    /** Resumes the DLNA player when the app returns to the foreground. */
    fun resumePlaybackFromUi() {
        mainHandler.post {
            player?.takeIf { started && !it.isReleased && currentUri != null }?.play()
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

            // ── Wait for the foreground ───────────────────────────────────────
            // The order a receiver must keep: the sender pushes media → the UI
            // comes up → the UI confirms "I am on screen" → only then is the
            // player prepared. Preparing earlier is what produced the
            // "Decoder init failed … 超时" loop on this box: ExoPlayer configured
            // the HEVC decoder while the Activity was still being started, and
            // every poll the sender made while that hung built another decoder.
            // Nothing is prepared before [setUiForeground] reports true.
            if (!uiForeground) {
                pendingStartUri = uri
                mainHandler.removeCallbacks(foregroundTimeoutRunnable)
                mainHandler.postDelayed(
                    foregroundTimeoutRunnable, FOREGROUND_WAIT_MS.toLong()
                )
                DebugLog.log(
                    "DLNA",
                    "界面不在前台 → 先拉起播放器，确认前台后再起播（${FOREGROUND_WAIT_MS / 1000}s 兜底仅音频）"
                )
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
            clearPlayback()
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

    @OptIn(UnstableApi::class)
    private fun clearPlayback() {
        currentUri = null
        pendingSeekMs = -1L
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
