package com.eyetracker.app.ml

import android.graphics.PointF
import kotlin.math.abs

data class CalibrationPoint(
    val targetX: Float,   // screen target (0..1)
    val targetY: Float,
    val measuredX: Float, // measured gaze (0..1)
    val measuredY: Float
)

class CalibrationManager {

    private val points = mutableListOf<CalibrationPoint>()

    // 9-point calibration grid targets
    val calibrationTargets = listOf(
        PointF(0.1f, 0.1f),
        PointF(0.5f, 0.1f),
        PointF(0.9f, 0.1f),
        PointF(0.1f, 0.5f),
        PointF(0.5f, 0.5f),
        PointF(0.9f, 0.5f),
        PointF(0.1f, 0.9f),
        PointF(0.5f, 0.9f),
        PointF(0.9f, 0.9f)
    )

    var currentPointIndex = 0
        private set

    val isComplete get() = currentPointIndex >= calibrationTargets.size
    val currentTarget get() = calibrationTargets.getOrNull(currentPointIndex)
    val progress get() = currentPointIndex.toFloat() / calibrationTargets.size

    fun recordPoint(measuredGaze: PointF) {
        val target = currentTarget ?: return
        points.add(
            CalibrationPoint(
                targetX  = target.x,
                targetY  = target.y,
                measuredX = measuredGaze.x,
                measuredY = measuredGaze.y
            )
        )
        currentPointIndex++
    }

    /**
     * Computes simple linear offset correction from calibration data.
     * Returns Pair(offsetX, offsetY) to apply to gaze estimates.
     */
    fun computeCorrection(): Pair<Float, Float> {
        if (points.isEmpty()) return Pair(0f, 0f)

        val avgOffsetX = points.map { it.targetX - it.measuredX }.average().toFloat()
        val avgOffsetY = points.map { it.targetY - it.measuredY }.average().toFloat()

        return Pair(avgOffsetX, avgOffsetY)
    }

    fun reset() {
        points.clear()
        currentPointIndex = 0
    }

    fun getAccuracyMm(): Float {
        if (points.isEmpty()) return 0f
        val errors = points.map { p ->
            val dx = abs(p.targetX - (p.measuredX + computeCorrection().first))
            val dy = abs(p.targetY - (p.measuredY + computeCorrection().second))
            Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
        }
        return errors.average().toFloat() * 100f // rough % of screen
    }
}
