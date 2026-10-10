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
import android.net.wifi.WifiManager
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    // Held for the service lifetime so Wi-Fi stays up when the screen turns off.
    // Requires android.permission.WAKE_LOCK — without it acquire() throws SecurityException
    // and the receiver becomes invisible after the box idles.
    private var wifiLock: WifiManager.WifiLock? = null

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

    /**
     * Last user-facing DLNA hint (null when none), e.g. "本盒 HEVC 硬解初始化
     * 失败". Deliberately separate from [dlnaError]: a decoder that refuses to
     * initialise is a box limitation, not an app fault that should flash the
     * card red — the receiver is cooling down and retrying on its own, and the
     * hint exists so a black screen comes with an explanation instead of
     * silence.
     */
    private val _dlnaHint = MutableStateFlow<String?>(null)
    val dlnaHint: StateFlow<String?> = _dlnaHint.asStateFlow()

    /**
     * Bumped whenever the DLNA player's own state moves (IDLE/BUFFERING/READY)
     * or a first frame is rendered.
     *
     * The UI needs this because `ProtocolState.CONNECTED` is emitted once and
     * then never again, while "should we be showing a picture?" keeps changing
     * after it. See [com.phairplay.MainActivity] for the bug this fixes.
     */
    private val _dlnaPlaybackTick = MutableStateFlow(0)
    val dlnaPlaybackTick: StateFlow<Int> = _dlnaPlaybackTick.asStateFlow()

    /**
     * One-shot "a new cast arrived" signal — emitted only when the uri really
     * changed, so a control point polling SetAVTransportURI every 10-30 s with
     * the same uri cannot re-raise the player the user dismissed.
     *
     * NO replay by design: a replayed event would re-open the closed cast
     * whenever the Activity is recreated. Late subscribers are covered by
     * [dlnaPlaybackTick] plus "an item is loaded".
     */
    private val _dlnaCastArrived = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val dlnaCastArrived: SharedFlow<Unit> = _dlnaCastArrived.asSharedFlow()

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
        acquireWifiLock()
    }

    /**
     * Holds a WifiLock for as long as the receiver runs.
     *
     * WHY: the whole product depends on being discoverable and reachable on the LAN. Without
     * a lock, the platform is free to drop Wi-Fi when the screen goes off, and a TV box spends
     * most of its life with the screen off — the device then silently stops being discoverable
     * even though the service is still running. Type 3 (WIFI_MODE_FULL_HIGH_PERF) is what
     * Android 10+ wants; older levels reject it, so fall back to WIFI_MODE_FULL there.
     */
    private fun acquireWifiLock() {
        val appContext = applicationContext
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            Logger.w("WifiLock unavailable: no WifiManager")
            return
        }
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        } else {
            WifiManager.WIFI_MODE_FULL
        }
        wifiLock = wifiManager.createWifiLock(mode, "PhairPlay:castReceiver")
        // Reference-counted: repeated onCreate without a matching release would leak.
        wifiLock?.setReferenceCounted(false)
        runCatching { wifiLock?.acquire() }
            .onSuccess { Logger.i("WifiLock acquired (mode=$mode)") }
            .onFailure { Logger.w("WifiLock acquire failed: ${it.message}") }
    }

    private fun releaseWifiLock() {
        wifiLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
                    .onSuccess { Logger.i("WifiLock released") }
            }
        }
        wifiLock = null
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
        releaseWifiLock()
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

        // Diagnostics live as long as the receivers do: there is nothing to
        // diagnose before they start, and a listening socket with no receiver
        // behind it would only be an open door.
        com.phairplay.util.DiagnosticsServer.start()

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
     * The IP we are *waiting to see stay put*, and when it was first reported.
     *
     * A DHCP renewal that hands out a different address for a moment and then
     * takes it back used to restart every receiver twice within 300 ms, cutting
     * a live AirPlay/DLNA cast short. The window below is deliberately long
     * because an SSDP `LOCATION` pointing at a dead address is harmless for a
     * few seconds, while a cast being killed is not.
     */
    private var pendingStableIp: String? = null
    private var pendingStableSinceMs = 0L

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
            // NOTE: this callback also fires when only the DNS server list
            // changes, because LinkProperties treats DNS as part of the link.
            // That is harmless here — the dedupe below is on the IPv4 address,
            // so a DNS-only change never reaches the restart path.
            val addresses = linkProperties.linkAddresses
            val ip = addresses?.firstOrNull { ia ->
                val a = ia.address
                a is java.net.Inet4Address
                        && !a.isLoopbackAddress
                        && !a.isLinkLocalAddress
            }?.address?.hostAddress
            if (ip.isNullOrBlank()) return

            val now = System.currentTimeMillis()
            synchronized(ipRestartGuard) {
                if (watchedIp == ip) {
                    // Back to the address we are already advertising — cancel a
                    // pending restart so a DHCP flap costs nothing.
                    pendingStableIp = null
                    return
                }
                if (pendingStableIp == ip) return   // already waiting on this one
                pendingStableIp = ip
                pendingStableSinceMs = now
                Logger.i(
                    "本地IPv4 变为 $ip → 等待 ${IP_STABLE_CONFIRM_MS / 1000}s 确认稳定" +
                        "（期间若变回原地址则不重启，避免掐断正在播放的投屏）"
                )
            }

            serviceScope.launch {
                kotlinx.coroutines.delay(IP_STABLE_CONFIRM_MS.toLong())
                val target: String
                synchronized(ipRestartGuard) {
                    // Re-read from the watcher: only act if the address we were
                    // asked about is still the one we ended up with. A renewal
                    // that flapped and came back lands here with no action.
                    if (pendingStableIp != ip || watchedIp == ip) {
                        pendingStableIp = null
                        return@launch
                    }
                    target = ip
                    pendingStableIp = null
                    watchedIp = target
                    if (ipRestarting) return@launch
                    ipRestarting = true
                }
                Logger.i("本地IPv4 稳定为 $target，重建接收链路（旧的发现记录指向已失效地址）")
                restartReceivers()
                // Give the guard a moment before allowing another rebuild, so
                // several callbacks arriving together cannot stack restarts.
                kotlinx.coroutines.delay(IP_RESTART_SETTLE_MS.toLong())
                synchronized(ipRestartGuard) { ipRestarting = false }
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
                // The receiver parks an item when a decoder init failed with no
                // surface behind it; only the Activity can see when one appears.
                onSurfaceProbeNeeded = { requestDlnaSurfaceProbe() },
                // Still images never reach ExoPlayer (media3 1.4.1 has no image
                // renderer), so the receiver fetches the bytes itself and hands
                // them over here. Routing them into the same _photoFrame the
                // AirPlay `/photo` path uses means one photo screen serves both
                // protocols instead of a second view that can drift.
                photoSink = { frame ->
                    _photoFrame.value = frame
                    updateNotification(isRunning = true)
                },
                // A video arriving has to retire the photo screen: both live in
                // the same full-screen container, so without this a photo would
                // keep covering the video that replaced it.
                onVideoStart = {
                    _photoFrame.value = null
                    isPhotoCast = false
                },
                // Recorded the instant the uri is recognised as a photo, which
                // is what the UI's cast-arrival decision actually needs to see.
                onPhotoStart = {
                    isPhotoCast = true
                },
                // …and only the Activity can bring the playback layer back. A
                // parked item sits in ADVERTISING while showDlnaPlayer() only runs
                // on CONNECTED, so without this the retry would wait for a Surface
                // behind a hidden PlayerView — forever. The field log shows four
                // "cooldown elapsed" notices with no start after any of them.
                onPlaybackUiNeeded = {
                    requestDlnaSurfaceProbe()
                    bringActivityToForeground("DLNA 重试")
                },
                isCastDismissed = { uri -> dismissalStillInsideWindow(uri) },
                // v98 F2: a Play instruction is the sender plainly asking to play,
                // which outranks whatever we remembered about this uri. Without
                // this the receiver's gate and this one disagreed: the receiver
                // had already let the item play while the service still held the
                // dismissal, and the two halves had to agree on one answer.
                onCastPlayIntent = { clearDlnaDismissed() },
                // v101-⑧: the pause is holding the only hardware decoder, and
                // the idle sweep will take it back after a minute. Say so, or
                // the user has no idea why the slot went quiet.
                onPauseIdleWarning = {
                    _dlnaHint.value = "已暂停，闲置一分钟会释放解码资源（随时可回播放继续）"
                },
                // v101-⑩: a source that serves an anti-scraping page instead of
                // a manifest will never fix itself, so it gets a line on screen
                // rather than a black rectangle and no explanation.
                onSourceHint = { text -> _dlnaHint.value = text },
                onError = { message ->
                    _dlnaError.value = message
                    Logger.e("DLNA error surfaced to UI: $message")
                },
                onDecoderHint = { hint ->
                    _dlnaHint.value = hint
                    Logger.w("DLNA decoder hint surfaced to UI: $hint")
                },
                // A picture arrived, so the advice is stale: retract it. The
                // UI keeps whatever it was last handed, and without this a
                // later rebind replays a failure notice over a perfectly good
                // picture.
                // Every player state change must be able to re-open the
                // question "should the picture be on screen?" — see
                // [_dlnaPlaybackTick].
                onPlaybackActivityChanged = { _dlnaPlaybackTick.value++ },
                onDecoderHintCleared = {
                    if (_dlnaHint.value != null) {
                        _dlnaHint.value = null
                        Logger.i("DLNA decoder hint retracted after recovery")
                    }
                },
                // The control point itself said Stop. Forgetting the uri here
                // is what makes "stop, then cast the same channel again" count
                // as a new cast while a poll echo of a dismissed one does not.
                onSenderStop = {
                    lastSeenCastUri = null
                    // Deliberately does NOT clear [dismissedCastUri].
                    //
                    // A control-point Stop is not a clean "session over": a
                    // sender that re-casts (a live stream that dropped, a
                    // re-pick on the phone) sends Stop, then
                    // SetAVTransportURI, then Play again with the SAME uri
                    // inside a couple of seconds. Field log 11:48:59 / 11:49:03:
                    // the user pressed Back, the 120 s dismissal was wiped by
                    // the very next Stop, and four seconds later the same
                    // stream climbed back into full-screen playback — which is
                    // exactly the "I had to press Back ten times" cycle.
                    //
                    // The dismissal is what the user asked for, so only time
                    // ([DISMISS_TTL_MS]) or a genuinely different uri may lift
                    // it. [stoppedSince] below answers the "cast the same
                    // channel again later" question on its own.
                    Logger.i("Sender Stop → lastSeenCastUri forgotten, dismissal kept")
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
                            //
                            // …unless the user already closed this cast. A
                            // polling control point re-sends SetAVTransportURI
                            // + Play every 10-30 s with the SAME uri, so
                            // "CONNECTED" alone cannot tell a fresh cast from
                            // an echo of one the user dismissed.
                            //
                            // Re-arming rules (v84). This is the second fix for
                            // the same complaint; the first one compared the uri
                            // but cleared it on ADVERTISING, which the app
                            // itself emits after the user presses Back — so the
                            // very next poll looked brand new and the player
                            // climbed back 3 s later. [lastSeenCastUri] now
                            // survives our own stop and is only forgotten on a
                            // control-point Stop (see [onSenderStop]) or when
                            // the uri really changes.
                            val uri = dlnaReceiver?.currentCastUri
                            if (uri != null) {
                                val newUri = uri != lastSeenCastUri
                                // lastSeenCastUri == null means the sender
                                // stopped since we last saw this uri, which
                                // makes the same uri a new cast again.
                                val stoppedSince = lastSeenCastUri == null
                                lastSeenCastUri = uri
                                if (newUri || stoppedSince) {
                                    // A sender that re-casts sends Stop and
                                    // then the same uri again, so [stoppedSince]
                                    // is NOT proof that the user's dismissal
                                    // went away — only that the sender spoke.
                                    // A dismissal still inside its window wins:
                                    // no re-arm, no castArrived, no picture.
                                    // The item is parked by [startPlayback] too,
                                    // so this stays silent instead of audible.
                                    if (dismissalStillInsideWindow(uri)) {
                                        // No re-arm, no castArrived, no
                                        // picture. [startPlayback] parks the
                                        // item as well, so this stays quiet
                                        // instead of audible, and
                                        // [bringActivityToForeground] below
                                        // refuses too through the same check.
                                        com.phairplay.util.DebugLog.log(
                                            "DLNA",
                                            "发送端重投（Stop→SetURI→Play）但用户已关闭该投屏 → 不算新投屏，不拉前台"
                                        )
                                    } else {
                                        // A genuinely different item — or the
                                        // sender really stopping. Either one
                                        // is a fresh action, so whatever the
                                        // user dismissed a moment ago no
                                        // longer counts.
                                        dismissedCastUri = null
                                        dismissedAtMs = 0L
                                        Logger.i(
                                            "DLNA cast re-armed (newUri=$newUri stoppedSince=$stoppedSince)"
                                        )
                                        // A genuinely new cast: tell the UI to
                                        // show it. One-shot, no replay —
                                        // see [_dlnaCastArrived].
                                        _dlnaCastArrived.tryEmit(Unit)
                                    }
                                }
                            }
                            bringActivityToForeground("DLNA 播放")
                        }
                        ProtocolState.ADVERTISING,
                        ProtocolState.DISABLED,
                        ProtocolState.ERROR       -> {
                            // NOT clearing [lastSeenCastUri] here is deliberate.
                            // ADVERTISING is reported by our own
                            // stopPlaybackFromUi() as well as by a real cast
                            // ending, and forgetting the uri here is what let a
                            // polling sender resurrect a cast the user had just
                            // closed. A control-point Stop clears it through
                            // [onSenderStop]; a service-level stop does it here.
                            if (state != ProtocolState.ADVERTISING) {
                                lastSeenCastUri = null
                            }
                            _activeConnection.value = null
                            updateNotification(isRunning = state != ProtocolState.DISABLED &&
                                                           state != ProtocolState.ERROR)
                        }
                    }
                }
            ).also {
                it.start()
                // v86: replay the current foreground state into a receiver that
                // did not exist when the Activity reported it. Without this the
                // instance is born with uiForeground=false and no Activity
                // callback can ever reach it again (onResume already ran), so
                // every cast into an open app waits out the full
                // FOREGROUND_WAIT_MS before it even starts.
                it.setUiForeground(activityResumed)
            }
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

    /**
     * Asks the UI to run another render-surface probe.
     *
     * The receiver parks an item whenever a decoder init failed without a real
     * surface behind it. Whether a surface shows up afterwards is something only
     * the Activity can answer (it watches `player.videoSurface`), so the
     * receiver rings this bell instead of guessing. Bumped as a counter rather
     * than a boolean so two requests in a row are two requests, not one.
     */
    private val _dlnaSurfaceProbeTick = MutableStateFlow(0)
    val dlnaSurfaceProbeTick: StateFlow<Int> = _dlnaSurfaceProbeTick

    fun requestDlnaSurfaceProbe() {
        _dlnaSurfaceProbeTick.value += 1
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
     *
     * Also records a dismissal for the uri that was on screen: the sender
     * keeps polling, and without this the app climbed back into the user's
     * face a few seconds later — "opened PhairPlay and it went straight into
     * the player, not the home screen" is that, seen from the outside.
     *
     * The dismissal carries a timestamp and only holds while the UI is in the
     * foreground, so it survives the two or three poll echoes right after
     * Back without becoming permanent — see [dismissalApplies].
     *
     * Recording a dismissal is deliberately unconditional: no caller today has
     * a stop path that reaches the user's eyes without them having closed the
     * picture themselves, so the flag never had a false caller. A stop path
     * added later (a remote's exit key, a cast that ended on its own) has to
     * say so here rather than counting on this defaulting to off — pinning a
     * uri the user never dismissed blocks that channel for the next two
     * minutes.
     */
    /**
     * v98: [fromUserGesture] separates the two things that used to be one.
     *
     * Only a stop the user asked for records a dismissal, and a dismissal now
     * only keeps the playback layer off the screen — it no longer stops the
     * pipeline. Anything automatic (the Activity finishing on its own, a cast
     * that ended by itself, any future path) passes false and writes nothing,
     * so the next cast of that channel never inherits a gate it did not earn.
     */
    fun stopDlnaPlayback(fromUserGesture: Boolean = false) {
        val onScreen = dlnaReceiver?.currentCastUri
        if (fromUserGesture && onScreen != null) {
            dismissedCastUri = onScreen
            dismissedAtMs = System.currentTimeMillis()
            com.phairplay.util.DebugLog.log(
                "DLNA",
                "用户结束投屏 → 已停止播放，${DISMISS_TTL_MS / 1000}s 内不再自动拉起前台: ${onScreen.take(64)}…"
            )
        }
        dlnaReceiver?.stopPlaybackFromUi()
    }

    /**
     * The user asked for the picture back (nav pill, DLNA card). Clears the
     * dismissal so a following auto-foreground is allowed again.
     *
     * Also forgets the uri: the sender keeps pushing the same one, and without
     * this the re-arm check would call it an echo and keep the player hidden
     * for a cast the user just asked to see.
     */
    fun clearDlnaDismissed() {
        dismissedCastUri = null
        dismissedAtMs = 0L
        lastSeenCastUri = null
    }

    /**
     * True while the renderer is genuinely playing (not merely holding an
     * item). The UI uses this to decide whether opening the app should land on
     * the picture or on the home screen.
     */
    fun isDlnaPlaybackLive(): Boolean = dlnaReceiver?.isPlaybackLive() == true

    /** True once a cast has handed a real item to the renderer. */
    fun hasDlnaMedia(): Boolean =
        dlnaReceiver != null && com.phairplay.dlna.DlnaMediaMeta.hasMedia

    /**
     * True while the sender's item is queued but still waiting for the layer
     * the UI has to reveal.
     *
     * Separate from [hasDlnaMedia] on purpose: the media metadata is only set
     * in [runStart], whereas an item that still needs its surface is already a
     * promise that something is coming. Answering "no" here locked the UI out
     * of the very layer that was owed to that item.
     */
    fun hasPendingCast(): Boolean = dlnaReceiver?.hasPendingCast() == true

    /**
     * v101-② — Back inside the playback layer: pause and keep the cast.
     *
     * The dismissal is still recorded, because the user's actual complaint is
     * about the foreground: whatever the sender does next, the picture must not
     * come back over the home screen on its own. A real Play still clears it
     * ([onCastPlayIntent]) — that is the user asking for playback again, either
     * from the phone or by tapping the card.
     */
    fun pauseDlnaForUser() {
        val onScreen = dlnaReceiver?.currentCastUri
        if (onScreen != null) {
            dismissedCastUri = onScreen
            dismissedAtMs = System.currentTimeMillis()
            com.phairplay.util.DebugLog.log(
                "DLNA",
                "用户在播放中按返回 → 暂停并保留投屏（首页可继续，发送端不会自动抢回界面）: ${onScreen.take(64)}…"
            )
        }
        dlnaReceiver?.pauseForUser()
    }

    /** True when the cast is paused by the user and waiting to be resumed. */
    fun isCastPausedByUser(): Boolean = dlnaReceiver?.isCastPausedByUser == true

    /** v101-⑤ — the user asked for the picture back from the home screen. */
    fun resumeDlnaFromUserPause() {
        dismissedCastUri = null
        dismissedAtMs = 0L
        dlnaReceiver?.resumeFromUserPause()
    }

    /**
     * v99-② — a pending item young enough to still deserve the playback layer.
     *
     * The difference from [hasPendingCast] is the deadline: a cast parked for
     * a moment while the surface is being laid out is worth showing, one that
     * has been parked for a minute is not.
     */
    fun hasRecentPendingCast(): Boolean = dlnaReceiver?.hasRecentPendingCast() == true

    /** Last DLNA uri seen, to tell a new cast from a sender poll (see [dismissedCastUri]). */
    @Volatile private var lastSeenCastUri: String? = null

    /**
     * The uri whose picture the user sent away with Back.
     *
     * A plain boolean is not enough: it has to say *when*. Closing the UI once
     * used to pin auto-foreground off forever — the field log showed a cast at
     * 00:31 that still carried a dismissal from 21:56, so the very next cast
     * of the same channel sat out the full 10 s and started audio-only. The
     * block now lives in [DISMISS_TTL_MS] and dies with it, and a different
     * uri kills it at once.
     */
    @Volatile private var dismissedCastUri: String? = null

    /** When [dismissedCastUri] was dismissed, or 0 for "nothing dismissed". */
    @Volatile private var dismissedAtMs = 0L

    /**
     * How long a dismissal keeps withholding the picture.
     *
     * The sender polls every 10–30 s, so this window covers the echo or two
     * right after the user pressed Back, and no more.
     */
    /**
     * True while the dismissal for [uri] is still inside its time window.
     *
     * Deliberately **not** keyed on the foreground flag: [bringActivityToForeground]
     * only ever reaches this check when the UI is already known to be hidden,
     * so asking "is someone watching?" here could only ever be false. The
     * window is what tells the two situations apart — a poll echo a few
     * seconds after Back stays suppressed, while a cast that arrives minutes
     * later (or a stale dismissal from half an hour ago) comes up with its
     * picture. That last case is the field report: a flag set at 21:56 was
     * still pinning auto-foreground off at 00:31, so the box made nothing but
     * sound for a cast the user had never closed.
     */
    private fun dismissalStillInsideWindow(uri: String?): Boolean {
        if (uri == null) return false
        if (dismissedCastUri != uri) return false
        return System.currentTimeMillis() - dismissedAtMs <= DISMISS_TTL_MS
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

    /**
     * Whether the Activity's *window* is on screen and interactive — the
     * honest version of "somebody is watching".
     *
     * [activityResumed] flips true in `onResume`, which on this box is not
     * the same thing as a picture: a warm resume from the background often
     * shows a window that has not been laid out or painted yet, so the user
     * stares at a black rectangle. Field report: bring the app back by hand,
     * press Back to get the home screen, cast — and the sound comes out of a
     * screen that never shows the video. `onResume` already said "visible",
     * so nothing ever asked the Activity to draw again.
     *
     * Only the window focus callback knows the difference. The service keeps
     * its own copy because the receiver runs on other threads and because the
     * Activity has already gone away by the time it matters.
     */
    @Volatile private var uiWindowFocused = false

    /** Guards against a sender hammering Play (polling every 10–30 s). */
    @Volatile private var lastForegroundLaunchMs = 0L

    /**
     * v99-③ — how many times this service has dragged the Activity up in the
     * current minute, and when that minute started.
     *
     * [AUTOFOREGROUND_THROTTLE_MS] only spaces launches 3 s apart, which a
     * sender polling every 10-30 s never notices. What it cannot stop is the
     * pattern the field log actually showed at 13:04:31-37 — window focus
     * flipping false -> true every 2-3 s, and a fresh MainActivity each time,
     * with the user never having asked for any of it. F2 makes this reachable
     * on purpose (a Play instruction clears the dismissal outright), so the
     * counterpart has to be a budget, not a delay: past the budget the cast
     * still plays, it just stops dragging the user back to the home screen.
     */
    private var autoForegroundWindowStartMs = 0L
    private var autoForegroundCountInWindow = 0

    /**
     * v103 — the distinct items that have already been dragged into the
     * foreground inside the current window. The budget above asks "how many
     * launches", which a single cold-start cast can exhaust on its own (one
     * retry plus the Play behind it); this asks "how many different things has
     * the user been dragged out of their app for".
     */
    private val autoForegroundBudgetUris = HashSet<String>()

    /**
     * v103 — how often each reason was skipped because the UI was already up.
     * Cleared with the budget window; kept as counts so the line stays short
     * without losing the fact that it kept happening.
     */
    private val autoForegroundSkipped = HashMap<String, Int>()

    /** How often a refused auto-foreground launch has been retried. */
    private var foregroundLaunchRetries = 0

    /** Called by MainActivity so the service knows whether someone is watching. */
    fun onActivityResumed() { activityResumed = true }

    /** Called by MainActivity when it loses visibility (pause/destroy). */
    fun onActivityPaused() { activityResumed = false }

    /**
     * Tells the service whether the Activity's window is really on screen.
     *
     * [MainActivity] reports this from `onWindowFocusChanged`, which is the
     * only callback that fires when a window exists but is not painted (a
     * black cold/warm resume) or has been pushed behind another window
     * (screensaver, another full-screen app).
     */
    fun setUiWindowFocused(focused: Boolean) {
        if (uiWindowFocused == focused) return
        uiWindowFocused = focused
        com.phairplay.util.DebugLog.log(
            "UI",
            "窗口焦点变化 → focused=$focused（activityResumed=$activityResumed）"
        )
        // The window just came back: whatever it should be showing may have
        // changed while it was invisible — a cast that started during the
        // black screen, or a home screen that is owed the picture back. The
        // Activity re-evaluates itself; the service only rings the bell.
        if (focused) {
            _dlnaPlaybackTick.value++
        }
    }

    /** The uri the receiver is currently holding, for the UI's own gates. */
    fun dlnaCurrentUri(): String? = dlnaReceiver?.currentCastUri

    /**
     * True while the current DLNA item is a still image.
     *
     * The UI needs this to decide NOT to open the PlayerView. Checking
     * `_photoFrame` instead does not work: the cast-arrived signal fires the
     * instant the sender sends a new uri, which is before the photo has been
     * downloaded, so the frame is still null at exactly the moment the decision
     * is made — and the playback layer goes up over the picture.
     */
    @Volatile private var isPhotoCast = false

    fun dlnaIsPhotoCast(): Boolean = isPhotoCast

    /** True while a cast the user dismissed is still inside its time window. */
    fun isCastDismissed(uri: String?): Boolean = dismissalStillInsideWindow(uri)

    /**
     * True when the Intent came from our own auto-foreground launch, as
     * opposed to a launcher / shortcut / notification tap.
     *
     * A launcher start means the user opened the app, which says more than any
     * dismissal flag could; an auto-foreground start is our own doing, so it
     * must not be mistaken for one.
     */
    fun isAutoForegroundLaunch(intent: android.content.Intent?): Boolean =
        intent?.getStringExtra(EXTRA_AUTO_FOREGROUND_REASON) != null

    /**
     * Hands the DLNA receiver the confirmed foreground state.
     *
     * The receiver does not play until this says true, so the cast order is
     * "sender pushes → UI comes up → UI is on screen → media starts".
     */
    fun setDlnaUiForeground(foreground: Boolean) {
        // Keep the service's own copy as the single source of truth. The
        // receiver is created lazily and after this has already run, so it has
        // to be replayed to every new instance — see [startDlna].
        activityResumed = foreground
        dlnaReceiver?.setUiForeground(foreground)
    }

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
        // Every gate writes to the diagnostic log, not just to logcat. The
        // field report "cast from the background, nothing happens for 10 s"
        // could not be diagnosed from the log because these three refusals
        // were Timber-only, and the one that fires in practice is invisible
        // without it.
        // Resumed is not the same as on screen. A window that was just woken
        // from the background reports `onResume` before it has laid anything
        // out, so the user is staring at black while we consider ourselves
        // "visible" and skip the launch — the picture then never arrives and
        // the cast plays on inaudibly behind a black rectangle. Only a window
        // that actually holds focus may suppress a launch.
        if (activityResumed) {
            if (uiWindowFocused) {
                // v103 — senders poll every 10-30 s, so one visible cast used to
                // print this on every single poll: dozens of identical lines
                // that pushed real entries out of the rolling 8099 window.
                // Counted instead of silenced — "it should have come forward and
                // did not" is a signal we must not swallow.
                val key = reason
                autoForegroundSkipped[key] = (autoForegroundSkipped[key] ?: 0) + 1
                val seen = autoForegroundSkipped[key] ?: 1
                if (seen == 1) {
                    com.phairplay.util.DebugLog.log("DLNA", "自动前台被跳过：UI 已可见（$reason）")
                } else if (seen % 10 == 0) {
                    com.phairplay.util.DebugLog.log(
                        "DLNA",
                        "自动前台被跳过 x$seen 次（UI 已可见，$reason）"
                    )
                }
                return
            }
            com.phairplay.util.DebugLog.log(
                "DLNA",
                "UI 已 resume 但窗口没有焦点（黑屏/未绘制）→ 仍发起前台：$reason"
            )
        }
        // A cast the user already closed does not get to come back on its own.
        // [dismissalApplies] keeps this narrow on purpose: the same uri has to
        // stay closed, while the time window and "is the UI on screen" question
        // decide *when* it stops applying. [dismissalApplies] also tells the
        // receiver to drop its foreground wait, so a refused cast never sits
        // out the full 10 s before making a sound.
        if (reason.startsWith("DLNA") && dismissalStillInsideWindow(dlnaReceiver?.currentCastUri)) {
            com.phairplay.util.DebugLog.log("DLNA", "自动前台被抑制：用户已关闭该投屏（$reason）")
            dlnaReceiver?.cancelForegroundWaitAndStartAudio()
            return
        }
        val now = System.currentTimeMillis()
        // v99-③ — the per-minute budget, checked before the 3 s throttle so a
        // minute of "every poll gets a fresh Activity" is bounded even when
        // each individual launch is far enough apart to pass it.
        if (now - autoForegroundWindowStartMs >= AUTOFOREGROUND_BUDGET_WINDOW_MS) {
            autoForegroundWindowStartMs = now
            autoForegroundCountInWindow = 0
            autoForegroundBudgetUris.clear()
            autoForegroundSkipped.clear()
        }
        // v103 — the budget counts DISTINCT items, not launches.
        //
        // A single cold-start cast needs 2-3 launches to get on screen (one
        // retry plus the Play that follows it), so counting launches let a cast
        // spend its own budget before it had even shown a picture. Field log
        // 18:02:34-18:02:40: one live stream took all three slots in six
        // seconds, and for the next 60 s every new item "played but did not
        // come forward" — which the user experiences as "the fourth channel I
        // switch to shows no picture".
        //
        // A set keyed by uri fixes it: re-sending the same item (senders poll
        // every 10-30 s, and a dismissal/return cycle re-posts) no longer costs
        // anything, while genuinely new items still get their own budget.
        val budgetUri = dlnaReceiver?.currentCastUri ?: dlnaReceiver?.pendingCastUri
        val newBudgetItem = budgetUri != null && autoForegroundBudgetUris.add(budgetUri)
        if (!newBudgetItem && budgetUri == null && autoForegroundCountInWindow >= AUTOFOREGROUND_BUDGET_MAX) {
            // No uri to attribute the launch to: fall back to the old counter so
            // a launch storm with nothing identifiable is still bounded.
            com.phairplay.util.DebugLog.log(
                "DLNA",
                "自动前台总闸（无 URI 归属）：60s 内已拉起 $autoForegroundCountInWindow 次（上限 $AUTOFOREGROUND_BUDGET_MAX）" +
                    "→ 本次只播不拉前台（$reason）。播放正常，界面不再自动抢"
            )
            dlnaReceiver?.cancelForegroundWaitAndStartAudio()
            return
        }
        if (newBudgetItem && autoForegroundBudgetUris.size > AUTOFOREGROUND_BUDGET_MAX) {
            // Over budget: play, do not drag. Same courtesy the dismissal path
            // extends — the receiver stops waiting for a UI that is not coming
            // and starts on its own, so the user hears the cast instead of
            // watching an app they left keep pulling itself into view.
            com.phairplay.util.DebugLog.log(
                "DLNA",
                "自动前台总闸：60s 内已为 ${autoForegroundBudgetUris.size} 个不同投屏拉起过前台（上限 $AUTOFOREGROUND_BUDGET_MAX）" +
                    "→ 本次只播不拉前台（$reason）。播放正常，界面不再自动抢"
            )
            dlnaReceiver?.cancelForegroundWaitAndStartAudio()
            return
        }
        if (now - lastForegroundLaunchMs < AUTOFOREGROUND_THROTTLE_MS) {
            // Re-queue instead of dropping. A dropped request is what made the
            // "偶尔投屏只出声" reports: the receiver had already parked the
            // item and was sitting on its 10 s fallback, the launch never left,
            // and the app either stayed in the background or came up only later
            // — by which time the sender's item was already audible behind the
            // home screen. Re-sending once lands the app inside the same
            // window the fallback runs in, and the retry cannot loop because
            // the delay lands exactly on the throttle edge.
            val waitMs = AUTOFOREGROUND_THROTTLE_MS - (now - lastForegroundLaunchMs)
            com.phairplay.util.DebugLog.log(
                "DLNA",
                "自动前台被限流（$reason，${waitMs / 1000}s 后补发一次）"
            )
            mainHandler.postDelayed({
                // Re-check at landing time, not when the request was queued:
                // the sender may have stopped or moved on during the wait, and
                // dragging an empty app into front then buys nothing but a
                // home screen the user never asked for.
                if (reason.startsWith("DLNA") && !hasDlnaMedia() && !hasPendingCast()) {
                    com.phairplay.util.DebugLog.log(
                        "DLNA",
                        "限流补发作废：等待期间投屏已结束（$reason）"
                    )
                    return@postDelayed
                }
                bringActivityToForeground(reason)
            }, waitMs)
            return
        }
        val attemptedAt = now
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
            // The launch takes a second or two to land, but the receiver has
            // already parked the item and is about to sit out the whole
            // foreground timeout. Tell it the UI is on its way — without
            // cancelling the fallback, so a launch that never materialises
            // still ends in sound.
            dlnaReceiver?.setUiForegroundPending(true)
            // Only a launch that actually went out counts as "last attempt",
            // otherwise a refused launch would be remembered as a successful
            // one and the throttle would suppress the retry that might work.
            lastForegroundLaunchMs = attemptedAt
            foregroundLaunchRetries = 0
            autoForegroundCountInWindow++
            com.phairplay.util.DebugLog.log("DLNA", "自动前台：已发出 MainActivity（$reason）")
        }.onFailure { err ->
            // A launch refused in the same second is usually a race with the
            // user arriving at the launcher, not a permanent answer. Try once
            // more, but stay bounded: a sender that polls every 10-30 s must
            // not be able to keep the Activity in a launch loop.
            if (foregroundLaunchRetries < MAX_FOREGROUND_LAUNCH_RETRIES) {
                foregroundLaunchRetries++
                com.phairplay.util.DebugLog.log(
                    "DLNA",
                    "自动前台：startActivity 失败（$reason）=${err.message} → " +
                        "${FOREGROUND_LAUNCH_RETRY_MS / 1000}s 后重试 $foregroundLaunchRetries/" +
                        "$MAX_FOREGROUND_LAUNCH_RETRIES"
                )
                mainHandler.postDelayed(
                    { bringActivityToForeground(reason) },
                    FOREGROUND_LAUNCH_RETRY_MS
                )
            } else {
                // The notification's content intent opens the same Activity, so
                // the user is never left with a silent picture and no way back.
                com.phairplay.util.DebugLog.log(
                    "DLNA",
                    "自动前台：已放弃（$reason，连续失败 $foregroundLaunchRetries 次）→ 通知仍可打开界面"
                )
            }
        }
    }

    private fun stopAllReceiversInternal() {
        com.phairplay.util.DiagnosticsServer.stop()
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
        _dlnaHint.value = null
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

        /**
         * v99-③ — per-minute auto-foreground budget. Past this many launches
         * the cast keeps playing and the UI stops being pulled up. The window
         * is a minute because that is the senders' polling period.
         */
        const val AUTOFOREGROUND_BUDGET_WINDOW_MS = 60_000L

        /** How many auto-foreground launches one minute buys. */
        const val AUTOFOREGROUND_BUDGET_MAX = 3

        /**
         * How long a dismissal keeps withholding the picture — see
         * [dismissedCastUri]. The sender polls every 10–30 s, so this window
         * covers the echo or two right after Back and no more.
         */
        // v98: 20 s instead of 120 s. Only the second line of defence now — the
        // primary fix is that a Play instruction clears the dismissal outright
        // and the dismissal itself never stops playback, so this window only
        // covers the rare case where the sender keeps replaying without ever
        // asking again.
        const val DISMISS_TTL_MS = 20 * 1000L

        /**
         * A launch the system refused is usually a race, so it is worth one
         * more shot a moment later — but only a bounded number of times, or a
         * polling sender could keep the launcher in a loop.
         */
        const val FOREGROUND_LAUNCH_RETRY_MS = 1_500L
        const val MAX_FOREGROUND_LAUNCH_RETRIES = 2

        /**
         * How long a new LAN IPv4 must hold still before the receivers are
         * rebuilt for it.
         *
         * A DHCP renewal can hand out a different address for a moment and then
         * take the original back; restarting on the transient one killed a live
         * AirPlay/DLNA cast twice inside 300 ms. The cost of waiting is an SSDP
         * `LOCATION` that points at a dead address for a few seconds — harmless,
         * because senders re-run M-SEARCH constantly. The cost of not waiting is
         * a cast that dies for no reason, so the window is deliberately long.
         *
         * This deliberately does not compare subnets: a same-subnet address
         * change invalidates the advertised `LOCATION` just as thoroughly.
         */
        const val IP_STABLE_CONFIRM_MS = 4_000L

        /** Minimum gap between two consecutive receiver rebuilds. */
        const val IP_RESTART_SETTLE_MS = 2_000L
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
