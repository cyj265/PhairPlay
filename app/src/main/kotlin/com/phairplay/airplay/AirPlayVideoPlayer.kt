package com.phairplay.airplay

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.phairplay.util.Logger

/** Snapshot of URL-video playback for `GET /playback-info`. */
data class PlaybackInfo(
    val durationSec: Double,
    val positionSec: Double,
    val rate: Double,        // 0.0 = paused, 1.0 = playing
    val readyToPlay: Boolean,
)

/**
 * AirPlayVideoPlayer — plays an AirPlay "video URL" stream (the non-mirroring mode: a sender app
 * like Safari or a TV app says "AirPlay this video" and POSTs a URL via `/play`). We hand the URL to
 * Android's [MediaPlayer] and render to the same streaming [Surface] the mirror decoder uses.
 *
 * Distinct from screen mirroring (H.264 over a data stream): here the TV fetches and plays the media
 * itself, and the sender only drives transport (`/rate`, `/scrub`, `/stop`) + polls `/playback-info`.
 *
 * Methods are `@Synchronized` because RTSP control verbs arrive on the RTSP thread while MediaPlayer
 * callbacks fire on its own thread.
 */
class AirPlayVideoPlayer(
    private val surfaceProvider: () -> Surface?,
    private val onEnded: () -> Unit = {},
    private val surfaceWaitTimeoutMs: Long = DEFAULT_SURFACE_WAIT_MS,
) {
    private var mp: MediaPlayer? = null
    @Volatile private var prepared = false
    @Volatile private var startFraction = 0.0

    /** Surface currently handed to [mp] (null = nothing attached). */
    private var surface: Surface? = null

    /**
     * URL waiting for a render Surface.
     *
     * WHY DEFER INSTEAD OF PREPARING WITHOUT ONE: MediaPlayer prepared with no Surface selects the
     * audio track only and never instantiates a video decoder, so a later `setSurface()` keeps
     * playing sound over a black screen — exactly the "AirPlay plays audio but shows nothing"
     * report (video decoder instance count 0 during playback, 54 for the same file over DLNA).
     * Holding the URL until the Surface exists keeps the video track alive.
     */
    private var pendingUrl: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val surfaceTimeout = Runnable { startWithoutSurface() }

    /** Starts playing [url], seeking to [startPositionFraction] (0..1 of duration) once prepared. */
    @Synchronized
    fun play(url: String, startPositionFraction: Double) {
        release()
        startFraction = startPositionFraction.coerceIn(0.0, 1.0)
        Logger.i("AirPlay video: play url=$url start=$startFraction")
        val live = surfaceProvider()
        if (live != null && live.isValid) {
            surface = live
            com.phairplay.util.DebugLog.log("AIRPLAY", "视频起播：渲染面已就绪")
            startPlayback(url, live)
        } else {
            // The Activity creates StreamingScreen's Surface asynchronously after CONNECTED, so the
            // first frames after /play usually have no Surface yet. Wait for it (bounded) instead of
            // starting an audio-only player that can never grow a picture.
            pendingUrl = url
            Logger.i("AirPlay video: 等待渲染面（最多 ${surfaceWaitTimeoutMs}ms）url=$url")
            com.phairplay.util.DebugLog.log(
                "AIRPLAY", "视频起播：暂无渲染面（provider=${if (live == null) "null" else "invalid"}），等待"
            )
            mainHandler.postDelayed(surfaceTimeout, surfaceWaitTimeoutMs)
        }
    }

    /** Called when the Activity's Surface appears (or reappears after a background round trip). */
    @Synchronized
    fun attachSurface(newSurface: Surface) {
        if (!newSurface.isValid) return
        surface = newSurface
        val url = pendingUrl
        com.phairplay.util.DebugLog.log(
            "AIRPLAY", "渲染面回调：pending=${url != null}, mp=${mp != null}"
        )
        if (url != null) {
            pendingUrl = null
            mainHandler.removeCallbacks(surfaceTimeout)
            Logger.i("AirPlay video: 渲染面就绪，开始起播 url=$url")
            startPlayback(url, newSurface)
        } else {
            // Already playing (possibly audio-only from the timeout path) — attaching the Surface
            // now lets MediaPlayer spin up the video decoder.
            mp?.let { player ->
                runCatching { player.setSurface(newSurface) }
                    .onSuccess { Logger.i("AirPlay video: 运行中挂载渲染面 $newSurface") }
                    .onFailure { Logger.w("AirPlay video: 挂载渲染面失败 ${it.message}") }
            }
        }
    }

    /** Called when the Activity's Surface goes away (Activity stopped / surface destroyed). */
    @Synchronized
    fun detachSurface() {
        surface = null
        mp?.let { runCatching { it.setSurface(null) } }
    }

    /** Prepares and starts [url]; [target] may be null when the wait timed out (audio-only fallback). */
    private fun startPlayback(url: String, target: Surface?) {
        val player = MediaPlayer()
        mp = player
        prepared = false
        com.phairplay.util.DebugLog.log("AIRPLAY", "开始 prepare：渲染面=${target != null}")
        runCatching {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            target?.let { player.setSurface(it) }
            player.setOnPreparedListener { onPrepared(it) }
            player.setOnCompletionListener { Logger.i("AirPlay video: completed"); onEnded() }
            player.setOnErrorListener { _, what, extra ->
                Logger.e("AirPlay video error what=$what extra=$extra")
                true   // handled — don't also fire onCompletion
            }
            player.setDataSource(url)
            player.prepareAsync()
        }.onFailure { Logger.e("AirPlay video: setup failed", it); release() }
    }

    /** Surface wait elapsed with no picture target — start anyway so at least the audio keeps going. */
    private fun startWithoutSurface() {
        synchronized(this) {
            val url = pendingUrl ?: return
            pendingUrl = null
            Logger.w("AirPlay video: ${surfaceWaitTimeoutMs}ms 内没有渲染面 → 按纯音频起播 url=$url")
            startPlayback(url, null)
        }
    }

    @Synchronized
    private fun onPrepared(player: MediaPlayer) {
        // onPrepared fires on MediaPlayer's own thread and can race release()/a new play(): if the
        // player was released (or replaced) while preparing, mp no longer points at it — bail before
        // touching a dead MediaPlayer (which would throw IllegalStateException).
        if (mp !== player) return
        // A Surface may have appeared between play() and prepare completion; attach the freshest one.
        val live = surface ?: surfaceProvider()?.takeIf { it.isValid }
        if (live != null) {
            surface = live
            runCatching { player.setSurface(live) }
        }
        if (startFraction > 0.0) runCatching { player.seekTo((startFraction * player.duration).toInt()) }
        prepared = true
        runCatching { player.start() }
        com.phairplay.util.DebugLog.log("AIRPLAY", "prepared → 起播（渲染面=${live != null}）")
        Logger.i("AirPlay video: prepared dur=${runCatching { player.duration }.getOrDefault(0)}ms → playing (surface=${live != null})")
    }

    /** rate ≤ 0 pauses, > 0 resumes. */
    @Synchronized
    fun setRate(rate: Float) {
        val player = mp ?: return
        runCatching {
            if (rate <= 0f) { if (player.isPlaying) player.pause() }
            else { if (!player.isPlaying) player.start() }
        }
    }

    /** Seeks to [positionSec] seconds. */
    @Synchronized
    fun scrub(positionSec: Double) {
        runCatching { mp?.seekTo((positionSec * 1000).toInt()) }
    }

    /** Current playback snapshot for `/playback-info`, or null if nothing is loaded. */
    @Synchronized
    fun info(): PlaybackInfo? {
        val player = mp ?: return null
        if (!prepared) return PlaybackInfo(0.0, 0.0, 0.0, readyToPlay = false)
        val dur = runCatching { player.duration }.getOrDefault(0)
        val pos = runCatching { player.currentPosition }.getOrDefault(0)
        val playing = runCatching { player.isPlaying }.getOrDefault(false)
        return PlaybackInfo(dur / 1000.0, pos / 1000.0, if (playing) 1.0 else 0.0, readyToPlay = true)
    }

    @Synchronized
    fun release() {
        pendingUrl = null
        mainHandler.removeCallbacks(surfaceTimeout)
        mp?.let { p -> runCatching { p.stop() }; runCatching { p.release() } }
        mp = null
        surface = null
        prepared = false
    }

    companion object {
        /**
         * How long a `/play` waits for the Activity's Surface before falling back to audio-only.
         * The Surface normally appears one or two frames after CONNECTED (~100-400 ms on the p230);
         * 2 s covers a cold Activity start without stalling the sender's first progress poll.
         */
        const val DEFAULT_SURFACE_WAIT_MS = 2_000L
    }
}
