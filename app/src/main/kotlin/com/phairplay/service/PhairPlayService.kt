package com.phairplay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import android.view.SurfaceView
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.ExoPlayer
import com.phairplay.MainActivity
import com.phairplay.R
import com.phairplay.airplay.AirPlayReceiver
import com.phairplay.cast.CastReceiver
import com.phairplay.dlna.DlnaReceiver
import com.phairplay.miracast.MiracastReceiver
import com.phairplay.settings.AppSettings
import com.phairplay.settings.SettingsRepository
import com.phairplay.util.Logger
import com.phairplay.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * PhairPlayService — Android ForegroundService that hosts all receiver protocols.
 *
 * WHY: The AirPlay/Miracast/Cast receivers need to run continuously in the background.
 * Android may kill background processes. A ForegroundService with a persistent
 * notification keeps the app alive and shows the user that PhairPlay is active.
 *
 * HOW: Bind to this service from [MainActivity] to receive state updates.
 * Use [ServiceController] to send start/stop/restart commands.
 *
 * Service lifecycle:
 *   startForegroundService() → onCreate() → onStartCommand() → [running in background]
 *   stopSelf() / stopService() → onDestroy() → all receivers stopped
 *
 * Commands via Intent actions (sent by [ServiceController]):
 *   ACTION_START   — starts all enabled receivers
 *   ACTION_STOP    — stops all receivers and stops the service
 *   ACTION_RESTART — stops then starts all receivers (service keeps running)
 */
class PhairPlayService : Service() {

    // Binder for Activity binding (returns this service directly)
    private val binder = LocalBinder()

    // Coroutine scope — cancelled in onDestroy() to clean up all coroutines
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Observable state — Activities and Fragments observe this via the binder
    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _airPlayState = MutableStateFlow(ProtocolState.DISABLED)
    val airPlayState: StateFlow<ProtocolState> = _airPlayState.asStateFlow()

    private val _miracastState = MutableStateFlow(ProtocolState.DISABLED)
    val miracastState: StateFlow<ProtocolState> = _miracastState.asStateFlow()

    private val _castState = MutableStateFlow(ProtocolState.DISABLED)
    val castState: StateFlow<ProtocolState> = _castState.asStateFlow()

    private val _dlnaState = MutableStateFlow(ProtocolState.DISABLED)
    val dlnaState: StateFlow<ProtocolState> = _dlnaState.asStateFlow()

    /** Last DLNA startup error (null when healthy); surfaced on the DLNA card. */
    private val _dlnaError = MutableStateFlow<String?>(null)
    val dlnaError: StateFlow<String?> = _dlnaError.asStateFlow()

    private val _activeConnection = MutableStateFlow<ActiveConnection?>(null)
    val activeConnection: StateFlow<ActiveConnection?> = _activeConnection.asStateFlow()

    private val _photoFrame = MutableStateFlow<PhotoFrame?>(null)
    val photoFrame: StateFlow<PhotoFrame?> = _photoFrame.asStateFlow()

    // Non-null while AirPlay audio is playing WITHOUT video — drives the now-playing overlay.
    private val _nowPlaying = MutableStateFlow<com.phairplay.airplay.NowPlayingInfo?>(null)
    val nowPlaying: StateFlow<com.phairplay.airplay.NowPlayingInfo?> = _nowPlaying.asStateFlow()

    // Non-null while a PIN should be shown on screen for SRP pair-setup (PIN access control).
    private val _pairingPin = MutableStateFlow<String?>(null)
    val pairingPin: StateFlow<String?> = _pairingPin.asStateFlow()

    // Surface provider — supplied by MainActivity after binding (Sprint 5).
    // The lambda captures this field so it always uses the latest provider even if
    // setVideoSurfaceProvider() is called after startAirPlay().
    @Volatile private var videoSurfaceProvider: (() -> Surface?)? = null

    // Receiver instances — null when not running
    private var airPlayReceiver: AirPlayReceiver? = null
    private var miracastReceiver: MiracastReceiver? = null
    private var castReceiver: CastReceiver? = null
    private var dlnaReceiver: DlnaReceiver? = null

    // DLNA needs the main thread (ExoPlayer + Cling creation); service starts receivers
    // from an IO coroutine, so dispatch DLNA lifecycle through the main handler.
    private val mainHandler = Handler(Looper.getMainLooper())

    // Settings — read once when starting, re-read on restart
    private lateinit var settingsRepository: SettingsRepository

