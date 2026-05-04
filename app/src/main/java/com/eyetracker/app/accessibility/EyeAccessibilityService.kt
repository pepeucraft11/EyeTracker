package com.eyetracker.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class EyeAccessibilityService : AccessibilityService() {

    companion object {
        var instance: EyeAccessibilityService? = null

        fun performClick(x: Float, y: Float) {
            instance?.performTap(x, y)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    fun performTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
