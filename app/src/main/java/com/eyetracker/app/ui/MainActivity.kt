package com.eyetracker.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.eyetracker.app.R
import com.eyetracker.app.databinding.ActivityMainBinding
import com.eyetracker.app.ml.CalibrationManager
import com.eyetracker.app.ml.EyeTrackingAnalyzer
import com.eyetracker.app.ml.GazeData
import com.eyetracker.app.overlay.EyeTrackingService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraExecutor: ExecutorService
    private var analyzer: EyeTrackingAnalyzer? = null
    private val calibrationManager = CalibrationManager()
    private var isTracking = false
    private var lastGaze: PointF? = null

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val OVERLAY_PERMISSION_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        binding.btnStartStop.setOnClickListener {
            if (isTracking) stopTracking() else startTracking()
        }

        binding.btnCalibrate.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        binding.btnDemo.setOnClickListener {
            startActivity(Intent(this, DemoActivity::class.java))
        }

        binding.btnOverlay.setOnClickListener {
            toggleOverlayService()
        }

        binding.switchHeatmap.setOnCheckedChangeListener { _, checked ->
            binding.gazeOverlay.showHeatmap = checked
        }

        binding.switchTrail.setOnCheckedChangeListener { _, checked ->
            binding.gazeOverlay.showTrail = checked
        }

        binding.btnClearHeatmap.setOnClickListener {
            binding.gazeOverlay.clearHeatmap()
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
            )
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
        }
    }

    private fun startCamera() {
        val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }

        analyzer = EyeTrackingAnalyzer(
            screenWidth  = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            onGazeDetected = { gazeData -> runOnUiThread { handleGaze(gazeData) } },
            onNoFace       = { runOnUiThread { handleNoFace() } }
        )

        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, analyzer!!) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleGaze(data: GazeData) {
        lastGaze = data.gazePoint

        if (isTracking) {
            binding.gazeOverlay.gazeData = data
        }

        // Update stats
        binding.tvGazeX.text   = "X: ${"%.2f".format(data.gazePoint.x)}"
        binding.tvGazeY.text   = "Y: ${"%.2f".format(data.gazePoint.y)}"
        binding.tvYaw.text     = "Yaw: ${"%.1f".format(data.headEulerY)}°"
        binding.tvPitch.text   = "Pitch: ${"%.1f".format(data.headEulerX)}°"
        binding.tvBlink.text   = if (data.isBlinking) "👁 BLINK" else ""
        binding.tvConfidence.text = "Conf: ${"%.0f".format(data.confidence * 100)}%"
        binding.tvLeftEye.text = "L: ${"%.0f".format(data.leftEyeOpenProb * 100)}%"
        binding.tvRightEye.text= "R: ${"%.0f".format(data.rightEyeOpenProb * 100)}%"

        binding.statusIndicator.setBackgroundResource(R.drawable.circle_green)
        binding.tvStatus.text = "Tracking"
    }

    private fun handleNoFace() {
        if (isTracking) {
            binding.gazeOverlay.gazeData = null
        }
        binding.statusIndicator.setBackgroundResource(R.drawable.circle_red)
        binding.tvStatus.text = "No face"
        binding.tvBlink.text = ""
    }

    private fun startTracking() {
        isTracking = true
        binding.gazeOverlay.startTracking()
        binding.gazeOverlay.visibility = View.VISIBLE
        binding.btnStartStop.text = "Stop Tracking"
        binding.btnStartStop.setBackgroundColor(
            ContextCompat.getColor(this, R.color.stop_color)
        )
    }

    private fun stopTracking() {
        isTracking = false
        binding.gazeOverlay.stopTracking()
        binding.gazeOverlay.visibility = View.GONE
        binding.btnStartStop.text = "Start Tracking"
        binding.btnStartStop.setBackgroundColor(
            ContextCompat.getColor(this, R.color.start_color)
        )
    }

    private fun toggleOverlayService() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Overlay Permission Required")
                .setMessage("To show the gaze cursor over other apps, please grant the 'Display over other apps' permission.")
                .setPositiveButton("Grant") { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivityForResult(intent, OVERLAY_PERMISSION_CODE)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        val serviceIntent = Intent(this, EyeTrackingService::class.java)
        if (EyeTrackingService.isRunning) {
            stopService(serviceIntent)
            binding.btnOverlay.text = "Enable Global Overlay"
        } else {
            ContextCompat.startForegroundService(this, serviceIntent)
            binding.btnOverlay.text = "Disable Global Overlay"
            Toast.makeText(this, "Eye tracking overlay active system-wide!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
