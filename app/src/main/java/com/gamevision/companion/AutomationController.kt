package com.gamevision.companion

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AutomationController {
    companion object { private const val MAX_STEPS = 200 }
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    private var serverUrl = ""
    private var goal = ""
    private var history = mutableListOf<JSONObject>()
    private var steps = 0
    private var failures = 0
    private var statusListener: ((String) -> Unit)? = null

    fun start(server: String, requestedGoal: String, previousMessages: List<JSONObject>, listener: (String) -> Unit): Boolean {
        if (active.get()) return false
        if (!GameVisionAccessibilityService.isEnabled()) { listener("Enable GameVision Accessibility in Android Settings before using AUTO mode."); return false }
        serverUrl = server.trim().removeSuffix("/"); goal = requestedGoal.trim(); history = previousMessages.takeLast(10).toMutableList(); steps = 0; failures = 0; statusListener = listener; active.set(true)
        listener("AUTO ON • planning the first move…")
        requestDecision(1100)
        return true
    }

    fun stop(reason: String = "Stopped by user") {
        if (active.getAndSet(false)) handler.post { statusListener?.invoke("AUTO OFF • $reason") }
    }

    fun isActive() = active.get()

    private fun requestDecision(delayMs: Long = 0) {
        handler.postDelayed({ if (active.get()) executor.execute { decide() } }, delayMs)
    }

    private fun decide() {
        if (!active.get()) return
        if (steps >= MAX_STEPS) { stop("maximum session steps reached"); return }
        try {
            val connection = URL("$serverUrl/api/automation/decide").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"; connection.connectTimeout = 7000; connection.readTimeout = 22000; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Accept", "application/json")
            val payload = JSONObject().put("goal", goal).put("messages", JSONArray(history.map { it.toString() }))
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${body.take(160)}")
            val actionJson = JSONObject(body).optJSONObject("action") ?: throw IllegalStateException("No action returned")
            val action = AutomationAction(
                type = actionJson.optString("type", "STOP"), x = actionJson.optInt("x", 0), y = actionJson.optInt("y", 0),
                x2 = actionJson.optInt("x2", 0), y2 = actionJson.optInt("y2", 0), durationMs = actionJson.optLong("durationMs", 600),
                waitMs = actionJson.optLong("waitMs", 800), reason = actionJson.optString("reason", ""), confidence = actionJson.optInt("confidence", 0),
                verify = actionJson.optBoolean("verify", true), stopReason = actionJson.optString("stopReason", "")
            )
            if (!active.get()) return
            if (action.type == "STOP") { stop(action.stopReason.ifBlank { action.reason.ifBlank { "AI requested stop" } }); return }
            if (action.confidence < 70) { stop("AI confidence too low (${action.confidence}%)"); return }
            steps++
            postStatus("AUTO • ${action.type} • ${action.confidence}% • ${action.reason.take(90)}")
            GameVisionAccessibilityService.execute(action) { success, result ->
                handler.post {
                    if (!active.get()) return@post
                    if (!success) { failures++; postStatus("Action failed: $result"); if (failures >= 3) stop("three consecutive gesture failures") else requestDecision(1200); return@post }
                    failures = 0
                    history += JSONObject().put("role", "assistant").put("content", "ACTION ${action.type}: ${action.reason}")
                    history += JSONObject().put("role", "user").put("content", "Android result: $result. Re-check the screen and continue the goal if appropriate.")
                    postStatus("VERIFYING • waiting for a fresh screen…")
                    requestDecision((action.waitMs.coerceIn(500, 5000) + 1300).toLong())
                }
            }
        } catch (error: Exception) {
            if (!active.get()) return
            failures++
            postStatus("Automation error: ${error.message ?: "network failure"}")
            if (failures >= 3) stop("repeated automation errors") else requestDecision(1800)
        }
    }

    private fun postStatus(message: String) { handler.post { statusListener?.invoke(message) } }
    fun shutdown() { active.set(false); executor.shutdownNow() }
}
