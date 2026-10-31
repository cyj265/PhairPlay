package com.phairplay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.atan2
import kotlin.math.min

/**
 * The stylus arm that rests on the record in [VinylDiscView].
 *
 * Drawn as a sibling so it stays put while the record turns under it; a record
 * with a spinning tonearm would look broken. Geometry is expressed as fractions
 * of the disc radius, so it keeps its proportions at any card size.
 */
class TonearmView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val armShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(6f)
        color = 0x70000000
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val armPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = 0x66000000
    }

    private val armPath = Path()
    private val body = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f - dp(2f)
        if (r <= 0f) return

        // Pivot sits just inside the disc's right edge, the stylus reaches about
        // a third of the way in — the same overhang as a real turntable.
        val px = cx + 0.95f * r
        val py = cy - 0.76f * r
        val ex = cx + 0.88f * r
        val ey = cy + 0.24f * r
        val sx = cx + 0.36f * r
        val sy = cy + 0.30f * r

        armPath.reset()
        armPath.moveTo(px, py)
        armPath.quadTo(px + 0.02f * r, cy - 0.08f * r, ex, ey)
        armPath.lineTo(sx, sy)

        canvas.save()
        canvas.translate(dp(2f), dp(3f))
        canvas.drawPath(armPath, armShadow)
        canvas.restore()

        armPaint.shader = LinearGradient(
            px - r * 0.2f, py - r * 0.2f, sx + r * 0.2f, sy + r * 0.3f,
            intArrayOf(0xFFE9ECF2.toInt(), 0xFF9AA0AB.toInt(), 0xFFD5D9E0.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(armPath, armPaint)
        armPaint.shader = null

        // Bearing housing: a short cylinder standing on the pivot.
        fill.shader = LinearGradient(
            px - dp(8f), 0f, px + dp(8f), 0f,
            intArrayOf(0xFFCED3DB.toInt(), 0xFFF2F4F8.toInt(), 0xFF8B919C.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        body.set(px - dp(8f), py - dp(46f), px + dp(8f), py - dp(4f))
        canvas.drawRoundRect(body, dp(6f), dp(6f), fill)
        canvas.drawRoundRect(body, dp(6f), dp(6f), edge)
        fill.shader = null

        fill.shader = RadialGradient(
            px, py, dp(12f),
            intArrayOf(0xFFF4F6F9.toInt(), 0xFFB4BAC4.toInt(), 0xFF5E636C.toInt()),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(px, py, dp(11f), fill)
        fill.shader = null
        canvas.drawCircle(px, py, dp(11f), edge)
        fill.color = 0xFF23252B.toInt()
        canvas.drawCircle(px, py, dp(4f), fill)

        // Counterweight knob on top of the post.
        fill.shader = RadialGradient(
            px - dp(2f), py - dp(52f), dp(8f),
            intArrayOf(0xFFF7F8FA.toInt(), 0xFF9BA1AC.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(px, py - dp(50f), dp(6f), fill)
        fill.shader = null

        // Elbow joint where the vertical and the angled run meet.
        fill.shader = RadialGradient(
            ex - dp(2f), ey - dp(2f), dp(7f),
            intArrayOf(0xFFEDEFF3.toInt(), 0xFF7C828D.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(ex, ey, dp(5f), fill)
        fill.shader = null
        canvas.drawCircle(ex, ey, dp(5f), edge)

        // Cartridge, aligned with the last segment of the arm.
        val deg = Math.toDegrees(atan2((sy - ey).toDouble(), (sx - ex).toDouble())).toFloat()
        canvas.save()
        canvas.translate(sx, sy)
        canvas.rotate(deg)
        fill.shader = LinearGradient(
            -dp(26f), -dp(8f), -dp(26f), dp(8f),
            intArrayOf(0xFF3A3E46.toInt(), 0xFF1D1F24.toInt()),
            null,
            Shader.TileMode.CLAMP
        )
        body.set(-dp(26f), -dp(7f), dp(2f), dp(7f))
        canvas.drawRoundRect(body, dp(3f), dp(3f), fill)
        canvas.drawRoundRect(body, dp(3f), dp(3f), edge)
        fill.shader = null
        fill.color = 0xFFE8EAF0.toInt()
        canvas.drawCircle(-dp(22f), 0f, dp(1.6f), fill)
        canvas.restore()
    }
}
