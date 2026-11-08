package com.phairplay.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.phairplay.util.Logger
import com.phairplay.util.MediaUriPolicy

/**
 * Plays the media a Cast sender loads into the Default Media Receiver.
 *
 * ExoPlayer renders straight into the Surface the Activity exposes, the same
 * Surface the mirror decoders use. Surface polling is needed because the
 * Activity only creates it after CONNECTED, which happens on LOAD.
 */
class CastPlayer(
    private val context: Context,
    private val surfaceProvider: () -> Surface?,
    private val onState: (CastPlaybackState) -> Unit,
    private val onStreamingChanged: (Boolean) -> Unit
) : Player.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var token: String? = null
    private var attachedSurface: Surface? = null
    private var ended = false

    private val surfacePoll = object : Runnable {
        override fun run() {
            val exo = player ?: return
            val surface = surfaceProvider()
            if (surface != null && surface.isValid && surface !== attachedSurface) {
                attachedSurface = surface
                exo.setVideoSurface(surface)
                Logger.i("Cast: attached video surface")
            }
            handler.postDelayed(this, SURFACE_POLL_MS)
        }
    }

    private val positionTicker = object : Runnable {
        override fun run() {
            val exo = player ?: return
            report(stateOf(exo), exo.currentPosition, exo.duration)
            handler.postDelayed(this, POSITION_TICK_MS)
        }
    }

    fun load(request: CastLoad) {
        handler.post {
            MediaUriPolicy.check(request.url)?.let {
                Logger.w("Cast: LOAD refused ($it)")
                onState(CastPlaybackState(request.token, "IDLE", idleReason = "ERROR"))
                return@post
            }
            releasePlayer()
            token = request.token
            ended = false
            val exo = ExoPlayer.Builder(context).build()
            player = exo
            exo.addListener(this)
            exo.setVolume(request.volume.coerceIn(0f, 1f))
            exo.setMediaItem(MediaItem.fromUri(request.url))
            if (request.positionMs > 0) exo.seekTo(request.positionMs)
            exo.prepare()
            if (request.autoplay) exo.play()
            report("BUFFERING", request.positionMs, 0)
            onStreamingChanged(true)
            handler.post(surfacePoll)
            handler.postDelayed(positionTicker, POSITION_TICK_MS)
            Logger.i("Cast: loaded ${request.contentType} autoplay=${request.autoplay}")
        }
    }

    fun command(command: CastCommand) {
        handler.post {
            val exo = player ?: return@post
            if (command.token != token) return@post
            when (command.type) {
                "PLAY" -> exo.play()
                "PAUSE" -> exo.pause()
                "SEEK" -> {
                    exo.seekTo(command.value.toLong().coerceAtLeast(0))
                    report(stateOf(exo), command.value.toLong(), exo.duration)
                }
                "VOLUME" -> exo.volume = command.value.toFloat().coerceIn(0f, 1f)
                "STOP" -> stopInternal("CANCELLED")
                else -> Logger.d("Cast: ignoring unknown command ${command.type}")
            }
        }
    }

    /** Releases the player and tells the sender the item was cancelled. */
    fun stop() {
        handler.post { stopInternal("CANCELLED") }
    }

    fun release() {
        handler.post {
            releasePlayer()
            token = null
        }
    }

    private fun stopInternal(reason: String) {
        val current = token ?: return
        releasePlayer()
        token = null
        onState(CastPlaybackState(current, "IDLE", idleReason = reason))
        onStreamingChanged(false)
    }

    private fun releasePlayer() {
        handler.removeCallbacks(surfacePoll)
        handler.removeCallbacks(positionTicker)
        player?.let {
            it.removeListener(this)
            it.clearVideoSurface()
            it.release()
        }
        player = null
        attachedSurface = null
    }

    private fun stateOf(exo: ExoPlayer): String = when (exo.playbackState) {
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> if (exo.isPlaying) "PLAYING" else "PAUSED"
        Player.STATE_ENDED -> "IDLE"
        else -> "IDLE"
    }

    private fun report(state: String, positionMs: Long, durationMs: Long, idleReason: String? = null) {
        val current = token ?: return
        onState(
            CastPlaybackState(
                current, state,
                positionMs.coerceAtLeast(0),
                if (durationMs > 0 && durationMs != C.TIME_UNSET) durationMs else 0,
                idleReason,
                player?.volume ?: 1f
            )
        )
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        val exo = player ?: return
        when (playbackState) {
            Player.STATE_IDLE -> Unit // idle is reported by the stop/release paths
            Player.STATE_BUFFERING -> report("BUFFERING", exo.currentPosition, exo.duration)
            Player.STATE_READY -> report(if (exo.isPlaying) "PLAYING" else "PAUSED", exo.currentPosition, exo.duration)
            Player.STATE_ENDED -> if (!ended) {
                ended = true
                report("IDLE", exo.duration.coerceAtLeast(0), exo.duration, "FINISHED")
                releasePlayer()
                onStreamingChanged(false)
            }
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val exo = player ?: return
        if (exo.playbackState == Player.STATE_READY) {
            report(if (isPlaying) "PLAYING" else "PAUSED", exo.currentPosition, exo.duration)
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        Logger.e("Cast: playback error ${error.errorCodeName}", error)
        val current = token ?: return
        releasePlayer()
        token = null
        onState(CastPlaybackState(current, "IDLE", idleReason = "ERROR"))
        onStreamingChanged(false)
    }

    private companion object {
        const val SURFACE_POLL_MS = 250L
        const val POSITION_TICK_MS = 1_000L
    }
}
