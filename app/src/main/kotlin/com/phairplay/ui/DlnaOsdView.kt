package com.phairplay.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.Player
import com.phairplay.R

/**
 * DlnaOsdView — the on-screen playback bar for the full-screen DLNA player.
 *
 * WHY: DLNA was the only screen in the app still rendering Media3's own
 * PlayerControlView (`use_controller="true"` in dlna_player_view.xml). That
 * controller is a phone widget: hairline icons, a 4 dp progress bar, light
 * grey on transparent, and a D-pad can only ever reveal or hide it. Every
 * other screen in the app (NowPlayingScreen, StreamingScreen, PhotoScreen,
 * TvDialogs) is drawn by hand with the TV palette, so DLNA looked like a
 * foreign window pasted on top of the app.
 *
 * HOW: a bottom-anchored rounded card in the TV palette — title, progress bar
 * in the DLNA orange, position / duration, playback state, and the key hints
 * the remote actually supports. It is a pure decoration: never focusable and
 * FOCUS_BLOCK_DESCENDANTS, exactly like the music card, so the PlayerView
 * underneath keeps every D-pad event.
 *
 * Lifecycle: [show] reveals it and starts a 2 Hz tick; it hides itself after
 * [AUTO_HIDE_MS] of no interaction. [hide] must also be called when the
 * player layer goes away, otherwise a stale bar outlives the picture.
 */
class DlnaOsdView(context: Context) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())

    /** Player the bar is currently mirroring; kept so the tick can re-read it. */
    private var attached: Player? = null

    private val titleView: TextView
    private val bar: ProgressBar
    private val timeView: TextView
    private val stateView: TextView

    private val hideRunnable = Runnable { hide() }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (visibility != View.VISIBLE) return
            update(attached)
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Convenience for the key handler, which used to ask the PlayerView. */
    val isVisible: Boolean
        get() = visibility == View.VISIBLE

    init {
        // Purely decorative. If this ever took focus the D-pad would stop
        // reaching the transport controls — the same failure the remote
        // "completely dead" report came from.
        isFocusable = false
        isClickable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        visibility = View.GONE

        val d = resources.displayMetrics.density
        val pad = (28 * d).toInt()

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xF21C1C1E.toInt())
                cornerRadius = 16f * d
            }
            setPadding(pad, pad, pad, pad)
        }

        titleView = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 20f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
        }

        bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressDrawable = buildBarDrawable()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (8 * d).toInt()
            ).apply { topMargin = (14 * d).toInt() }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        timeView = TextView(context).apply {
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 17f
        }
        stateView = TextView(context).apply {
            setTextColor(colorRes(R.color.protocol_dlna))
            textSize = 17f
            gravity = Gravity.END
        }
        row.addView(
            timeView,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(
            stateView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val hint = TextView(context).apply {
            text = "OK 播放/暂停 · ← → 快退/快进 · 菜单 设置 · 返回 回到首页"
            setTextColor(0xFF8E8E93.toInt())
            textSize = 15f
            setPadding(0, (12 * d).toInt(), 0, 0)
        }

        panel.addView(titleView)
        panel.addView(bar)
        panel.addView(row)
        panel.addView(hint)

        addView(
            panel,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                marginStart = (56 * d).toInt()
                marginEnd = (56 * d).toInt()
                bottomMargin = (40 * d).toInt()
            }
        )
    }

    /** Reveals the bar (or just re-arms the auto-hide if already shown). */
    fun show(player: Player?) {
        attached = player
        if (visibility != View.VISIBLE) {
            visibility = View.VISIBLE
            bringToFront()
        }
        update(player)
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(hideRunnable, AUTO_HIDE_MS)
        handler.postDelayed(tickRunnable, TICK_MS)
    }

    /** Hides the bar and stops both timers. Safe to call when already hidden. */
    fun hide() {
        handler.removeCallbacks(hideRunnable)
        handler.removeCallbacks(tickRunnable)
        visibility = View.GONE
    }

    /** Re-reads the player. Cheap enough for the 2 Hz tick; no-op while hidden. */
    fun update(player: Player?) {
        val p = player ?: attached ?: return
        if (visibility != View.VISIBLE) return
        val pos = p.currentPosition.coerceAtLeast(0L)
        // TIME_UNSET is Long.MIN_VALUE on live streams — anything <= 0 means
        // "no known duration", not "zero length".
        val dur = p.duration.takeIf { it > 0L } ?: 0L
        bar.progress = if (dur > 0L) (pos * 1000L / dur).toInt() else 0
        timeView.text = buildString {
            append(fmtTime(pos))
            append("  /  ")
            append(if (dur > 0L) fmtTime(dur) else "--:--")
        }
        stateView.text = when {
            p.playbackState == Player.STATE_BUFFERING -> "缓冲中…"
            p.playWhenReady -> "播放中"
            else -> "已暂停"
        }
        titleView.text = com.phairplay.dlna.DlnaMediaMeta.title
            ?.takeIf { it.isNotBlank() }
            ?: "DLNA 投屏"
    }

    private fun buildBarDrawable(): LayerDrawable {
        val r = 4f * resources.displayMetrics.density
        val track = GradientDrawable().apply {
            setColor(0xFF38383A.toInt())
            cornerRadius = r
        }
        val fill = GradientDrawable().apply {
            setColor(colorRes(R.color.protocol_dlna))
            cornerRadius = r
        }
        return LayerDrawable(
            arrayOf(track, ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL))
        ).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    private fun fmtTime(ms: Long): String {
        val total = ms / 1000L
        return String.format("%02d:%02d", total / 60, total % 60)
    }

    private fun colorRes(res: Int): Int =
        try {
            context.getColor(res)
        } catch (_: Exception) {
            0xFFFF9500.toInt()
        }

    companion object {
        private const val TICK_MS = 500L
        private const val AUTO_HIDE_MS = 5000L
    }
}