    // ─── Service Lifecycle ───────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Logger.i("PhairPlayService created")
        settingsRepository = SettingsRepository(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground immediately with a persistent notification.
        // On Android 14+ the manifest declares foregroundServiceType="connectedDevice",
        // which the system enforces: without NEARBY_WIFI_DEVICES / ACCESS_FINE_LOCATION
        // a plain startForeground() throws SecurityException and the whole receiver
        // (mDNS + SSDP) never comes up. Retry without the type so the box still gets
        // visible on the LAN instead of failing silently.
        startForegroundSafely(isRunning = false)

        when (intent?.action) {
            ACTION_START   -> serviceScope.launch { startReceivers() }
            ACTION_STOP    -> serviceScope.launch { stopReceivers(); stopSelf() }
            ACTION_RESTART -> serviceScope.launch { restartReceivers() }
            else           -> serviceScope.launch { startReceivers() } // default: start
        }

        // START_STICKY: if the system kills the service, restart it with a null intent
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * The app was swiped away from recents ( Android TV launcher "close app" gesture ).
     *
     * Legacy behaviour stopped every receiver here, which meant a phone on the same
     * Wi-Fi could no longer find the box — the reported "cast device not found" symptom,
     * reproducible by simply installing the APK and closing the app. A receiver app is
     * supposed to keep advertising; so we now only park the playback surfaces and let the
     * foreground service (START_STICKY / startOnBoot) keep the receivers alive.
     * The explicit Stop button and ACTION_STOP remain the only ways to go silent.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.i("App task removed — parking playback, keeping receivers advertised")
        pauseDlnaPlayback()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Called by [MainActivity] after it binds, to supply the [Surface] for video rendering.
     *
     * The lambda is invoked lazily — only when a stream is actually being started — so it
     * is safe to call this before or after [startAirPlay]. The lambda should return null
     * if the Activity's StreamingScreen is not yet available (e.g., surface not yet created).
     *
     * Call with `{ null }` (or simply don't call) during Activity destruction so we stop
     * holding a reference to the Activity's Surface after the window is gone.
     *
     * @param provider Lambda that returns the current [Surface], or null if unavailable.
     */
    fun setVideoSurfaceProvider(provider: () -> Surface?) {
        videoSurfaceProvider = provider
    }

    /**
     * Sends a DACP transport command (TV remote → AirPlay sender), e.g. play/pause or skip what the
     * Mac/iPhone is streaming. Bound Activities call this from media-key events. No-op if no AirPlay
     * sender has advertised a DACP identity.
     */
    fun sendAirPlayRemoteCommand(command: String) {
        airPlayReceiver?.sendRemoteCommand(command)
    }

    override fun onDestroy() {
        Logger.i("PhairPlayService destroying")
        stopAllReceiversInternal()
        serviceJob.cancel()
        super.onDestroy()
    }

    // ─── Service Control ─────────────────────────────────────────────────────

    /**
     * Starts all receivers that are enabled in Settings.
     *
     * Reads current settings, then starts AirPlay, Miracast, and/or Cast
     * receivers according to the enabled flags.
     */
    private suspend fun startReceivers() {
        val settings = settingsRepository.settingsFlow.first()
        Logger.i("Starting receivers: AirPlay=${settings.airPlayEnabled}, Miracast=${settings.miracastEnabled}, Cast=${settings.castEnabled}, DLNA=${settings.dlnaEnabled}")

        // Watch the LAN address first: every receiver captures the IP at start
        // time, so a subnet change must rebuild them on the new address.
        ensureLocalIpWatcher()

        _serviceState.value = ServiceState.Running
        updateNotification(isRunning = true)

        if (settings.airPlayEnabled)   startAirPlay(settings)
        if (settings.miracastEnabled)  startMiracast()
        if (settings.castEnabled)      startCast()
        if (settings.dlnaEnabled)      startDlna(settings)
    }

    /**
     * Stops all active receivers and updates the service state to Stopped.
     * Does NOT call stopSelf() — use [ACTION_STOP] for that.
     */
    private fun stopReceivers() {
        Logger.i("Stopping all receivers")
        stopAllReceiversInternal()
        _serviceState.value = ServiceState.Stopped
        _activeConnection.value = null
        updateNotification(isRunning = false)
    }

    /**
     * Restarts all receivers: stops them, waits briefly, then starts them again.
     * Used for applying settings changes or recovering from errors.
     */
    private suspend fun restartReceivers() {
        Logger.i("Restarting all receivers")
        _serviceState.value = ServiceState.Restarting
        updateNotification(isRunning = false)
        stopAllReceiversInternal()
        kotlinx.coroutines.delay(500) // brief pause to ensure ports are released
        startReceivers()
    }

    // ─── Local-IP self healing ───────────────────────────────────────────────

    /** Last LAN IPv4 the receivers were (re)started with. */
    private var watchedIp: String? = null
    private var ipWatcherRegistered = false
    private val ipRestartGuard = Any()
    private var ipRestarting = false

    /**
     * Watches the box's LAN IPv4 and rebuilds the receivers when it changes.
     *
     * A TV box — notably a Phicomm N1 after a router change, a Wi-Fi reconnect
     * or a DHCP renewal — can change its address while the app keeps running.
     * Every receiver snapshots the IP at start time (SSDP LOCATION, mDNS
     * records, the DLNA/RTSP control URLs), so a stale IP leaves the phone
     * either not seeing the device at all or seeing a dead entry that times
     * out on connect. Rebuilding on change makes the receiver follow the box
     * instead of stranding the sender on the previous address.
     */
    private fun ensureLocalIpWatcher() {
        if (ipWatcherRegistered) return
        ipWatcherRegistered = true
        try {
            watchedIp = NetworkUtils.getLocalIpv4()
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            cm.registerNetworkCallback(request, ipWatcher)
            Logger.i("本地IP变化监听已注册（当前 $watchedIp）：IP 变更后自动重建接收链路")
        } catch (e: Exception) {
            Logger.w("本地IP变化监听注册失败: ${e.message}")
            ipWatcherRegistered = false
        }
    }

    private val ipWatcher = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val addresses = linkProperties.linkAddresses
            val ip = addresses?.firstOrNull { ia ->
                val a = ia.address
                a is java.net.Inet4Address
                        && !a.isLoopbackAddress
                        && !a.isLinkLocalAddress
            }?.address?.hostAddress
            if (ip.isNullOrBlank()) return
            synchronized(ipRestartGuard) {
                if (watchedIp == ip) return
                watchedIp = ip
                if (ipRestarting) return
                ipRestarting = true
            }
            Logger.i("本地IPv4 变化 -> $ip，重建接收链路（旧的发现记录指向已失效地址）")
            serviceScope.launch {
                kotlinx.coroutines.delay(300)
                synchronized(ipRestartGuard) { ipRestarting = false }
                restartReceivers()
            }
        }
    }

