package com.eyetracker.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.eyetracker.app.ml.EyeTrackingAnalyzer
import com.eyetracker.app.ml.GazeData
import com.eyetracker.app.overlay.EyeTrackingService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var analyzer: EyeTrackingAnalyzer? = null
    private var isTracking = false
    private lateinit var gazeOverlay: GazeOverlayView
    private lateinit var tvStatus: TextView
    private lateinit var tvGaze: TextView
    private lateinit var tvBlink: TextView
    private lateinit var btnStartStop: Button
    private lateinit var cameraPreview: PreviewView

    // Configurações
    private var sensitivity = 1.0f
    private var smoothing = 0.35f
    private var blinkThreshold = 0.4f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Carrega configurações salvas
        val prefs = getSharedPreferences("eye_tracker", MODE_PRIVATE)
        sensitivity = prefs.getFloat("sensitivity", 1.0f)
        smoothing = prefs.getFloat("smoothing", 0.35f)
        blinkThreshold = prefs.getFloat("blink_threshold", 0.4f)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF0A0E1A.toInt())
        }
        val root = FrameLayout(this)
        scroll.addView(root, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        cameraPreview = PreviewView(this)
        root.addView(cameraPreview, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 600
        ))

        gazeOverlay = GazeOverlayView(this)
        gazeOverlay.visibility = View.GONE
        root.addView(gazeOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 620, 40, 40)
        }

        // Status
        tvStatus = TextView(this).apply {
            text = "Iniciando..."
            textSize = 15f
            setTextColor(0xFFB0BEC5.toInt())
            setPadding(0, 0, 0, 4)
        }
        controls.addView(tvStatus)

        tvGaze = TextView(this).apply {
            text = "Gaze: --"
            textSize = 12f
            setTextColor(0xFF64B5F6.toInt())
            setPadding(0, 0, 0, 4)
        }
        controls.addView(tvGaze)

        tvBlink = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(0xFFFFC107.toInt())
            setPadding(0, 0, 0, 16)
        }
        controls.addView(tvBlink)

        // Botão tracking
        btnStartStop = Button(this).apply {
            text = "▶ Iniciar Tracking"
            setBackgroundColor(0xFF1565C0.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { if (isTracking) stopTracking() else startTracking() }
        }
        controls.addView(btnStartStop, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 130
        ).apply { bottomMargin = 16 })

        // Botão overlay
        val btnOverlay = Button(this).apply {
            text = "👁 Ativar Overlay Global"
            setBackgroundColor(0xFF1A0D37.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { toggleOverlay() }
        }
        controls.addView(btnOverlay, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 120
        ).apply { bottomMargin = 16 })

        // Botão acessibilidade
        val btnAccessibility = Button(this).apply {
            text = "♿ Ativar Piscada = Clique"
            setBackgroundColor(0xFF1A3720.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this@MainActivity,
                    "Ative 'Eye Tracker Click' na lista",
                    Toast.LENGTH_LONG).show()
            }
        }
        controls.addView(btnAccessibility, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 120
        ).apply { bottomMargin = 24 })

        // ── SEÇÃO DE CALIBRAÇÃO ──
        val tvConfigTitle = TextView(this).apply {
            text = "⚙ Calibração"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 12)
        }
        controls.addView(tvConfigTitle)

        // Sensibilidade do cursor
        val tvSensLabel = TextView(this).apply {
            text = "Sensibilidade do cursor: ${"%.1f".format(sensitivity)}x"
            textSize = 13f
            setTextColor(0xFFB0BEC5.toInt())
        }
        controls.addView(tvSensLabel)

        val seekSensitivity = SeekBar(this).apply {
            max = 30
            progress = ((sensitivity - 0.5f) * 20).toInt().coerceIn(0, 30)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) {
                    sensitivity = 0.5f + p / 20f
                    tvSensLabel.text = "Sensibilidade do cursor: ${"%.1f".format(sensitivity)}x"
                    analyzer?.sensitivityX = sensitivity
                    analyzer?.sensitivityY = sensitivity
                    prefs.edit().putFloat("sensitivity", sensitivity).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        controls.addView(seekSensitivity, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 })

        // Suavização do cursor
        val tvSmoothLabel = TextView(this).apply {
            text = "Suavização (velocidade): ${"%.2f".format(smoothing)}"
            textSize = 13f
            setTextColor(0xFFB0BEC5.toInt())
        }
        controls.addView(tvSmoothLabel)

        val seekSmoothing = SeekBar(this).apply {
            max = 19
            progress = ((smoothing - 0.05f) * 20).toInt().coerceIn(0, 19)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) {
                    smoothing = 0.05f + p / 20f
                    tvSmoothLabel.text = "Suavização (velocidade): ${"%.2f".format(smoothing)}"
                    analyzer?.smoothingAlphaPublic = smoothing
                    prefs.edit().putFloat("smoothing", smoothing).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        controls.addView(seekSmoothing, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 })

        // Sensibilidade de piscada
        val tvBlinkLabel = TextView(this).apply {
            text = "Sensibilidade de piscada: ${"%.2f".format(blinkThreshold)}"
            textSize = 13f
            setTextColor(0xFFB0BEC5.toInt())
        }
        controls.addView(tvBlinkLabel)

        val seekBlink = SeekBar(this).apply {
            max = 20
            progress = ((blinkThreshold - 0.1f) * 20).toInt().coerceIn(0, 20)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) {
                    blinkThreshold = 0.1f + p / 20f
                    tvBlinkLabel.text = "Sensibilidade de piscada: ${"%.2f".format(blinkThreshold)}"
                    analyzer?.blinkThreshold = blinkThreshold
                    prefs.edit().putFloat("blink_threshold", blinkThreshold).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        controls.addView(seekBlink, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 16 })

        // Dica
        val tvDica = TextView(this).apply {
            text = "💡 Cursor lento? Aumente sensibilidade.\nCursor tremido? Aumente suavização.\nPiscada não detectada? Aumente sensibilidade de piscada."
            textSize = 11f
            setTextColor(0xFF546E7A.toInt())
            setPadding(8, 8, 8, 8)
        }
        controls.addView(tvDica)

        root.addView(controls)
        setContentView(scroll)

        checkPermissions()
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        } else {
            startCamera()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        }
    }

    private fun startCamera() {
        val prefs = getSharedPreferences("eye_tracker", MODE_PRIVATE)
        val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
        analyzer = EyeTrackingAnalyzer(
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            onGazeDetected = { data -> runOnUiThread { handleGaze(data) } },
            onNoFace = { runOnUiThread { tvStatus.text = "Nenhum rosto detectado" } }
        ).apply {
            sensitivityX = sensitivity
            sensitivityY = sensitivity
            smoothingAlphaPublic = smoothing
            blinkThreshold = this@MainActivity.blinkThreshold
            calibrationOffsetX = prefs.getFloat("calibration_offset_x", 0f)
            calibrationOffsetY = prefs.getFloat("calibration_offset_y", 0f)
        }

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
                tvStatus.text = "Câmera pronta"
            } catch (e: Exception) {
                tvStatus.text = "Erro: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleGaze(data: GazeData) {
        if (isTracking) gazeOverlay.gazeData = data
        tvGaze.text = "Gaze: ${"%.2f".format(data.gazePoint.x)}, ${"%.2f".format(data.gazePoint.y)}"
        tvStatus.text = if (data.isBlinking) "👁 PISCANDO..." else "✅ Rastreando"
        if (data.isSingleBlink) {
            tvBlink.text = "👆 CLIQUE!"
            android.os.Handler(mainLooper).postDelayed({ tvBlink.text = "" }, 500)
        }
    }

    private fun startTracking() {
        isTracking = true
        gazeOverlay.startTracking()
        gazeOverlay.visibility = View.VISIBLE
        btnStartStop.text = "⏹ Parar Tracking"
        btnStartStop.setBackgroundColor(0xFFC62828.toInt())
    }

    private fun stopTracking() {
        isTracking = false
        gazeOverlay.stopTracking()
        gazeOverlay.visibility = View.GONE
        btnStartStop.text = "▶ Iniciar Tracking"
        btnStartStop.setBackgroundColor(0xFF1565C0.toInt())
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            Toast.makeText(this, "Ative a permissão e volte ao app", Toast.LENGTH_LONG).show()
            return
        }
        val serviceIntent = Intent(this, EyeTrackingService::class.java)
        if (EyeTrackingService.isRunning) {
            stopService(serviceIntent)
            Toast.makeText(this, "Overlay desativado", Toast.LENGTH_SHORT).show()
        } else {
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, "Overlay ativo! Pisque para clicar!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
