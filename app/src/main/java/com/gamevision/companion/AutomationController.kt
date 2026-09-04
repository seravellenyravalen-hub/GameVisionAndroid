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
    private var lowConfidenceRetries = 0
    private var lastFrameSequence = 0L
    private var lastServerEpoch: String? = null
    private var statusListener: ((String) -> Unit)? = null

    fun start(server: String, token: String, requestedGoal: String, previousMessages: List<JSONObject>, listener: (String) -> Unit): Boolean {
        if (active.get()) return false
        if (!GameVisionAccessibilityService.isEnabled()) {
            listener("Enable GameVision Accessibility in Android Settings before using AUTO mode.")
            return false
        }
        serverUrl = server.trim().removeSuffix("/")
        authToken = token.trim()
        if (authToken.isBlank()) { listener("Sign in again before using AUTO mode."); return false }
        goal = requestedGoal.trim()
        history = previousMessages.takeLast(10).toMutableList()
        steps = 0; failures = 0; lowConfidenceRetries = 0; lastFrameSequence = 0L; lastServerEpoch = null; statusListener = listener; active.set(true)
        listener("AUTO ON • getting a fresh screen…")
        waitForFreshFrame(0L, null, MAX_FRAME_WAIT_MS) { ready, sequence, epoch, error ->
            if (!active.get()) return@waitForFreshFrame
            if (!ready) { stop(error ?: "No fresh screen capture available"); return@waitForFreshFrame }
            lastFrameSequence = sequence
            lastServerEpoch = epoch
            requestDecision()
        }
        return true
    }

    fun start(server: String, requestedGoal: String, previousMessages: List<JSONObject>, listener: (String) -> Unit): Boolean =
        start(server, "", requestedGoal, previousMessages, listener)

    fun stop(reason: String = "Stopped by user") {
        if (active.getAndSet(false)) handler.post { statusListener?.invoke("AUTO OFF • $reason") }
    }

    fun isActive() = active.get()

    private fun requestDecision(delayMs: Long = 0) { handler.postDelayed({ if (active.get()) executor.execute { decide() } }, delayMs) }

    private fun decide() {
        if (!active.get()) return
        if (steps >= MAX_STEPS) { stop("maximum session steps reached"); return }
        try {
            val connection = URL("$serverUrl/api/automation/decide").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"; connection.connectTimeout = 7000; connection.readTimeout = 22000; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Accept", "application/json")
            if (authToken.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $authToken")
            val payload = JSONObject()
                .put("goal", goal)
                .put("minFrameSequence", lastFrameSequence)
                .put("minFrameEpoch", lastServerEpoch)
                .put("messages", JSONArray(history.map { it.toString() }))
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()

            if (code == 401) { stop("session expired — sign in again"); return }
            if (code == 429 && body.contains("FREE_ALLOWANCE_EXHAUSTED")) { stop("free allowance used — it resets automatically"); return }
            if (code == 409) {
                postStatus("WAITING • fresh screen…")
                waitForFreshFrame(lastFrameSequence, lastServerEpoch, MAX_FRAME_WAIT_MS) { ready, sequence, epoch, error ->
                    if (!active.get()) return@waitForFreshFrame
                    if (ready) { lastFrameSequence = sequence; lastServerEpoch = epoch; requestDecision(120) }
                    else { failures++; postStatus(error ?: "Fresh screen timeout"); if (failures >= 3) stop("screen capture is not updating") else requestDecision(1000) }
                }
                return
            }
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${body.take(160)}")

            val json = JSONObject(body)
            val actionJson = json.optJSONObject("action") ?: throw IllegalStateException("No action returned")
            val frame = json.optJSONObject("frame")
            val sequence = frame?.optLong("sequence", lastFrameSequence) ?: lastFrameSequence
            val epoch = frame?.optString("serverEpoch", lastServerEpoch.orEmpty())?.ifBlank { lastServerEpoch } ?: lastServerEpoch
            lastFrameSequence = sequence
            lastServerEpoch = epoch
            val action = AutomationAction(
                type = actionJson.optString("type", "STOP"), x = actionJson.optInt("x", 0), y = actionJson.optInt("y", 0),
                x2 = actionJson.optInt("x2", 0), y2 = actionJson.optInt("y2", 0), text = actionJson.optString("text", ""),
                durationMs = actionJson.optLong("durationMs", 600), waitMs = actionJson.optLong("waitMs", 800),
                reason = actionJson.optString("reason", ""), confidence = actionJson.optInt("confidence", 0),
                verify = actionJson.optBoolean("verify", true), stopReason = actionJson.optString("stopReason", "")
            )
            if (!active.get()) return
            if (action.type == "STOP") { stop(action.stopReason.ifBlank { action.reason.ifBlank { "AI requested stop" } }); return }
            if (action.confidence < 70) {
                lowConfidenceRetries++; postStatus("RECHECKING • AI confidence ${action.confidence}%")
                if (lowConfidenceRetries >= LOW_CONFIDENCE_RETRIES) { stop("AI could not confidently determine the next action"); return }
                requestDecision(400); return
            }
            lowConfidenceRetries = 0; failures = 0; steps++
            postStatus("AUTO • ${action.type} • ${action.confidence}% • ${action.reason.take(90)}")
            GameVisionAccessibilityService.execute(action) { success, result ->
                handler.post {
                    if (!active.get()) return@post
                    if (!success) {
                        failures++; postStatus("RETRYING • $result")
                        if (failures >= 3) stop("three consecutive Android action failures") else requestDecision(700)
                        return@post
                    }
                    history += JSONObject().put("role", "assistant").put("content", "ACTION ${action.type}: ${action.reason}")
                    history += JSONObject().put("role", "user").put("content", "Android result: $result. Re-check the current screen and continue the goal if appropriate.")
                    postStatus("VERIFYING • waiting for a new screen…")
                    val delay = action.waitMs.coerceIn(0, 5000)
                    handler.postDelayed({
                        if (active.get()) waitForFreshFrame(lastFrameSequence, lastServerEpoch, MAX_FRAME_WAIT_MS) { ready, sequenceAfter, epochAfter, error ->
                            if (!active.get()) return@waitForFreshFrame
                            if (ready) { lastFrameSequence = sequenceAfter; lastServerEpoch = epochAfter; failures = 0; requestDecision(100) }
                            else { failures++; postStatus(error ?: "Fresh screen timeout"); if (failures >= 3) stop("screen capture is not updating") else requestDecision(900) }
                        }
                    }, delay)
                }
            }
        } catch (error: Exception) {
            if (!active.get()) return
            failures++; postStatus("RETRYING • ${error.message ?: "network failure"}")
            if (failures >= 3) stop("repeated automation errors") else requestDecision(1200)
        }
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
                    if (code !in 200..299) throw IllegalStateException("HTTP $code")
                    val json = JSONObject(body)
                    val sequence = json.optLong("sequence", 0L)
                    val epoch = json.optString("serverEpoch", "").ifBlank { null }
                    val fresh = json.optBoolean("fresh", false)
                    val epochChanged = minEpoch != null && epoch != null && epoch != minEpoch
                    val sequenceFresh = sequence > minSequence
                    if (fresh && (sequenceFresh || epochChanged || minEpoch == null)) { handler.post { callback(true, sequence, epoch, null) }; return@execute }
                    if (System.currentTimeMillis() >= deadline) { handler.post { callback(false, sequence, epoch, "Timed out waiting for a fresh screen") }; return@execute }
                    handler.postDelayed({ poll() }, FRAME_POLL_MS)
                } catch (error: Exception) {
                    if (System.currentTimeMillis() >= deadline) handler.post { callback(false, minSequence, minEpoch, "Screen status unavailable: ${error.message ?: "network failure"}") }
                    else handler.postDelayed({ poll() }, FRAME_POLL_MS)
                }
            }
        }
        poll()
    }

    private fun postStatus(message: String) { handler.post { statusListener?.invoke(message) } }
    fun shutdown() { active.set(false); executor.shutdownNow() }
}
