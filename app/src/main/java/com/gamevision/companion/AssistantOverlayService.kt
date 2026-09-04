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
import android.widget.ImageButton
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
        private const val FRAME_POLL_MS = 2000L
        private const val LIVE_RESTART_MS = 900L
    }

    private data class Message(val role: String, val content: String)
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val messages = mutableListOf<Message>()
    private var serverUrl = "https://gamevision-api.onrender.com"
    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var scroll: ScrollView? = null
    private var messagesLayout: LinearLayout? = null
    private var input: EditText? = null
    private var statusView: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var liveMic: ImageButton? = null
    private var liveMicParams: WindowManager.LayoutParams? = null
    private var liveVoiceActive = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var automation: AutomationController? = null
    private var voiceRetryCount = 0

    private val framePoller = object : Runnable { override fun run() { pollFrameStatus(); handler.postDelayed(this, FRAME_POLL_MS) } }

    override fun onCreate() { super.onCreate(); createChannel(); tts = TextToSpeech(this, this); automation = AutomationController() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> { serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)?.trim()?.removeSuffix("/") ?: serverUrl; if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIFICATION_ID, notification()); showBubble() }
            ACTION_STOP -> { liveVoiceActive = false; speechRecognizer?.cancel(); automation?.stop("service stopped"); stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun showBubble() {
        if (bubble != null) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val b = TextView(this).apply { text = "G/V"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.BLACK); typeface = android.graphics.Typeface.DEFAULT_BOLD; background = rounded(0xFFB8FF3D.toInt(), 50); elevation = 12f; setOnClickListener { togglePanel() }; setOnTouchListener(DragTouchListener { bubbleParams }) }
        val params = WindowManager.LayoutParams(dp(54), dp(54), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(18); y = dp(180) }
        runCatching { wm?.addView(b, params); bubble = b; bubbleParams = params; showLiveMic() }.onFailure { stopSelf() }
    }

    private fun showLiveMic() {
        if (liveMic != null) return
        val mic = ImageButton(this).apply { setImageResource(R.drawable.ic_gv_mic); contentDescription = "Live voice assistant. Tap to start or stop continuous listening"; background = rounded(0xFF202733.toInt(), 50); setPadding(dp(12), dp(12), dp(12), dp(12)); elevation = 14f; setOnClickListener { toggleLiveVoice() }; setOnTouchListener(LiveMicDragTouchListener()) }
        val params = WindowManager.LayoutParams(dp(54), dp(54), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(82); y = dp(180) }
        runCatching { wm?.addView(mic, params); liveMic = mic; liveMicParams = params }.onFailure { liveMic = null; liveMicParams = null }
    }

    private fun toggleLiveVoice() {
        liveVoiceActive = !liveVoiceActive
        if (liveVoiceActive) {
            statusView?.text = "VOICE • LIVE MODE • LISTENING"
            liveMic?.setBackground(rounded(0xFFB8FF3D.toInt(), 50)); liveMic?.setColorFilter(Color.BLACK)
            voiceRetryCount = 0
            startVoiceAttempt()
        } else {
            speechRecognizer?.cancel(); speechRecognizer?.destroy(); speechRecognizer = null
            liveMic?.setBackground(rounded(0xFF202733.toInt(), 50)); liveMic?.setColorFilter(0xFFB8FF3D.toInt())
            statusView?.text = "VOICE • LIVE MODE OFF"
        }
    }

    private fun togglePanel() {
        if (panel != null) { removePanel(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(0xF010141B.toInt(), 22); elevation = 18f }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply { text = "GAMEVISION AI"; textSize = 14f; setTextColor(0xFFF4F7FA.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val close = TextView(this).apply { text = "×"; textSize = 22f; setTextColor(0xFF8D9AAA.toInt()); gravity = Gravity.CENTER; setOnClickListener { removePanel() } }
        header.addView(title, LinearLayout.LayoutParams(0, dp(34), 1f)); header.addView(close, LinearLayout.LayoutParams(dp(36), dp(34))); root.addView(header)
        statusView = TextView(this).apply { text = if (liveVoiceActive) "VOICE • LIVE MODE" else "CAPTURE • CHECKING"; textSize = 9f; setTextColor(0xFFB8FF3D.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(7)) }; root.addView(statusView)
        val access = textButton("ACCESSIBILITY") { openAccessibilitySettings() }; root.addView(access, LinearLayout.LayoutParams(-1, dp(34)).apply { setMargins(0, 0, 0, dp(7)) })
        scroll = ScrollView(this).apply { isFillViewport = true; background = rounded(0xFF151A22.toInt(), 14) }
        messagesLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }; scroll?.addView(messagesLayout); root.addView(scroll, LinearLayout.LayoutParams(-1, dp(190)))
        val chatRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(7), 0, 0) }
        input = EditText(this).apply { hint = "Message GameVision…"; textSize = 13f; setTextColor(0xFFF4F7FA.toInt()); setHintTextColor(0xFF687586.toInt()); maxLines = 3; background = rounded(0xFF171D26.toInt(), 16); setPadding(dp(12), dp(8), dp(12), dp(8)); isFocusableInTouchMode = true }
        chatRow.addView(input, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(0, 0, dp(5), 0) }); chatRow.addView(iconButton(R.drawable.ic_gv_mic, "Toggle live voice mode") { toggleLiveVoice() }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { setMargins(0, 0, dp(3), 0) }); chatRow.addView(iconButton(R.drawable.ic_gv_send, "Send message") { sendCurrent() }, LinearLayout.LayoutParams(dp(46), dp(46))); root.addView(chatRow)
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }; controls.addView(iconButton(R.drawable.ic_gv_auto, "Start automatic control") { startAuto() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(0, dp(7), dp(3), 0) }); controls.addView(iconButton(R.drawable.ic_gv_stop, "Stop automatic control") { stopAuto() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { setMargins(dp(3), dp(7), 0, 0) }); root.addView(controls)
        val params = WindowManager.LayoutParams(dp(330), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = max(8, (bubbleParams?.x ?: 18) - dp(130)); y = max(dp(65), (bubbleParams?.y ?: 180) - dp(20)); softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
        runCatching { wm?.addView(root, params); panel = root; input?.requestFocus(); pollFrameStatus(); handler.removeCallbacks(framePoller); handler.post(framePoller) }; renderMessages()
    }

    private fun sendCurrent() { val text = input?.text?.toString().orEmpty().trim(); if (text.isBlank()) return; input?.setText(""); addMessage("user", text); if (looksLikeActionCommand(text)) startAutoGoal(text) else ask(text) }
    private fun looksLikeActionCommand(text: String): Boolean { val value = text.trim().lowercase(Locale.US); if (value.matches(Regex("^(tap|click|press|hold|swipe|scroll|drag|drop|type|enter|open|close|select|choose|start|stop|play|find|search|send|reply|go back|go home|navigate|turn on|turn off|enable|disable|launch|move|touch|double tap|long press|use|check|look at|do|make|keep|continue)\\b.*"))) return true; return value.contains(" for me") && !value.startsWith("what") && !value.startsWith("why") && !value.startsWith("how") }

    private fun ask(question: String) {
        statusView?.text = "AI • THINKING • CAPTURE STATUS BELOW"; val history = messages.takeLast(12)
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("$serverUrl/api/ask").openConnection() as HttpURLConnection; connection.requestMethod = "POST"; connection.connectTimeout = 9000; connection.readTimeout = 30000; connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Accept", "application/json")
                val token = AuthStore.token(this).orEmpty(); if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
                val array = JSONArray(); history.forEach { array.put(JSONObject().put("role", it.role).put("content", it.content)) }; connection.outputStream.use { it.write(JSONObject().put("instruction", question).put("messages", array).toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode; val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty(); val json = if (text.isNotBlank()) JSONObject(text) else JSONObject(); val answer = json.optJSONObject("reply")?.optString("answer").orEmpty().ifBlank { json.optString("error").ifBlank { "Assistant returned no answer." } }; val provider = json.optString("provider", "AI"); val vision = json.optBoolean("visionUsed", false)
                handler.post { if (code in 200..299) { addMessage("assistant", answer); statusView?.text = "AI • ${provider.uppercase(Locale.US)} • ${if (vision) "VISION ON" else "CHAT"}"; speak(answer); scheduleLiveListen() } else if (code == 401) { addMessage("assistant", "Your GameVision session expired. Sign in again to continue."); statusView?.text = "ACCOUNT • SIGN IN REQUIRED" } else if (code == 409) { addMessage("assistant", "Screen capture is not ready yet. Keep Monitor running and I will use the next frame."); statusView?.text = "CAPTURE • WAITING FOR FRAME" } else if (code == 429) { addMessage("assistant", json.optString("error").ifBlank { "Your free allowance is temporarily unavailable." }); statusView?.text = "AI • FREE ALLOWANCE / RETRY" } else { addMessage("assistant", "Free AI is temporarily unavailable. Please try again shortly."); statusView?.text = "AI • RETRY AVAILABLE" } }
            } catch (e: Exception) { handler.post { addMessage("assistant", "GameVision cannot reach the AI service. Check your network connection and try again."); statusView?.text = "AI • CONNECTION ERROR" } } finally { connection?.disconnect() }
        }
    }

    private fun scheduleLiveListen() { if (liveVoiceActive) handler.postDelayed({ if (liveVoiceActive) startVoiceAttempt() }, LIVE_RESTART_MS) }

    private fun pollFrameStatus() {
        if (panel == null) return
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("$serverUrl/api/frame-status").openConnection() as HttpURLConnection; connection.requestMethod = "GET"; connection.connectTimeout = 4000; connection.readTimeout = 5000; val token = AuthStore.token(this).orEmpty(); if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
                val code = connection.responseCode; val body = if (code in 200..299) connection.inputStream.bufferedReader().use { it.readText() } else ""
                if (code == 401) { handler.post { statusView?.text = "ACCOUNT • SIGN IN REQUIRED" }; return@execute }
                if (code in 200..299 && body.isNotBlank()) { val json = JSONObject(body); val sequence = json.optInt("sequence", 0); val fresh = json.optBoolean("fresh", false); val age = json.optLong("ageMs", -1L); handler.post { if (!liveVoiceActive) statusView?.text = when { fresh && sequence > 0 -> "CAPTURE • LIVE • FRAME #$sequence • ${if (age >= 0) "${age}ms" else "fresh"}"; sequence > 0 -> "CAPTURE • STALE • FRAME #$sequence"; else -> "CAPTURE • WAITING FOR FIRST FRAME" } } } else handler.post { statusView?.text = "CAPTURE • SERVER $code" }
            } catch (_: Exception) { if (!liveVoiceActive) handler.post { statusView?.text = "CAPTURE • CONNECTION CHECK FAILED" } } finally { connection?.disconnect() }
        }
    }

    private fun startAuto() { val goal = input?.text?.toString().orEmpty().trim(); if (goal.isBlank()) { addMessage("assistant", "Type or say the task first. I will send it to the action planner."); return }; input?.setText(""); addMessage("user", "AUTO: $goal"); startAutoGoal(goal) }
    private fun startAutoGoal(goal: String) { val snapshot = messages.takeLast(10).map { JSONObject().put("role", it.role).put("content", it.content) }; val token = AuthStore.token(this).orEmpty(); val started = automation?.start(serverUrl, token, goal, snapshot) { status -> handler.post { statusView?.text = status; if (status.startsWith("AUTO OFF")) addMessage("assistant", status.removePrefix("AUTO OFF • ")) } } == true; if (!started && automation?.isActive() != true) addMessage("assistant", "Control is not active. Enable GameVision Accessibility, then try the command again.") }
    private fun stopAuto() { automation?.stop("Stopped by user"); statusView?.text = "AUTO • STOPPED BY USER" }

    private fun startVoiceAttempt() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { addMessage("assistant", "Microphone permission is not enabled. Enable it in Android app permissions, then tap the mic icon again."); liveVoiceActive = false; return }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { addMessage("assistant", "Speech recognition is not available on this device."); liveVoiceActive = false; return }
        speechRecognizer?.cancel(); speechRecognizer?.destroy(); speechRecognizer = null
        val recognizer = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(this) }.getOrNull() else null
        speechRecognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.apply { setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { handler.post { statusView?.text = if (liveVoiceActive) "VOICE • LIVE • LISTENING…" else "VOICE • LISTENING…" } }
            override fun onBeginningOfSpeech() { handler.post { statusView?.text = "VOICE • RECEIVING" } }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onPartialResults(partialResults: android.os.Bundle?) { val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty(); if (text.isNotBlank()) handler.post { input?.setText(text); input?.setSelection(input?.text?.length ?: 0); statusView?.text = "VOICE • $text" } }
            override fun onResults(results: android.os.Bundle?) { val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(); val text = candidates.firstOrNull { it.isNotBlank() }?.trim().orEmpty(); handler.post { speechRecognizer?.destroy(); speechRecognizer = null; if (text.isNotBlank()) { voiceRetryCount = 0; input?.setText(text); input?.setSelection(text.length); sendCurrent(); scheduleLiveListen() } else retryVoice("VOICE • NO SPEECH") } }
            override fun onError(error: Int) { val message = voiceError(error); handler.post { speechRecognizer?.destroy(); speechRecognizer = null; retryVoice(message) } }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { handler.post { statusView?.text = "VOICE • PROCESSING" } }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        }) }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag()); putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault().toLanguageTag()); putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true); putExtra(RecognizerIntent.EXTRA_PROMPT, if (liveVoiceActive) "Live GameVision command" else "Speak your GameVision command"); putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L); putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700L); putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1400L) }
        runCatching { speechRecognizer?.startListening(intent) }.onFailure { speechRecognizer?.destroy(); speechRecognizer = null; retryVoice("VOICE • START FAILED") }
    }
    private fun retryVoice(message: String) { if (voiceRetryCount < MAX_VOICE_RETRIES) { voiceRetryCount++; statusView?.text = "$message • RETRY $voiceRetryCount/$MAX_VOICE_RETRIES"; handler.postDelayed({ if (liveVoiceActive || voiceRetryCount <= MAX_VOICE_RETRIES) startVoiceAttempt() }, 700L) } else if (liveVoiceActive) { voiceRetryCount = 0; handler.postDelayed({ if (liveVoiceActive) startVoiceAttempt() }, 1200L) } else statusView?.text = "$message • TAP MIC TO TRY AGAIN" }
    private fun voiceError(error: Int): String = when (error) { SpeechRecognizer.ERROR_AUDIO -> "VOICE ERROR • MICROPHONE AUDIO"; SpeechRecognizer.ERROR_CLIENT -> "VOICE ERROR • RECOGNIZER RESET"; SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "VOICE ERROR • MICROPHONE PERMISSION"; SpeechRecognizer.ERROR_NETWORK -> "VOICE ERROR • SPEECH NETWORK"; SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "VOICE ERROR • SPEECH TIMEOUT"; SpeechRecognizer.ERROR_NO_MATCH -> "VOICE • NO MATCH"; SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "VOICE • BUSY"; SpeechRecognizer.ERROR_SERVER -> "VOICE ERROR • SERVER"; SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "VOICE ERROR • DISCONNECTED"; SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "VOICE • NO SPEECH"; SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "VOICE • BUSY"; else -> "VOICE ERROR • CODE $error" }

    private fun addMessage(role: String, content: String) { messages += Message(role, content.take(1600)); while (messages.size > 24) messages.removeAt(0); renderMessages() }
    private fun renderMessages() { handler.post { val box = messagesLayout ?: return@post; box.removeAllViews(); if (messages.isEmpty()) addMessageView(box, "assistant", "Ready. Monitor will show CAPTURE LIVE when a fresh screen frame reaches the server. Tap the floating mic to start LIVE voice mode; drag it anywhere on screen. I can chat, understand the visible screen, and execute supported Android UI commands when Accessibility is enabled.") else messages.forEach { addMessageView(box, it.role, it.content) }; scroll?.post { scroll?.fullScroll(View.FOCUS_DOWN) } } }
    private fun addMessageView(box: LinearLayout, role: String, text: String) { val view = TextView(this).apply { this.text = "${if (role == "user") "YOU" else "AI"}\n$text"; textSize = 12f; setTextColor(if (role == "user") 0xFFB8FF3D.toInt() else 0xFFE7ECF2.toInt()); background = rounded(if (role == "user") 0x3326B500 else 0xFF202733.toInt(), 12); setPadding(dp(9), dp(8), dp(9), dp(8)) }; box.addView(view, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, dp(7)) }) }
    private fun textButton(label: String, click: () -> Unit) = TextView(this).apply { text = label; textSize = 9f; gravity = Gravity.CENTER; setTextColor(0xFFB8FF3D.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD; background = rounded(0x3326B500, 12); setOnClickListener { click() } }
    private fun iconButton(icon: Int, description: String, click: () -> Unit) = ImageButton(this).apply { setImageResource(icon); setColorFilter(0xFFB8FF3D.toInt()); contentDescription = description; background = rounded(0x3326B500, 12); setPadding(dp(11), dp(11), dp(11), dp(11)); isFocusable = true; setOnClickListener { click() } }
    private fun openAccessibilitySettings() { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); statusView?.text = "ENABLE GAMEVISION ACCESSIBILITY" } }
    private fun speak(text: String) { handler.post { tts?.speak(text.take(700), TextToSpeech.QUEUE_FLUSH, null, "gamevision-answer") } }
    private fun removePanel() { handler.removeCallbacks(framePoller); panel?.let { runCatching { wm?.removeView(it) } }; panel = null; scroll = null; messagesLayout = null; input = null; statusView = null }

    private inner class DragTouchListener(private val paramsProvider: () -> WindowManager.LayoutParams?) : View.OnTouchListener { private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0; private var dragging = false; override fun onTouch(v: View, event: MotionEvent): Boolean { val p = paramsProvider() ?: return false; when (event.actionMasked) { MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = p.x; startY = p.y; dragging = false; return true }; MotionEvent.ACTION_MOVE -> { val dx = event.rawX - downX; val dy = event.rawY - downY; if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) dragging = true; p.x = startX + dx.toInt(); p.y = startY + dy.toInt(); wm?.updateViewLayout(v, p); return true }; MotionEvent.ACTION_UP -> { if (!dragging) v.performClick(); return true } }; return false } }
    private inner class LiveMicDragTouchListener : View.OnTouchListener { private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0; private var dragging = false; override fun onTouch(v: View, event: MotionEvent): Boolean { val p = liveMicParams ?: return false; when (event.actionMasked) { MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = p.x; startY = p.y; dragging = false; return true }; MotionEvent.ACTION_MOVE -> { val dx = event.rawX - downX; val dy = event.rawY - downY; if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) dragging = true; p.x = startX + dx.toInt(); p.y = startY + dy.toInt(); wm?.updateViewLayout(v, p); return true }; MotionEvent.ACTION_UP -> { if (!dragging) v.performClick(); return true } }; return false } }
    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL, "GameVision Assistant", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(): Notification = Notification.Builder(this, CHANNEL).setContentTitle("GameVision Assistant").setContentText("Screen monitor, chat and optional AUTO control are active").setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(true).build()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onInit(status: Int) {}
    override fun onDestroy() { liveVoiceActive = false; handler.removeCallbacks(framePoller); automation?.stop("service destroyed"); automation?.shutdown(); removePanel(); bubble?.let { runCatching { wm?.removeView(it) } }; bubble = null; liveMic?.let { runCatching { wm?.removeView(it) } }; liveMic = null; speechRecognizer?.destroy(); speechRecognizer = null; tts?.shutdown(); tts = null; executor.shutdownNow(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
