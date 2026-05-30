package com.eyetracker.app.ui

import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.eyetracker.app.ml.EyeTrackingAnalyzer
import java.util.concurrent.Executors

data class CalibPoint(val targetX: Float, val targetY: Float, val samples: MutableList<PointF> = mutableListOf())

class AutoCalibrationActivity : AppCompatActivity() {

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var analyzer: EyeTrackingAnalyzer? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var calibView: CalibrationDrawView
    private lateinit var tvInstruction: TextView
    private lateinit var tvProgress: TextView
    private lateinit var btnStart: Button

    private val points = mutableListOf<CalibPoint>()
    private var currentIndex = 0
    private var isCollecting = false
    private var countdownValue = 3

    // 4 cantos + centro
    private val targets = listOf(
        PointF(0.1f, 0.1f),   // topo esquerdo
        PointF(0.9f, 0.1f),   // topo direito
        PointF(0.9f, 0.9f),   // baixo direito
        PointF(0.1f, 0.9f),   // baixo esquerdo
        PointF(0.5f, 0.5f)    // centro
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        calibView = CalibrationDrawView(this)
        root.addView(calibView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            setPadding(40, 0, 40, 120)
        }

        tvInstruction = TextView(this).apply {
            text = "Calibração de 5 pontos\nOlhe para o alvo e ele será capturado automaticamente"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 12)
        }
        overlay.addView(tvInstruction)

        tvProgress = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(0xFF4FC3F7.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        overlay.addView(tvProgress)

        btnStart = Button(this).apply {
            text = "▶ Iniciar Calibração"
            setBackgroundColor(0xFF1565C0.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { startCalibration() }
        }
        overlay.addView(btnStart, LinearLayout.LayoutParams(600, 120))

        val btnSkip = Button(this).apply {
            text = "Pular"
            setBackgroundColor(0xFF333333.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { finish() }
        }
        overlay.addView(btnSkip, LinearLayout.LayoutParams(600, 100).apply { topMargin = 12 })

        root.addView(overlay)
        setContentView(root)

        setupCamera()
    }

    private fun setupCamera() {
        val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
        analyzer = EyeTrackingAnalyzer(
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            onGazeDetected = { data ->
                if (isCollecting) {
                    points.getOrNull(currentIndex)?.samples?.add(data.gazePoint)
                }
                runOnUiThread { calibView.gazeX = data.gazePoint.x; calibView.gazeY = data.gazePoint.y; calibView.invalidate() }
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

    private fun startCalibration() {
        points.clear()
        targets.forEach { points.add(CalibPoint(it.x, it.y)) }
        currentIndex = 0
        btnStart.visibility = View.GONE
        showCurrentTarget()
    }

    private fun showCurrentTarget() {
        if (currentIndex >= targets.size) {
            finishCalibration()
            return
        }

        val target = targets[currentIndex]
        calibView.targetX = target.x
        calibView.targetY = target.y
        calibView.phase = "approach"
        calibView.countdown = 3
        calibView.invalidate()

        tvProgress.text = "Ponto ${currentIndex + 1} de ${targets.size}"
        tvInstruction.text = "Olhe para o alvo e aguarde..."

        countdownValue = 3
        runCountdown()
    }

    private fun runCountdown() {
        calibView.countdown = countdownValue
        calibView.phase = "countdown"
        calibView.invalidate()

        if (countdownValue > 0) {
            countdownValue--
            handler.postDelayed({ runCountdown() }, 1000)
        } else {
            collectSamples()
        }
    }

    private fun collectSamples() {
        calibView.phase = "collecting"
        calibView.invalidate()
        tvInstruction.text = "Capturando..."
        isCollecting = true

        handler.postDelayed({
            isCollecting = false
            calibView.phase = "done"
            calibView.invalidate()
            handler.postDelayed({
                currentIndex++
                showCurrentTarget()
            }, 400)
        }, 1500)
    }

    private fun finishCalibration() {
        calibView.targetX = -1f
        calibView.invalidate()
        tvInstruction.text = "Calculando calibração..."

        // Calcula offsets para cada ponto
        var totalOffsetX = 0f
        var totalOffsetY = 0f
        var validPoints = 0

        points.forEach { pt ->
            if (pt.samples.isNotEmpty()) {
                val avgX = pt.samples.map { it.x }.average().toFloat()
                val avgY = pt.samples.map { it.y }.average().toFloat()
                totalOffsetX += pt.targetX - avgX
                totalOffsetY += pt.targetY - avgY
                validPoints++
            }
        }

        if (validPoints > 0) {
            val offsetX = totalOffsetX / validPoints
            val offsetY = totalOffsetY / validPoints

            getSharedPreferences("eye_tracker", MODE_PRIVATE).edit()
                .putFloat("calibration_offset_x", offsetX)
                .putFloat("calibration_offset_y", offsetY)
                .apply()

            tvInstruction.text = "✅ Calibração concluída!\nOffset X: ${"%.3f".format(offsetX)} Y: ${"%.3f".format(offsetY)}"
        } else {
            tvInstruction.text = "❌ Falha na calibração. Tente novamente."
        }

        tvProgress.text = ""
        btnStart.text = "Recalibrar"
        btnStart.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        handler.removeCallbacksAndMessages(null)
    }
}

class CalibrationDrawView(context: android.content.Context) : View(context) {

    var targetX = 0.5f
    var targetY = 0.5f
    var gazeX = 0.5f
    var gazeY = 0.5f
    var phase = "idle" // approach, countdown, collecting, done
    var countdown = 3

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gazePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x884FC3F7.toInt()
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 80f
        textAlign = Paint.Align.CENTER
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (targetX < 0) return

        val tx = targetX * width
        val ty = targetY * height
        val gx = gazeX * width
        val gy = gazeY * height

        // Gaze cursor
        gazePaint.color = 0x884FC3F7.toInt()
        canvas.drawCircle(gx, gy, 20f, gazePaint)

        // Outer ring animado
        val ringColor = when (phase) {
            "collecting" -> 0xFF4CAF50.toInt()
            "done"       -> 0xFF4CAF50.toInt()
            "countdown"  -> 0xFFFFC107.toInt()
            else         -> 0xFF4FC3F7.toInt()
        }
        ringPaint.color = ringColor
        canvas.drawCircle(tx, ty, 60f, ringPaint)

        // Inner dot
        targetPaint.style = Paint.Style.FILL
        targetPaint.color = ringColor
        canvas.drawCircle(tx, ty, 20f, targetPaint)

        // Branco central
        targetPaint.color = Color.WHITE
        canvas.drawCircle(tx, ty, 8f, targetPaint)

        // Countdown
        if (phase == "countdown" && countdown > 0) {
            canvas.drawText("$countdown", tx, ty - 100f, textPaint)
        }

        // Collecting progress ring
        if (phase == "collecting") {
            val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 8f
                color = 0xFF4CAF50.toInt()
            }
            canvas.drawCircle(tx, ty, 70f, progressPaint)
        }
    }
}
