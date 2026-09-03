package com.gamevision.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
            "WAIT" -> { Handler(Looper.getMainLooper()).postDelayed({ callback(true, "wait complete") }, action.waitMs.coerceIn(0, 10000)); return }
            "STOP" -> { callback(true, "stop requested"); return }
            "TYPE_TEXT" -> { typeText(action.text, callback); return }
            "BACK" -> { performGlobal(action.type, GLOBAL_ACTION_BACK, callback); return }
            "HOME" -> { performGlobal(action.type, GLOBAL_ACTION_HOME, callback); return }
            "RECENTS" -> { performGlobal(action.type, GLOBAL_ACTION_RECENTS, callback); return }
            "NOTIFICATIONS" -> { performGlobal(action.type, GLOBAL_ACTION_NOTIFICATIONS, callback); return }
            "QUICK_SETTINGS" -> { performGlobal(action.type, GLOBAL_ACTION_QUICK_SETTINGS, callback); return }
        }

        val width = resources.displayMetrics.widthPixels.toFloat()
        val height = resources.displayMetrics.heightPixels.toFloat()
        fun px(value: Int, maxValue: Float) = (value.coerceIn(0, 1000) / 1000f) * maxValue
        val x = px(action.x, width); val y = px(action.y, height); val x2 = px(action.x2, width); val y2 = px(action.y2, height)
        val tapDuration = ViewConfiguration.getTapTimeout().toLong().coerceAtLeast(40L)
        val duration = when (action.type) {
            "LONG_PRESS" -> action.durationMs.coerceAtLeast(500L)
            "SWIPE", "DRAG", "TWO_FINGER_SWIPE" -> action.durationMs.coerceAtLeast(150L)
            "PINCH_IN", "PINCH_OUT" -> action.durationMs.coerceAtLeast(250L)
            else -> tapDuration
        }

        val builder = GestureDescription.Builder()
        when (action.type) {
            "DOUBLE_TAP" -> {
                builder.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, tapDuration))
                builder.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, tapDuration + 90L, tapDuration))
            }
            "PINCH_IN", "PINCH_OUT" -> addPinch(builder, x, y, x2, y2, duration, action.type == "PINCH_OUT")
            "TWO_FINGER_SWIPE" -> addTwoFingerSwipe(builder, x, y, x2, y2, duration)
            else -> {
                val path = Path().apply {
                    if (action.type == "SWIPE" || action.type == "DRAG") { moveTo(x, y); lineTo(x2, y2) }
                    else moveTo(x, y)
                }
                builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            }
        }

        val gesture = builder.build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { callback(true, "${action.type} completed") }
            override fun onCancelled(gestureDescription: GestureDescription?) { callback(false, "${action.type} cancelled by Android") }
        }, Handler(Looper.getMainLooper()))
        if (!dispatched) callback(false, "Android rejected ${action.type}")
    }

    private fun addPinch(builder: GestureDescription.Builder, cx: Float, cy: Float, outerX: Float, outerY: Float, duration: Long, outward: Boolean) {
        var dx = outerX - cx
        var dy = outerY - cy
        val length = max(40f, kotlin.math.sqrt(dx * dx + dy * dy))
        dx = dx / length * min(length, 360f)
        dy = dy / length * min(length, 360f)
        val startDistance = 40f
        val endDistance = min(length, 360f)
        val start1 = if (outward) Pair(cx - dx * startDistance / endDistance, cy - dy * startDistance / endDistance) else Pair(cx - dx, cy - dy)
        val start2 = if (outward) Pair(cx + dx * startDistance / endDistance, cy + dy * startDistance / endDistance) else Pair(cx + dx, cy + dy)
        val end1 = if (outward) Pair(cx - dx, cy - dy) else Pair(cx, cy)
        val end2 = if (outward) Pair(cx + dx, cy + dy) else Pair(cx, cy)
        builder.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(start1.first, start1.second); lineTo(end1.first, end1.second) }, 0, duration))
        builder.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(start2.first, start2.second); lineTo(end2.first, end2.second) }, 0, duration))
    }

    private fun addTwoFingerSwipe(builder: GestureDescription.Builder, startX: Float, startY: Float, endX: Float, endY: Float, duration: Long) {
        val dx = endX - startX
        val dy = endY - startY
        val distance = max(1f, kotlin.math.sqrt(dx * dx + dy * dy))
        val offset = min(55f, distance / 4f)
        val ox = -dy / distance * offset
        val oy = dx / distance * offset
        builder.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(startX + ox, startY + oy); lineTo(endX + ox, endY + oy) }, 0, duration))
        builder.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(startX - ox, startY - oy); lineTo(endX - ox, endY - oy) }, 0, duration))
    }

    private fun typeText(text: String, callback: (Boolean, String) -> Unit) {
        if (text.isEmpty()) { callback(false, "No text was supplied"); return }
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) { callback(false, "No focused editable field is available"); return }
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val success = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) }.getOrDefault(false)
        node.recycle()
        callback(success, if (success) "text entered" else "Android rejected text entry")
    }

    private fun performGlobal(type: String, actionId: Int, callback: (Boolean, String) -> Unit) {
        val available = getSystemActions().any { it.id == actionId }
        if (!available) { callback(false, "Android system action $type is unavailable on this device/state"); return }
        val success = runCatching { performGlobalAction(actionId) }.getOrDefault(false)
        callback(success, if (success) "$type completed" else "Android could not perform $type")
    }
}

data class AutomationAction(val type: String, val x: Int, val y: Int, val x2: Int, val y2: Int, val text: String, val durationMs: Long, val waitMs: Long, val reason: String, val confidence: Int, val verify: Boolean, val stopReason: String)
