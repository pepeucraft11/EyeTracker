package com.eyetracker.app.overlay

import android.app.*
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.eyetracker.app.ml.EyeTrackingAnalyzer
import com.eyetracker.app.ui.MainActivity
import java.util.concurrent.Executors

class EyeTrackingService : LifecycleService() {

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "eye_tracking_channel"
        private const val NOTIF_ID = 1
    }

    private lateinit var windowManager: WindowManager
    private lateinit var cursorView: View
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        setupOverlayCursor()
        startCamera()
    }

    private fun setupOverlayCursor() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics().also { windowManager.defaultDisplay.getMetrics(it) }
        cursorView = View(this).apply { setBackgroundColor(0x554FC3F7.toInt()) }
        val params = WindowManager.LayoutParams(
            80, 80,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = metrics.widthPixels / 2
            y = metrics.heightPixels / 2
        }
        windowManager.addView(cursorView, params)
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
                val params = cursorView.layoutParams as WindowManager.LayoutParams
                params.x = (data.gazePoint.x * sw - 40f).toInt()
                params.y = (data.gazePoint.y * sh - 40f).toInt()
                android.os.Handler(mainLooper).post {
                    windowManager.updateViewLayout(cursorView, params)
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
            .setContentTitle("Eye Tracking Active")
            .setContentText("Gaze cursor is visible over all apps")
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
        isRunning = false
        if (::cursorView.isInitialized && ::windowManager.isInitialized)
            windowManager.removeView(cursorView)
        cameraExecutor.shutdown()
    }
}
