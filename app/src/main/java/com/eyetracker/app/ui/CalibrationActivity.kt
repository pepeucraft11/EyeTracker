package com.eyetracker.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.PointF
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.eyetracker.app.R
import com.eyetracker.app.databinding.ActivityCalibrationBinding
import com.eyetracker.app.ml.CalibrationManager
import com.eyetracker.app.ml.EyeTrackingAnalyzer
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import java.util.concurrent.Executors

class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private val calibrationManager = CalibrationManager()
    private var analyzer: EyeTrackingAnalyzer? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var gazeBuffer = mutableListOf<PointF>()
    private var isCollecting = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setupCamera()
        binding.btnNext.setOnClickListener { advanceCalibration() }
        binding.btnSkip.setOnClickListener { finish() }
        showCurrentTarget()
    }

    private fun setupCamera() {
        val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
        analyzer = EyeTrackingAnalyzer(
            screenWidth  = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            onGazeDetected = { data ->
                if (isCollecting) gazeBuffer.add(data.gazePoint)
            },
            onNoFace = {}
        )

        ProcessCameraProvider.getInstance(this).addListener({
            val provider = ProcessCameraProvider.getInstance(this).get()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(cameraExecutor, analyzer!!) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (_: Exception) {}
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showCurrentTarget() {
        val target = calibrationManager.currentTarget
        if (target == null) {
            finishCalibration()
            return
        }

        val sw = resources.displayMetrics.widthPixels.toFloat()
        val sh = resources.displayMetrics.heightPixels.toFloat()

        binding.calibrationDot.animate()
            .x(target.x * sw - 40f)
            .y(target.y * sh - 40f)
            .scaleX(1.5f).scaleY(1.5f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator())
            .withEndAction {
                binding.calibrationDot.animate()
                    .scaleX(1f).scaleY(1f).setDuration(200).start()
            }.start()

        binding.tvProgress.text =
            "Point ${calibrationManager.currentPointIndex + 1} of ${calibrationManager.calibrationTargets.size}"
        binding.progressBar.progress =
            (calibrationManager.progress * 100).toInt()

        binding.tvInstruction.text = "Look at the dot and tap CAPTURE"
        binding.btnNext.text = "CAPTURE"
    }

    private fun advanceCalibration() {
        binding.tvInstruction.text = "Hold still…"
        binding.btnNext.isEnabled = false
        gazeBuffer.clear()
        isCollecting = true

        scope.launch {
            delay(1500) // collect 1.5s of data
            isCollecting = false

            if (gazeBuffer.isNotEmpty()) {
                val avgX = gazeBuffer.map { it.x }.average().toFloat()
                val avgY = gazeBuffer.map { it.y }.average().toFloat()
                calibrationManager.recordPoint(PointF(avgX, avgY))
            }

            gazeBuffer.clear()
            binding.btnNext.isEnabled = true

            if (calibrationManager.isComplete) {
                finishCalibration()
            } else {
                showCurrentTarget()
            }
        }
    }

    private fun finishCalibration() {
        val (offsetX, offsetY) = calibrationManager.computeCorrection()
        val prefs = getSharedPreferences("eye_tracker", MODE_PRIVATE)
        prefs.edit()
            .putFloat("calibration_offset_x", offsetX)
            .putFloat("calibration_offset_y", offsetY)
            .apply()

        Toast.makeText(this,
            "Calibration complete! Accuracy ≈ ${"%.1f".format(calibrationManager.getAccuracyMm())}%",
            Toast.LENGTH_LONG
        ).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        cameraExecutor.shutdown()
    }
}
