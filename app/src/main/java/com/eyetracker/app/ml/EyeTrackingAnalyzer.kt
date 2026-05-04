package com.eyetracker.app.ml

import android.graphics.PointF
import android.graphics.RectF
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs

data class GazeData(
    val gazePoint: PointF,
    val leftEyeOpenProb: Float,
    val rightEyeOpenProb: Float,
    val headEulerX: Float,
    val headEulerY: Float,
    val headEulerZ: Float,
    val leftPupil: PointF?,
    val rightPupil: PointF?,
    val faceBounds: RectF,
    val isBlinking: Boolean,
    val isSingleBlink: Boolean,
    val confidence: Float
)

class EyeTrackingAnalyzer(
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onGazeDetected: (GazeData) -> Unit,
    private val onNoFace: () -> Unit
) : ImageAnalysis.Analyzer {

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build()
    )

    var calibrationOffsetX: Float = 0f
    var calibrationOffsetY: Float = 0f

    private val smoothingAlpha = 0.35f
    private var smoothedX = 0.5f
    private var smoothedY = 0.5f
    private var isFirstFrame = true

    private var wasBlinking = false
    private var blinkStartTime = 0L
    private var lastBlinkTime = 0L
    private val MIN_BLINK_MS = 50L
    private val MAX_BLINK_MS = 600L
    private val BLINK_COOLDOWN_MS = 400L

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) onNoFace()
                else processFace(faces[0], imageProxy.width.toFloat(), imageProxy.height.toFloat())
            }
            .addOnFailureListener { onNoFace() }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun processFace(face: Face, imageWidth: Float, imageHeight: Float) {
        val leftEyeOpen = face.leftEyeOpenProbability ?: 1f
        val rightEyeOpen = face.rightEyeOpenProbability ?: 1f
        val isBlinking = leftEyeOpen < 0.4f && rightEyeOpen < 0.4f

        val now = System.currentTimeMillis()
        var isSingleBlink = false
        if (isBlinking && !wasBlinking) {
            blinkStartTime = now
        } else if (!isBlinking && wasBlinking) {
            val duration = now - blinkStartTime
            val cooldown = now - lastBlinkTime
            if (duration in MIN_BLINK_MS..MAX_BLINK_MS && cooldown > BLINK_COOLDOWN_MS) {
                isSingleBlink = true
                lastBlinkTime = now
            }
        }
        wasBlinking = isBlinking

        val eulerX = face.headEulerAngleX
        val eulerY = face.headEulerAngleY
        val yawNorm = (-eulerY / 45f).coerceIn(-1f, 1f)
        val pitchNorm = (-eulerX / 30f).coerceIn(-1f, 1f)
        var rawX = (0.5f + yawNorm * 0.5f + calibrationOffsetX).coerceIn(0f, 1f)
        var rawY = (0.5f + pitchNorm * 0.5f + calibrationOffsetY).coerceIn(0f, 1f)

        if (isFirstFrame) { smoothedX = rawX; smoothedY = rawY; isFirstFrame = false }
        else {
            smoothedX = smoothingAlpha * rawX + (1f - smoothingAlpha) * smoothedX
            smoothedY = smoothingAlpha * rawY + (1f - smoothingAlpha) * smoothedY
        }

        val leftPupil = face.getLandmark(FaceLandmark.LEFT_EYE)?.position?.let {
            PointF(it.x / imageWidth, it.y / imageHeight)
        }
        val rightPupil = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position?.let {
            PointF(it.x / imageWidth, it.y / imageHeight)
        }

        val bounds = face.boundingBox
        val faceBounds = RectF(
            bounds.left / imageWidth, bounds.top / imageHeight,
            bounds.right / imageWidth, bounds.bottom / imageHeight
        )

        val confidence = (1f - abs(eulerX) / 45f).coerceIn(0f, 1f) *
                         (1f - abs(eulerY) / 45f).coerceIn(0f, 1f)

        onGazeDetected(GazeData(
            gazePoint = PointF(smoothedX, smoothedY),
            leftEyeOpenProb = leftEyeOpen,
            rightEyeOpenProb = rightEyeOpen,
            headEulerX = eulerX,
            headEulerY = eulerY,
            headEulerZ = face.headEulerAngleZ,
            leftPupil = leftPupil,
            rightPupil = rightPupil,
            faceBounds = faceBounds,
            isBlinking = isBlinking,
            isSingleBlink = isSingleBlink,
            confidence = confidence
        ))
    }

    fun reset() { isFirstFrame = true }
}