    // ─── Individual Protocol Starters ────────────────────────────────────────

    /**
     * Creates and starts the [AirPlayReceiver].
     *
     * The display name comes from settings — blank means use the Android device name,
     * which [MdnsService] resolves at runtime.
     *
     * Surface is not available here (it lives in the Activity/Fragment).
     * The surface provider is wired up from [MainActivity] in Sprint 5.
     * Until then, video frames are silently discarded and only audio plays.
     *
     * @param settings Current app settings; read once per start/restart cycle.
     */
    private fun startAirPlay(settings: AppSettings) {
        // Mirror the debug-overlay setting into the shared stats bus that StreamingScreen reads.
        com.phairplay.airplay.StreamStats.overlayEnabled = settings.showDebugOverlay

        // Idempotent: a redundant ACTION_START (e.g. the activity being recreated while the
        // foreground service is still alive) must NOT spin up a second AirPlayReceiver competing
        // for port 7000. The existing receiver keeps running and picks up the new Surface via the
        // surfaceProvider. A genuine restart goes through ACTION_RESTART (stop → delay → start).
        if (airPlayReceiver != null) {
            Logger.i("AirPlay receiver already running — skipping duplicate start")
            return
        }
        // Captures the sender name reported by AirPlayReceiver before CONNECTED fires.
        // onSenderNameChanged is called synchronously before emitState(CONNECTED), so
        // this assignment happens-before the Main-thread read in onStateChanged.
        var pendingSenderName = "AirPlay Sender"

        airPlayReceiver = AirPlayReceiver(
            context = applicationContext,
            displayName = settings.effectiveDisplayName,
            mirrorWidth = settings.mirrorWidth,
            mirrorHeight = settings.mirrorHeight,
            audioEnabled = settings.mirrorAudioEnabled,
            pinAuthEnabled = settings.airPlayPinAuthEnabled,
            // Delegate to the current provider at call time — captures the field, not a fixed value.
            // When MainActivity calls setVideoSurfaceProvider(), future surface requests use it.
            videoSurfaceProvider = { videoSurfaceProvider?.invoke() },
            onSenderNameChanged = { name ->
                pendingSenderName = name.ifEmpty { "AirPlay Sender" }
            },
            onPhotoReceived = { bytes, imageType ->
                _photoFrame.value = PhotoFrame(
                    bytes = bytes.copyOf(),
                    mimeType = imageType.mimeType
                )
                updateNotification(isRunning = true)
            },
            onPhotoCleared = {
                _photoFrame.value = null
            },
            onNowPlayingChanged = { info ->
                _nowPlaying.value = info
            },
            onPinChanged = { pin ->
                _pairingPin.value = pin
            },
            onStateChanged = { state ->
                _airPlayState.value = state
                when (state) {
                    ProtocolState.CONNECTED   -> {
                        _photoFrame.value = null
                        _activeConnection.value =
                            ActiveConnection(pendingSenderName, Protocol.AIRPLAY)
                        updateNotification(isRunning = true, streamingSenderName = pendingSenderName)
                        // Mirror/AirPlay video needs the same UI rescue as DLNA:
                        // a background Activity means no Surface and no picture.
                        bringActivityToForeground("AirPlay 投屏")
                    }
                    ProtocolState.ADVERTISING,
                    ProtocolState.DISABLED,
                    ProtocolState.ERROR       -> {
                        _activeConnection.value = null
                        updateNotification(isRunning = state != ProtocolState.DISABLED &&
                                                       state != ProtocolState.ERROR)
                    }
                }
            }
        ).also { it.start() }
        Logger.d("AirPlay receiver started (displayName='${settings.effectiveDisplayName}')")
    }

