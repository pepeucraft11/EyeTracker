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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(0xFF0A0E1A.toInt())
        }
        val root = android.widget.FrameLayout(this)
        scroll.addView(root, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))

        cameraPreview = PreviewView(this)
        root.addView(cameraPreview, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 600
        ))

        gazeOverlay = GazeOverlayView(this)
        gazeOverlay.visibility = View.GONE
        root.addView(gazeOverlay, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 620, 40, 40)
        }

        tvStatus = TextView(this).apply {
            text = "Iniciando..."
            textSize = 15f
            setTextColor(0xFFB0BEC5.toInt())
            setPadding(0, 0, 0, 8)
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

        btnStartStop = Button(this).apply {
            text = "▶ Iniciar Tracking"
            setBackgroundColor(0xFF1565C0.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 8)
            setOnClickListener { if (isTracking) stopTracking() else startTracking() }
        }
        controls.addView(btnStartStop, LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 130
        ).apply { bottomMargin = 16 })

        val btnOverlay = Button(this).apply {
            text = "👁 Ativar Overlay Global"
            setBackgroundColor(0xFF1A0D37.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { toggleOverlay() }
        }
        controls.addView(btnOverlay, LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 120
        ).apply { bottomMargin = 16 })

        val btnAccessibility = Button(this).apply {
            text = "♿ Ativar Piscada = Clique"
            setBackgroundColor(0xFF1A3720.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this@MainActivity,
                    "Ative 'Eye Tracker Click' na lista de acessibilidade",
                    Toast.LENGTH_LONG).show()
            }
        }
        controls.addView(btnAccessibility, LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, 120
        ).apply { bottomMargin = 16 })

        val instrucoes = TextView(this).apply {
            text = "Como usar piscada como clique:\n1. Toque em 'Ativar Piscada = Clique'\n2. Ative 'Eye Tracker Click' nas configurações\n3. Ative o Overlay Global\n4. Use o olhar para mirar e pisque para clicar!"
            textSize = 12f
            setTextColor(0xFF78909C.toInt())
            setPadding(8, 8, 8, 8)
        }
        controls.addView(instrucoes)

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
        val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
        analyzer = EyeTrackingAnalyzer(
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            onGazeDetected = { data -> runOnUiThread { handleGaze(data) } },
            onNoFace = { runOnUiThread { tvStatus.text = "Nenhum rosto detectado" } }
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
                tvStatus.text = "Câmera pronta"
            } catch (e: Exception) {
                tvStatus.text = "Erro na câmera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleGaze(data: GazeData) {
        if (isTracking) gazeOverlay.gazeData = data
        tvGaze.text = "Gaze: ${"%.2f".format(data.gazePoint.x)}, ${"%.2f".format(data.gazePoint.y)}"
        tvStatus.text = if (data.isBlinking) "👁 PISCANDO..." else "✅ Rastreando"
        tvBlink.text = if (data.isSingleBlink) "👆 CLIQUE!" else ""
        if (data.isSingleBlink) {
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
