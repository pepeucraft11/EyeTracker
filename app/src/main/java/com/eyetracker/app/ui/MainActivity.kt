package com.eyetracker.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.eyetracker.app.ml.EyeTrackingAnalyzer
import com.eyetracker.app.ml.GazeData
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var analyzer: EyeTrackingAnalyzer? = null
    private var isTracking = false
    private lateinit var gazeOverlay: GazeOverlayView
    private lateinit var tvStatus: TextView
    private lateinit var tvGazeX: TextView
    private lateinit var tvGazeY: TextView
    private lateinit var btnStartStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = android.widget.FrameLayout(this)

        // Camera preview
        val cameraPreview = PreviewView(this)
        root.addView(cameraPreview, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 600
        ))

        // Gaze overlay
        gazeOverlay = GazeOverlayView(this)
        root.addView(gazeOverlay, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))

        // Controls layout
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 650, 32, 32)
        }

        tvStatus = TextView(this).apply { text = "Camera ready"; textSize = 14f; setTextColor(0xFFB0BEC5.toInt()) }
        controls.addView(tvStatus)

        tvGazeX = TextView(this).apply { text = "X: --"; textSize = 12f; setTextColor(0xFF64B5F6.toInt()) }
        controls.addView(tvGazeX)

        tvGazeY = TextView(this).apply { text = "Y: --"; textSize = 12f; setTextColor(0xFF64B5F6.toInt()) }
        controls.addView(tvGazeY)

        btnStartStop = Button(this).apply {
            text = "Start Tracking"
            setOnClickListener { if (isTracking) stopTracking() else startTracking() }
        }
        controls.addView(btnStartStop)

        root.addView(controls)
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        } else {
            startCamera(cameraPreview)
        }
    }

    private fun startCamera(cameraPreview: PreviewView) {
        val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
        analyzer = EyeTrackingAnalyzer(
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            onGazeDetected = { data -> runOnUiThread { handleGaze(data) } },
            onNoFace = { runOnUiThread { tvStatus.text = "No face detected" } }
        )
        ProcessCameraProvider.getInstance(this).addListener({
            val provider = ProcessCameraProvider.getInstance(this).get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(cameraPreview.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(cameraExecutor, analyzer!!) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            } catch (e: Exception) {
                tvStatus.text = "Camera error: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleGaze(data: GazeData) {
        if (isTracking) gazeOverlay.gazeData = data
        tvGazeX.text = "X: ${"%.2f".format(data.gazePoint.x)}"
        tvGazeY.text = "Y: ${"%.2f".format(data.gazePoint.y)}"
        tvStatus.text = if (data.isBlinking) "BLINK!" else "Tracking"
    }

    private fun startTracking() {
        isTracking = true
        gazeOverlay.startTracking()
        gazeOverlay.visibility = android.view.View.VISIBLE
        btnStartStop.text = "Stop Tracking"
    }

    private fun stopTracking() {
        isTracking = false
        gazeOverlay.stopTracking()
        btnStartStop.text = "Start Tracking"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            val preview = (window.decorView.rootView as android.widget.FrameLayout).getChildAt(0) as PreviewView
            startCamera(preview)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
