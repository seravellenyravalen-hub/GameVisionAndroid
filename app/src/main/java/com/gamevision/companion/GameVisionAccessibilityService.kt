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
        when (action.type) {
            "WAIT" -> {
                Handler(Looper.getMainLooper()).postDelayed({ callback(true, "wait complete") }, action.waitMs.coerceIn(0, 10000))
                return
            }
            "STOP" -> { callback(true, "stop requested"); return }
            "BACK" -> { performGlobal(action.type, GLOBAL_ACTION_BACK, callback); return }
            "HOME" -> { performGlobal(action.type, GLOBAL_ACTION_HOME, callback); return }
            "RECENTS" -> { performGlobal(action.type, GLOBAL_ACTION_RECENTS, callback); return }
            "NOTIFICATIONS" -> { performGlobal(action.type, GLOBAL_ACTION_NOTIFICATIONS, callback); return }
            "QUICK_SETTINGS" -> { performGlobal(action.type, GLOBAL_ACTION_QUICK_SETTINGS, callback); return }
        }

        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        fun px(value: Int, max: Float) = (value.coerceIn(0, 1000) / 1000f) * max
        val x = px(action.x, width)
        val y = px(action.y, height)
        val x2 = px(action.x2, width)
        val y2 = px(action.y2, height)
        val tapDuration = ViewConfiguration.getTapTimeout().toLong().coerceAtLeast(40L)
        val path = Path().apply {
            if (action.type == "SWIPE" || action.type == "DRAG") { moveTo(x, y); lineTo(x2, y2) }
            else moveTo(x, y)
        }
        val duration = when (action.type) {
            "LONG_PRESS" -> action.durationMs.coerceAtLeast(500L)
            "SWIPE", "DRAG" -> action.durationMs.coerceAtLeast(150L)
            else -> tapDuration
        }
        val builder = GestureDescription.Builder()
        if (action.type == "DOUBLE_TAP") {
            builder.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, tapDuration))
            builder.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, tapDuration + 90L, tapDuration))
        } else {
            builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
        }
        val gesture = builder.build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { callback(true, "${action.type} completed") }
            override fun onCancelled(gestureDescription: GestureDescription?) { callback(false, "${action.type} cancelled by Android") }
        }, Handler(Looper.getMainLooper()))
        if (!dispatched) callback(false, "Android rejected ${action.type}")
    }

    private fun performGlobal(type: String, actionId: Int, callback: (Boolean, String) -> Unit) {
        val available = getSystemActions().any { it.id == actionId }
        if (!available) { callback(false, "Android system action $type is unavailable on this device/state"); return }
        val success = runCatching { performGlobalAction(actionId) }.getOrDefault(false)
        callback(success, if (success) "$type completed" else "Android could not perform $type")
    }
}

data class AutomationAction(val type: String, val x: Int, val y: Int, val x2: Int, val y2: Int, val durationMs: Long, val waitMs: Long, val reason: String, val confidence: Int, val verify: Boolean, val stopReason: String)
