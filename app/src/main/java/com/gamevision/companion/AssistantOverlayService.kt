package com.gamevision.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

class AssistantOverlayService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_START = "com.gamevision.companion.ASSISTANT_START"
        const val ACTION_STOP = "com.gamevision.companion.ASSISTANT_STOP"
        const val EXTRA_SERVER_URL = "server_url"
        private const val CHANNEL = "gamevision_assistant"
        private const val NOTIFICATION_ID = 78
        private const val MAX_VOICE_RETRIES = 2
    }

    private data class Message(val role: String, val content: String)
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val messages = mutableListOf<Message>()
    private var serverUrl = "https://gamevision-api-v2-production.up.railway.app"
    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var messagesLayout: LinearLayout? = null
    private var input: EditText? = null
    private var statusView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var automation: AutomationController? = null
    private var voiceRetryCount = 0

    override fun onCreate() { super.onCreate(); createChannel(); tts = TextToSpeech(this, this); automation = AutomationController() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> { serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)?.trim()?.removeSuffix("/") ?: serverUrl; if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIFICATION_ID, notification()); showBubble() }
            ACTION_STOP -> { automation?.stop("service stopped"); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun showBubble() {
        if (bubble != null) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val b = TextView(this).apply { text = "G/V"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.BLACK); typeface = android.graphics.Typeface.DEFAULT_BOLD; background = rounded(0xFFB8FF3D.toInt(), 50); elevation = 12f; setOnClickListener { togglePanel() }; setOnTouchListener(DragTouchListener()) }
        val params = WindowManager.LayoutParams(dp(54), dp(54), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(18); y = dp(180) }
        runCatching { wm?.addView(b, params); bubble = b; bubbleParams = params }.onFailure { stopSelf() }
    }

    private fun togglePanel() {
        if (panel != null) { removePanel(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(0xF010141B.toInt(), 22); elevation = 18f }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply { text = "GAMEVISION AI"; textSize = 14f; setTextColor(0xFFF4F7FA.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val close = TextView(this).apply { text = "×"; textSize = 22f; setTextColor(0xFF8D9AAA.toInt()); gravity = Gravity.CENTER; setOnClickListener { removePanel() } }
        header.addView(title, LinearLayout.LayoutParams(0, dp(34), 1f)); header.addView(close, LinearLayout.LayoutParams(dp(36), dp(34))); root.addView(header)

        statusView = TextView(this).apply { text = "MANUAL • active assistant"; textSize = 9f; setTextColor(0xFFB8FF3D.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(7)) }
        root.addView(statusView)
        val access = actionButton("ACCESSIBILITY") { openAccessibilitySettings() }
        root.addView(access, LinearLayout.LayoutParams(-1, dp(34)).apply { setMargins(0, 0, 0, dp(7)) })

        scroll = ScrollView(this).apply { isFillViewport = true; background = rounded(0xFF151A22.toInt(), 14) }
        messagesLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        scroll?.addView(messagesLayout)
        root.addView(scroll, LinearLayout.LayoutParams(-1, dp(190)))

        input = EditText(this).apply { hint = "Type what you want GameVision to do…"; textSize = 13f; setTextColor(0xFFF4F7FA.toInt()); setHintTextColor(0xFF687586.toInt()); maxLines = 3; background = rounded(0xFF171D26.toInt(), 14); setPadding(dp(12), dp(8), dp(12), dp(8)); isFocusableInTouchMode = true }
        root.addView(input, LinearLayout.LayoutParams(-1, dp(66)).apply { setMargins(0, dp(7), 0, 0) })

        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(actionButton("MIC") { startVoice() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(0, dp(7), dp(3), 0) })
        actions.addView(actionButton("SEND") { sendCurrent() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(3), dp(7), dp(3), 0) })
        actions.addView(actionButton("AUTO") { startAuto() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(3), dp(7), dp(3), 0) })
        actions.addView(actionButton("STOP") { stopAuto() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(3), dp(7), 0, 0) })
        root.addView(actions)

        val params = WindowManager.LayoutParams(dp(330), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = max(8, (bubbleParams?.x ?: 18) - dp(130)); y = max(dp(65), (bubbleParams?.y ?: 180) - dp(20)); softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        runCatching { wm?.addView(root, params); panel = root; input?.requestFocus() }
        renderMessages()
    }

    private fun sendCurrent() {
        val text = input?.text?.toString().orEmpty().trim()
        if (text.isBlank()) return
        input?.setText("")
        addMessage("user", text)
        if (looksLikeActionCommand(text)) startAutoGoal(text) else ask(text)
    }

    private fun looksLikeActionCommand(text: String): Boolean {
        val value = text.trim().lowercase(Locale.US)
        if (value.matches(Regex("^(tap|click|press|hold|swipe|scroll|drag|drop|type|enter|open|close|select|choose|start|stop|play|find|search|send|reply|go back|go home|navigate|turn on|turn off|enable|disable|launch|move|touch|double tap|long press)\\b.*"))) return true
        if (value.startsWith("do ") || value.startsWith("make ") || value.startsWith("keep ") || value.startsWith("continue ")) return true
        if (value.contains(" for me") && !value.startsWith("what") && !value.startsWith("why") && !value.startsWith("how")) return true
        return false
    }

    private fun ask(question: String) {
        statusView?.text = "THINKING…"
        val history = messages.takeLast(12)
        executor.execute {
            try {
                val connection = URL("$serverUrl/api/ask").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"; connection.connectTimeout = 9000; connection.readTimeout = 30000; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Accept", "application/json")
                val array = JSONArray(); history.forEach { array.put(JSONObject().put("role", it.role).put("content", it.content)) }
                val body = JSONObject().put("instruction", question).put("messages", array).toString()
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                val json = if (text.isNotBlank()) JSONObject(text) else JSONObject()
                val answer = json.optJSONObject("reply")?.optString("answer").orEmpty().ifBlank { json.optString("error").ifBlank { "Assistant did not return an answer." } }
                handler.post { addMessage("assistant", answer); statusView?.text = "MANUAL • ${json.optString("provider", "AI").uppercase(Locale.US)}"; if (code in 200..299) speak(answer) }
            } catch (e: Exception) { handler.post { addMessage("assistant", "Assistant connection failed. Retrying is available."); statusView?.text = "ERROR • connection" } }
        }
    }

    private fun startAuto() {
        val goal = input?.text?.toString().orEmpty().trim()
        if (goal.isBlank()) { addMessage("assistant", "Type or say the goal first. I will execute action commands automatically."); return }
        input?.setText("")
        addMessage("user", "AUTO: $goal")
        startAutoGoal(goal)
    }

    private fun startAutoGoal(goal: String) {
        val snapshot = messages.takeLast(10).map { JSONObject().put("role", it.role).put("content", it.content) }
        val started = automation?.start(serverUrl, goal, snapshot) { status ->
            handler.post {
                statusView?.text = status
                if (status.startsWith("AUTO OFF")) addMessage("assistant", status.removePrefix("AUTO OFF • "))
            }
        } == true
        if (!started && !automation!!.isActive()) addMessage("assistant", "I couldn't start control. Make sure GameVision Accessibility is enabled, then try the command again.")
    }

    private fun stopAuto() { automation?.stop("Stopped by user"); statusView?.text = "MANUAL • stopped" }

    private fun startVoice() {
        voiceRetryCount = 0
        startVoiceAttempt()
    }

    private fun startVoiceAttempt() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { addMessage("assistant", "Microphone permission is not enabled. Enable it in Android app permissions, then try MIC again."); return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { addMessage("assistant", "Speech recognition is not available on this device."); return }
        speechRecognizer?.cancel(); speechRecognizer?.destroy(); speechRecognizer = null
        val recognizer = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(this) }.getOrNull()
        } else null
        speechRecognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) { handler.post { statusView?.text = "LISTENING… speak now" } }
                override fun onBeginningOfSpeech() { handler.post { statusView?.text = "LISTENING…" } }
                override fun onRmsChanged(rmsdB: Float) { if (rmsdB > -2f) handler.post { statusView?.text = "LISTENING… receiving voice" } }
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) handler.post { input?.setText(text); input?.setSelection(input?.text?.length ?: 0); statusView?.text = "HEARING • $text" }
                }
                override fun onResults(results: android.os.Bundle?) {
                    val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    val text = candidates.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                    handler.post {
                        speechRecognizer?.destroy(); speechRecognizer = null
                        if (text.isNotBlank()) { voiceRetryCount = 0; input?.setText(text); input?.setSelection(text.length); sendCurrent() }
                        else retryVoice("NO SPEECH • trying again")
                    }
                }
                override fun onError(error: Int) { val message = voiceError(error); handler.post { speechRecognizer?.destroy(); speechRecognizer = null; retryVoice(message) } }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { handler.post { statusView?.text = "PROCESSING VOICE…" } }
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your GameVision command")
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L)
        }
        runCatching { speechRecognizer?.startListening(intent) }.onFailure { speechRecognizer?.destroy(); speechRecognizer = null; retryVoice("VOICE START FAILED") }
    }

    private fun retryVoice(message: String) {
        if (voiceRetryCount < MAX_VOICE_RETRIES) {
            voiceRetryCount++
            statusView?.text = "$message • retry ${voiceRetryCount}/$MAX_VOICE_RETRIES"
            handler.postDelayed({ startVoiceAttempt() }, 700L)
        } else {
            statusView?.text = "$message • tap MIC to try again"
        }
    }

    private fun voiceError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "VOICE ERROR • microphone audio"
        SpeechRecognizer.ERROR_CLIENT -> "VOICE ERROR • recognizer reset"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "VOICE ERROR • microphone permission"
        SpeechRecognizer.ERROR_NETWORK -> "VOICE ERROR • speech network unavailable"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "VOICE ERROR • speech network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "NO SPEECH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "VOICE BUSY"
        SpeechRecognizer.ERROR_SERVER -> "VOICE ERROR • recognition server"
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "VOICE ERROR • recognition disconnected"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "NO SPEECH"
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "VOICE BUSY • too many requests"
        else -> "VOICE ERROR • code $error"
    }

    private fun addMessage(role: String, content: String) { messages += Message(role, content.take(1600)); while (messages.size > 24) messages.removeAt(0); renderMessages() }
    private fun renderMessages() { handler.post { val box = messagesLayout ?: return@post; box.removeAllViews(); if (messages.isEmpty()) addMessageView(box, "assistant", "Ready. I can chat, understand your goal, see the current screen, and execute supported commands when Accessibility is enabled.") else messages.forEach { addMessageView(box, it.role, it.content) }; scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) } } }
    private fun addMessageView(box: LinearLayout, role: String, text: String) { val view = TextView(this).apply { this.text = "${if (role == "user") "YOU" else "AI"}\n$text"; textSize = 12f; setTextColor(if (role == "user") 0xFFB8FF3D.toInt() else 0xFFE7ECF2.toInt()); background = rounded(if (role == "user") 0x3326B500 else 0xFF202733.toInt(), 12); setPadding(dp(9), dp(8), dp(9), dp(8)) }; box.addView(view, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(7)) }) }
    private fun actionButton(label: String, click: () -> Unit) = TextView(this).apply { text = label; textSize = 9f; gravity = Gravity.CENTER; setTextColor(0xFFB8FF3D.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD; background = rounded(0x3326B500, 12); setOnClickListener { click() } }
    private fun openAccessibilitySettings() { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); statusView?.text = "ENABLE GAMEVISION ACCESSIBILITY" } }
    private fun speak(text: String) { handler.post { tts?.speak(text.take(700), TextToSpeech.QUEUE_FLUSH, null, "gamevision-answer") } }
    private fun removePanel() { panel?.let { runCatching { wm?.removeView(it) } }; panel = null; scroll = null; messagesLayout = null; input = null; statusView = null }
    private inner class DragTouchListener : View.OnTouchListener { private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0; private var dragging = false; override fun onTouch(v: View, event: MotionEvent): Boolean { val p = bubbleParams ?: return false; when (event.actionMasked) { MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = p.x; startY = p.y; dragging = false; return true }; MotionEvent.ACTION_MOVE -> { val dx = event.rawX - downX; val dy = event.rawY - downY; if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) dragging = true; p.x = startX + dx.toInt(); p.y = startY + dy.toInt(); wm?.updateViewLayout(v, p); return true }; MotionEvent.ACTION_UP -> { if (!dragging) v.performClick(); return true } }; return false } }
    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL, "GameVision Assistant", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(): Notification = Notification.Builder(this, CHANNEL).setContentTitle("GameVision Assistant").setContentText("Visual assistant and optional AUTO control are active").setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(true).build()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onInit(status: Int) {}
    override fun onDestroy() { automation?.stop("service destroyed"); automation?.shutdown(); removePanel(); bubble?.let { runCatching { wm?.removeView(it) } }; bubble = null; speechRecognizer?.destroy(); speechRecognizer = null; tts?.shutdown(); tts = null; executor.shutdownNow(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}