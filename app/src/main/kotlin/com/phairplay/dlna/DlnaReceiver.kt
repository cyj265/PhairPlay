package com.phairplay.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.MediaItem
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
    private val onError: (String) -> Unit = {}
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

    private var upnpService: UpnpService? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var manualSsdp: ManualSsdp? = null

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
                    DebugLog.setSsdpStatus("未启动: 未获取到局域网IP")
                    DebugLog.log("SSDP", "未获取到局域网IP，Manual SSDP 未启动")
                    Logger.w("Manual SSDP skipped: no LAN IPv4 found")
                }
            } catch (t: Throwable) {
                Logger.i("Manual SSDP start failed: ${t.message}")
            }

            // HttpDataSource tuned for DLNA senders: browser-style UA (some
            // senders like 5KPlayer behave differently for non-browser clients),
            // cross-protocol redirects, and generous timeouts.
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 15; PhairPlay) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000)

            // Renderers with decoder fallback: some DLNA senders (5KPlayer)
            // produce H.264 High@5.0 streams whose format is *reported* as
            // supported by the hardware MediaCodec yet fails at decode time
            // (ERROR_CODE_DECODING_FAILED). Enabling fallback lets ExoPlayer
            // retry the same stream with the software decoder automatically.
            val renderersFactory = DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)

            val exoPlayer = ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(context).setDataSourceFactory(httpFactory)
                )
                .build()
                .also { p ->
                    p.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            val stateName = when (playbackState) {
                                Player.STATE_IDLE -> "IDLE"
                                Player.STATE_BUFFERING -> "BUFFERING"
                                Player.STATE_READY -> "READY"
                                Player.STATE_ENDED -> "ENDED"
                                else -> "UNKNOWN"
                            }
                            Logger.d("DLNA player state: $stateName uri=${currentUri}")
                            when (playbackState) {
                                Player.STATE_READY -> if (currentUri != null) {
                                    report(ProtocolState.CONNECTED)
                                }
                                Player.STATE_ENDED -> {
                                    // The stream finished naturally — return to idle.
                                    if (currentUri != null) {
                                        clearPlayback()
                                        report(ProtocolState.ADVERTISING)
                                    }
                                }
                                else -> {
                                    // STATE_IDLE / STATE_BUFFERING: transient, no UI change needed.
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            val msg = "DLNA播放失败: ${error.errorCodeName ?: error.errorCode} ${error.message}"
                            Logger.e("DLNA playback error: $msg", error)
                            DebugLog.log("DLNA", msg)
                            onError(msg)
                            // Transient failures (Surface recreation across a
                            // background/foreground switch, momentary network
                            // stalls) recover with a single retry.
                            val uri = currentUri
                            if (uri != null) {
                                mainHandler.postDelayed({
                                    if (started && currentUri == uri) {
                                        player?.let { p ->
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

    /** Unregisters the renderer, releases the multicast lock and the player. Main thread only. */
    fun stop() {
        if (!started) return
        started = false
        DlnaPlayerBridge.setControl(null)
        releaseResources()
        report(ProtocolState.DISABLED)
    }

    /** Releases everything owned by the receiver. Main thread only. */
    private fun releaseResources() {
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

        try {
            player?.let { p ->
                p.stop()
                p.clearMediaItems()
                p.release()
            }
        } catch (e: Exception) {
            Logger.w("ExoPlayer release warning: ${e.message}")
        }
        player = null
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
            currentUri = uri
            Logger.i("DLNA playback start: $uri")
            DebugLog.log("DLNA", "开始播放: $uri")
            player?.let { p ->
                p.setMediaItem(MediaItem.fromUri(uri))
                p.volume = (DlnaAudioRenderingControl.getVolumeValue() / 100f)
                    .coerceIn(0f, 1f)
                p.prepare()
                p.play()
            }
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
            player?.takeIf { started && !it.isReleased }?.seekTo(positionSeconds * 1000L)
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────

    @OptIn(UnstableApi::class)
    private fun clearPlayback() {
        currentUri = null
        player?.let { p ->
            p.stop()
            p.clearMediaItems()
        }
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
