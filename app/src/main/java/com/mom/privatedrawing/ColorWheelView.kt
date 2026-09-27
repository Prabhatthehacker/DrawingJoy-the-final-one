package com.mom.privatedrawing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Tap or drag anywhere in the wheel to pick a hue (angle) and saturation (distance
 * from center). Brightness/value is controlled separately via a slider in the dialog.
 */
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var value: Float = 1f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    var onColorPicked: ((Int) -> Unit)? = null

    private var wheelBitmap: Bitmap? = null
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.WHITE
    }
    private val markerOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.DKGRAY
    }

    private var currentHue = 0f
    private var currentSat = 0f
    private var markerX = 0f
    private var markerY = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) buildWheel(w, h)
    }

    private fun buildWheel(w: Int, h: Int) {
        val size = min(w, h)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f
        val radius = size / 2f

        val hues = IntArray(361)
        for (i in 0..360) hues[i] = Color.HSVToColor(floatArrayOf(i.toFloat(), 1f, 1f))
        val sweep = SweepGradient(cx, cy, hues, null)
        val sat = RadialGradient(cx, cy, radius, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = sweep
        canvas.drawCircle(cx, cy, radius, paint)

        val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        satPaint.shader = sat
        canvas.drawCircle(cx, cy, radius, satPaint)

        wheelBitmap = bmp
        markerX = cx
        markerY = cy
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        wheelBitmap?.let {
            val left = (width - it.width) / 2f
            val top = (height - it.height) / 2f
            canvas.drawBitmap(it, left, top, null)
        }
        canvas.drawCircle(markerX, markerY, 12f, markerOutlinePaint)
        canvas.drawCircle(markerX, markerY, 12f, markerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = wheelBitmap ?: return true
        val left = (width - bmp.width) / 2f
        val top = (height - bmp.height) / 2f
        val cx = left + bmp.width / 2f
        val cy = top + bmp.height / 2f
        val radius = bmp.width / 2f

        var dx = event.x - cx
        var dy = event.y - cy
        var dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (dist > radius) {
            val scale = radius / dist
            dx *= scale
            dy *= scale
            dist = radius
        }

        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        currentHue = (angle + 360f) % 360f
        currentSat = (dist / radius).coerceIn(0f, 1f)
        markerX = cx + dx
        markerY = cy + dy
        invalidate()

        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            emitColor()
        }
        return true
    }

    fun setInitialColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        currentHue = hsv[0]
        currentSat = hsv[1]
        value = hsv[2]
        post {
            val bmp = wheelBitmap ?: return@post
            val left = (width - bmp.width) / 2f
            val top = (height - bmp.height) / 2f
            val cx = left + bmp.width / 2f
            val cy = top + bmp.height / 2f
            val radius = bmp.width / 2f
            val rad = Math.toRadians(currentHue.toDouble())
            markerX = (cx + currentSat * radius * Math.cos(rad)).toFloat()
            markerY = (cy + currentSat * radius * Math.sin(rad)).toFloat()
            invalidate()
        }
    }

    fun currentColor(): Int = Color.HSVToColor(floatArrayOf(currentHue, currentSat, value))

    private fun emitColor() {
        onColorPicked?.invoke(currentColor())
    }

    fun refreshForValueChange() {
        emitColor()
    }
}
