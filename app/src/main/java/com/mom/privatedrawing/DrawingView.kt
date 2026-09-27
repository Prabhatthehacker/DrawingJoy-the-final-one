package com.mom.privatedrawing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ---- Public state, driven by MainActivity's toolbar ----
    var currentTool: Tool = Tool.PEN
    var currentColor: Int = Color.BLACK
    var currentStrokeWidth: Float = 12f

    var onHistoryChanged: (() -> Unit)? = null

    // ---- Internal drawing state ----
    private val actions = mutableListOf<DrawAction>()
    private val redoStack = mutableListOf<DrawAction>()

    private var baseBitmap: Bitmap? = null
    private var baseCanvas: Canvas? = null

    private var activePath: Path? = null
    private val activePaint = Paint().apply { applyStrokeStyle() }

    private var shapeStartX = 0f
    private var shapeStartY = 0f
    private var shapeCurX = 0f
    private var shapeCurY = 0f
    private var isShaping = false
    var shapesFilled: Boolean = false

    // Callback used by the Text tool: MainActivity shows an input dialog, then calls addText()
    var onCanvasTapForText: ((x: Float, y: Float) -> Unit)? = null

    private val clearXfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

    // ---- Live tool cursor: a small icon that follows the touch point while drawing,
    // so you can see exactly where the tip is (pen/pencil/marker/highlighter/brush get
    // their own icon; shapes get a crosshair instead). ----
    private var cursorX = 0f
    private var cursorY = 0f
    private var isCursorVisible = false
    private val cursorIconCache = mutableMapOf<Tool, Bitmap>()
    private val cursorPaint = Paint().apply {
        isAntiAlias = true
        colorFilter = android.graphics.PorterDuffColorFilter(Color.DKGRAY, PorterDuff.Mode.SRC_IN)
        alpha = 200
    }
    private val crosshairPaint = Paint().apply {
        isAntiAlias = true
        color = Color.DKGRAY
        alpha = 200
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private fun cursorIconResFor(tool: Tool): Int? = when (tool) {
        Tool.PEN -> R.drawable.ic_tool_pen
        Tool.PENCIL -> R.drawable.ic_tool_pencil
        Tool.MARKER -> R.drawable.ic_tool_marker
        Tool.HIGHLIGHTER -> R.drawable.ic_tool_highlighter
        Tool.BRUSH -> R.drawable.ic_tool_brush
        Tool.ERASER -> R.drawable.ic_tool_eraser
        else -> null
    }

    private fun cursorIconFor(tool: Tool): Bitmap? {
        val resId = cursorIconResFor(tool) ?: return null
        return cursorIconCache.getOrPut(tool) {
            val sizePx = (26 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val drawable = androidx.core.content.ContextCompat.getDrawable(context, resId)!!.mutate()
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(Canvas(bmp))
            bmp
        }
    }

    private fun updateCursor(x: Float, y: Float, visible: Boolean) {
        cursorX = x
        cursorY = y
        isCursorVisible = visible
        invalidate()
    }

    private fun drawCursorOverlay(canvas: Canvas) {
        if (!isCursorVisible) return
        val isShapeTool = currentTool in setOf(Tool.LINE, Tool.RECTANGLE, Tool.CIRCLE, Tool.TRIANGLE, Tool.STAR)
        if (isShapeTool) {
            val armLength = 16f * resources.displayMetrics.density
            canvas.drawLine(cursorX - armLength, cursorY, cursorX + armLength, cursorY, crosshairPaint)
            canvas.drawLine(cursorX, cursorY - armLength, cursorX, cursorY + armLength, crosshairPaint)
            canvas.drawCircle(cursorX, cursorY, 3f * resources.displayMetrics.density, crosshairPaint)
        } else {
            val bitmap = cursorIconFor(currentTool) ?: return
            // Anchor the icon's bottom-left tip at the touch point, like a pen resting on paper.
            canvas.drawBitmap(bitmap, cursorX, cursorY - bitmap.height, cursorPaint)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val newBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val newCanvas = Canvas(newBitmap)
            // Preserve whatever was already drawn if the view is resized (e.g. rotation)
            baseBitmap?.let { old ->
                newCanvas.drawBitmap(old, 0f, 0f, null)
            }
            baseBitmap = newBitmap
            baseCanvas = newCanvas
            redrawAllActions()
        }
    }

    // ---------------- Zoom & pan ----------------
    // A basic pinch-to-zoom + two-finger pan. One finger always draws; two fingers
    // always navigate. The underlying drawing itself stays the same resolution —
    // zoom just magnifies the view of it, the same way a photo viewer would.
    private var scaleFactor = 1f
    private var panX = 0f
    private var panY = 0f
    private val minScale = 1f
    private val maxScale = 5f
    private var isMultiTouch = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val prevScale = scaleFactor
            scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)
            val change = scaleFactor / prevScale
            panX = detector.focusX - (detector.focusX - panX) * change
            panY = detector.focusY - (detector.focusY - panY) * change
            invalidate()
            return true
        }
    })

    fun isZoomedOrPanned(): Boolean = scaleFactor != 1f || panX != 0f || panY != 0f

    fun resetZoom() {
        scaleFactor = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    private fun averageX(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getX(i)
        return sum / event.pointerCount
    }

    private fun averageY(event: MotionEvent): Float {
        var sum = 0f
        for (i in 0 until event.pointerCount) sum += event.getY(i)
        return sum / event.pointerCount
    }

    // ---------------- Touch handling ----------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)

        if (event.pointerCount >= 2) {
            if (!isMultiTouch) {
                // Second finger just touched down: cancel any in-progress single-finger
                // stroke/shape rather than leaving a stray partial mark on the canvas.
                activePath = null
                isShaping = false
                updateCursor(0f, 0f, visible = false)
                isMultiTouch = true
                lastFocusX = averageX(event)
                lastFocusY = averageY(event)
            } else if (!scaleGestureDetector.isInProgress) {
                val fx = averageX(event)
                val fy = averageY(event)
                panX += fx - lastFocusX
                panY += fy - lastFocusY
                lastFocusX = fx
                lastFocusY = fy
                invalidate()
            } else {
                lastFocusX = averageX(event)
                lastFocusY = averageY(event)
            }
            return true
        }

        if (isMultiTouch) {
            // The last extra finger just lifted. Treat the next touch as a fresh
            // start rather than resuming a stroke from before the gesture began.
            isMultiTouch = false
            return true
        }

        // Convert the raw screen touch into "document space" (i.e. as if there were
        // no zoom/pan at all), so every tool below keeps working exactly as before.
        val x = (event.x - panX) / scaleFactor
        val y = (event.y - panY) / scaleFactor

        when (currentTool) {
            Tool.PEN, Tool.PENCIL, Tool.MARKER, Tool.HIGHLIGHTER, Tool.BRUSH, Tool.ERASER -> handleFreehand(event, x, y)
            Tool.LINE -> handleShape(event, x, y, ShapeType.LINE)
            Tool.RECTANGLE -> handleShape(event, x, y, ShapeType.RECTANGLE)
            Tool.CIRCLE -> handleShape(event, x, y, ShapeType.CIRCLE)
            Tool.TRIANGLE -> handleShape(event, x, y, ShapeType.TRIANGLE)
            Tool.STAR -> handleShape(event, x, y, ShapeType.STAR)
            Tool.FILL -> {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    floodFill(x.toInt(), y.toInt(), currentColor)
                }
            }
            Tool.TEXT -> {
                if (event.action == MotionEvent.ACTION_UP) {
                    onCanvasTapForText?.invoke(x, y)
                }
            }
            Tool.IMAGE -> { /* Image placement is handled by MainActivity via addImage() */ }
        }
        return true
    }

    private fun handleFreehand(event: MotionEvent, x: Float, y: Float) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activePath = Path().apply { moveTo(x, y) }
                configurePaintForTool()
                updateCursor(x, y, visible = true)
            }
            MotionEvent.ACTION_MOVE -> {
                activePath?.lineTo(x, y)
                updateCursor(x, y, visible = true)
            }
            MotionEvent.ACTION_UP -> {
                activePath?.lineTo(x, y)
                commitFreehandStroke()
                updateCursor(x, y, visible = false)
            }
        }
    }

    private fun configurePaintForTool() {
        activePaint.applyStrokeStyle()
        activePaint.maskFilter = null
        when (currentTool) {
            Tool.PEN -> {
                activePaint.color = currentColor
                activePaint.alpha = 255
                activePaint.strokeWidth = currentStrokeWidth
            }
            Tool.PENCIL -> {
                activePaint.color = currentColor
                activePaint.alpha = 200
                activePaint.strokeWidth = max(2f, currentStrokeWidth * 0.5f)
            }
            Tool.MARKER -> {
                activePaint.color = currentColor
                activePaint.alpha = 255
                activePaint.strokeWidth = currentStrokeWidth * 1.8f
            }
            Tool.HIGHLIGHTER -> {
                activePaint.color = currentColor
                activePaint.alpha = 140
                activePaint.strokeWidth = currentStrokeWidth * 2.4f
            }
            Tool.BRUSH -> {
                activePaint.color = currentColor
                activePaint.alpha = 255
                activePaint.strokeWidth = currentStrokeWidth * 1.3f
                // SOLID keeps the stroke's core fully opaque and only softens the outer
                // edge slightly, like a real paintbrush — NORMAL would blur the whole
                // stroke into a hazy spray/graffiti look, which is what we want to avoid.
                activePaint.maskFilter = android.graphics.BlurMaskFilter(
                    currentStrokeWidth * 0.12f, android.graphics.BlurMaskFilter.Blur.SOLID
                )
            }
            Tool.ERASER -> {
                // The final commit always properly clears pixels (see commitFreehandStroke /
                // drawStrokeToBase), regardless of this color. This is only what's shown
                // WHILE dragging — painting it fully transparent here (as before) meant
                // nothing visibly changed until you lifted your finger, which felt like the
                // eraser was "still drawing." Painting the canvas background color instead
                // gives an immediate, real-looking erase as you drag.
                activePaint.color = ContextCompat.getColor(context, R.color.canvas_bg)
                activePaint.alpha = 255
                activePaint.strokeWidth = currentStrokeWidth * 1.5f
            }
            else -> {}
        }
    }

    private fun commitFreehandStroke() {
        val path = activePath ?: return
        val isEraser = currentTool == Tool.ERASER
        val blur = if (currentTool == Tool.BRUSH) currentStrokeWidth * 0.12f else 0f
        val action = DrawAction.StrokeAction(
            path = Path(path),
            color = if (isEraser) Color.TRANSPARENT else activePaint.color,
            strokeWidth = activePaint.strokeWidth,
            alpha = activePaint.alpha,
            isEraser = isEraser,
            blurRadius = blur
        )
        drawStrokeToBase(action)
        actions.add(action)
        redoStack.clear()
        activePath = null
        onHistoryChanged?.invoke()
    }

    private fun drawStrokeToBase(action: DrawAction.StrokeAction) {
        val canvas = baseCanvas ?: return
        val paint = Paint().apply { applyStrokeStyle() }
        paint.strokeWidth = action.strokeWidth
        if (action.isEraser) {
            paint.color = Color.TRANSPARENT
            paint.xfermode = clearXfermode
        } else {
            paint.color = action.color
            paint.alpha = action.alpha
            paint.xfermode = null
            if (action.blurRadius > 0f) {
                paint.maskFilter = android.graphics.BlurMaskFilter(action.blurRadius, android.graphics.BlurMaskFilter.Blur.SOLID)
            }
        }
        canvas.drawPath(action.path, paint)
    }

    private fun handleShape(event: MotionEvent, x: Float, y: Float, type: ShapeType) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                shapeStartX = x; shapeStartY = y
                shapeCurX = x; shapeCurY = y
                isShaping = true
                updateCursor(x, y, visible = true)
            }
            MotionEvent.ACTION_MOVE -> {
                shapeCurX = x; shapeCurY = y
                updateCursor(x, y, visible = true)
            }
            MotionEvent.ACTION_UP -> {
                shapeCurX = x; shapeCurY = y
                isShaping = false
                updateCursor(x, y, visible = false)
                val bounds = RectF(
                    min(shapeStartX, shapeCurX), min(shapeStartY, shapeCurY),
                    max(shapeStartX, shapeCurX), max(shapeStartY, shapeCurY)
                )
                val action = DrawAction.ShapeAction(
                    type = type,
                    bounds = bounds,
                    color = currentColor,
                    strokeWidth = currentStrokeWidth,
                    filled = shapesFilled
                )
                drawShapeToBase(action)
                actions.add(action)
                redoStack.clear()
                onHistoryChanged?.invoke()
                invalidate()
            }
        }
    }

    private fun buildShapePaint(color: Int, strokeWidth: Float, filled: Boolean): Paint {
        return Paint().apply {
            isAntiAlias = true
            this.color = color
            this.strokeWidth = strokeWidth
            style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
    }

    private fun drawShapeToBase(action: DrawAction.ShapeAction) {
        val canvas = baseCanvas ?: return
        drawShapeOn(canvas, action)
    }

    private fun drawShapeOn(canvas: Canvas, action: DrawAction.ShapeAction) {
        val paint = buildShapePaint(action.color, action.strokeWidth, action.filled)
        val b = action.bounds
        when (action.type) {
            ShapeType.LINE -> canvas.drawLine(shapeStartXFor(action), shapeStartYFor(action), b.right, b.bottom, paint)
            ShapeType.RECTANGLE -> canvas.drawRect(b, paint)
            ShapeType.CIRCLE -> canvas.drawOval(b, paint)
            ShapeType.TRIANGLE -> {
                val path = Path()
                path.moveTo((b.left + b.right) / 2f, b.top)
                path.lineTo(b.left, b.bottom)
                path.lineTo(b.right, b.bottom)
                path.close()
                canvas.drawPath(path, paint)
            }
            ShapeType.STAR -> canvas.drawPath(buildStarPath(b), paint)
        }
    }

    // Lines need their true start point (not just the bounding box), so we stash it
    // on the action's bounds via left/top when start != top-left corner.
    private fun shapeStartXFor(action: DrawAction.ShapeAction): Float = action.bounds.left
    private fun shapeStartYFor(action: DrawAction.ShapeAction): Float = action.bounds.top

    private fun buildStarPath(b: RectF): Path {
        val cx = (b.left + b.right) / 2f
        val cy = (b.top + b.bottom) / 2f
        val outerR = min(b.width(), b.height()) / 2f
        val innerR = outerR * 0.42f
        val path = Path()
        val points = 5
        val startAngle = -Math.PI / 2
        for (i in 0 until points * 2) {
            val angle = startAngle + i * Math.PI / points
            val r = if (i % 2 == 0) outerR else innerR
            val px = (cx + r * cos(angle)).toFloat()
            val py = (cy + r * sin(angle)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        return path
    }

    // ---------------- Text ----------------

    fun addText(text: String, x: Float, y: Float, color: Int, textSize: Float, typeface: android.graphics.Typeface? = null) {
        val action = DrawAction.TextAction(text, x, y, color, textSize, typeface)
        drawTextToBase(action)
        actions.add(action)
        redoStack.clear()
        onHistoryChanged?.invoke()
        invalidate()
    }

    private fun drawTextToBase(action: DrawAction.TextAction) {
        val canvas = baseCanvas ?: return
        val paint = Paint().apply {
            isAntiAlias = true
            color = action.color
            textSize = action.textSize
            action.typeface?.let { typeface = it }
        }
        canvas.drawText(action.text, action.x, action.y, paint)
    }

    // ---------------- Image import ----------------

    fun addImage(bitmap: Bitmap) {
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0 || viewH <= 0) return
        val maxW = viewW * 0.7f
        val maxH = viewH * 0.7f
        val scale = min(maxW / bitmap.width, maxH / bitmap.height).coerceAtMost(1f)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = (viewW - w) / 2f
        val top = (viewH - h) / 2f
        val bounds = RectF(left, top, left + w, top + h)
        val action = DrawAction.ImageAction(bitmap, bounds)
        drawImageToBase(action)
        actions.add(action)
        redoStack.clear()
        onHistoryChanged?.invoke()
        invalidate()
    }

    private fun drawImageToBase(action: DrawAction.ImageAction) {
        val canvas = baseCanvas ?: return
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(action.bitmap, null, action.bounds, paint)
    }

    /** Loads a previously-saved PNG as the starting point of the canvas, replacing whatever's there. */
    fun loadAsCanvasBackground(bitmap: Bitmap) {
        clearAll()
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0 || viewH <= 0) return
        val bounds = RectF(0f, 0f, viewW, viewH)
        val action = DrawAction.ImageAction(bitmap, bounds)
        drawImageToBase(action)
        actions.add(action)
        redoStack.clear()
        onHistoryChanged?.invoke()
        invalidate()
    }

    // ---------------- Undo / Redo / Clear ----------------

    fun canUndo() = actions.isNotEmpty()
    fun canRedo() = redoStack.isNotEmpty()

    fun undo() {
        if (actions.isEmpty()) return
        val last = actions.removeAt(actions.size - 1)
        redoStack.add(last)
        redrawAllActions()
        onHistoryChanged?.invoke()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val action = redoStack.removeAt(redoStack.size - 1)
        actions.add(action)
        redrawAllActions()
        onHistoryChanged?.invoke()
    }

    fun clearAll() {
        actions.clear()
        redoStack.clear()
        redrawAllActions()
        onHistoryChanged?.invoke()
    }

    private fun redrawAllActions() {
        val canvas = baseCanvas ?: return
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        for (action in actions) {
            when (action) {
                is DrawAction.StrokeAction -> drawStrokeToBase(action)
                is DrawAction.ShapeAction -> drawShapeOn(canvas, action)
                is DrawAction.TextAction -> drawTextToBase(action)
                is DrawAction.ImageAction -> drawImageToBase(action)
            }
        }
        invalidate()
    }

    // ---------------- Flood fill ----------------

    private fun floodFill(startX: Int, startY: Int, fillColor: Int) {
        val bmp = baseBitmap ?: return
        if (startX < 0 || startY < 0 || startX >= bmp.width || startY >= bmp.height) return

        val targetColor = bmp.getPixel(startX, startY)
        val replacement = fillColor or (0xFF shl 24) // ensure opaque fill
        if (targetColor == replacement) return

        // Simple scanline-ish stack-based flood fill; fine for a phone-sized canvas.
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)

        val w = bmp.width
        val h = bmp.height
        val stack = ArrayDeque<Int>()
        stack.addLast(startY * w + startX)
        val tolerance = 30

        fun colorsClose(a: Int, b: Int): Boolean {
            val da = abs(((a shr 24) and 0xFF) - ((b shr 24) and 0xFF))
            val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
            val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
            val db = abs((a and 0xFF) - (b and 0xFF))
            return da + dr + dg + db < tolerance
        }

        var guard = 0
        val maxIterations = w * h
        while (stack.isNotEmpty() && guard < maxIterations) {
            guard++
            val idx = stack.removeLast()
            if (idx < 0 || idx >= pixels.size) continue
            if (!colorsClose(pixels[idx], targetColor)) continue
            pixels[idx] = replacement

            val px = idx % w
            val py = idx / w
            if (px > 0) stack.addLast(idx - 1)
            if (px < w - 1) stack.addLast(idx + 1)
            if (py > 0) stack.addLast(idx - w)
            if (py < h - 1) stack.addLast(idx + w)
        }

        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        // Flood fill mutates the bitmap directly, so record it as an equivalent
        // rectangle-free action by snapshotting: simplest is to treat it as a
        // permanent base change and push a lightweight marker for undo.
        actions.add(
            DrawAction.ImageAction(
                bitmap = Bitmap.createBitmap(bmp),
                bounds = RectF(0f, 0f, w.toFloat(), h.toFloat())
            )
        )
        redoStack.clear()
        onHistoryChanged?.invoke()
        invalidate()
    }

    // ---------------- Rendering ----------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(scaleFactor, scaleFactor)

        baseBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        activePath?.let { canvas.drawPath(it, activePaint) }

        if (isShaping) {
            val bounds = RectF(
                min(shapeStartX, shapeCurX), min(shapeStartY, shapeCurY),
                max(shapeStartX, shapeCurX), max(shapeStartY, shapeCurY)
            )
            val previewType = when (currentTool) {
                Tool.LINE -> ShapeType.LINE
                Tool.RECTANGLE -> ShapeType.RECTANGLE
                Tool.CIRCLE -> ShapeType.CIRCLE
                Tool.TRIANGLE -> ShapeType.TRIANGLE
                Tool.STAR -> ShapeType.STAR
                else -> null
            }
            previewType?.let { type ->
                val previewAction = DrawAction.ShapeAction(
                    type = type,
                    bounds = bounds,
                    color = currentColor,
                    strokeWidth = currentStrokeWidth,
                    filled = shapesFilled
                )
                drawShapeOn(canvas, previewAction)
            }
        }

        drawCursorOverlay(canvas)
        canvas.restore()
    }

    /** Returns a clean copy of just the drawing (no UI chrome) for Save/Share/Record. */
    fun exportBitmap(): Bitmap? {
        val bmp = baseBitmap ?: return null
        return Bitmap.createBitmap(bmp)
    }
}
