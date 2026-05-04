package com.eyetracker.app.ui

import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.eyetracker.app.R
import com.eyetracker.app.databinding.ActivityDemoBinding
import com.eyetracker.app.ml.EyeTrackingAnalyzer
import com.eyetracker.app.ml.GazeData
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import java.util.concurrent.Executors
import kotlin.math.abs

class DemoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDemoBinding
    private var analyzer: EyeTrackingAnalyzer? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Dwell-click state
    private var dwellTarget: View? = null
    private var dwellStartTime = 0L
    private val DWELL_MS = 1500L
    private var dwellJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupCamera()
        setupDemoTargets()

        binding.btnBack.setOnClickListener { finish() }
    }

    private fun setupCamera() {
        val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
        val prefs = getSharedPreferences("eye_tracker", MODE_PRIVATE)

        analyzer = EyeTrackingAnalyzer(
            screenWidth  = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            onGazeDetected = { data ->
                analyzer?.calibrationOffsetX = prefs.getFloat("calibration_offset_x", 0f)
                analyzer?.calibrationOffsetY = prefs.getFloat("calibration_offset_y", 0f)
                runOnUiThread { handleGaze(data) }
            },
            onNoFace = {}
        )

        ProcessCameraProvider.getInstance(this).addListener({
            val provider = ProcessCameraProvider.getInstance(this).get()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(cameraExecutor, analyzer!!) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleGaze(data: GazeData) {
        binding.gazeOverlay.gazeData = data

        // Blink indicator
        binding.blinkIndicator.visibility = if (data.isBlinking) View.VISIBLE else View.INVISIBLE

        // Check dwell-click on demo buttons
        val gazeX = data.gazePoint.x * binding.root.width
        val gazeY = data.gazePoint.y * binding.root.height

        val targets = listOf(
            binding.demoBtn1, binding.demoBtn2,
            binding.demoBtn3, binding.demoBtn4,
            binding.demoBtn5
        )

        var hitTarget: View? = null
        for (target in targets) {
            val loc = IntArray(2)
            target.getLocationOnScreen(loc)
            val tx = loc[0] + target.width / 2f
            val ty = loc[1] + target.height / 2f
            if (abs(gazeX - tx) < 80f && abs(gazeY - ty) < 80f) {
                hitTarget = target
                break
            }
        }

        if (hitTarget != null && hitTarget != dwellTarget) {
            dwellTarget = hitTarget
            dwellStartTime = System.currentTimeMillis()
            startDwellTimer(hitTarget)
        } else if (hitTarget == null) {
            dwellTarget = null
            dwellJob?.cancel()
            binding.dwellProgress.progress = 0
        }
    }

    private fun startDwellTimer(target: View) {
        dwellJob?.cancel()
        dwellJob = scope.launch {
            val start = System.currentTimeMillis()
            while (isActive) {
                val elapsed = System.currentTimeMillis() - start
                val progress = (elapsed * 100 / DWELL_MS).toInt().coerceIn(0, 100)
                binding.dwellProgress.progress = progress
                if (elapsed >= DWELL_MS) {
                    activateTarget(target)
                    break
                }
                delay(16)
            }
        }
    }

    private fun activateTarget(target: View) {
        target.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in))
        target.performClick()
        binding.dwellProgress.progress = 0
        dwellTarget = null
    }

    private fun setupDemoTargets() {
        val buttons = listOf(
            binding.demoBtn1 to "🔴 Red",
            binding.demoBtn2 to "🟢 Green",
            binding.demoBtn3 to "🔵 Blue",
            binding.demoBtn4 to "🟡 Yellow",
            binding.demoBtn5 to "🟣 Purple"
        )
        val colors = listOf(0xFFE53935, 0xFF43A047, 0xFF1E88E5, 0xFFFDD835, 0xFF8E24AA)

        buttons.forEachIndexed { i, (btn, label) ->
            btn.text = label
            btn.setOnClickListener {
                binding.demoCanvas.setBackgroundColor(colors[i].toInt() or 0xFF000000.toInt())
                binding.tvDemoFeedback.text = "Gaze selected: $label"
                Toast.makeText(this, "Selected by gaze: $label", Toast.LENGTH_SHORT).show()
            }
        }

        binding.gazeOverlay.startTracking()
        binding.gazeOverlay.showTrail = true
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        cameraExecutor.shutdown()
    }
}
