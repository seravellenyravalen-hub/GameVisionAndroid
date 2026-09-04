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
    companion object {
        private const val MAX_STEPS = 200
        private const val MAX_FRAME_WAIT_MS = 12000L
        private const val FRAME_POLL_MS = 450L
        private const val LOW_CONFIDENCE_RETRIES = 3
        private const val MAX_RECOVERY_ATTEMPTS = 3
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val active = AtomicBoolean(false)
    private var serverUrl = ""
    private var authToken = ""
    private var goal = ""
    private var history = mutableListOf<JSONObject>()
    private var steps = 0
    private var failures = 0
    private var recoveryAttempts = 0
    private var lowConfidenceRetries = 0
    private var lastFrameSequence = 0L
    private var lastServerEpoch: String? = null
    private var lastAction: AutomationAction? = null
    private var statusListener: ((String) -> Unit)? = null
    private var fastCommand = false

    fun start(server: String, token: String, requestedGoal: String, previousMessages: List<JSONObject>, listener: (String) -> Unit): Boolean {
        if (active.get()) return false
        if (!GameVisionAccessibilityService.isEnabled()) {
            listener("FAILED • Enable GameVision Accessibility in Android Settings before using AUTO mode.")
            return false
        }
        serverUrl = server.trim().removeSuffix("/")
        authToken = token.trim()
        if (authToken.isBlank()) { listener("FAILED • Sign in again before using AUTO mode."); return false }
        goal = requestedGoal.trim()
        history = previousMessages.takeLast(10).toMutableList()
        steps = 0; failures = 0; recoveryAttempts = 0; lowConfidenceRetries = 0; lastFrameSequence = 0L; lastServerEpoch = null; lastAction = null; statusListener = listener; active.set(true)

        val fastAction = FastCommandRouter.parse(goal)
        fastCommand = fastAction != null
        if (fastAction != null) {
            postStatus("ACTING • FAST • ${fastAction.type}")
            if (fastAction.type == "STOP") { stopWithStatus("SUCCESS • stopped by user command"); return true }
            steps = 1
            lastAction = fastAction
            executeAndVerify(fastAction)
            return true
        }

        postStatus("THINKING • getting a fresh screen…")
        waitForFreshFrame(0L, null, MAX_FRAME_WAIT_MS) { ready, sequence, epoch, error ->
            if (!active.get()) return@waitForFreshFrame
            if (!ready) { stopWithStatus("FAILED • ${error ?: "No fresh screen capture available"}"); return@waitForFreshFrame }
            lastFrameSequence = sequence
            lastServerEpoch = epoch
            requestDecision()
        }
        return true
    }

    fun start(server: String, requestedGoal: String, previousMessages: List<JSONObject>, listener: (String) -> Unit): Boolean =
        start(server, "", requestedGoal, previousMessages, listener)

    fun stop(reason: String = "Stopped by user") {
        if (active.getAndSet(false)) handler.post { statusListener?.invoke("FAILED • $reason") }
    }

    private fun stopWithStatus(message: String) {
        if (active.getAndSet(false)) handler.post { statusListener?.invoke(message) }
    }

    fun isActive() = active.get()

    private fun requestDecision(delayMs: Long = 0) {
        handler.postDelayed({
            if (active.get()) {
                postStatus("THINKING • deciding next action…")
                executor.execute { decide() }
            }
        }, delayMs)
    }

    private fun decide() {
        if (!active.get()) return
        if (steps >= MAX_STEPS) { stopWithStatus("FAILED • maximum session steps reached"); return }
        try {
            val connection = URL("$serverUrl/api/automation/decide").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"; connection.connectTimeout = 7000; connection.readTimeout = 22000; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Accept", "application/json")
            if (authToken.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $authToken")
            val payload = JSONObject().put("goal", goal).put("minFrameSequence", lastFrameSequence).put("minFrameEpoch", lastServerEpoch).put("messages", JSONArray(history.map { it.toString() }))
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            if (code == 401) { stopWithStatus("FAILED • session expired — sign in again"); return }
            if (code == 429 && body.contains("FREE_ALLOWANCE_EXHAUSTED")) { stopWithStatus("FAILED • free allowance used — it resets automatically"); return }
            if (code == 409) { recover("the screen did not provide a fresh frame"); return }
            if (code !in 200..299) throw IllegalStateException(extractServerError(body, "HTTP $code"))
            val json = JSONObject(body)
            val actionJson = json.optJSONObject("action") ?: throw IllegalStateException("No action returned")
            val frame = json.optJSONObject("frame")
            val sequence = frame?.optLong("sequence", lastFrameSequence) ?: lastFrameSequence
            val epoch = frame?.optString("serverEpoch", lastServerEpoch.orEmpty())?.ifBlank { lastServerEpoch } ?: lastServerEpoch
            lastFrameSequence = sequence; lastServerEpoch = epoch
            val action = AutomationAction(
                type = actionJson.optString("type", "STOP"), x = actionJson.optInt("x", 0), y = actionJson.optInt("y", 0), x2 = actionJson.optInt("x2", 0), y2 = actionJson.optInt("y2", 0),
                text = actionJson.optString("text", ""), durationMs = actionJson.optLong("durationMs", 600), waitMs = actionJson.optLong("waitMs", 800), reason = actionJson.optString("reason", ""),
                confidence = actionJson.optInt("confidence", 0), verify = actionJson.optBoolean("verify", true), stopReason = actionJson.optString("stopReason", "")
            )
            if (!active.get()) return
            if (action.type == "STOP") { stopWithStatus("FAILED • ${action.stopReason.ifBlank { action.reason.ifBlank { "AI requested stop" } }}"); return }
            if (action.confidence < 70) {
                lowConfidenceRetries++
                postStatus("RECOVERING • AI confidence ${action.confidence}% • retry $lowConfidenceRetries/$LOW_CONFIDENCE_RETRIES")
                if (lowConfidenceRetries >= LOW_CONFIDENCE_RETRIES) { stopWithStatus("FAILED • AI could not confidently determine the next action"); return }
                requestDecision(400); return
            }
            lowConfidenceRetries = 0; failures = 0; lastAction = action
            steps++
            executeAndVerify(action)
        } catch (error: Exception) {
            if (!active.get()) return
            recover(error.message ?: "network failure")
        }
    }

    private fun executeAndVerify(action: AutomationAction) {
        if (!active.get()) return
        lastAction = action
        postStatus("ACTING • ${if (fastCommand) "FAST" else "AI"} • ${action.type} • ${action.confidence}%")
        GameVisionAccessibilityService.execute(action) { success, result ->
            handler.post {
                if (!active.get()) return@post
                if (!success) {
                    // Fast routing is an optimization, never a dead end. If an explicit local
                    // command cannot be executed (for example a game target has no accessibility
                    // node), hand the same natural-language goal to the vision planner.
                    if (fastCommand && action.type != "STOP") {
                        fastCommand = false
                        history += JSONObject().put("role", "assistant").put("content", "FAST ACTION ${action.type} could not execute: $result")
                        history += JSONObject().put("role", "user").put("content", "Use vision/AI fallback for the original goal. Do not assume the failed fast action changed the screen.")
                        postStatus("RECOVERING • fast path unavailable • switching to AI vision")
                        waitForFreshFrame(lastFrameSequence, lastServerEpoch, MAX_FRAME_WAIT_MS) { ready, sequence, epoch, error ->
                            if (!active.get()) return@waitForFreshFrame
                            if (ready) {
                                lastFrameSequence = sequence; lastServerEpoch = epoch
                                requestDecision(50)
                            } else {
                                stopWithStatus("FAILED • ${error ?: "AI fallback could not obtain a fresh screen"}")
                            }
                        }
                    } else {
                        recover(result)
                    }
                    return@post
                }
                history += JSONObject().put("role", "assistant").put("content", "ACTION ${action.type}: ${action.reason}")
                history += JSONObject().put("role", "user").put("content", "Android result: $result. Re-check the current screen and continue the goal if appropriate.")
                postStatus("VERIFYING • waiting for a new screen…")
                val delay = action.waitMs.coerceIn(0, 5000)
                handler.postDelayed({
                    if (!active.get()) return@postDelayed
                    waitForFreshFrame(lastFrameSequence, lastServerEpoch, MAX_FRAME_WAIT_MS) { ready, sequenceAfter, epochAfter, error ->
                        if (!active.get()) return@waitForFreshFrame
                        if (ready) {
                            lastFrameSequence = sequenceAfter; lastServerEpoch = epochAfter; failures = 0; recoveryAttempts = 0
                            if (fastCommand) stopWithStatus("SUCCESS • ${action.type} completed and verified") else requestDecision(100)
                        } else {
                            recover(error ?: "expected screen change did not appear")
                        }
                    }
                }, delay)
            }
        }
    }

    private fun recover(reason: String) {
        failures++
        recoveryAttempts++
        postStatus("RECOVERING • $reason • attempt $recoveryAttempts/$MAX_RECOVERY_ATTEMPTS")
        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS || failures >= MAX_RECOVERY_ATTEMPTS) {
            stopWithStatus("FAILED • recovery limit reached: $reason")
            return
        }
        handler.postDelayed({
            if (!active.get()) return@postDelayed
            waitForFreshFrame(lastFrameSequence, lastServerEpoch, MAX_FRAME_WAIT_MS) { ready, sequence, epoch, error ->
                if (!active.get()) return@waitForFreshFrame
                if (ready) {
                    lastFrameSequence = sequence; lastServerEpoch = epoch
                    if (fastCommand && lastAction != null) {
                        executeAndVerify(lastAction!!)
                    } else {
                        requestDecision(100)
                    }
                } else {
                    recover(error ?: "fresh screen still unavailable")
                }
            }
        }, 350L)
    }

    private fun extractServerError(body: String, fallback: String): String {
        return runCatching {
            val json = JSONObject(body)
            val code = json.optString("code").ifBlank { "SERVER_ERROR" }
            val error = json.optString("error").ifBlank { fallback }
            "$code: $error"
        }.getOrDefault(fallback).take(240)
    }

    private fun waitForFreshFrame(minSequence: Long, minEpoch: String?, timeoutMs: Long, callback: (Boolean, Long, String?, String?) -> Unit) {
        val deadline = System.currentTimeMillis() + timeoutMs
        fun poll() {
            if (!active.get()) return
            executor.execute {
                try {
                    val connection = URL("$serverUrl/api/frame-status").openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"; connection.connectTimeout = 4000; connection.readTimeout = 5000
                    if (authToken.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $authToken")
                    val code = connection.responseCode
                    val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    connection.disconnect()
                    if (code == 401) { handler.post { callback(false, minSequence, minEpoch, "Session expired — sign in again") }; return@execute }
                    if (code !in 200..299) throw IllegalStateException(extractServerError(body, "HTTP $code"))
                    val json = JSONObject(body)
                    val sequence = json.optLong("sequence", 0L); val epoch = json.optString("serverEpoch", "").ifBlank { null }; val fresh = json.optBoolean("fresh", false)
                    val epochChanged = minEpoch != null && epoch != null && epoch != minEpoch; val sequenceFresh = sequence > minSequence
                    if (fresh && (sequenceFresh || epochChanged || minEpoch == null)) { handler.post { callback(true, sequence, epoch, null) }; return@execute }
                    if (System.currentTimeMillis() >= deadline) { handler.post { callback(false, sequence, epoch, "Timed out waiting for a fresh screen") }; return@execute }
                    handler.postDelayed({ poll() }, FRAME_POLL_MS)
                } catch (error: Exception) {
                    if (System.currentTimeMillis() >= deadline) handler.post { callback(false, minSequence, minEpoch, error.message ?: "screen status unavailable") }
                    else handler.postDelayed({ poll() }, FRAME_POLL_MS)
                }
            }
        }
        poll()
    }

    private fun postStatus(message: String) { handler.post { statusListener?.invoke(message) } }
    fun shutdown() { active.set(false); executor.shutdownNow() }
}

data class AutomationAction(val type: String, val x: Int, val y: Int, val x2: Int, val y2: Int, val text: String, val durationMs: Long, val waitMs: Long, val reason: String, val confidence: Int, val verify: Boolean, val stopReason: String)