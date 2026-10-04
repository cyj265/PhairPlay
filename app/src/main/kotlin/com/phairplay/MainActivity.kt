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
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.TextureView
import android.view.ViewTreeObserver
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.phairplay.dlna.DlnaMediaMeta
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
import com.phairplay.ui.TvDialogs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * Whether this Activity is on screen, tracked locally because the service
     * binding is asynchronous — see [onResume].
     */
    private var isUiInForeground = false

    /**
     * Whether this window is really on screen — the honest twin of
     * [isUiInForeground].
     *
     * `onResume` is not enough: bringing the app back from the background
     * often shows a window that has not been laid out yet, so the user sees
     * black. Casting into that state produced sound without a picture, and
     * the only thing that ever fixed it was pressing Back — a key event that
     * made the window draw again. [onWindowFocusChanged] is the callback that
     * says "the window is up and interactive"; the service gets the same
     * answer so it stops trusting a resume that was never painted.
     */
    private var uiWindowFocused = false

    private var currentAirPlayState = ProtocolState.DISABLED
    private var currentPhotoFrame: PhotoFrame? = null
    private var currentNowPlaying: NowPlayingInfo? = null
    private var currentPin: String? = null

    /**
     * Whether the picture deserves the full-screen layer right now.
     *
     * Three cases, all of which must be answered the same way everywhere:
     * a pipeline that is actually playing, an item already loaded (so coming
     * back to the app can resume it), and an item the receiver is *holding*
     * because it is still waiting for a render surface. The third one is the
     * one that used to be missed: while a cast is parked on the surface,
     * `hasDlnaMedia()` and `isDlnaPlaybackLive()` are both still false, so any
     * recompute that only asked those two hid the layer, tore the surface down,
     * and left the sender's next poll to restart the whole dance — which is how
     * a cast ended up with sound and no picture for a dozen seconds
     * (field log 10:22:36, 9900000501: layer shown at :36, frame only at :51).
     *
     * Every "show or hide the playback layer" decision goes through here so a
     * future third state cannot slip past again.
     */
    private fun hasCastToPaint(): Boolean {
        val svc = service ?: return false
        // v98 F3: only a cast that is really producing something may put the
        // playback layer over the home screen. A merely pending item (queued,
        // waiting for a surface) used to qualify, and that is the black
        // rectangle over the home screen with sound coming out of it: the
        // picture was never there but the layer claimed to be showing one.
        // hasDlnaMedia() stays as the fallback so a paused cast keeps its
        // layer and the "back to playback" pill keeps working.
        //
        // v99-②: pending is back, but with a 3 s deadline. Dropping it
        // outright cured the lying layer and left the 1-3 s between SetURI
        // and the first real surface with no way back at all — press Back
        // there and there is nothing to return to. The 3 s deadline covers that wait; a cast that never starts stops holding the layer.
        return svc.hasDlnaMedia() || svc.isDlnaPlaybackLive() || svc.hasRecentPendingCast()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? PhairPlayService.LocalBinder)?.getService()
            isBound = true
            Timber.d("MainActivity: bound to PhairPlayService")

            // Wire the streaming Surface so the service can pass it to VideoDecoder
            service?.setVideoSurfaceProvider { getVideoSurface() }

            // onResume fired before this callback (bindService is async), so the
            // receiver never heard "the UI is on screen" and every cast started
            // by waiting out the full foreground timeout. Re-send it now.
            if (isUiInForeground) {
                service?.onActivityResumed()
                service?.setDlnaUiForeground(true)
            }

            // Show/hide the full-screen overlay for video streams and photos.
            observeOverlayState()

            // Foreground resume after a background-initiated cast: if DLNA is
            // already CONNECTED (the cast started while this Activity was
            // stopped — onStop paused playback and dropped the collectors),
            // immediately restore the full-screen player and resume playback.
            // Only land on the picture when there really is one. CONNECTED
            // survives a sender pause and a dead pipeline, so it used to drop
            // the user into a black player every time the app was opened —
            // "打开 app 后直接进播放器，不是首页".
            // A launch that carried EXTRA_AUTO_FOREGROUND_REASON makes
            // shouldOpenOnPlayer() true on its own, which used to drop the user
            // straight into an empty, black player whenever the process came
            // back up. There has to be something to look at first.
            if (shouldOpenOnPlayer() && hasCastToPaint()) {
                service?.resumeDlnaPlayback()
                showDlnaPlayer()
            } else {
                hideDlnaPlayer()
            }

            // The receiver parks an item whenever a decoder init failed with no
            // surface behind it, and rings this bell instead of retrying (each
            // blind retry burns another MediaCodec and stalls the picture). Only
            // this side can see when a Surface actually shows up.
            //
            // The probe is started unconditionally: the request usually arrives
            // while the playback layer is still hidden, and a probe gated on
            // "is it visible?" would never run — which is how a parked item ended
            // up waiting for a Surface behind a GONE PlayerView indefinitely. The
            // probe itself is harmless when there is nothing to show.
            lifecycleScope.launch {
                service?.dlnaSurfaceProbeTick?.collectLatest {
                    if (it > 0) scheduleDlnaSurfaceProbe()
                }
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

    /** Music card for audio-only DLNA casts (see [setupDlnaMusicCard]). */
    private var dlnaMusicView: View? = null

    /** Nav-panel pill that returns to playback after Back left the player. */
    private var resumePlaybackPill: TextView? = null

    private val dlnaDebugHandler = Handler(Looper.getMainLooper())
    private val dlnaDebugTick = object : Runnable {
        override fun run() {
            updateDlnaDebugText()
            dlnaDebugHandler.postDelayed(this, 500)
        }
    }

    /** Keeps the music card progress and the "back to playback" pill in sync
     *  with the player (2 Hz is plenty and only touches a few views). */
    private val dlnaUiTick = object : Runnable {
        override fun run() {
            updateDlnaMusicCard()
            updateResumePill()
            // A4: keep the screensaver story honest. BUFFERING, stalls and the end
            // of an item all change the playback state without firing a dedicated
            // callback, so the 2 Hz tick is the cheapest correct place for it.
            syncDlnaSessionState()
            // A5: only ever reconsider a window we opened for a cast.
            if (isDlnaPlayerVisible) maybeAutoBackoffForCast()
            dlnaDebugHandler.postDelayed(this, 500)
        }
    }

    /**
     * Waits until the PlayerView has actually created its Surface, then tells
     * the receiver it may prepare.
     *
     * WHY a poll and not a layout callback: `TextureView` reports its size
     * before its `SurfaceTexture` exists, and PlayerView hands the player a
     * video surface only once it does. Preparing in between makes ExoPlayer
     * configure the Amlogic HEVC decoder against a placeholder surface, where
     * the HAL blocks in `configure()` and never returns — the item then dies
     * with `视频轨: 0x0` + `DecoderInitializationException … 超时`, repeatedly,
     * once per sender poll. That is the whole "直播投得上但一直黑屏/卡" loop.
     *
     * `TextureView.isAvailable` is the honest signal: it flips to true only
     * when the SurfaceTexture exists. The receiver keeps its own bounded
     * audio-only fallback, so this probe can stop after
     * [DLNA_SURFACE_PROBE_MS] without leaving a cast stuck.
     */
    private val dlnaSurfaceProbe = object : Runnable {
        override fun run() {
            if (hasRealRenderSurface()) {
                service?.markDlnaSurfaceReady()
                com.phairplay.util.DebugLog.log(
                    "UI", "渲染面已可用 → 通知接收层可以 prepare"
                )
                return
            }
            // No Surface yet. The layer being hidden is the usual reason, and
            // that is our own doing: a parked item sits in ADVERTISING, which
            // never re-triggers showDlnaPlayer(). So re-show it here — otherwise
            // this probe is polling a GONE view forever, which is exactly how
            // the field log ended up with "cooldown elapsed" notices that were
            // never followed by a start.
            //
            // Unconditional, not gated on hasDlnaMedia(): the receiver rings this
            // bell precisely when it has a parked item, and clearing that item's
            // metadata is what stopped the re-show from happening (the log ends
            // with "等待渲染面 4s 未就绪（播放层可见=false）" and nothing after).
            if (!isDlnaPlayerVisible) {
                showDlnaPlayer()
                if (hasRealRenderSurface()) return
            }
            if (SystemClock.uptimeMillis() - dlnaSurfaceProbeStartMs < DLNA_SURFACE_PROBE_MS) {
                dlnaDebugHandler.postDelayed(this, DLNA_SURFACE_PROBE_INTERVAL_MS)
            } else {
                // Out of time: stay silent and let the receiver's own bounded
                // audio-only fallback start the item, which is the old behaviour.
                com.phairplay.util.DebugLog.log(
                    "UI", "等待渲染面 ${DLNA_SURFACE_PROBE_MS / 1000}s 未就绪（播放层可见=$isDlnaPlayerVisible）"
                )
            }
        }
    }

    private var dlnaSurfaceProbeStartMs = 0L

    /**
     * True when the PlayerView's render target really has a Surface.
     *
     * `PlayerView.getVideoSurfaceView()` is the public accessor for the inner
     * SurfaceView/TextureView (which one depends on `app:surface_type`). A
     * TextureView counts as ready only when `isAvailable` — that is exactly the
     * "SurfaceTexture has been created" moment. A laid-out-but-unavailable
     * TextureView is the state that used to hang the decoder.
     */
    private fun hasRealRenderSurface(): Boolean {
        val target = dlnaPlayerView?.videoSurfaceView ?: return false
        return when (target) {
            is TextureView -> target.isAvailable
            is SurfaceView -> target.holder?.surface?.isValid == true
            else -> target.width > 0 && target.height > 0
        }
    }

    private fun scheduleDlnaSurfaceProbe() {
        dlnaDebugHandler.removeCallbacks(dlnaSurfaceProbe)
        dlnaSurfaceProbeStartMs = SystemClock.uptimeMillis()
        dlnaDebugHandler.postDelayed(dlnaSurfaceProbe, DLNA_SURFACE_PROBE_INTERVAL_MS)
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

    /**
     * The service auto-launched this Activity because a sender pushed media
     * while the UI was in the background. Without handling it here the launch
     * would just resume the app on whatever screen was last open (Settings,
     * Home, …) instead of the picture the user actually asked for.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
            if (intent.getStringExtra(PhairPlayService.EXTRA_AUTO_FOREGROUND_REASON) != null) {
                service?.resumeDlnaPlayback()
                if (hasCastToPaint()) showDlnaPlayer()
            }
    }

    /**
     * A5: any key, touch or pointer counts as the user being present. Without
     * this the auto-backoff would happily move a screen the person is watching
     * back to the background.
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        userInteractedThisForeground = true
    }

    override fun onResume() {
        super.onResume()
        // A fresh foreground window starts with nothing remembered: both flags
        // describe what happened *since the screen became visible*, so they are
        // meaningless across a pause/resume cycle.
        userInteractedThisForeground = false
        autoOpenedForCast = false
        autoBackedOffForCast = false
        // Report visibility before resuming: a cast that starts right now must
        // not trigger another auto-foreground launch on top of us. This is the
        // signal the receiver waits for — the media is only prepared once the
        // Activity confirms it is really on screen.
        //
        // Remember it locally as well: bindService is asynchronous, so on a cold
        // start `service` is still null here and the call would be dropped. The
        // flag is then re-sent from onServiceConnected, which is the only reason
        // a first cast after launching the app used to sit through the full
        // 10 s "UI not in foreground" wait before starting — and it started with
        // a perfectly good picture, because the PlayerView was there all along.
        isUiInForeground = true
        // Opened from the home screen rather than by our own auto-foreground
        // launch: the user walked up to the TV and started the app by hand. A
        // dismissal recorded when they wandered off with Back no longer
        // describes what they want, and keeping it would strand the next cast
        // on sound only. An auto-foreground launch is exempt — that one is
        // *our* intent (and the picture belongs to it), so it does not clear
        // anything.
        if (service?.isAutoForegroundLaunch(intent) != true) {
            service?.clearDlnaDismissed()
        }
        service?.onActivityResumed()
        service?.setDlnaUiForeground(true)
    }

    /**
     * The window is up and interactive — or has stopped being.
     *
     * This is the callback that separates "the app is resumed" from "the user
     * can actually see it". A black warm resume fires `onResume` and nothing
     * else, which is why a cast into it made noise behind a black rectangle
     * until somebody pressed a key.
     *
     * On regained focus the whole visibility question is re-evaluated: the
     * surface may have been rebuilt while we were invisible (so the receiver
     * has to be told it may prepare again), and a cast that started during the
     * black screen is owed its picture. On lost focus the window is behind
     * something else (screensaver, another app) and the service must stop
     * treating the screen as ours.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (uiWindowFocused == hasFocus) return
        uiWindowFocused = hasFocus
        service?.setUiWindowFocused(hasFocus)
        if (!hasFocus) return
        // Re-evaluate, don't assume. showDlnaPlayer/hideDlnaPlayer are both
        // idempotent, so asking the question is safe at 2 Hz-worth of focus
        // churn — and it is the only thing that ever repainted the black
        // window after a cast came in behind it.
        val svc = service
        if (svc == null) {
            com.phairplay.util.DebugLog.log("UI", "窗口重新获得焦点，但服务未绑定 → 等 onServiceConnected 重算")
            return
        }
        com.phairplay.util.DebugLog.log(
            "UI",
            "窗口已可见 → 重算播放层（媒体=${svc.hasDlnaMedia()} 播放中=${svc.isDlnaPlaybackLive()} 待渲染=${svc.hasPendingCast()}）"
        )
        if (svc.isCastDismissed(svc.dlnaCurrentUri())) {
            com.phairplay.util.DebugLog.log("UI", "窗口重显：该投屏已被用户关闭 → 保持首页")
            hideDlnaPlayer()
        } else if (hasCastToPaint()) {
            service?.resumeDlnaPlayback()
            showDlnaPlayer()
        } else {
            hideDlnaPlayer()
        }
        // Only for a picture that really came back. Polling a hidden layer
        // would drag an empty player into view (see [dlnaSurfaceProbe]).
        if (isDlnaPlayerVisible) scheduleDlnaSurfaceProbe()
    }

    override fun onPause() {
        // Keep the flag accurate even if the pause race with a cast start.
        isUiInForeground = false
        service?.onActivityPaused()
        service?.setDlnaUiForeground(false)
        service?.setUiWindowFocused(false)
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        // Bind so we can observe StateFlows and supply the video Surface
        val intent = Intent(this, PhairPlayService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        service?.resumeDlnaPlayback()
        // Keep the "back to playback" affordances in sync while the UI is up
        // (media can start, end, or go audio-only without the player showing).
        dlnaDebugHandler.post(dlnaUiTick)
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            // Leaving the app for good ends the DLNA session — AirPlay-aligned:
            // closing the receiver UI stops the media (the receiver service
            // itself keeps running and stays discoverable). The Back-key path
            // already called stopDlnaPlayback(); this covers every other way
            // the Activity can finish.
            // v98: real user action — the only kind that may record a dismissal.
            service?.stopDlnaPlayback(true)
        } else {
            // Pause DLNA playback before the Surface is destroyed so the
            // MediaCodec renderer never writes into a dead Surface (avoids
            // DECODING_FAILED on background/foreground switches).
            service?.pauseDlnaPlayback()
        }
        // Clear surface reference before unbinding to avoid holding a dead Surface
        service?.setVideoSurfaceProvider { null }
        dlnaDebugHandler.removeCallbacks(dlnaUiTick)
        // No window, no MediaSession: the screensaver must be allowed back.
        uiWindowFocused = false
        service?.setUiWindowFocused(false)
        releaseDlnaMediaSession()
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

        setupDlnaMusicCard()
        setupResumePill()
    }

    // ─── Audio-only (music) DLNA card ────────────────────────────────────

    /** Album art view of [dlnaMusicView]; loaded asynchronously when set. */
    private var musicArtView: android.widget.ImageView? = null
    private var musicTitleView: TextView? = null
    private var musicArtistView: TextView? = null
    private var musicProgressView: android.widget.ProgressBar? = null
    private var musicTimeView: TextView? = null

    /**
     * A music player card for audio-only casts (网易云/QQ音乐 投屏音乐).
     * Rendering an audio item into a video surface gives exactly what the user
     * reported: a black screen with sound. The card is purely decorative — it
     * never takes focus, so the D-pad keeps driving the (bound) player.
     */
    private fun setupDlnaMusicCard() {
        val card = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xF2101010.toInt())
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            val p = (32 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
            visibility = View.GONE
            // Must not steal the D-pad from the player underneath.
            isFocusable = false
            descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        musicArtView = android.widget.ImageView(this).apply {
            val side = (220 * resources.displayMetrics.density).toInt()
            layoutParams = android.widget.LinearLayout.LayoutParams(side, side)
            setImageResource(android.R.drawable.ic_media_play)
            imageTintList = android.content.res.ColorStateList.valueOf(0xFF8A8A8A.toInt())
            val v = (16 * resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, v)
        }
        musicTitleView = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 30f
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        musicArtistView = TextView(this).apply {
            setTextColor(0xFFBBBBBB.toInt())
            textSize = 20f
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            val v = (10 * resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, v)
        }
        musicProgressView = android.widget.ProgressBar(
            this, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 1000
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (6 * resources.displayMetrics.density).toInt()
            ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
        }
        musicTimeView = TextView(this).apply {
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 18f
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            val v = (6 * resources.displayMetrics.density).toInt()
            setPadding(0, v, 0, v)
        }
        val hint = TextView(this).apply {
            text = "OK 播放/暂停 · ← → 快退/快进 · 菜单 设置 · 返回 回到首页"
            setTextColor(0xFF888888.toInt())
            textSize = 16f
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        card.addView(musicArtView)
        card.addView(musicTitleView)
        card.addView(musicArtistView)
        card.addView(musicProgressView)
        card.addView(musicTimeView)
        card.addView(hint)
        dlnaMusicView = card
        streamingContainer.addView(
            card,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = android.view.Gravity.CENTER }
        )
    }

    /** Shows/updates the music card for the current audio-only item. */
    private fun updateDlnaMusicCard() {
        val card = dlnaMusicView ?: return
        // Belt and braces: the receiver retracts [DlnaMediaMeta.audioOnly] once a
        // real frame lands, but a card that covers the whole player must not be
        // able to keep an item "audio only" on the strength of a stale flag —
        // the player's own video size is the one thing that cannot lie.
        val pvVideo = dlnaPlayerView?.player?.videoSize
        val hasVideo = pvVideo != null && pvVideo.width > 0 && pvVideo.height > 0
        val visible = isDlnaPlayerVisible && DlnaMediaMeta.audioOnly && !hasVideo
        card.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        card.bringToFront()
        musicTitleView?.text = DlnaMediaMeta.title ?: "正在播放"
        musicArtistView?.text = DlnaMediaMeta.artist ?: "来自 DLNA 投屏"
        val p = dlnaPlayerView?.player
        val pos = (p?.currentPosition ?: 0L).coerceAtLeast(0L)
        val dur = p?.duration?.takeIf { it > 0L } ?: 0L
        musicProgressView?.progress = if (dur > 0L) (pos * 1000L / dur).toInt() else 0
        musicTimeView?.text = "${fmtTime(pos)} / ${if (dur > 0L) fmtTime(dur) else "--:--"}" +
            if (p?.playWhenReady == true) "" else "  (已暂停)"
        loadAlbumArt(DlnaMediaMeta.albumArtUri)
    }

    private fun fmtTime(ms: Long): String {
        val total = ms / 1000L
        return String.format("%02d:%02d", total / 60, total % 60)
    }

    /** Fetches the cover art once per URI (best effort; never blocks the UI). */
    private fun loadAlbumArt(uri: String?) {
        val view = musicArtView ?: return
        if (uri.isNullOrBlank()) return
        if (view.tag == uri) return
        view.tag = uri
        Thread {
            try {
                val conn = java.net.URL(uri).openConnection()
                conn.connectTimeout = 5000
                conn.readTimeout = 8000
                val bmp = android.graphics.BitmapFactory.decodeStream(conn.getInputStream())
                if (bmp != null) {
                    runOnUiThread {
                        view.setImageBitmap(bmp)
                        view.imageTintList = null
                    }
                }
            } catch (t: Throwable) {
                // A missing/expired cover is not worth failing playback over.
            }
        }.start()
    }

    // ─── "Back to playback" entry point ──────────────────────────────────

    /** True while an item is loaded on the DLNA renderer, regardless of UI state. */
    private fun hasDlnaMedia(): Boolean =
        service?.dlnaPlayer != null && DlnaMediaMeta.hasMedia

    /**
     * Whether opening (or re-binding) the app should land on the player.
     *
     * Two things qualify: the app was launched *because* a cast arrived (the
     * service put [PhairPlayService.EXTRA_AUTO_FOREGROUND_REASON] in the
     * intent), or the renderer is genuinely playing right now.
     *
     * `dlnaState == CONNECTED` deliberately does not qualify on its own — it
     * stays true while the sender pauses and while the pipeline is dead, which
     * is exactly how a plain "open the app" ended up inside a black player
     * instead of on the home screen.
     */
    /**
     * One automatic "open the player" per cast.
     *
     * WHY A QUOTA: the sender re-sends Play every 10-30 s and the player keeps
     * changing state, so without a limit every buffer blip drags the user back
     * into full-screen playback right after they pressed Back to look at the
     * home screen — the long-standing "后台被 poll 拉回前台" complaint.
     *
     * Consumed by USER GESTURES ONLY, never by [hideDlnaPlayer] itself: that
     * also runs automatically when a cast ends, and a control point that sends
     * Stop followed by Play would then burn the quota on its own, leaving the
     * legitimate restart stuck behind the home screen.
     */
    private var autoOpenUsedForCast = false

    /**
     * v86 / A4 from the fork audit: [FLAG_KEEP_SCREEN_ON] alone did NOT stop this
     * box's screensaver from kicking in mid-cast. The OEM dream logic does not
     * just look at the window flag — it asks MediaSessionManager whether *someone*
     * is playing, and with no MediaSession on screen the TV still dims, the
     * Surface goes away, and the decoder keeps writing into a dead buffer queue.
     *
     * So the flag stays AND we advertise an active MediaSession while the player
     * runs. [dlnaSessionPlaying] is an edge cache: [setPlaybackState] is not
     * free and the UI tick runs at 2 Hz, so only real transitions are published.
     */
    private var dlnaMediaSession: android.media.session.MediaSession? = null
    private var dlnaSessionPlaying = false

    /**
     * v86 / A5 from the fork audit: remembers whether *this* foreground window was
     * opened for the user or opened for a cast. [onUserInteraction] sets it, so
     * "user opened the app themselves" and "the system pulled it up for a sender"
     * never get confused — that confusion is what would make us shove a screen the
     * user is actively looking at back to the background.
     */
    private var userInteractedThisForeground = false
    private var autoOpenedForCast = false
    private var autoBackedOffForCast = false

    /** Throttle for the "showDlnaPlayer refused" line, which fires every tick. */
    private var lastShowBlockedMs = 0L

    /**
     * Opens the player if a cast deserves it — and can be called as often as
     * the evidence changes.
     *
     * The v82 bug was deciding this exactly once, at the instant
     * `ProtocolState.CONNECTED` was emitted: at that moment the item has only
     * just been handed to ExoPlayer (`BUFFERING playWhenReady=false`, or even
     * still idle), so "is it playing?" is false and the player stayed hidden
     * for the rest of the cast. `hasDlnaMedia()` is the line that fixes it:
     * an item loaded for a cast the user just started IS worth showing, even
     * before the first frame.
     */
    private fun tryOpenPlayerForCast() {
        val svc = service ?: return
        if (autoOpenUsedForCast) return
        if (svc.dlnaState.value != ProtocolState.CONNECTED) return
        // A sender that pushed Play and is now waiting on the surface counts as
        // "there is a picture owed to the user". Leaving it out here left the
        // item parked: the surface timeout was reset by the next poll before it
        // could ever fire, so the layer stayed hidden and the cast played on
        // silently — the reported "sound only, no picture" case, as seen in the
        // 10:08:13 field log, where sixteen seconds passed with no frame.
        if (!hasCastToPaint()) return
        autoOpenUsedForCast = true
        autoOpenedForCast = true
        showDlnaPlayer()
        com.phairplay.util.DebugLog.log("UI", "自动打开播放层（本轮投屏首次）")
    }

    /** Creates the screensaver-blocking MediaSession, once, on the UI thread. */
    private fun ensureDlnaMediaSession() {
        if (dlnaMediaSession != null) return
        runCatching {
            dlnaMediaSession = android.media.session.MediaSession(this, "PhairPlay DLNA")
                .apply { isActive = true }
        }.onFailure { err ->
            // Losing the session only costs us the screensaver protection;
            // FLAG_KEEP_SCREEN_ON and everything else keep working.
            com.phairplay.util.DebugLog.log("UI", "MediaSession 创建失败（屏保防护降级）：${err.message}")
        }
        syncDlnaSessionState()
    }

    private fun releaseDlnaMediaSession() {
        runCatching { dlnaMediaSession?.release() }
        dlnaMediaSession = null
        dlnaSessionPlaying = false
    }

    /**
     * Publishes play/pause to the system so the TV's own screensaver logic sees
     * an active playback. Called from the 2 Hz tick: cheap, and it also covers
     * the states nobody wires an explicit callback for (buffering, stall, end).
     */
    private fun syncDlnaSessionState() {
        val session = dlnaMediaSession ?: return
        val player = service?.dlnaPlayer
        val playing = player?.playWhenReady == true
        if (playing == dlnaSessionPlaying) return
        dlnaSessionPlaying = playing
        runCatching {
            session.setPlaybackState(
                android.media.session.PlaybackState.Builder()
                    .setState(
                        if (playing) android.media.session.PlaybackState.STATE_PLAYING
                        else android.media.session.PlaybackState.STATE_PAUSED,
                        player?.currentPosition ?: 0L,
                        1f
                    )
                    .build()
            )
        }
    }

    /**
     * v86 / A5: puts the app back behind where it came from once the auto-opened
     * player is empty and the user never touched a key.
     *
     * This is the other half of the auto-foreground work — without it a sender
     * that stops leaves PhairPlay sitting full-screen in front of whatever the
     * user was watching before the cast arrived. Three guards, all deliberate:
     * only auto-opened windows, only with no user interaction this window, and
     * only when the receiver has no media left. Anything else is a screen the
     * person is actually using.
     */
    private fun maybeAutoBackoffForCast() {
        if (!autoOpenedForCast || autoBackedOffForCast) return
        if (userInteractedThisForeground) return
        // The item may still be waiting for its render surface rather than
        // finished: the metadata is only set once [runStart] runs, so an item
        // parked on the surface gate would look like an over-and-done session
        // and the app would slide back behind the launcher with the picture
        // still owed.
        if (hasCastToPaint()) return
        autoBackedOffForCast = true
        // Attach to nothing, move the whole task behind the launcher stack.
        moveTaskToBack(true)
        com.phairplay.util.DebugLog.log(
            "UI",
            "自动拉起的播放层会话已结束且用户未操作 → 自动退回后台（接收服务保持运行）"
        )
    }

    private fun shouldOpenOnPlayer(): Boolean {
        if (intent?.getStringExtra(PhairPlayService.EXTRA_AUTO_FOREGROUND_REASON) != null) {
            return true
        }
        // An item waiting on its surface comes from the same sender promise as
        // one that already plays; the caller pairs this with "there is
        // something to paint" so the answer only ever admits a real cast.
        return service?.isDlnaPlaybackLive() == true || service?.hasPendingCast() == true
    }

    /**
     * The user asked for the picture back, so every gate that keeps it away
     * goes at once: the "user closed this cast" flag in the service, and the
     * local auto-open quota.
     *
     * Both must be cleared together — otherwise the manual `showDlnaPlayer()`
     * works but the semantic is wrong, and the next state tick behaves as if
     * the user had never asked.
     */
    private fun reopenDlnaByUser() {
        service?.clearDlnaDismissed()
        autoOpenUsedForCast = false
        showDlnaPlayer()
    }

    /**
     * v100-④ — say something when the picture is deliberately not being shown.
     *
     * The sender re-pushes Set+Play while it waits, and after a Back that is
     * a real instruction; the item therefore starts and keeps playing while
     * the playback layer stays down (dismissal closes the foreground path
     * only — see the F1 note in DlnaReceiver). That is the right behaviour and
     * a terrible experience: from the sofa it looks like the tap did nothing.
     *
     * One line, once per item, pointing at the entry that already exists:
     * [reopenDlnaByUser] is what the "回播放" pill calls, so the promise in the
     * toast is a promise the UI already keeps.
     */
    private fun maybeHintPlaybackBehindLayer() {
        val svc = service ?: return
        val uri = svc.dlnaCurrentUri() ?: return
        if (!svc.isDlnaPlaybackLive()) return
        if (!svc.isCastDismissed(uri)) return
        val now = System.currentTimeMillis()
        if (uri == lastHiddenPlaybackHintUri && now - lastHiddenPlaybackHintAtMs < 8000) return
        lastHiddenPlaybackHintUri = uri
        lastHiddenPlaybackHintAtMs = now
        com.phairplay.util.DebugLog.log("UI", "dismissal 生效中但已在播放 → 提示一次「点回播放查看」")
        showTvToast("已在后台播放，点「回播放」查看")
    }

    private var lastHiddenPlaybackHintUri: String? = null
    private var lastHiddenPlaybackHintAtMs = 0L

    /** Re-opens full-screen playback — how a TV remote gets back after Back. */
    fun returnToDlnaPlayback() {
        if (hasDlnaMedia()) {
            reopenDlnaByUser()
        } else {
            android.widget.Toast.makeText(this, "当前没有正在播放的内容", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Pill shown in the nav panel while media plays behind the app UI. */
    private fun setupResumePill() {
        val panel = findViewById<android.view.ViewGroup>(R.id.nav_panel) ?: return
        val pill = TextView(this).apply {
            text = "▶  返回播放"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 17f
            gravity = android.view.Gravity.CENTER_VERTICAL
            val h = (14 * resources.displayMetrics.density).toInt()
            setPadding(h, h, h, h)
            setBackgroundColor(0xFF1F6FEB.toInt())
            visibility = View.GONE
            isFocusable = true
            nextFocusUpId = R.id.nav_item_settings
            setOnClickListener { returnToDlnaPlayback() }
            setOnFocusChangeListener { _, f ->
                setBackgroundColor(if (f) 0xFF3A8BFF.toInt() else 0xFF1F6FEB.toInt())
            }
        }
        val lp = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = (16 * resources.displayMetrics.density).toInt() }
        panel.addView(pill, lp)
        resumePlaybackPill = pill
    }

    /** Pill visible only when media is playing but the player is not on screen. */
    private fun updateResumePill() {
        val pill = resumePlaybackPill ?: return
        // The old guard asked only for media metadata, which the user saw as "no
        // button at all": a cast that was already sounding while the layer was
        // hidden (or one still waiting for its surface) has a picture owed to it
        // but no way back except re-casting. Any of the three states in
        // [hasCastToPaint] means there is something to look at — the same
        // three-state question the show/hide decision asks, so the pill can
        // never disagree with the layer.
        pill.visibility =
            if (!isDlnaPlayerVisible && hasCastToPaint()) View.VISIBLE else View.GONE
    }

    /**
     * Sets up click listeners for the navigation panel items.
     * Also updates the visual selected state (text color) of the active item.
     */
    private fun setupNavigation() {
        navItemHome.setOnClickListener {
            if (selectedNavIndex != 0) {
                navigateTo(HomeFragment(), navItemHome)
            } else if (hasDlnaMedia()) {
                // Already on home and DLNA still has an item loaded underneath
                // (the remote pressed Back out of the player). Re-selecting
                // Home returns to the picture — otherwise the only way back
                // would be re-casting from the phone.
                //
                // The old check was `dlnaState == CONNECTED`, which is false
                // the moment the sender pauses (PAUSED_PLAYBACK), so Home
                // looked broken exactly when the user most needed to go back.
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

        /** How often the render-surface probe re-checks the player. */
        private const val DLNA_SURFACE_PROBE_INTERVAL_MS = 120L

        /** Minimum gap between two "showDlnaPlayer refused" diagnostics. */
        private const val SHOW_BLOCKED_LOG_INTERVAL_MS = 20_000L

        /**
         * How long the probe looks for a Surface before giving up and letting
         * the receiver start the item audio-only. Generous on purpose: a
         * TextureView that was just revealed needs a frame or two, and giving
         * up early is exactly what this probe exists to prevent.
         */
        private const val DLNA_SURFACE_PROBE_MS = 4_000L
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
                if (state != ProtocolState.CONNECTED) {
                    hideDlnaPlayer()
                } else {
                    // Never let one emission settle this. CONNECTED arrives
                    // before the player has even buffered; the picture's
                    // arrival is reported by [tryOpenPlayerForCast].
                    tryOpenPlayerForCast()
                }
            }
        }
        // A new cast is the strongest possible reason to show the player — and
        // it has to open it UNCONDITIONALLY, which is the whole difference
        // between a 1 s and a 10 s start.
        //
        // [tryOpenPlayerForCast] asks "is it playing right now?", and on a cold
        // cast that question is always no: the item has not reached ExoPlayer
        // yet, so isDlnaPlaybackLive() is false and hasDlnaMedia() is false. The
        // layer therefore stayed hidden, the surface never became real, and the
        // receiver sat out its entire 10 s foreground wait before the fallback
        // timer force-opened it. A fresh uri from the sender IS the evidence we
        // were waiting for — the user just picked something on their phone.
        //
        // Still safe against the "user closed this cast" complaint: a dismissed
        // cast stops emitting (same uri never re-arms) and the service clears
        // the dismissal only for a genuinely new uri.
        lifecycleScope.launch {
            svc.dlnaCastArrived.collectLatest {
                // The service only postpones an auto-foreground launch for a
                // dismissed cast; showing the player is a UI decision made
                // here, and this collector used to take it unconditionally —
                // which is how the home screen pulled the user back into the
                // picture a few seconds after they pressed Back.
                if (svc.isCastDismissed(svc.dlnaCurrentUri())) {
                    com.phairplay.util.DebugLog.log(
                        "UI", "新投屏到达，但用户已关闭该投屏 → 不抢首页"
                    )
                    return@collectLatest
                }
                // Spent only once the cast actually gets the picture. A
                // dismissal is spent on nothing, so closing one cast and then
                // casting another still leaves the second one free to open the
                // player instead of waiting for the user to reach for it.
                autoOpenUsedForCast = true
                autoOpenedForCast = true
                showDlnaPlayer()
                com.phairplay.util.DebugLog.log("UI", "新投屏到达 → 立即显示播放层")
            }
        }
        // …and every change inside the player re-opens the question, which is
        // what v82 could not do: it evaluated the same question once, too
        // early, and then never again.
        lifecycleScope.launch {
            svc.dlnaPlaybackTick.collectLatest {
                tryOpenPlayerForCast()
                maybeHintPlaybackBehindLayer()
            }
        }
        // A decoder hint ("本盒 HEVC 硬解初始化失败") is worth interrupting a
        // black screen for: it is the difference between "the box is broken"
        // and silence. Toast, not the card — the user is looking at the
        // playback layer, not the home screen, when this fires.
        lifecycleScope.launch {
            svc.dlnaHint.collectLatest { hint ->
                if (!hint.isNullOrBlank()) {
                    showTvToast(hint)
                }
            }
        }
    }

    /**
     * A Toast the user can actually read from a sofa.
     *
     * WHY hand-drawn: the stock Toast is ~14sp pinned to the bottom edge of a
     * 1080p screen — invisible from three metres away, and this hint is the
     * only thing standing between the user and a silent black screen. Drawn
     * with explicit colours/sizes for the same reason as TvDialogs: no theme
     * can make the text disappear.
     *
     * A custom view is allowed here because this runs from the foreground
     * Activity; Android 11+ blocks custom toasts posted from the background
     * only. Kept as an overlay-style Toast rather than a dialog so it never
     * steals the D-pad from the player.
     */
    @Suppress("DEPRECATION") // Toast.setView: still honoured for foreground toasts
    private fun showTvToast(message: String) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val body = TextView(this).apply {
            text = message
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xF2101010.toInt())
        }
        Toast(this).apply {
            view = body
            duration = Toast.LENGTH_LONG
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, (72 * density).toInt())
            show()
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
        // An empty player is a black rectangle, and an empty player was
        // exactly what a warm resume dropped the user into: the app came back
        // (a launch still carrying EXTRA_AUTO_FOREGROUND_REASON, or a rebind
        // after a background trip) with nothing loaded, showed the surface,
        // and the only way out of the black screen was Back. No uri on the
        // receiver means there is nothing to paint, so stay on the home screen.
        val svc = service
        // A pending cast counts as "something to paint". It is the state the
        // sender is waiting on: it pushed Play, the renderer is holding the
        // item, and only the layer makes the surface that lets the item start.
        // Refusing on the strength of [dlnaCurrentUri] alone deadlocked the
        // three steps that depend on each other (reveal → surface → start) and
        // left the box audible but sightless.
        if (svc != null &&
            svc.dlnaCurrentUri() == null &&
            svc.isDlnaPlaybackLive() != true &&
            !svc.hasPendingCast()
        ) {
            // The collectors call this every tick, so an unthrottled line here
            // drowns the diagnostic port within a second.
            val now = System.currentTimeMillis()
            if (now - lastShowBlockedMs > SHOW_BLOCKED_LOG_INTERVAL_MS) {
                lastShowBlockedMs = now
                com.phairplay.util.DebugLog.log(
                    "UI", "showDlnaPlayer 被挡下：手上既没有 uri 也没有待播（空播放器=黑屏）"
                )
            }
            hideDlnaPlayer()
            return
        }
        // A cast the user closed must not climb back into the picture. The
        // [dlnaCastArrived] collector checks this too, but that flow is
        // one-shot: a sender that keeps polling the same uri re-arms
        // `hasPendingCast()` on its own, and the recompute triggered by a
        // window-focus change (or the 2 Hz tick) reached this method through a
        // completely different door. Field log 11:03:17: the layer was shown,
        // a picture rendered, and it was still the user's own dismissal that
        // was holding the home screen — the door the dismissal never guarded.
        if (svc != null && svc.isCastDismissed(svc.dlnaCurrentUri())) {
            val now = System.currentTimeMillis()
            if (now - lastShowBlockedMs > SHOW_BLOCKED_LOG_INTERVAL_MS) {
                lastShowBlockedMs = now
                com.phairplay.util.DebugLog.log(
                    "UI", "showDlnaPlayer 被挡下：该投屏已被用户关闭（dismissal 闸门）"
                )
            }
            hideDlnaPlayer()
            return
        }
        // Re-binding the player tears the TextureView surface down and rebuilds
        // it, and a cast regularly asks for this twice in the same second (the
        // auto-foreground launch and the state change both reach us). One
        // binding per show is enough — the second one is what made the picture
        // stutter right after the cast started.
        if (pv.visibility == View.VISIBLE && pv.player != null) {
            updateDlnaMusicCard()
            updateResumePill()
            pv.isFocusable = true
            pv.requestFocus()
            return
        }
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
        // The player must not prepare against a surface that does not exist
        // yet. On this box MediaCodec then blocks forever inside configure()
        // and every poll the sender makes burns another decoder.
        //
        // A sized View is NOT that signal. addOnPreDrawListener only proves
        // width/height > 0; a TextureView's SurfaceTexture is usually still
        // being created at that moment, so the PlayerView has not handed
        // anything to the player yet and ExoPlayer configures against a
        // placeholder surface. Field evidence: the cast that started in the
        // same second as the layer was shown logged "video track 0x0" and
        // failed three times, while a cast started on that same surface 40 s
        // later ("UI not in foreground" and all) played at 1920x1080 on the
        // first try. So poll for the real thing instead — [dlnaSurfaceProbe].
        scheduleDlnaSurfaceProbe()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The window flag stops the CPU sleeping; the MediaSession stops the
        // TV's own screensaver. Both are needed — see [dlnaMediaSession].
        ensureDlnaMediaSession()
        updateDlnaMusicCard()
        updateResumePill()
        dlnaDebugHandler.removeCallbacks(dlnaUiTick)
        dlnaDebugHandler.post(dlnaUiTick)
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
        // The texture this player was writing into is gone: tell the receiver
        // so the next Play is not armed against a dead surface (and so the
        // codec verdicts taken on it are not carried over).
        // The probe must die with it, or it would report "surface ready" for
        // a view that is on its way out.
        dlnaDebugHandler.removeCallbacks(dlnaSurfaceProbe)
        service?.markDlnaSurfaceGone()
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
        dlnaMusicView?.visibility = View.GONE
        updateResumePill()
        // Nothing is playing behind the home screen any more: handing the screen
        // back also releases the MediaSession, so the TV may idle again.
        releaseDlnaMediaSession()
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
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Back is handled OUTSIDE the overlay gate. The old code put the
            // "leaving the app ends playback" branch inside `if (dlna || airPlay)`,
            // which is false once the player is hidden — so exiting the app never
            // reached stopDlnaPlayback, the item merely got paused in onStop, and
            // re-entering the app resumed it. That is the "退出 App 停止媒体没有
            // 生效" report.
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.repeatCount == 0) {
                if (dlna) {
                    // Back inside full-screen playback ends the cast — picture
                    // *and* sound.
                    //
                    // WHY A STOP AND NOT JUST A HIDE: hiding the layer left the
                    // renderer playing behind the home screen, so the box went on
                    // speaking while the user stared at a picture-less app — the
                    // "只有声音，图像在首页" report. Hiding is not an answer the
                    // user can act on; silence is. The receiver service keeps
                    // running and stays discoverable, so the sender can still
                    // push again, and [PhairPlayService.stopDlnaPlayback]
                    // records the dismissal that keeps that next push out.
                    //
                    // This is a user gesture, so it spends the auto-open quota:
                    // having asked for the home screen, they must not be dragged
                    // back by the sender's next poll.
                    autoOpenUsedForCast = true
                    // v98: real user action — the only kind that may record a dismissal.
                    service?.stopDlnaPlayback(true)
                    val target = if (selectedNavIndex == 0) navItemHome else navItemSettings
                    target.requestFocus()
                    return true
                }
                // Back out of the app UI: leaving must END playback, not pause it.
                // Otherwise the box keeps sounding an app the user closed, and
                // re-opening PhairPlay resumes the old item as if nothing happened.
                // (No-op for AirPlay sessions — those are driven by the sender.)
                // …
                // …but not while the window is a black rectangle nobody can see.
                // Backing out of an app that was never on screen is not the
                // user rejecting this cast, and writing that down for two
                // minutes is what made the next cast of the same channel silent.
                if (uiWindowFocused) {
                    // v98: real user action — the only kind that may record a dismissal.
                    service?.stopDlnaPlayback(true)
                } else {
                    com.phairplay.util.DebugLog.log(
                        "UI", "Back：窗口无焦点（黑屏）→ 不判定为用户关闭投屏"
                    )
                }
                return super.dispatchKeyEvent(event)
            }
            if (dlna || airPlay) {
                when (event.keyCode) {
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
     * TV-safe menu dialog — moved into [TvDialogs] so the Settings screen's
     * display-name and debug-info dialogs share the exact same hand-drawn
     * style (the leanback AlertDialog rendered invisible text on the N1).
     */
    private fun showMenuDialog(title: String, entries: List<Pair<String, () -> Unit>>) {
        com.phairplay.ui.TvDialogs.menu(this, title, entries)
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
