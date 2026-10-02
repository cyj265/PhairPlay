package com.phairplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.KeyEvent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.phairplay.service.PhairPlayService
import com.phairplay.service.PhotoFrame
import com.phairplay.service.ProtocolState
import com.phairplay.service.ServiceController
import com.phairplay.settings.SettingsRepository
import com.phairplay.airplay.DacpClient
import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.ui.HomeFragment
import com.phairplay.ui.NowPlayingScreen
import com.phairplay.ui.PhotoScreen
import com.phairplay.ui.PinScreen
import com.phairplay.ui.SettingsFragment
import com.phairplay.ui.StreamingScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * MainActivity — The single Activity hosting PhairPlay's navigation and fragments.
 *
 * WHY: PhairPlay uses a single-Activity architecture with Fragment-based navigation.
 * This is the recommended pattern for Android TV apps: one Activity with swappable
 * Fragments avoids the overhead of Activity transitions and keeps the Leanback
 * launcher integration simple.
 *
 * Layout structure:
 *   ┌─ Nav Panel ──┬─ Content (FrameLayout) ─────────────────┐
 *   │  Home        │  HomeFragment  OR  SettingsFragment      │
 *   │  Settings    │                                          │
 *   └──────────────┴──────────────────────────────────────────┘
 *   [streaming_container] — full-screen overlay (GONE when idle)
 *
 * HOW: D-pad left/right navigation between nav panel and content area.
 * The nav panel items switch fragments. PhairPlayService is started on app launch.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity : AppCompatActivity() {

    // UI references
    private lateinit var navItemHome: TextView
    private lateinit var navItemSettings: TextView
    private lateinit var contentContainer: FrameLayout
    private lateinit var streamingContainer: FrameLayout

    // The SurfaceView for full-screen video output
    private lateinit var streamingScreen: StreamingScreen
    private lateinit var photoScreen: PhotoScreen
    private lateinit var nowPlayingScreen: NowPlayingScreen
    private lateinit var pinScreen: PinScreen

    // Service binding — gives access to state flows for showing/hiding the streaming overlay
    private var service: PhairPlayService? = null
    private var isBound = false
    private var currentAirPlayState = ProtocolState.DISABLED
    private var currentPhotoFrame: PhotoFrame? = null
    private var currentNowPlaying: NowPlayingInfo? = null
    private var currentPin: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? PhairPlayService.LocalBinder)?.getService()
            isBound = true
            Timber.d("MainActivity: bound to PhairPlayService")

            // Wire the streaming Surface so the service can pass it to VideoDecoder
            service?.setVideoSurfaceProvider { getVideoSurface() }

            // Show/hide the full-screen overlay for video streams and photos.
            observeOverlayState()

            // Foreground resume after a background-initiated cast: if DLNA is
            // already CONNECTED (the cast started while this Activity was
            // stopped — onStop paused playback and dropped the collectors),
            // immediately restore the full-screen player and resume playback.
            if (service?.dlnaState?.value == ProtocolState.CONNECTED) {
                service?.resumeDlnaPlayback()
                showDlnaPlayer()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            Timber.d("MainActivity: unbound from PhairPlayService")
        }
    }

    // Currently selected nav item index (0 = Home, 1 = Settings)
    private var selectedNavIndex = 0

    /** Full-screen DLNA playback view (inside streaming_container so it covers
     *  the nav panel). PlayerView provides the controller and Surface lifecycle. */
    private var dlnaPlayerView: PlayerView? = null

    /** DLNA debug HUD (Settings → "Debug overlay"), drawn ABOVE the DLNA
     *  Surface (plain View above the Surface layer) so it is never clipped. */
    private var dlnaDebugView: TextView? = null

    private val dlnaDebugHandler = Handler(Looper.getMainLooper())
    private val dlnaDebugTick = object : Runnable {
        override fun run() {
            updateDlnaDebugText()
            dlnaDebugHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Timber.d("MainActivity created")
        bindViews()
        setupOverlayScreens()
        setupNavigation()

        // Show HomeFragment on first launch
        if (savedInstanceState == null) {
            navigateTo(HomeFragment(), navItemHome)
        }

        // Start the service immediately so it's running before any sender discovers us
        ServiceController.start(this)

        // Android 13+ requires an explicit runtime grant for POST_NOTIFICATIONS
        requestNotificationPermission()
        // Android 14+ requires NEARBY_WIFI_DEVICES, otherwise the connectedDevice
        // foreground service can never start and the receiver stays completely
        // invisible to phones (no mDNS/SSDP advertisement at all).
        requestNearbyWifiPermission()
    }

    override fun onStart() {
        super.onStart()
        // Bind so we can observe StateFlows and supply the video Surface
        val intent = Intent(this, PhairPlayService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        service?.resumeDlnaPlayback()
    }

    override fun onStop() {
        super.onStop()
        // Pause DLNA playback before the Surface is destroyed so the MediaCodec
        // renderer never writes into a dead Surface (avoids DECODING_FAILED on
        // background/foreground switches).
        service?.pauseDlnaPlayback()
        // Clear surface reference before unbinding to avoid holding a dead Surface
        service?.setVideoSurfaceProvider { null }
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Receivers are NO LONGER torn down when the user backs out of the app.
        //
        // WHY: "install the APK, back out, then try to cast from the phone" was the
        // exact report that made the TV invisible in the phone's cast picker. Stopping
        // the service here killed mDNS + SSDP. The service now only stops when the user
        // presses Stop (home screen / notification action), on an explicit ACTION_STOP,
        // or when the system finally decides the process is no longer wanted.
        if (isFinishing) {
            val connected = service?.activeConnection?.value
            if (connected != null) {
                // An active sender is still streaming: keep the service alive so the
                // session isn't dropped mid-playback, and let it end on its own.
                Timber.d("MainActivity finishing — sender '${connected.senderName}' still connected, keeping service running")
            } else {
                Timber.d("MainActivity finishing — leaving PhairPlayService running so the box stays discoverable")
            }
        } else {
            Timber.d("MainActivity destroyed (recreation) — leaving service running")
        }
    }

    // ─── View Setup ──────────────────────────────────────────────────────────

    private fun bindViews() {
        navItemHome       = findViewById(R.id.nav_item_home)
        navItemSettings   = findViewById(R.id.nav_item_settings)
        contentContainer  = findViewById(R.id.content_container)
        streamingContainer = findViewById(R.id.streaming_container)
    }

    /**
     * Creates the StreamingScreen (SurfaceView for video) and adds it to the
     * streaming_container. Created eagerly so the Surface is ready before streaming starts.
     */
    private fun setupOverlayScreens() {
        streamingScreen = StreamingScreen(this)
        photoScreen = PhotoScreen(this)
        nowPlayingScreen = NowPlayingScreen(this)
        pinScreen = PinScreen(this)
        streamingContainer.addView(streamingScreen)
        streamingContainer.addView(photoScreen)
        streamingContainer.addView(nowPlayingScreen)
        streamingContainer.addView(pinScreen)

        // Full-screen DLNA playback: a PlayerView with built-in controller,
        // keep-screen-on, and automatic Surface lifecycle management.
        // Created from XML because the render target has to be a TextureView
        // (see dlna_player_view.xml) and that can only be set via the
        // resource attribute.
        dlnaPlayerView = layoutInflater.inflate(
            R.layout.dlna_player_view, streamingContainer, false
        ) as PlayerView
        dlnaPlayerView?.visibility = View.GONE
        streamingContainer.addView(
            dlnaPlayerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // DLNA debug HUD — added AFTER the PlayerView so it sits above its
        // Surface layer and is never clipped by it.
        dlnaDebugView = TextView(this).apply {
            setTextColor(android.graphics.Color.parseColor("#FF00FF66"))
            setBackgroundColor(android.graphics.Color.parseColor("#A6000000"))
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 16, 24, 16)
            visibility = View.GONE
        }
        streamingContainer.addView(
            dlnaDebugView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                topMargin = 48
                leftMargin = 48
            }
        )
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
    }

    /**
     * Sets up click listeners for the navigation panel items.
     * Also updates the visual selected state (text color) of the active item.
     */
    private fun setupNavigation() {
        navItemHome.setOnClickListener {
            if (selectedNavIndex != 0) {
                navigateTo(HomeFragment(), navItemHome)
            } else if (service?.dlnaState?.value == ProtocolState.CONNECTED) {
                // Already on home, and DLNA is still playing underneath (the
                // remote pressed Back out of the player). Re-selecting Home
                // returns to the picture; otherwise the only way back would be
                // re-casting from the phone.
                showDlnaPlayer()
            }
        }
        navItemSettings.setOnClickListener {
            if (selectedNavIndex != 1) {
                navigateTo(SettingsFragment(), navItemSettings)
            }
        }

        // Set initial selected state
        setNavSelected(navItemHome, true)
        setNavSelected(navItemSettings, false)
    }

    /**
     * Replaces the content_container fragment with [fragment] and updates
     * the nav panel selection highlight.
     *
     * @param fragment  The Fragment to show in the content area.
     * @param navItem   The nav panel TextView that was clicked (for highlight update).
     */
    private fun navigateTo(fragment: Fragment, navItem: TextView) {
        // Update nav highlight
        setNavSelected(navItemHome, navItem == navItemHome)
        setNavSelected(navItemSettings, navItem == navItemSettings)
        selectedNavIndex = if (navItem == navItemHome) 0 else 1

        // Replace fragment
        supportFragmentManager.beginTransaction()
            .replace(R.id.content_container, fragment)
            .commit()
    }

    /**
     * Updates the nav panel item's visual state.
     *
     * @param item     The nav item TextView.
     * @param selected True if this item is currently active.
     */
    private fun setNavSelected(item: TextView, selected: Boolean) {
        item.isSelected = selected
        item.setTextColor(
            getColor(if (selected) R.color.text_primary else R.color.nav_item_normal)
        )
    }

    /**
     * Shows the full-screen streaming overlay (called by PhairPlayService
     * via a state update or broadcast when a stream becomes active).
     *
     * Hides the nav panel and content area to give the stream the full screen.
     */
    fun showStreamingScreen() {
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        nowPlayingScreen.clear()
        pinScreen.visibility = View.GONE
        streamingScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
    }

    fun showPhotoScreen(photoFrame: PhotoFrame) {
        if (photoScreen.showPhoto(photoFrame.bytes)) {
            streamingScreen.visibility = View.GONE
            nowPlayingScreen.visibility = View.GONE
            pinScreen.visibility = View.GONE
            photoScreen.visibility = View.VISIBLE
            streamingContainer.visibility = View.VISIBLE
            streamingContainer.bringToFront()
        }
    }

    /** Shows the audio-only now-playing card (AirPlay audio with no video). */
    fun showNowPlayingScreen(info: NowPlayingInfo) {
        nowPlayingScreen.update(info)
        streamingScreen.visibility = View.GONE
        photoScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
    }

    /**
     * Hides the streaming overlay and returns to the normal app UI.
     * Called when a stream ends.
     */
    fun hideStreamingScreen() {
        photoScreen.clearPhoto()
        photoScreen.visibility = View.GONE
        nowPlayingScreen.clear()
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
        streamingScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.GONE
    }

    /** Returns the SurfaceView Surface for the VideoDecoder. */
    fun getVideoSurface() = streamingScreen.getSurface()

    /**
     * Volume keys stay with the system on both protocols — forwarding them to
     * the sender only worked for AirPlay and made the same button behave
     * differently depending on what was casting.
     */
    /**
     * Requests POST_NOTIFICATIONS permission on Android 13+ (API 33+).
     * On older versions the permission is granted automatically with the manifest declaration.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQUEST_NOTIFICATIONS
                )
            }
        }
    }

    /**
     * Android 14+ (API 34) requires NEARBY_WIFI_DEVICES for the `connectedDevice`
     * foreground service type that hosts the mDNS/SSDP receivers. Without it the
     * service start fails and the TV disappears from the phone's cast list.
     * On Android 10–13 the equivalent requirement is silently covered by
     * NEARBY_WIFI_DEVICES being granted with the manifest declaration.
     */
    private fun requestNearbyWifiPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        ) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES),
            PERMISSION_REQUEST_NEARBY_WIFI
        )
    }

    companion object {
        private const val PERMISSION_REQUEST_NOTIFICATIONS = 1001
        private const val PERMISSION_REQUEST_NEARBY_WIFI = 1002

        /** How far the D-pad left/right seek (±10 s). */
        private const val SEEK_STEP_MS = 10_000L
    }

    // ─── Streaming overlay ────────────────────────────────────────────────────

    /**
     * Observes [PhairPlayService.airPlayState] and [PhairPlayService.photoFrame]
     * and shows the appropriate full-screen overlay.
     *
     * Called once after the service is bound. The coroutine is automatically cancelled
     * by [lifecycleScope] when the Activity stops.
     */
    private fun observeOverlayState() {
        val svc = service ?: return
        lifecycleScope.launch {
            svc.airPlayState.collectLatest { state ->
                currentAirPlayState = state
                updateOverlay()
            }
        }
        lifecycleScope.launch {
            svc.photoFrame.collectLatest { frame ->
                currentPhotoFrame = frame
                updateOverlay()
            }
        }
        lifecycleScope.launch {
            svc.nowPlaying.collectLatest { info ->
                currentNowPlaying = info
                updateOverlay()
            }
        }
        lifecycleScope.launch {
            svc.pairingPin.collectLatest { pin ->
                currentPin = pin
                updateOverlay()
            }
        }
        lifecycleScope.launch {
            svc.dlnaState.collectLatest { state ->
                if (state == ProtocolState.CONNECTED) {
                    showDlnaPlayer()
                } else {
                    hideDlnaPlayer()
                }
            }
        }
    }

    private fun updateOverlay() {
        val photoFrame = currentPhotoFrame
        val nowPlaying = currentNowPlaying
        val pin = currentPin
        when {
            // PIN pairing (access control) happens before streaming — show the code over everything.
            pin != null -> showPinScreen(pin)
            // Audio-only AirPlay (system audio, Music, podcasts): show the now-playing card instead
            // of the black video surface. Set whenever audio plays without video.
            nowPlaying != null -> showNowPlayingScreen(nowPlaying)
            currentAirPlayState == ProtocolState.CONNECTED -> showStreamingScreen()
            photoFrame != null -> showPhotoScreen(photoFrame)
            else -> hideStreamingScreen()
        }
    }

    /** Shows the AirPlay pairing PIN over the full screen during SRP pair-setup. */
    fun showPinScreen(pin: String) {
        pinScreen.setPin(pin)
        streamingScreen.visibility = View.GONE
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.VISIBLE
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
    }


    // ─── DLNA full-screen playback UI ─────────────────────────────────────

    /**
     * Shows the full-screen DLNA PlayerView and binds the active player.
     * Mirrors the AirPlay [showStreamingScreen] pattern exactly: run directly
     * on the main thread (no view.post), toggle the shared streaming_container
     * and bring it to front — that is what reliably covers the nav panel.
     */
    fun showDlnaPlayer() {
        val pv = dlnaPlayerView ?: return
        // DLNA owns the overlay: hide the AirPlay/photo screens (their debug
        // HUD would otherwise show through) and surface only the player.
        streamingScreen.visibility = View.GONE
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE

        pv.player = service?.dlnaPlayer
        pv.visibility = View.VISIBLE
        streamingContainer.visibility = View.VISIBLE
        streamingContainer.bringToFront()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The remote drives this screen. Without focus the D-pad events go to
        // whatever still holds it underneath and every key looks dead.
        pv.isFocusable = true
        pv.requestFocus()
        com.phairplay.util.DebugLog.log(
            "UI",
            "显示播放层 (TextureView, player=${if (pv.player != null) "已绑定" else "null"})"
        )

        // DLNA debug HUD honours the Settings → "Debug overlay" switch:
        // on → show live DLNA playback info; off → hidden.
        lifecycleScope.launch {
            val show = SettingsRepository(this@MainActivity)
                .settingsFlow.first().showDebugOverlay
            dlnaDebugView?.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                updateDlnaDebugText()
                dlnaDebugHandler.removeCallbacks(dlnaDebugTick)
                dlnaDebugHandler.post(dlnaDebugTick)
            } else {
                dlnaDebugHandler.removeCallbacks(dlnaDebugTick)
            }
        }
    }

    /** Hides the full-screen DLNA PlayerView and releases the player binding. */
    fun hideDlnaPlayer() {
        val pv = dlnaPlayerView ?: return
        pv.player = null
        pv.visibility = View.GONE
        dlnaDebugHandler.removeCallbacks(dlnaDebugTick)
        dlnaDebugView?.visibility = View.GONE
        // DLNA ended: tear down the whole full-screen overlay, exactly like
        // hideStreamingScreen does. Leaving streamingContainer visible with
        // the (black, no-video) AirPlay streamingScreen on top is what caused
        // the "computer ended cast -> phone goes black until you re-enter the
        // app" symptom: the PlayerView was hidden but the black Surface stayed.
        streamingScreen.visibility = View.GONE
        photoScreen.visibility = View.GONE
        nowPlayingScreen.visibility = View.GONE
        pinScreen.visibility = View.GONE
        streamingContainer.visibility = View.GONE
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Refreshes the DLNA debug HUD with live playback info from ExoPlayer. */
    private fun updateDlnaDebugText() {
        val pv = dlnaPlayerView ?: return
        val debug = dlnaDebugView ?: return
        if (pv.visibility != View.VISIBLE) return
        val player = service?.dlnaPlayer ?: run {
            debug.text = "PhairPlay · DLNA\nno player"
            return
        }
        val state = when (player.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "?"
        }
        val playing = if (player.isPlaying) "▶" else "⏸"
        val pos = player.currentPosition / 1000
        val dur = player.duration.takeIf { it > 0 }?.div(1000) ?: -1L
        val size = if (androidx.media3.common.util.UnstableApi::class.isInstance(player)) {
            runCatching { player.videoSize }.getOrNull()?.let { "${it.width}x${it.height}" } ?: "—"
        } else "—"
        val uri = runCatching {
            player.currentMediaItem?.localConfiguration?.uri?.toString()?.substringAfter("//") ?: "—"
        }.getOrNull() ?: "—"
        debug.text = "PhairPlay · DLNA\n" +
            "$playing $state  ${pos}s/${if (dur > 0) "${dur}s" else "∞"}\n" +
            "$size  $uri"
    }

    // ─── Remote control (D-pad) support for DLNA full-screen playback ─────
    // On a TV, the sender is remote-controlled: D-pad + OK. The PlayerView
    // controller is touch-oriented, so we surface it on any D-pad press and
    // hand focus to it; media keys (play/pause/ff/rew) are handled by
    // PlayerView itself.

    /**
     * TV remote mapping for the full-screen DLNA player.
     *
     * media3's PlayerView is built for touch: its controller only reacts to
     * Left/Right once focus has landed on the seek bar, which a D-pad never
     * does by itself here. So the keys that matter are handled explicitly —
     * Left/Right seek, OK toggles play, Menu opens the player settings —
     * and everything else falls through unchanged.
     */
    /**
     * Single key map for both cast protocols.
     *
     * WHY: AirPlay used to be handled in onKeyDown and DLNA in
     * dispatchKeyEvent, with two different key sets — the same physical button
     * did nothing, or did different things, depending on which protocol was
     * casting. Everything now routes through one table, taking AirPlay's
     * transport semantics as the reference, and applies the local (ExoPlayer)
     * equivalent when DLNA is the active session.
     *
     * Volume stays with the system in both cases so the remote always controls
     * the box's own output level.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val dlna = isDlnaPlayerVisible
        val airPlay = isAirPlayOverlayActive
        if ((dlna || airPlay) && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                // Back leaves full-screen playback. DLNA drops back to the app
                // UI first (that UI is reachable and stays usable); pressing
                // Back there exits the app exactly like AirPlay does.
                KeyEvent.KEYCODE_BACK -> {
                    if (dlna && event.repeatCount == 0) {
                        hideDlnaPlayer()
                        val target = if (selectedNavIndex == 0) navItemHome else navItemSettings
                        target.requestFocus()
                        return true
                    }
                    return false
                }
                KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> {
                    transportBackward(event.keyCode)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> {
                    transportForward(event.keyCode)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    transportPlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (dlna) showPlayerSettings() else showAirPlaySessionMenu()
                    return true
                }
                else -> {
                    // Any other key just reveals the controller. It must NOT be
                    // consumed: swallowing every direction press was why the
                    // remote looked completely dead in the first place.
                    if (dlna && dlnaPlayerView?.isControllerFullyVisible == false) {
                        dlnaPlayerView?.showController()
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** True while an AirPlay stream / now-playing card owns the screen. */
    private val isAirPlayOverlayActive: Boolean
        get() = currentNowPlaying != null || currentAirPlayState == ProtocolState.CONNECTED

    /** True while the full-screen DLNA player owns the screen. */
    private val isDlnaPlayerVisible: Boolean
        get() = dlnaPlayerView?.visibility == View.VISIBLE

    private fun transportPlayPause() {
        if (isDlnaPlayerVisible) {
            togglePlayPause()
            dlnaPlayerView?.showController()
        } else {
            sendDacp(DacpClient.CMD_PLAY_PAUSE)
        }
    }

    /**
     * Backwards transport control. DLNA has one media item in front of it, so
     * "previous" cannot mean another track — it seeks back, which is the same
     * intent the sender receives in the AirPlay case.
     */
    private fun transportBackward(keyCode: Int) {
        if (isDlnaPlayerVisible) {
            val step = if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) -30_000L else -SEEK_STEP_MS
            seekBy(step)
            dlnaPlayerView?.showController()
        } else {
            sendDacp(
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_REWIND -> DacpClient.CMD_REW
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> DacpClient.CMD_PREV
                    else -> DacpClient.CMD_REW
                }
            )
        }
    }

    private fun transportForward(keyCode: Int) {
        if (isDlnaPlayerVisible) {
            val step = if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) 30_000L else SEEK_STEP_MS
            seekBy(step)
            dlnaPlayerView?.showController()
        } else {
            sendDacp(
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> DacpClient.CMD_FF
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                    KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> DacpClient.CMD_NEXT
                    else -> DacpClient.CMD_FF
                }
            )
        }
    }

    private fun sendDacp(command: String) {
        service?.sendAirPlayRemoteCommand(command)
    }

    /**
     * TV-safe menu dialog, drawn by hand instead of AlertDialog. The leanback
     * theme's alert-dialog list items rendered with no visible text on the N1
     * (empty panel over a translucent window — the user saw a blank striped
     * box). Explicit colors/sizes here cannot be broken by any theme. Rows are
     * focusable so the D-pad works; Back dismisses (Dialog default).
     */
    private fun showMenuDialog(title: String, entries: List<Pair<String, () -> Unit>>) {
        val density = resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xF2101010.toInt())
            setPadding(pad, pad, pad, pad)
        }
        container.addView(android.widget.TextView(this).apply {
            text = title
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 22f
            setPadding(0, 0, 0, pad)
        })
        for ((label, action) in entries) {
            container.addView(android.widget.TextView(this).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 20f
                val v = (10 * density).toInt()
                setPadding(v, v, v, v)
                isFocusable = true
                isClickable = true
                setOnFocusChangeListener { _, hasFocus ->
                    setBackgroundColor(if (hasFocus) 0xFF3A5A78.toInt() else 0x00000000)
                }
                setOnClickListener {
                    (tag as? android.app.Dialog)?.dismiss()
                    action()
                }
                // The dialog is only known after setContentView; stash it on
                // the row via tag right after creation below.
            })
        }
        val dialog = android.app.Dialog(this).apply {
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            setContentView(container)
            setCancelable(true)
            window?.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(0x00000000)
            )
        }
        for (i in 0 until container.childCount) {
            container.getChildAt(i).tag = dialog
        }
        // Initial focus on the first row so the D-pad is immediately live.
        if (container.childCount > 1) {
            container.getChildAt(1).requestFocus()
        }
        dialog.show()
    }

    /**
     * The Menu-key equivalent of the DLNA player settings: what the remote can
     * do to the session it is driving, since a D-pad cannot reach an on-screen
     * button either way.
     */
    private fun showAirPlaySessionMenu() {
        showMenuDialog(
            "投屏控制 · AirPlay",
            listOf(
                "播放 / 暂停" to { sendDacp(DacpClient.CMD_PLAY_PAUSE) },
                "下一首" to { sendDacp(DacpClient.CMD_NEXT) },
                "上一首" to { sendDacp(DacpClient.CMD_PREV) },
                "关闭" to {}
            )
        )
    }

    /** Seeks the DLNA player relative to the current position, clamped to [0, duration]. */
    private fun seekBy(deltaMs: Long) {
        val p = dlnaPlayerView?.player ?: return
        val pos = p.currentPosition.coerceAtLeast(0L)
        val dur = p.duration
        val target = if (dur > 0L) {
            (pos + deltaMs).coerceIn(0L, dur)
        } else {
            (pos + deltaMs).coerceAtLeast(0L)
        }
        p.seekTo(target)
    }

    private fun togglePlayPause() {
        val p = dlnaPlayerView?.player ?: return
        p.playWhenReady = !p.playWhenReady
    }

    /**
     * Player settings reachable from the Menu key — what PlayerView's own
     * gear button offers, but that button is unreachable with a D-pad.
     */
    private fun showPlayerSettings() {
        val pv = dlnaPlayerView ?: return
        val p = pv.player ?: return
        showMenuDialog(
            "播放器设置",
            listOf(
                "音轨 / 字幕" to { showTrackSettings(p) },
                "播放速度" to { showSpeedSettings(p) },
                "画面比例" to { cycleResizeMode(pv) },
                "关闭设置" to {}
            )
        )
    }

    private fun showSpeedSettings(p: androidx.media3.common.Player) {
        val speeds = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        showMenuDialog(
            "播放速度",
            speeds.map { s -> "${s}x" to { p.playbackParameters = p.playbackParameters.withSpeed(s) } }
        )
    }

    private fun cycleResizeMode(pv: PlayerView) {
        val next = when (pv.resizeMode) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT ->
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL ->
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        pv.resizeMode = next
        val name = when (next) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> "拉伸填满"
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "放大裁切"
            else -> "原始比例"
        }
        android.widget.Toast.makeText(this, "画面比例: $name", android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Audio/text track chooser via the Tracks API. Selecting a group override
     * is how an unsupported audio codec (AC3/DTS on a TV box) gets swapped for
     * a track the hardware can decode.
     */
    private fun showTrackSettings(p: androidx.media3.common.Player) {
        val groups = p.currentTracks.groups.filter {
            it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO ||
                it.type == androidx.media3.common.C.TRACK_TYPE_TEXT
        }
        if (groups.isEmpty()) {
            android.widget.Toast.makeText(this, "该媒体没有可选音轨/字幕", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val entries = ArrayList<Pair<String, () -> Unit>>()
        for (group in groups) {
            val kind = if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) "音轨" else "字幕"
            for (i in 0 until group.length) {
                val fmt = group.getTrackFormat(i)
                val lang = fmt.language?.takeIf { it.isNotBlank() && it != "und" } ?: "默认"
                val label = fmt.label ?: fmt.codecs ?: ""
                entries.add("$kind $i · $lang ${label.ifBlank { "" }}".trim() to {
                    p.trackSelectionParameters = p.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(
                            androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i)
                        )
                        .build()
                })
            }
        }
        entries.add("关闭" to {})
        showMenuDialog("音轨 / 字幕", entries)
    }

}
