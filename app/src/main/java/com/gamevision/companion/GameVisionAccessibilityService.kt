package com.gamevision.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
        fun liveToolContext(): String = instance?.describeLiveScreen() ?: "ACCESSIBILITY_UNAVAILABLE"
    }

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    private fun describeLiveScreen(): String {
        val root = rootInActiveWindow ?: return "NO_ACTIVE_ACCESSIBILITY_WINDOW"
        val packageName = root.packageName?.toString().orEmpty()
        val nodes = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (nodes.size >= 120) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val label = when {
                text.isNotBlank() -> text
                desc.isNotBlank() -> desc
                else -> ""
            }
            val interesting = label.isNotBlank() || node.isClickable || node.isFocusable || node.isEditable || node.isScrollable
            if (interesting) {
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                nodes += "${"  ".repeat(depth.coerceAtMost(4))}${node.className?.toString()?.substringAfterLast('.') ?: "Node"}|label=${label.take(120)}|bounds=${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}|clickable=${node.isClickable}|editable=${node.isEditable}|scrollable=${node.isScrollable}|enabled=${node.isEnabled}"
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
                child.recycle()
                if (nodes.size >= 120) return
            }
        }
        walk(root, 0)
        return buildString {
            append("package=").append(packageName).append("\n")
            append("accessibilityNodes=").append(nodes.size).append("\n")
            nodes.forEach { append(it).append('\n') }
        }.take(14000)
    }

    private fun dispatch(action: AutomationAction, callback: (Boolean, String) -> Unit) {
        when (action.type) {
            "WAIT" -> { Handler(Looper.getMainLooper()).postDelayed({ callback(true, "wait complete") }, action.waitMs.coerceIn(0, 10000)); return }
            "STOP" -> { callback(true, "stop requested"); return }
            "OPEN_APP" -> { openApp(action.text, callback); return }
            "TYPE_TEXT" -> { typeText(action.text, callback); return }
            "TAP_TARGET", "DOUBLE_TAP_TARGET", "LONG_PRESS_TARGET" -> { gestureOnTarget(action, callback); return }
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
                val path = Path().apply { if (action.type == "SWIPE" || action.type == "DRAG") { moveTo(x, y); lineTo(x2, y2) } else moveTo(x, y) }
                builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            }
        }
        dispatchBuiltGesture(builder.build(), action.type, callback)
    }

    private fun openApp(request: String, callback: (Boolean, String) -> Unit) {
        val wanted = normalizeAppName(request)
        if (wanted.isBlank()) { callback(false, "No app name was supplied"); return }
        val candidates = packageManager.getInstalledApplications(0).mapNotNull { app ->
            val label = app.loadLabel(packageManager)?.toString().orEmpty()
            val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName) ?: return@mapNotNull null
            Triple(app.packageName, label, launchIntent)
        }
        val exact = candidates.firstOrNull { normalizeAppName(it.second) == wanted }
        val partial = exact ?: candidates.firstOrNull { val label = normalizeAppName(it.second); label.contains(wanted) || wanted.contains(label) }
        val match = partial ?: run { callback(false, "Could not find an installed launchable app named '$request'"); return }
        val launch = match.third.apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP) }
        val packageName = match.first
        runCatching { startActivity(launch) }.onFailure { callback(false, "Android rejected launch of ${match.second}: ${it.message ?: "unknown error"}"); return }
        waitForForegroundPackage(packageName, match.second, callback)
    }

    private fun normalizeAppName(value: String): String = value.trim().lowercase().replace(Regex("[^a-z0-9]+"), "")

    private fun waitForForegroundPackage(expectedPackage: String, label: String, callback: (Boolean, String) -> Unit) {
        val handler = Handler(Looper.getMainLooper()); val deadline = System.currentTimeMillis() + 2500L
        fun poll() {
            val current = rootInActiveWindow?.packageName?.toString().orEmpty()
            if (current == expectedPackage) { callback(true, "$label opened and verified (package=$expectedPackage)"); return }
            if (System.currentTimeMillis() >= deadline) { callback(false, "Launch dispatched but '$label' did not become the foreground app"); return }
            handler.postDelayed(::poll, 120L)
        }
        poll()
    }

    private fun gestureOnTarget(action: AutomationAction, callback: (Boolean, String) -> Unit) {
        val target = action.text.trim()
        if (target.isBlank()) { callback(false, "No target was supplied"); return }
        val node = findTarget(rootInActiveWindow, target)
        if (node == null) { callback(false, "Could not find visible target '$target'"); return }
        val bounds = Rect(); node.getBoundsInScreen(bounds); node.recycle()
        if (bounds.isEmpty || bounds.width() <= 0 || bounds.height() <= 0) { callback(false, "Target '$target' has no usable screen bounds"); return }
        val x = bounds.centerX().toFloat(); val y = bounds.centerY().toFloat()
        when (action.type) {
            "TAP_TARGET" -> {
                val clicked = runCatching {
                    val current = findTarget(rootInActiveWindow, target) ?: return@runCatching false
                    val result = current.performAction(AccessibilityNodeInfo.ACTION_CLICK); current.recycle(); result
                }.getOrDefault(false)
                if (clicked) callback(true, "target '$target' clicked")
                else dispatchBuiltGesture(GestureDescription.Builder().apply { addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, ViewConfiguration.getTapTimeout().toLong().coerceAtLeast(40L))) }.build(), "TAP_TARGET", callback)
            }
            "DOUBLE_TAP_TARGET" -> {
                val d = ViewConfiguration.getTapTimeout().toLong().coerceAtLeast(40L); val builder = GestureDescription.Builder()
                builder.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, d)); builder.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, d + 90L, d)); dispatchBuiltGesture(builder.build(), "DOUBLE_TAP_TARGET", callback)
            }
            else -> { val duration = action.durationMs.coerceAtLeast(500L); dispatchBuiltGesture(GestureDescription.Builder().apply { addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x, y) }, 0, duration)) }.build(), "LONG_PRESS_TARGET", callback) }
        }
    }

    private fun findTarget(root: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        if (root == null) return null
        val wanted = target.trim().lowercase()
        fun matches(node: AccessibilityNodeInfo): Boolean {
            val text = node.text?.toString()?.trim()?.lowercase().orEmpty(); val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
            return text == wanted || desc == wanted || text.contains(wanted) || desc.contains(wanted)
        }
        if (matches(root)) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue; val found = findTarget(child, target)
            if (found != null) { if (found !== child) child.recycle(); return found }
            child.recycle()
        }
        return null
    }

    private fun dispatchBuiltGesture(gesture: GestureDescription, type: String, callback: (Boolean, String) -> Unit) {
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { callback(true, "$type completed") }
            override fun onCancelled(gestureDescription: GestureDescription?) { callback(false, "$type cancelled by Android") }
        }, Handler(Looper.getMainLooper()))
        if (!dispatched) callback(false, "Android rejected $type")
    }

    private fun addPinch(builder: GestureDescription.Builder, cx: Float, cy: Float, outerX: Float, outerY: Float, duration: Long, outward: Boolean) {
        var dx = outerX - cx; var dy = outerY - cy; val length = max(40f, kotlin.math.sqrt(dx * dx + dy * dy))
        dx = dx / length * min(length, 360f); dy = dy / length * min(length, 360f); val startDistance = 40f; val endDistance = min(length, 360f)
        val start1 = if (outward) Pair(cx - dx * startDistance / endDistance, cy - dy * startDistance / endDistance) else Pair(cx - dx, cy - dy)
        val start2 = if (outward) Pair(cx + dx * startDistance / endDistance, cy + dy * startDistance / endDistance) else Pair(cx + dx, cy + dy)
        val end1 = if (outward) Pair(cx - dx, cy - dy) else Pair(cx, cy); val end2 = if (outward) Pair(cx + dx, cy + dy) else Pair(cx, cy)
        builder.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(start1.first, start1.second); lineTo(end1.first, end1.second) }, 0, duration))
        builder.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(start2.first, start2.second); lineTo(end2.first, end2.second) }, 0, duration))
    }

    private fun addTwoFingerSwipe(builder: GestureDescription.Builder, startX: Float, startY: Float, endX: Float, endY: Float, duration: Long) {
        val dx = endX - startX; val dy = endY - startY; val distance = max(1f, kotlin.math.sqrt(dx * dx + dy * dy)); val offset = min(55f, distance / 4f); val ox = -dy / distance * offset; val oy = dx / distance * offset
        builder.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(startX + ox, startY + oy); lineTo(endX + ox, endY + oy) }, 0, duration)); builder.addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(startX - ox, startY - oy); lineTo(endX - ox, endY - oy) }, 0, duration))
    }

    private fun typeText(text: String, callback: (Boolean, String) -> Unit) {
        if (text.isEmpty()) { callback(false, "No text was supplied"); return }
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT); if (node == null) { callback(false, "No focused editable field is available"); return }
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val success = runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments) }.getOrDefault(false); node.recycle(); callback(success, if (success) "text entered" else "Android rejected text entry")
    }

    private fun performGlobal(type: String, actionId: Int, callback: (Boolean, String) -> Unit) {
        val available = getSystemActions().any { it.id == actionId }; if (!available) { callback(false, "Android system action $type is unavailable on this device/state"); return }
        val success = runCatching { performGlobalAction(actionId) }.getOrDefault(false); callback(success, if (success) "$type completed" else "Android could not perform $type")
    }
}

data class AutomationAction(val type: String, val x: Int, val y: Int, val x2: Int, val y2: Int, val text: String, val durationMs: Long, val waitMs: Long, val reason: String, val confidence: Int, val verify: Boolean, val stopReason: String)
