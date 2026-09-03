package com.gamevision.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import android.widget.TextView
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
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var serverUrl = "https://gamevision-api-live-production.up.railway.app"
    private var wm: WindowManager? = null
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var input: EditText? = null
    private var answerView: TextView? = null

    override fun onCreate() { super.onCreate(); createChannel(); tts = TextToSpeech(this, this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)?.trim()?.removeSuffix("/") ?: serverUrl
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIFICATION_ID, notification())
                showBubble()
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun showBubble() {
        if (bubble != null) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val b = TextView(this).apply {
            text = "G/V"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(Color.BLACK); typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = rounded(0xFFB8FF3D.toInt(), 50); elevation = 12f; setOnClickListener { togglePanel() }; setOnTouchListener(DragTouchListener())
        }
        val params = WindowManager.LayoutParams(dp(54), dp(54), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = dp(18); y = dp(180) }
        try { wm?.addView(b, params); bubble = b; bubbleParams = params } catch (_: Exception) { stopSelf() }
    }

    private fun togglePanel() {
        if (panel != null) { removePanel(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(0xF010141B.toInt(), 22); elevation = 18f }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply { text = "GAMEVISION AI"; textSize = 14f; setTextColor(0xFFF4F7FA.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val close = TextView(this).apply { text = "×"; textSize = 22f; setTextColor(0xFF8D9AAA.toInt()); gravity = Gravity.CENTER; setOnClickListener { removePanel() } }
        header.addView(title, LinearLayout.LayoutParams(0, dp(34), 1f)); header.addView(close, LinearLayout.LayoutParams(dp(36), dp(34))); root.addView(header)
        root.addView(TextView(this).apply { text = "Ask about what is visible on screen"; textSize = 10f; setTextColor(0xFF8D9AAA.toInt()); setPadding(0, 0, 0, dp(8)) })
        input = EditText(this).apply { hint = "e.g. Check the score and minute"; textSize = 13f; setTextColor(0xFFF4F7FA.toInt()); setHintTextColor(0xFF687586.toInt()); setSingleLine(false); maxLines = 3; background = rounded(0xFF171D26.toInt(), 14); setPadding(dp(12), dp(8), dp(12), dp(8)); isFocusableInTouchMode = true }
        root.addView(input, LinearLayout.LayoutParams(-1, dp(70)))
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(actionButton("MIC") { startVoice() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(0, dp(8), dp(6), 0) })
        actions.addView(actionButton("ASK") { ask(input?.text?.toString().orEmpty()) }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(6), dp(8), 0, 0) })
        root.addView(actions)
        answerView = TextView(this).apply { text = "Ready. Tap MIC or type a question."; textSize = 12f; setTextColor(0xFFE7ECF2.toInt()); setPadding(0, dp(12), 0, 0) }
        root.addView(answerView)
        val p = WindowManager.LayoutParams(dp(310), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START; x = max(8, (bubbleParams?.x ?: 18) - dp(120)); y = max(dp(70), (bubbleParams?.y ?: 180) - dp(20)); softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        try { wm?.addView(root, p); panel = root; input?.requestFocus() } catch (_: Exception) { }
    }

    private fun actionButton(label: String, click: () -> Unit) = TextView(this).apply { text = label; textSize = 11f; gravity = Gravity.CENTER; setTextColor(0xFFB8FF3D.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD; background = rounded(0x3326B500, 14); setOnClickListener { click() } }

    private fun ask(question: String) {
        val instruction = question.trim()
        if (instruction.isBlank()) { answerView?.text = "Tell me what you want me to check."; return }
        answerView?.text = "ANALYZING…"
        executor.execute {
            try {
                val connection = URL("$serverUrl/api/ask").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"; connection.connectTimeout = 8000; connection.readTimeout = 18000; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Accept", "application/json")
                connection.outputStream.use { it.write(JSONObject().put("instruction", instruction).toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                val json = JSONObject(text)
                val answer = json.optJSONObject("reply")?.optString("answer", "I could not determine that from the visible screen.") ?: json.optString("error", "I could not answer that right now.")
                handler.post { answerView?.text = answer; if (code in 200..299) speak(answer) }
            } catch (e: Exception) { handler.post { answerView?.text = "Assistant unavailable: ${e.message ?: "connection error"}" } }
        }
    }

    private fun startVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { answerView?.text = "Speech recognition is not available on this device."; return }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: android.os.Bundle?) { val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty(); input?.setText(text); if (text.isNotBlank()) ask(text) }
                    override fun onError(error: Int) { handler.post { answerView?.text = "Voice input error. Try again." } }
                    override fun onReadyForSpeech(params: android.os.Bundle?) { handler.post { answerView?.text = "LISTENING…" } }
                    override fun onBeginningOfSpeech() {}; override fun onRmsChanged(rmsdB: Float) {}; override fun onBufferReceived(buffer: ByteArray?) {}; override fun onEndOfSpeech() {}; override fun onPartialResults(partialResults: android.os.Bundle?) {}; override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault()); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false) }
        speechRecognizer?.startListening(intent)
    }

    private fun speak(text: String) { handler.post { tts?.speak(text.take(700), TextToSpeech.QUEUE_FLUSH, null, "gamevision-answer") } }
    private fun removePanel() { panel?.let { runCatching { wm?.removeView(it) } }; panel = null; input = null; answerView = null }

    private inner class DragTouchListener : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f; private var startX = 0; private var startY = 0; private var dragging = false
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val p = bubbleParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = p.x; startY = p.y; dragging = false; return true }
                MotionEvent.ACTION_MOVE -> { val dx = event.rawX - downX; val dy = event.rawY - downY; if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) dragging = true; p.x = startX + dx.toInt(); p.y = startY + dy.toInt(); wm?.updateViewLayout(v, p); return true }
                MotionEvent.ACTION_UP -> { if (!dragging) v.performClick(); return true }
            }
            return false
        }
    }

    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL, "GameVision Assistant", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(): Notification = Notification.Builder(this, CHANNEL).setContentTitle("GameVision Assistant").setContentText("Floating AI assistant is active").setSmallIcon(android.R.drawable.ic_dialog_info).setOngoing(true).build()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onInit(status: Int) {}
    override fun onDestroy() { removePanel(); bubble?.let { runCatching { wm?.removeView(it) } }; bubble = null; speechRecognizer?.destroy(); speechRecognizer = null; tts?.shutdown(); tts = null; executor.shutdownNow(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
