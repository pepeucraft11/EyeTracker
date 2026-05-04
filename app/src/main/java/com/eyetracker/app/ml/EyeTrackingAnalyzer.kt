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
import kotlin.math.*

data class GazeData(
    val gazePoint: PointF,          // normalized 0..1 gaze point on screen
    val leftEyeOpenProb: Float,
    val rightEyeOpenProb: Float,
    val headEulerX: Float,          // pitch (tilt up/down)
    val headEulerY: Float,          // yaw  (turn left/right)
    val headEulerZ: Float,          // roll (tilt sideways)
    val leftPupil: PointF?,
    val rightPupil: PointF?,
    val faceBounds: RectF,
    val isBlinking: Boolean,
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

    // Calibration offsets
    var calibrationOffsetX: Float = 0f
    var calibrationOffsetY: Float = 0f

    // Smoothing filter
    private val smoothingAlpha = 0.35f
    private var smoothedX = 0.5f
    private var smoothedY = 0.5f
    private var isFirstFrame = true

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        faceDetector.process(image)
            .addOnSuccessListener { faces ->
                if (faces.isEmpty()) {
                    onNoFace()
                } else {
                    processFace(faces[0], imageProxy.width.toFloat(), imageProxy.height.toFloat())
                }
            }
            .addOnFailureListener { onNoFace() }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun processFace(face: Face, imageWidth: Float, imageHeight: Float) {
        val leftEyeOpen  = face.leftEyeOpenProbability  ?: 1f
        val rightEyeOpen = face.rightEyeOpenProbability ?: 1f
        val isBlinking   = leftEyeOpen < 0.3f && rightEyeOpen < 0.3f

        val eulerX = face.headEulerAngleX  // pitch: negative = look down
        val eulerY = face.headEulerAngleY  // yaw:   negative = look left (front cam is mirrored)
        val eulerZ = face.headEulerAngleZ  // roll

        // --- Gaze estimation from head pose ---
        // Front camera image is mirrored on X, so invert eulerY
        val yawNorm   = (-eulerY / 45f).coerceIn(-1f, 1f)   // left/right
        val pitchNorm = (-eulerX / 30f).coerceIn(-1f, 1f)   // up/down

        // Map to screen coordinates (0..1)
        var rawX = 0.5f + yawNorm   * 0.5f
        var rawY = 0.5f + pitchNorm * 0.5f

        // Apply calibration
        rawX += calibrationOffsetX
        rawY += calibrationOffsetY
        rawX = rawX.coerceIn(0f, 1f)
        rawY = rawY.coerceIn(0f, 1f)

        // Apply exponential moving average smoothing
        if (isFirstFrame) {
            smoothedX = rawX
            smoothedY = rawY
            isFirstFrame = false
        } else {
            smoothedX = smoothingAlpha * rawX + (1f - smoothingAlpha) * smoothedX
            smoothedY = smoothingAlpha * rawY + (1f - smoothingAlpha) * smoothedY
        }

        // Landmarks
        val leftEyeLandmark  = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEyeLandmark = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position

        val leftPupil  = leftEyeLandmark?.let  { PointF(it.x / imageWidth, it.y / imageHeight) }
        val rightPupil = rightEyeLandmark?.let { PointF(it.x / imageWidth, it.y / imageHeight) }

        // Face bounds normalized
        val bounds = face.boundingBox
        val faceBounds = RectF(
            bounds.left   / imageWidth,
            bounds.top    / imageHeight,
            bounds.right  / imageWidth,
            bounds.bottom / imageHeight
        )

        val confidence = (1f - abs(eulerX) / 45f).coerceIn(0f, 1f) *
                         (1f - abs(eulerY) / 45f).coerceIn(0f, 1f)

        onGazeDetected(
            GazeData(
                gazePoint       = PointF(smoothedX, smoothedY),
                leftEyeOpenProb = leftEyeOpen,
                rightEyeOpenProb= rightEyeOpen,
                headEulerX      = eulerX,
                headEulerY      = eulerY,
                headEulerZ      = eulerZ,
                leftPupil       = leftPupil,
                rightPupil      = rightPupil,
                faceBounds      = faceBounds,
                isBlinking      = isBlinking,
                confidence      = confidence
            )
        )
    }

    fun reset() { isFirstFrame = true }
}
