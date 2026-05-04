package com.eyetracker.app.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.eyetracker.app.ml.GazeData
import kotlin.math.min

class GazeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Gaze cursor
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val cursorRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val cursorShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
    }

    // ── Heatmap
    private val heatmapBitmap by lazy {
        Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    }
    private val heatmapCanvas by lazy { Canvas(heatmapBitmap) }
    private val heatmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ── Trails
    private val trailPoints = ArrayDeque<PointF>(50)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val trailPath = Path()

    // ── State
    var gazeData: GazeData? = null
        set(value) {
            field = value
            value?.let { updateTrail(it) }
            invalidate()
        }

    var showHeatmap = false
    var showTrail   = true
    var showCursor  = true
    var isActive    = false

    // ── Pulse animation
    private var pulseRadius = 0f
    private var pulseAlpha  = 0
    private val pulseRunnable = object : Runnable {
        override fun run() {
            pulseRadius = (pulseRadius + 2f) % 60f
            pulseAlpha  = (255 * (1f - pulseRadius / 60f)).toInt()
            invalidate()
            if (isActive) postDelayed(this, 16)
        }
    }

    fun startTracking() {
        isActive = true
        post(pulseRunnable)
    }

    fun stopTracking() {
        isActive = false
        removeCallbacks(pulseRunnable)
        trailPoints.clear()
        gazeData = null
        invalidate()
    }

    fun clearHeatmap() {
        if (::heatmapBitmap.isInitialized) heatmapBitmap.eraseColor(Color.TRANSPARENT)
        invalidate()
    }

    private fun updateTrail(data: GazeData) {
        val pt = PointF(data.gazePoint.x * width, data.gazePoint.y * height)
        trailPoints.addLast(pt)
        if (trailPoints.size > 40) trailPoints.removeFirst()

        // Draw onto heatmap
        if (showHeatmap && ::heatmapCanvas.isInitialized) {
            val r = RadialGradient(
                pt.x, pt.y, 60f,
                intArrayOf(0x44FF4444, 0x22FF8844, 0x00000000),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            heatmapPaint.shader = r
            heatmapCanvas.drawCircle(pt.x, pt.y, 60f, heatmapPaint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = gazeData ?: return

        val gx = data.gazePoint.x * width
        val gy = data.gazePoint.y * height

        // Heatmap
        if (showHeatmap && ::heatmapBitmap.isInitialized) {
            canvas.drawBitmap(heatmapBitmap, 0f, 0f, null)
        }

        // Trail
        if (showTrail && trailPoints.size > 1) {
            trailPath.reset()
            trailPoints.forEachIndexed { i, pt ->
                val alpha = (255 * i / trailPoints.size.toFloat()).toInt()
                val green = (200 * i / trailPoints.size.toFloat()).toInt()
                trailPaint.color = Color.argb(alpha, 100, green, 255)
                if (i == 0) trailPath.moveTo(pt.x, pt.y)
                else trailPath.lineTo(pt.x, pt.y)
            }
            canvas.drawPath(trailPath, trailPaint)
        }

        if (!showCursor) return

        // Pulse ring
        val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(pulseAlpha, 100, 200, 255)
        }
        canvas.drawCircle(gx, gy, 20f + pulseRadius, pulsePaint)

        // Outer glow
        val confidence = data.confidence
        val alpha = (180 * confidence).toInt().coerceIn(0, 255)
        cursorShadowPaint.color = Color.argb(alpha / 3, 100, 200, 255)
        canvas.drawCircle(gx, gy, 30f, cursorShadowPaint)

        // Blink effect
        val cursorColor = when {
            data.isBlinking -> Color.argb(200, 255, 200, 50)
            else            -> Color.argb((200 * confidence).toInt().coerceIn(80, 200), 80, 180, 255)
        }

        // Inner filled cursor
        cursorPaint.color = cursorColor
        canvas.drawCircle(gx, gy, 14f, cursorPaint)

        // White ring
        cursorRingPaint.alpha = (255 * confidence).toInt().coerceIn(80, 255)
        canvas.drawCircle(gx, gy, 14f, cursorRingPaint)

        // Cross-hair
        val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 255, 255, 255)
            strokeWidth = 1.5f
        }
        canvas.drawLine(gx - 22f, gy, gx - 16f, gy, hairPaint)
        canvas.drawLine(gx + 16f, gy, gx + 22f, gy, hairPaint)
        canvas.drawLine(gx, gy - 22f, gx, gy - 16f, hairPaint)
        canvas.drawLine(gx, gy + 16f, gx, gy + 22f, hairPaint)
    }
}
