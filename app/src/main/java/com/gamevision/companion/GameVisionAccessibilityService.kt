package com.gamevision.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent

class GameVisionAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile private var instance: GameVisionAccessibilityService? = null
        fun isEnabled() = instance != null
        fun execute(action: AutomationAction, callback: (Boolean, String) -> Unit) {
            val service = instance
            if (service == null) { callback(false, "Accessibility service is not enabled"); return }
            service.dispatch(action, callback)
        }
    }
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    private fun dispatch(action: AutomationAction, callback: (Boolean, String) -> Unit) {
        if (action.type == "WAIT") { Handler(Looper.getMainLooper()).postDelayed({ callback(true, "wait complete") }, action.waitMs.coerceIn(0, 10000)); return }
        if (action.type == "STOP") { callback(true, "stop requested"); return }
        val width = resources.displayMetrics.widthPixels.toFloat(); val height = resources.displayMetrics.heightPixels.toFloat()
        fun px(value: Int, max: Float) = (value.coerceIn(0, 1000) / 1000f) * max
        val x = px(action.x, width); val y = px(action.y, height); val x2 = px(action.x2, width); val y2 = px(action.y2, height)
        val path = Path().apply { if (action.type == "SWIPE" || action.type == "DRAG") { moveTo(x, y); lineTo(x2, y2) } else moveTo(x, y) }
        val duration = when (action.type) { "LONG_PRESS" -> action.durationMs.coerceAtLeast(500L); "SWIPE", "DRAG" -> action.durationMs.coerceAtLeast(150L); else -> ViewConfiguration.getTapTimeout().toLong() }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { callback(true, "gesture completed") }
            override fun onCancelled(gestureDescription: GestureDescription?) { callback(false, "gesture cancelled") }
        }, Handler(Looper.getMainLooper()))
        if (!dispatched) callback(false, "Android rejected the gesture")
    }
}

data class AutomationAction(val type: String, val x: Int, val y: Int, val x2: Int, val y2: Int, val durationMs: Long, val waitMs: Long, val reason: String, val confidence: Int, val verify: Boolean, val stopReason: String)
