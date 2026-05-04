package com.eyetracker.app.overlay

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import com.eyetracker.app.accessibility.EyeAccessibilityService
import com.eyetracker.app.ml.EyeTrackingAnalyzer
import com.eyetracker.app.ui.MainActivity
import java.util.concurrent.Executors

class EyeTrackingService : Service(), LifecycleOwner {

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "eye_tracking_channel"
        private const val NOTIF_ID = 1
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var windowManager: WindowManager? = null
    private var cursorView: View? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        setupOverlayCursor()
        startCamera()
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun setupOverlayCursor() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics().also { windowManager!!.defaultDisplay.getMetrics(it) }
        cursorView = View(this).apply { setBackgroundColor(0xAA4FC3F7.toInt()) }
        val params = WindowManager.LayoutParams(
            60, 60,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels / 2
            y = metrics.heightPixels / 2
        }
        windowManager!!.addView(cursorView, params)
    }

    private fun startCamera() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics().also { wm.defaultDisplay.getMetrics(it) }
        val sw = metrics.widthPixels
        val sh = metrics.heightPixels

        val analyzer = EyeTrackingAnalyzer(
            screenWidth = sw,
            screenHeight = sh,
            onGazeDetected = { data ->
                val cv = cursorView ?: return@EyeTrackingAnalyzer
                val wm2 = windowManager ?: return@EyeTrackingAnalyzer
                val px = (data.gazePoint.x * sw - 30f).toInt()
                val py = (data.gazePoint.y * sh - 30f).toInt()
                val gx = data.gazePoint.x * sw
                val gy = data.gazePoint.y * sh

                handler.post {
                    try {
                        val params = cv.layoutParams as WindowManager.LayoutParams
                        params.x = px
                        params.y = py

                        if (data.isSingleBlink) {
                            cv.setBackgroundColor(0xFFFFFF00.toInt())
                            handler.postDelayed({
                                cv.setBackgroundColor(0xAA4FC3F7.toInt())
                                try { wm2.updateViewLayout(cv, params) } catch (_: Exception) {}
                            }, 300)
                            EyeAccessibilityService.performClick(gx, gy)
                        }

                        wm2.updateViewLayout(cv, params)
                    } catch (_: Exception) {}
                }
            },
            onNoFace = {}
        )

        ProcessCameraProvider.getInstance(this).addListener({
            val provider = ProcessCameraProvider.getInstance(this).get()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { it.setAnalyzer(cameraExecutor, analyzer) }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Eye Tracking Ativo")
            .setContentText("Pisque para clicar!")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Eye Tracking Service", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        isRunning = false
        cursorView?.let { windowManager?.removeView(it) }
        cameraExecutor.shutdown()
    }
}
