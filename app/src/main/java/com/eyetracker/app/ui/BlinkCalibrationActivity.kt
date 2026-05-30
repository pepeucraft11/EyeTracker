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

class BlinkCalibrationActivity : AppCompatActivity() {

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var analyzer: EyeTrackingAnalyzer? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvInstruction: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var blinkView: BlinkCalibView
    private lateinit var btnAction: Button

    // Dados de calibração
    private val openSamples  = mutableListOf<Float>() // olhos abertos
    private val closedSamples = mutableListOf<Float>() // olhos fechados
    private var phase = "idle" // idle, measuring_open, measuring_closed, done

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(48, 80, 48, 80)
            gravity = android.view.Gravity.CENTER
        }

        val tvTitle = TextView(this).apply {
            text = "👁 Calibração de Piscada"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        root.addView(tvTitle)

        val tvSubtitle = TextView(this).apply {
            text = "Aprenderemos como são seus olhos abertos e fechados"
            textSize = 13f
            setTextColor(0xFF78909C.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        root.addView(tvSubtitle)

        blinkView = BlinkCalibView(this)
        root.addView(blinkView, LinearLayout.LayoutParams(300, 300).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = 32
        })

        tvInstruction = TextView(this).apply {
            text = "Toque em 'Iniciar' para começar"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }
        root.addView(tvInstruction)

        tvStatus = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(0xFF4FC3F7.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        root.addView(tvStatus)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF4FC3F7.toInt())
        }
        root.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 20
        ).apply { bottomMargin = 24 })

        tvResult = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(0xFF4CAF50.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        root.addView(tvResult)

        btnAction = Button(this).apply {
            text = "▶ Iniciar Calibração"
            setBackgroundColor(0xFF1565C0.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { onButtonClick() }
        }
        root.addView(btnAction, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 130
        ).apply { bottomMargin = 12 })

        val btnSkip = Button(this).apply {
            text = "Voltar"
            setBackgroundColor(0xFF333333.toInt())
            setTextColor(Color.WHITE)
            setOnClickListener { finish() }
        }
        root.addView(btnSkip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 100
        ))

        setContentView(root)
        setupCamera()
    }

    private fun setupCamera() {
        val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
        analyzer = EyeTrackingAnalyzer(
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            onGazeDetected = { data ->
                val avg = (data.leftEyeOpenProb + data.rightEyeOpenProb) / 2f
                runOnUiThread {
                    blinkView.eyeOpenness = avg
                    blinkView.invalidate()
                    tvStatus.text = "Abertura dos olhos: ${"%.0f".format(avg * 100)}%"
                }
                when (phase) {
                    "measuring_open"   -> openSamples.add(avg)
                    "measuring_closed" -> closedSamples.add(avg)
                }
            },
            onNoFace = {
                runOnUiThread { tvStatus.text = "⚠ Nenhum rosto detectado" }
            }
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

    private fun onButtonClick() {
        when (phase) {
            "idle" -> startOpenMeasurement()
            "waiting_closed" -> startClosedMeasurement()
            "done" -> startOpenMeasurement() // recalibrar
        }
    }

    private fun startOpenMeasurement() {
        openSamples.clear()
        closedSamples.clear()
        tvResult.text = ""
        phase = "measuring_open"
        btnAction.isEnabled = false
        blinkView.state = "open"

        tvInstruction.text = "Olhe normalmente para a tela\nMantenha os olhos ABERTOS"

        var elapsed = 0
        val total = 30
        val tick = object : Runnable {
            override fun run() {
                elapsed++
                progressBar.progress = elapsed * 100 / total
                if (elapsed < total) {
                    handler.postDelayed(this, 100)
                } else {
                    phase = "waiting_closed"
                    blinkView.state = "waiting"
                    tvInstruction.text = "✅ Olhos abertos capturados!\n\nAgora prepare-se para FECHAR os olhos\nToque em 'Continuar' quando estiver pronto"
                    btnAction.text = "Continuar →"
                    btnAction.isEnabled = true
                    progressBar.progress = 0
                }
            }
        }
        handler.post(tick)
    }

    private fun startClosedMeasurement() {
        phase = "measuring_closed"
        btnAction.isEnabled = false
        blinkView.state = "closed"
        tvInstruction.text = "Feche os olhos suavemente\nMantenha-os FECHADOS por 3 segundos"

        var elapsed = 0
        val total = 30
        val tick = object : Runnable {
            override fun run() {
                elapsed++
                progressBar.progress = elapsed * 100 / total
                if (elapsed < total) {
                    handler.postDelayed(this, 100)
                } else {
                    phase = "done"
                    calculateThreshold()
                }
            }
        }
        handler.post(tick)
    }

    private fun calculateThreshold() {
        if (openSamples.isEmpty() || closedSamples.isEmpty()) {
            tvInstruction.text = "❌ Dados insuficientes. Tente novamente."
            phase = "idle"
            btnAction.text = "▶ Iniciar Calibração"
            btnAction.isEnabled = true
            return
        }

        val avgOpen   = openSamples.average().toFloat()
        val avgClosed = closedSamples.average().toFloat()

        // Threshold fica no meio entre aberto e fechado, ponderado
        val threshold = avgClosed + (avgOpen - avgClosed) * 0.4f

        blinkView.state = "done"
        blinkView.openLevel = avgOpen
        blinkView.closedLevel = avgClosed
        blinkView.thresholdLevel = threshold
        blinkView.invalidate()

        // Salva
        getSharedPreferences("eye_tracker", MODE_PRIVATE).edit()
            .putFloat("blink_threshold", threshold)
            .apply()

        tvInstruction.text = "✅ Calibração concluída!"
        tvResult.text = "Olhos abertos: ${"%.0f".format(avgOpen * 100)}%\n" +
                        "Olhos fechados: ${"%.0f".format(avgClosed * 100)}%\n" +
                        "Threshold definido: ${"%.0f".format(threshold * 100)}%"

        btnAction.text = "🔄 Recalibrar"
        btnAction.isEnabled = true
        progressBar.progress = 100
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        handler.removeCallbacksAndMessages(null)
    }
}

class BlinkCalibView(context: android.content.Context) : View(context) {

    var eyeOpenness = 1f
    var state = "idle"
    var openLevel = 0f
    var closedLevel = 0f
    var thresholdLevel = 0f

    private val eyePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1F35.toInt()
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 20f

        canvas.drawCircle(cx, cy, r, bgPaint)

        if (state == "done") {
            drawResultBars(canvas, cx, cy)
            return
        }

        // Desenha olho
        val eyeH = r * 0.5f * eyeOpenness.coerceIn(0.05f, 1f)
        val eyeW = r * 0.7f

        val eyeColor = when {
            eyeOpenness > 0.5f -> 0xFF4FC3F7.toInt()
            eyeOpenness > 0.2f -> 0xFFFFC107.toInt()
            else               -> 0xFFE53935.toInt()
        }

        eyePaint.color = eyeColor
        eyePaint.style = Paint.Style.FILL

        val path = Path().apply {
            moveTo(cx - eyeW, cy)
            quadTo(cx, cy - eyeH * 2, cx + eyeW, cy)
            quadTo(cx, cy + eyeH * 2, cx - eyeW, cy)
            close()
        }
        canvas.drawPath(path, eyePaint)

        // Pupila
        eyePaint.color = Color.BLACK
        canvas.drawCircle(cx, cy, eyeH * 0.6f, eyePaint)

        // Brilho
        eyePaint.color = Color.WHITE
        canvas.drawCircle(cx - eyeH * 0.2f, cy - eyeH * 0.2f, eyeH * 0.15f, eyePaint)

        // Percentual
        canvas.drawText("${"%.0f".format(eyeOpenness * 100)}%", cx, cy + r + 40f, textPaint)
    }

    private fun drawResultBars(canvas: Canvas, cx: Float, cy: Float) {
        val barW = width * 0.7f
        val barH = 24f
        val left = cx - barW / 2f

        textPaint.textSize = 28f

        // Aberto
        barPaint.color = 0xFF4CAF50.toInt()
        canvas.drawRect(left, cy - 80f, left + barW * openLevel, cy - 56f, barPaint)
        canvas.drawText("Aberto: ${"%.0f".format(openLevel * 100)}%", cx, cy - 36f, textPaint)

        // Threshold
        barPaint.color = 0xFFFFC107.toInt()
        canvas.drawRect(left, cy - 20f, left + barW * thresholdLevel, cy + 4f, barPaint)
        canvas.drawText("Threshold: ${"%.0f".format(thresholdLevel * 100)}%", cx, cy + 24f, textPaint)

        // Fechado
        barPaint.color = 0xFFE53935.toInt()
        canvas.drawRect(left, cy + 40f, left + barW * closedLevel, cy + 64f, barPaint)
        canvas.drawText("Fechado: ${"%.0f".format(closedLevel * 100)}%", cx, cy + 84f, textPaint)
    }
}
