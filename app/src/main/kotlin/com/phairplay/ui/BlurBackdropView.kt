package com.phairplay.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Full-screen backdrop for the music card: the current cover, blurred.
 *
 * The blur runs once per cover, on a 160×90 sample, with two separable box
 * passes — visually the same as a gaussian at TV distance and far cheaper than
 * a per-frame RenderEffect (which is API 31+ anyway). A first attempt simply
 * upscaled a 40 px thumbnail and it read as coloured blocks.
 */
// The gradients follow the view size and are rebuilt in draw, but this view is
// only invalidated when a cover or card state changes — never on a timer.
@SuppressLint("DrawAllocation")
class BlurBackdropView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val scrim = Paint(Paint.ANTI_ALIAS_FLAG)

    private var blurred: Bitmap? = null
    private val dst = RectF()

    /**
     * Blur runs off the main thread: ~500k pixel operations is a visible hitch
     * right when a new song's card appears, and the card is already on screen
     * with its dark fallback by then.
     */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "music-backdrop").apply { isDaemon = true }
    }
    private var token = 0

    fun setArt(bitmap: Bitmap?) {
        val request = ++token
        if (bitmap == null) {
            blurred?.recycle()
            blurred = null
            invalidate()
            return
        }
        worker.execute {
            val b = blurCover(bitmap)
            post {
                if (request != token) {
                    b?.recycle()
                    return@post
                }
                blurred?.recycle()
                blurred = b
                invalidate()
            }
        }
    }

    /**
     * Center-crops the (usually square) cover to 16:9, blurs it, and tones it
     * down. The sample is deliberately tiny: at 480 px a cover with a bright
     * ring still read as a giant white donut behind the text, and upscaling a
     * 160×90 bitmap is what melts it into the soft glow the reference design
     * has.
     */
    private fun blurCover(src: Bitmap): Bitmap? {
        val w = 160
        val h = 90
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scale = max(w.toFloat() / src.width, h.toFloat() / src.height)
        val cropW = w / scale
        val cropH = h / scale
        val left = (src.width - cropW) / 2f
        val top = (src.height - cropH) / 2f
        canvas.drawBitmap(
            src,
            Rect(left.toInt(), top.toInt(), (left + cropW).toInt(), (top + cropH).toInt()),
            Rect(0, 0, w, h),
            paint
        )
        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)
        boxBlur(pixels, w, h, 10)
        boxBlur(pixels, w, h, 10)
        // Pull most of the colour out and drop the overall level: the backdrop
        // must never compete with the title, and pure-white covers otherwise
        // wash the whole card out.
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = p ushr 16 and 0xFF
            val g = p ushr 8 and 0xFF
            val b = p and 0xFF
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            val nr = ((r * 0.62f + lum * 0.38f) * 0.56f).toInt().coerceIn(0, 255)
            val ng = ((g * 0.62f + lum * 0.38f) * 0.56f).toInt().coerceIn(0, 255)
            val nb = ((b * 0.62f + lum * 0.38f) * 0.56f).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int) {
        val tmp = IntArray(px.size)
        val span = radius * 2 + 1
        // Horizontal.
        for (y in 0 until h) {
            val row = y * w
            var a = 0; var r = 0; var g = 0; var b = 0
            for (i in -radius..radius) {
                val p = px[row + i.coerceIn(0, w - 1)]
                a += p ushr 24 and 0xFF; r += p ushr 16 and 0xFF
                g += p ushr 8 and 0xFF; b += p and 0xFF
            }
            for (x in 0 until w) {
                tmp[row + x] = (a / span shl 24) or (r / span shl 16) or (g / span shl 8) or (b / span)
                val add = px[row + (x + radius + 1).coerceAtMost(w - 1)]
                val sub = px[row + (x - radius).coerceAtLeast(0)]
                a += (add ushr 24 and 0xFF) - (sub ushr 24 and 0xFF)
                r += (add ushr 16 and 0xFF) - (sub ushr 16 and 0xFF)
                g += (add ushr 8 and 0xFF) - (sub ushr 8 and 0xFF)
                b += (add and 0xFF) - (sub and 0xFF)
            }
        }
        // Vertical.
        for (x in 0 until w) {
            var a = 0; var r = 0; var g = 0; var b = 0
            for (i in -radius..radius) {
                val p = tmp[i.coerceIn(0, h - 1) * w + x]
                a += p ushr 24 and 0xFF; r += p ushr 16 and 0xFF
                g += p ushr 8 and 0xFF; b += p and 0xFF
            }
            for (y in 0 until h) {
                px[y * w + x] = (a / span shl 24) or (r / span shl 16) or (g / span shl 8) or (b / span)
                val add = tmp[(y + radius + 1).coerceAtMost(h - 1) * w + x]
                val sub = tmp[(y - radius).coerceAtLeast(0) * w + x]
                a += (add ushr 24 and 0xFF) - (sub ushr 24 and 0xFF)
                r += (add ushr 16 and 0xFF) - (sub ushr 16 and 0xFF)
                g += (add ushr 8 and 0xFF) - (sub ushr 8 and 0xFF)
                b += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val bmp = blurred
        if (bmp != null) {
            dst.set(0f, 0f, w, h)
            canvas.drawBitmap(bmp, null, dst, paint)
        } else {
            paint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(0xFF12131C.toInt(), 0xFF1C1330.toInt(), 0xFF0A0A0D.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }

        // Two scrims, not one: the text column needs contrast even when the
        // cover is bright, and the bottom edge needs it for the key hints.
        scrim.color = 0x26000000
        canvas.drawRect(0f, 0f, w, h, scrim)

        scrim.shader = LinearGradient(
            w * 0.26f, 0f, w, 0f,
            intArrayOf(0x00000000, 0x99000000.toInt()),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, scrim)
        scrim.shader = null

        scrim.shader = LinearGradient(
            0f, h * 0.62f, 0f, h,
            intArrayOf(0x00000000, 0x7A000000),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h * 0.62f, w, h, scrim)
        scrim.shader = null
    }
}