    private fun startMiracast() {
        _miracastState.value = ProtocolState.ADVERTISING
        miracastReceiver = MiracastReceiver(
            context = applicationContext,
            onStateChanged = { state -> _miracastState.value = state }
        ).also { it.start() }
        Logger.d("Miracast receiver started")
    }

    private fun startCast() {
        _castState.value = ProtocolState.ADVERTISING
        castReceiver = CastReceiver(
            context = applicationContext,
            onStateChanged = { state -> _castState.value = state }
        ).also { it.start() }
        Logger.d("Cast receiver started")
    }

    /**
     * Creates and starts the [DlnaReceiver] on the main thread.
     *
     * ExoPlayer and the Cling UpnpService must be created on the main thread, so
     * unlike the other receivers this one is dispatched via [mainHandler].
     * Idempotent like the others: a redundant start while already running is skipped.
     */
    private fun startDlna(settings: AppSettings) {
        mainHandler.post {
            if (dlnaReceiver != null) {
                Logger.i("DLNA receiver already running — skipping duplicate start")
                return@post
            }
            dlnaReceiver = DlnaReceiver(
                context = applicationContext,
                displayName = settings.effectiveDisplayName,
                onError = { message ->
                    _dlnaError.value = message
                    Logger.e("DLNA error surfaced to UI: $message")
                },
                onStateChanged = { state ->
                    _dlnaState.value = state
                    when (state) {
                        ProtocolState.CONNECTED -> {
                            _dlnaError.value = null
                            _activeConnection.value =
                                ActiveConnection("DLNA Sender", Protocol.DLNA)
                            // AirPlay names its sender in the notification while
                            // streaming; the DLNA equivalent of that is the media
                            // title parsed from DIDL-Lite (set before Play fires).
                            val title = com.phairplay.dlna.DlnaMediaMeta.title
                            updateNotification(
                                isRunning = true,
                                streamingSenderName = if (title.isNullOrBlank()) "DLNA"
                                                      else "DLNA · $title"
                            )
                            // The sender just pushed media. If the UI is not on
                            // screen a Surface is missing and ExoPlayer keeps
                            // decoding audio only — pull the app to the front.
                            bringActivityToForeground("DLNA 播放")
                        }
                        ProtocolState.ADVERTISING,
                        ProtocolState.DISABLED,
                        ProtocolState.ERROR       -> {
                            _activeConnection.value = null
                            updateNotification(isRunning = state != ProtocolState.DISABLED &&
                                                           state != ProtocolState.ERROR)
                        }
                    }
                }
            ).also { it.start() }
            Logger.d("DLNA receiver started (displayName='${settings.effectiveDisplayName}')")
        }
    }

    /** Exposes the DLNA player so the UI can attach a SurfaceView for video rendering. */
    val dlnaPlayer: ExoPlayer?
        get() = dlnaReceiver?.playerOrNull

