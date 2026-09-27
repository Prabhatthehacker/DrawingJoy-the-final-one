package com.mom.privatedrawing

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Every mark made on the canvas is recorded as a DrawAction. To undo, we simply
 * drop the last action from the list and replay everything that remains onto a
 * fresh bitmap. That keeps undo/redo simple and reliable for a casual drawing app.
 */
sealed class DrawAction {

    data class StrokeAction(
        val path: Path,
        val color: Int,
        val strokeWidth: Float,
        val alpha: Int,
        val isEraser: Boolean,
        val blurRadius: Float = 0f
    ) : DrawAction()

    data class ShapeAction(
        val type: ShapeType,
        val bounds: RectF,
        val color: Int,
        val strokeWidth: Float,
        val filled: Boolean
    ) : DrawAction()

    data class TextAction(
        val text: String,
        val x: Float,
        val y: Float,
        val color: Int,
        val textSize: Float,
        val typeface: android.graphics.Typeface? = null
    ) : DrawAction()

    data class ImageAction(
        val bitmap: Bitmap,
        val bounds: RectF
    ) : DrawAction()
}

fun Paint.applyStrokeStyle() {
    this.style = Paint.Style.STROKE
    this.isAntiAlias = true
    this.strokeJoin = Paint.Join.ROUND
    this.strokeCap = Paint.Cap.ROUND
}
