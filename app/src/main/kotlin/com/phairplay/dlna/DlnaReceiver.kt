package com.phairplay.dlna

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.phairplay.dlna.renderer.DlnaAudioRenderingControl
import com.phairplay.dlna.renderer.DlnaNoMediaPresent
import com.phairplay.dlna.renderer.DlnaPlayerBridge
import com.phairplay.dlna.renderer.DlnaPlayerControl
import com.phairplay.dlna.renderer.DlnaRendererStateMachine
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import org.jupnp.UpnpService
import org.jupnp.android.AndroidUpnpService
import org.jupnp.android.AndroidUpnpServiceImpl
import org.jupnp.binding.annotations.AnnotationLocalServiceBinder
import org.jupnp.model.meta.DeviceDetails
import org.jupnp.model.meta.DeviceIdentity
import org.jupnp.model.meta.LocalDevice
import org.jupnp.model.meta.LocalService
import org.jupnp.model.types.UDADeviceType
import org.jupnp.model.types.UDN
import org.jupnp.support.avtransport.impl.AVTransportService
import org.jupnp.support.avtransport.lastchange.AVTransportLastChangeParser
import org.jupnp.support.connectionmanager.ConnectionManagerService
import org.jupnp.support.lastchange.LastChangeAwareServiceManager
import org.jupnp.support.model.AVTransport
import org.jupnp.support.renderingcontrol.lastchange.RenderingControlLastChangeParser
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
    private val onStateChanged: (ProtocolState) -> Unit
) : DlnaPlayerControl {

    private val mainHandler = Handler(Looper.getMainLooper())

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
    private var upnpBound = false
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * Connection to the jUPnP Android bound service. The actual [UpnpService]
     * is only available after [ServiceConnection.onServiceConnected]; that is
     * where the renderer device is registered and advertising starts.
     */
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            try {
                val upnp = (binder as AndroidUpnpService).get()
                upnpService = upnp
                upnp.registry.addDevice(createRendererDevice())
                Logger.i("DLNA renderer advertising as: $displayName")
                report(ProtocolState.ADVERTISING)
            } catch (e: Exception) {
                Logger.e("DLNA device registration failed", e)
                report(ProtocolState.ERROR)
                stop()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Logger.w("DLNA UpnpService disconnected")
            upnpService = null
        }
    }

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

            val exoPlayer = ExoPlayer.Builder(context).build().also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
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
                })
            }
            player = exoPlayer

            // The jUPnP UpnpService runs as an Android bound service; the renderer
            // device is registered in onServiceConnected above.
            val bound = context.bindService(
                Intent(context, AndroidUpnpServiceImpl::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            if (!bound) {
                Logger.e("DLNA: failed to bind AndroidUpnpServiceImpl")
                report(ProtocolState.ERROR)
                stop()
                return
            }
            upnpBound = true
        } catch (e: Exception) {
            Logger.e("DLNA startup failed", e)
            report(ProtocolState.ERROR)
            stop()
        }
    }

    /** Unregisters the renderer, releases the multicast lock and the player. Main thread only. */
    fun stop() {
        if (!started) return
        started = false
        DlnaPlayerBridge.setControl(null)

        if (upnpBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (e: Exception) {
                Logger.w("DLNA unbind warning: ${e.message}")
            }
            upnpBound = false
        }
        upnpService = null

        try {
            multicastLock?.release()
        } catch (e: Exception) {
            Logger.w("MulticastLock release warning: ${e.message}")
        }
        multicastLock = null

        player?.let { p ->
            p.stop()
            p.clearMediaItems()
            p.release()
        }
        player = null
        currentUri = null
        report(ProtocolState.DISABLED)
    }

    /** Binds the player output to a SurfaceView (call when the UI shows DLNA playback). */
    @OptIn(UnstableApi::class)
    fun attachSurface(surfaceView: SurfaceView) {
        mainHandler.post {
            player?.takeIf { !it.isReleased }?.setVideoSurfaceView(surfaceView)
        }
    }

    /** Detaches the player output from any SurfaceView (call when the UI hides playback). */
    @OptIn(UnstableApi::class)
    fun detachSurface() {
        mainHandler.post {
            player?.takeIf { !it.isReleased }?.clearVideoSurface()
        }
    }

    // ─── DlnaPlayerControl (called from Cling state-machine threads) ─────

    @OptIn(UnstableApi::class)
    override fun startPlayback(uri: String) {
        mainHandler.post {
            if (!started) return@post
            currentUri = uri
            Logger.i("DLNA playback start: $uri")
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
            DeviceIdentity(UDN("uuid-" + UUID.randomUUID())),
            UDADeviceType("MediaRenderer"),
            DeviceDetails(displayName.ifBlank { "PhairPlay" }),
            arrayOf<LocalService<*>>(avService, renderService, connService)
        )
    }
}