    /** Attaches the DLNA player output to the given SurfaceView (main thread). */
    fun attachDlnaSurface(surfaceView: SurfaceView) {
        dlnaReceiver?.attachSurface(surfaceView)
    }

    /** Detaches the DLNA player output (main thread). */
    fun detachDlnaSurface() {
        dlnaReceiver?.detachSurface()
    }

    /**
     * Called by the UI once the DLNA playback view has a surface with a real
     * size. Preparing the player before that makes the Amlogic HEVC decoder
     * block in configure() forever (see DlnaReceiver.surfaceReady).
     */
    fun markDlnaSurfaceReady() {
        dlnaReceiver?.markSurfaceReady()
    }

    /** Called by the UI when the DLNA playback view goes away. */
    fun markDlnaSurfaceGone() {
        dlnaReceiver?.markSurfaceGone()
    }

    /** Pauses the DLNA player when the app goes to the background. */
    fun pauseDlnaPlayback() {
        dlnaReceiver?.pausePlaybackFromUi()
    }

    /** Resumes the DLNA player when the app returns to the foreground. */
    fun resumeDlnaPlayback() {
        dlnaReceiver?.resumePlaybackFromUi()
    }

    /**
     * Ends DLNA playback because the user closed the UI. Discovery stays up:
     * only the media stops, and the sender is told STOPPED.
     */
    fun stopDlnaPlayback() {
        dlnaReceiver?.stopPlaybackFromUi()
    }

    // ─── Auto foreground (receiver → UI) ────────────────────────────────────

    /**
     * True while [MainActivity] is in the resumed state.
     *
     * The receivers report state changes from their own threads; this flag is
     * the only reliable answer to "is somebody watching?". When the Activity
     * was never opened, only this service is alive and the renderer runs
     * without a Surface — which is exactly what the "cast works but only
     * audio plays in the background" report looked like.
     */
    @Volatile private var activityResumed = false

    /** Guards against a sender hammering Play (polling every 10–30 s). */
    @Volatile private var lastForegroundLaunchMs = 0L

    /** Called by MainActivity so the service knows whether someone is watching. */
    fun onActivityResumed() { activityResumed = true }

    /** Called by MainActivity when it loses visibility (pause/destroy). */
    fun onActivityPaused() { activityResumed = false }

