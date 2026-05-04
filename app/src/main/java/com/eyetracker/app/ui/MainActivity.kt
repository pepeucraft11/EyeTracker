package com.eyetracker.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.eyetracker.app.R
import com.eyetracker.app.databinding.ActivityMainBinding
import com.eyetracker.app.ml.EyeTrackingAnalyzer
import com.eyetracker.app.ml.GazeData
import com.eyetracker.app.overlay.EyeTrackingService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var analyzer: EyeTrackingAnalyzer? = null
    private var isTracking = false

    companion object {
        private const val TAG = "EyeTracker"
        private const val CAMERA_PERMISSION_CODE = 100
        private const val OVERLAY_PERMISSION_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            Log.d(TAG, "onCreate start")
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            Log.d(TAG, "layout inflated")
            setupUI()
            checkPermissions()
            Log.d(TAG, "onCreate done")
        } catch (e: Exception) {
            Log.e(TAG, "CRASH onCreate: ${e.message}", e)
            Toast.makeText(this, "Erro: ${e.message}", Toast.LENGTH_LONG).show()
        }
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
        binding.btnOverlay.setOnClickListener { toggleOverlayService() }
        binding.switchHeatmap.setOnCheckedChangeListener { _, checked ->
            binding.gazeOverlay.showHeatmap = checked
        }
        binding.switchTrail.setOnCheckedChangeListener { _, checked ->
            binding.gazeOverlay.showTrail = checked
        }
        binding.btnClearHeatmap.setOnClickListener { binding.gazeOverlay.clearHeatmap() }
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
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    private fun startCamera() {
        try {
            val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
            analyzer = EyeTrackingAnalyzer(
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels,
                onGazeDetected = { data -> runOnUiThread { handleGaze(data) } },
                onNoFace = { runOnUiThread { handleNoFace() } }
            )
            ProcessCameraProvider.getInstance(this).addListener({
                try {
                    val provider = ProcessCameraProvider.getInstance(this).get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                    }
                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build().also { it.setAnalyzer(cameraExecutor, analyzer!!) }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Camera bind error: ${e.message}", e)
                    Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.e(TAG, "startCamera error: ${e.message}", e)
        }
    }

    private fun handleGaze(data: GazeData) {
        if (isTracking) binding.gazeOverlay.gazeData = data
        binding.tvGazeX.text = "X: ${"%.2f".format(data.gazePoint.x)}"
        binding.tvGazeY.text = "Y: ${"%.2f".format(data.gazePoint.y)}"
        binding.tvYaw.text = "Yaw: ${"%.1f".format(data.headEulerY)}°"
        binding.tvPitch.text = "Pitch: ${"%.1f".format(data.headEulerX)}°"
        binding.tvBlink.text = if (data.isBlinking) "👁 BLINK" else ""
        binding.tvConfidence.text = "Conf: ${"%.0f".format(data.confidence * 100)}%"
        binding.tvLeftEye.text = "L: ${"%.0f".format(data.leftEyeOpenProb * 100)}%"
        binding.tvRightEye.text = "R: ${"%.0f".format(data.rightEyeOpenProb * 100)}%"
        binding.statusIndicator.setBackgroundResource(R.drawable.circle_green)
        binding.tvStatus.text = "Tracking"
    }

    private fun handleNoFace() {
        if (isTracking) binding.gazeOverlay.gazeData = null
        binding.statusIndicator.setBackgroundResource(R.drawable.circle_red)
        binding.tvStatus.text = "No face"
        binding.tvBlink.text = ""
    }

    private fun startTracking() {
        isTracking = true
        binding.gazeOverlay.startTracking()
        binding.gazeOverlay.visibility = View.VISIBLE
        binding.btnStartStop.text = "Stop Tracking"
        binding.btnStartStop.setBackgroundColor(ContextCompat.getColor(this, R.color.stop_color))
    }

    private fun stopTracking() {
        isTracking = false
        binding.gazeOverlay.stopTracking()
        binding.gazeOverlay.visibility = View.GONE
        binding.btnStartStop.text = "Start Tracking"
        binding.btnStartStop.setBackgroundColor(ContextCompat.getColor(this, R.color.start_color))
    }

    private fun toggleOverlayService() {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Overlay Permission Required")
                .setPositiveButton("Grant") { _, _ ->
                    startActivityForResult(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")), OVERLAY_PERMISSION_CODE
                    )
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        val serviceIntent = Intent(this, EyeTrackingService::class.java)
        if (EyeTrackingService.isRunning) {
            stopService(serviceIntent)
            binding.btnOverlay.text = "Enable Global Overlay"
        } else {
            ContextCompat.startForegroundService(this, serviceIntent)
            binding.btnOverlay.text = "Disable Global Overlay"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
