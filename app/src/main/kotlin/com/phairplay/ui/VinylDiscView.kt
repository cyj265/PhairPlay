package com.phairplay.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The cover area of the audio-only card, drawn as a vinyl record.
 *
 * A bare square cover was the ugliest part of casting music: it reads as a
 * stalled video frame. A record is the same information with a shape that says
 * "audio", and the slow spin tells the viewer the item is actually playing
 * without adding another progress element.
 *
 * Drawing is split into a static layer (grooves, sheen, rim, hole) and a
 * rotating layer (the cover, spun around the centre). Rotating the whole view
 * would drag the light reflection around with it and read as a spinning
 * picture instead of a record.
 */
class VinylDiscView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
        color = 0x22FFFFFF
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = 0x40FFFFFF
    }
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = 0x66000000
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBDBDBD.toInt()
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private var art: Bitmap? = null
    private var artShader: BitmapShader? = null

    private var angle = 0f

    /**
     * A record does not start the instant an item loads — the stylus has to
     * drop. The delay also hides the case where a sender sets the URI but the
     * first frame is still buffering, so the disc never spins up for nothing.
     */
    private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 24_000L
        repeatCount = ValueAnimator.INFINITE
        startDelay = 700L
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    private var spinning = false
    /** ValueAnimator.resume() is a no-op before the first start(). */
    private var spinStarted = false

    fun setArt(bitmap: Bitmap?) {
        art = bitmap
        artShader = bitmap?.let {
            BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        invalidate()
    }

    fun clearArt() {
        setArt(null)
    }

    /** Called from the 2 Hz card tick; idempotent so it can be called freely. */
    fun setSpinning(on: Boolean) {
        if (on == spinning) return
        spinning = on
        if (on) {
            if (spinStarted) spin.resume() else { spinStarted = true; spin.start() }
        } else if (spinStarted) {
            spin.pause()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (spinning && visibility == VISIBLE && spinStarted) spin.resume()
    }

    override fun onDetachedFromWindow() {
        if (spinStarted) spin.pause()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!spinning || !spinStarted) return
        if (visibility == VISIBLE && isAttachedToWindow) spin.resume() else spin.pause()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - dp(2f)
        if (r <= 0f) return

        // Drop shadow: a stack of translucent circles beats a shadow layer here,
        // which would force this view onto the software renderer for every frame.
        discPaint.shader = null
        discPaint.style = Paint.Style.FILL
        for (i in 3 downTo 1) {
            discPaint.color = Color.argb(18, 0, 0, 0)
            canvas.drawCircle(cx, cy + dp(3f), r + dp(i * 2f), discPaint)
        }

        val artR = r * 0.62f

        discPaint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(0xFF232323.toInt(), 0xFF141414.toInt(), 0xFF070707.toInt()),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, discPaint)
        discPaint.shader = null

        // Grooves. Uneven spacing reads as a cut record; a perfectly regular
        // comb reads as a UI ring.
        var g = artR + dp(6f)
        var n = 0
        while (g < r - dp(3f)) {
            groovePaint.alpha = if (n % 5 == 0) 42 else 22
            canvas.drawCircle(cx, cy, g, groovePaint)
            g += dp(2.2f) + (if (n % 7 == 0) dp(1.1f) else 0f)
            n++
        }

        val a = artShader
        if (a != null) {
            val m = Matrix()
            val bw = art!!.width.toFloat()
            val bh = art!!.height.toFloat()
            val scale = (artR * 2f) / minOf(bw, bh)
            m.setScale(scale, scale)
            m.postTranslate(cx - bw * scale / 2f, cy - bh * scale / 2f)
            a.setLocalMatrix(m)
            artPaint.shader = a
            canvas.save()
            canvas.rotate(angle, cx, cy)
            canvas.drawCircle(cx, cy, artR, artPaint)
            canvas.restore()
            artPaint.shader = null
            canvas.drawCircle(cx, cy, artR, artRingPaint)
        } else {
            // No cover: a blank label, still on the record, so the card never
            // shows the old song's art or a broken-image box.
            labelPaint.color = 0xFF1E1E1E.toInt()
            canvas.drawCircle(cx, cy, r * 0.42f, labelPaint)
            rimPaint.color = 0x30FFFFFF
            canvas.drawCircle(cx, cy, r * 0.42f, rimPaint)
            rimPaint.color = 0x40FFFFFF
            notePaint.textSize = r * 0.34f
            val baseline = cy - (notePaint.descent() + notePaint.ascent()) / 2f
            canvas.drawText("♪", cx, baseline, notePaint)
        }

        // Spindle hole sits still, unlike everything around it.
        labelPaint.color = 0xFF050505.toInt()
        canvas.drawCircle(cx, cy, dp(6f), labelPaint)
        rimPaint.color = 0x55FFFFFF
        canvas.drawCircle(cx, cy, dp(6f), rimPaint)
        rimPaint.color = 0x40FFFFFF

        // Static sheen on the upper-left, outside the rotating layer.
        val sheen = LinearGradient(
            cx - r, cy - r, cx + r * 0.1f, cy + r * 0.1f,
            intArrayOf(0x1FFFFFFF, 0x00FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        discPaint.shader = sheen
        canvas.drawCircle(cx, cy, r, discPaint)
        discPaint.shader = null

        canvas.drawCircle(cx, cy, r, rimPaint)
    }
}