    /**
     * Brings the app UI to the front when a cast starts while it is in the
     * background — the receiver-side half of what every other receiver app
     * does: the picture has to appear when the sender pushes media, not when
     * the user happens to open the launcher.
     *
     * The Activity is started from this foreground service, which is exempt
     * from the background activity-start restrictions (Android 10+); on API 25
     * (N1) those restrictions do not exist at all.
     *
     * Skipped when the UI is already visible: the Activity's collectors then
     * show the player straight from the state flows. A short throttle keeps a
     * polling sender (it re-sends Play every 10–30 s) from re-launching the
     * Activity over and over when the first launch failed.
     *
     * @param reason Human-readable trigger, kept for diagnosis.
     */
    fun bringActivityToForeground(reason: String) {
        if (activityResumed) {
            Logger.d("Auto-foreground skipped — UI already visible ($reason)")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastForegroundLaunchMs < AUTOFOREGROUND_THROTTLE_MS) {
            Logger.d("Auto-foreground throttled ($reason)")
            return
        }
        lastForegroundLaunchMs = now
        runCatching {
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra(EXTRA_AUTO_FOREGROUND_REASON, reason)
            }
            startActivity(intent)
            Logger.i("Auto-foreground: opened MainActivity ($reason)")
        }.onFailure {
            // The notification's content intent opens the same Activity, so the
            // user is never left with a silent picture and no way back.
            Logger.w("Auto-foreground launch failed — notification can still open the app")
        }
    }

    private fun stopAllReceiversInternal() {
        try { airPlayReceiver?.stop() } catch (e: Exception) { Logger.e("AirPlay stop error", e) }
        try { miracastReceiver?.stop() } catch (e: Exception) { Logger.e("Miracast stop error", e) }
        try { castReceiver?.stop() } catch (e: Exception) { Logger.e("Cast stop error", e) }
        try { dlnaReceiver?.stop() } catch (e: Exception) { Logger.e("DLNA stop error", e) }
        airPlayReceiver = null
        miracastReceiver = null
        castReceiver = null
        dlnaReceiver = null
        _airPlayState.value = ProtocolState.DISABLED
        _miracastState.value = ProtocolState.DISABLED
        _castState.value = ProtocolState.DISABLED
        _dlnaState.value = ProtocolState.DISABLED
        _dlnaError.value = null
        _photoFrame.value = null
        _nowPlaying.value = null
        _pairingPin.value = null
    }

    // ─── Notification ────────────────────────────────────────────────────────

    /**
     * Promotes the service to the foreground, degrading gracefully on Android 14+.
     *
     * The manifest declares `foregroundServiceType="connectedDevice"`. From Android 14
     * (API 34) the system enforces that type: if the app does not hold
     * NEARBY_WIFI_DEVICES (or ACCESS_FINE_LOCATION) the call to [Service.startForeground]
     * throws SecurityException. An unhandled failure there meant the AirPlay/DLNA
     * receivers never started — the box was simply absent from every phone's cast menu.
     * Retrying without the type keeps the receiver alive even when the permission is denied.
     */
    private fun startForegroundSafely(isRunning: Boolean) {
        val notification = buildNotification(isRunning)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val withType = runCatching {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            }
            if (withType.isSuccess) return
            Logger.w("connectedDevice foreground type rejected — retrying without type: ${withType.exceptionOrNull()?.message}")
            runCatching { startForeground(NOTIFICATION_ID, notification) }
                .onFailure { e -> Logger.e("Unable to promote PhairPlayService to foreground", e) }
            return
        }
        runCatching { startForeground(NOTIFICATION_ID, notification) }
            .onFailure { e -> Logger.e("Unable to promote PhairPlayService to foreground", e) }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW  // LOW: no sound, minimal visual interruption
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds the persistent notification for the ForegroundService.
     *
     * The notification shows the service status and provides quick actions
     * so users can Stop or Restart without opening the app.
     *
     * @param isRunning            True if receivers are active; false if stopped/restarting.
     * @param notificationContentText Override for the notification body text.
     *   When null, the default running/stopped status string is used.
     *   Pass the sender name here (e.g. "Streaming from MacBook Pro") when connected.
     */
    private fun buildNotification(
        isRunning: Boolean,
        notificationContentText: String? = null
    ): Notification {
        // Tapping the notification opens the app
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Stop" action — sends ACTION_STOP to this service
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PhairPlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Restart" action — sends ACTION_RESTART to this service
        val restartIntent = PendingIntent.getService(
            this, 2,
            Intent(this, PhairPlayService::class.java).apply { action = ACTION_RESTART },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isRunning) R.string.notification_status_running
                         else           R.string.notification_status_stopped

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notificationContentText ?: getString(statusText))
            .setContentIntent(openAppIntent)
            .setOngoing(true)                   // Prevents user from swiping away
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_stop,    getString(R.string.action_stop),    stopIntent)
            .addAction(R.drawable.ic_restart, getString(R.string.action_restart), restartIntent)
            .build()
    }

    private fun updateNotification(isRunning: Boolean, streamingSenderName: String? = null) {
        val contentText = streamingSenderName?.let {
            getString(R.string.notification_status_streaming, it)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(isRunning, contentText))
    }

    // ─── Binder ─────────────────────────────────────────────────────────────

    /**
     * LocalBinder — Provides direct access to [PhairPlayService] for bound Activities.
     *
     * WHY: Binding (rather than just starting) the service gives the Activity a
     * direct reference, so it can observe the service's StateFlows without
     * using broadcasts or a shared ViewModel.
     */
    inner class LocalBinder : Binder() {
        fun getService(): PhairPlayService = this@PhairPlayService
    }

    companion object {
        const val CHANNEL_ID      = "phairplay_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START    = "com.phairplay.action.START"
        const val ACTION_STOP     = "com.phairplay.action.STOP"
        const val ACTION_RESTART  = "com.phairplay.action.RESTART"
        const val EXTRA_AUTO_FOREGROUND_REASON = "com.phairplay.extra.AUTO_FOREGROUND_REASON"
        const val AUTOFOREGROUND_THROTTLE_MS = 3_000L
    }
}

/**
 * PhotoFrame — latest still image received via AirPlay `/photo`.
 *
 * The bytes are kept in memory only and cleared on DELETE `/photo`, streaming
 * start, receiver stop, or service destruction.
 */
data class PhotoFrame(
    val bytes: ByteArray,
    val mimeType: String,
    val receivedAtMillis: Long = System.currentTimeMillis()
)
