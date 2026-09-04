package com.gamevision.companion

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var visionAiStatus: TextView
    private lateinit var serverUrl: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private val captureRequest = 4101
    private val audioRequest = 4102
    private val healthExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val defaultServerUrl = "https://gamevision-api-v2-production.up.railway.app"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)
        visionAiStatus = findViewById(R.id.visionAiStatus)
        serverUrl = findViewById(R.id.serverUrl)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        serverUrl.setText(defaultServerUrl)
        findViewById<Button>(R.id.overlayButton).setOnClickListener { openOverlaySettings() }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener { openAccessibilitySettings() }
        startButton.setOnClickListener { requestCapture() }
        stopButton.setOnClickListener { stopMonitoring() }
        findViewById<Button>(R.id.hudButton).setOnClickListener { sendHudToggle() }
        checkAiConfiguration()
        updateMonitorButtons(false)
    }

    private fun openOverlaySettings() {
        if (Settings.canDrawOverlays(this)) { status.text = "Display over other apps: ENABLED"; return }
        status.text = "Opening Android overlay settings…"
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { data = Uri.parse("package:$packageName") }) }
            .onFailure { status.text = "Open Settings → Special app access → Display over other apps → GameVision" }
    }

    private fun openAccessibilitySettings() {
        status.text = if (GameVisionAccessibilityService.isEnabled()) "GameVision AUTO CONTROL: ENABLED" else "Opening Accessibility settings…"
        if (!GameVisionAccessibilityService.isEnabled()) runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            status.text = when {
                GameVisionAccessibilityService.isEnabled() -> "AUTO CONTROL READY • Accessibility enabled"
                Settings.canDrawOverlays(this) -> "Overlay enabled • enable Accessibility for AUTO"
                else -> "Enable overlay and Accessibility for full control"
            }
        }
        if (::serverUrl.isInitialized) checkAiConfiguration()
        if (::startButton.isInitialized) updateMonitorButtons(MonitorService.isRunning())
    }

    private fun updateMonitorButtons(active: Boolean) {
        startButton.isEnabled = !active
        stopButton.isEnabled = active
        startButton.alpha = if (active) 0.45f else 1f
        stopButton.alpha = if (active) 1f else 0.45f
    }

    private fun checkAiConfiguration() {
        val baseUrl = serverUrl.text.toString().trim().removeSuffix("/")
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) { setAiStatus("SERVER URL INVALID", Color.RED); return }
        visionAiStatus.text = "CHECKING…"
        visionAiStatus.setTextColor(getColor(com.gamevision.companion.R.color.gv_lime))
        healthExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("$baseUrl/health").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val healthy = json.optString("status") == "healthy"
                val configured = json.optBoolean("aiConfigured", false)
                val model = json.optString("model", "AI")
                val primary = json.optString("primaryProvider", "AI").uppercase()
                runOnUiThread {
                    if (!healthy) setAiStatus("SERVER UNHEALTHY", Color.RED)
                    else if (configured) setAiStatus("READY • $primary", getColor(com.gamevision.companion.R.color.gv_lime))
                    else setAiStatus("AI NOT CONFIGURED", Color.RED)
                    status.text = if (configured) "AI connected • $model" else "Server online, but AI is not configured"
                }
            } catch (e: Exception) {
                runOnUiThread { setAiStatus("OFFLINE", Color.RED); status.text = "AI server unavailable • ${e.message ?: "connection failed"}" }
            } finally { connection?.disconnect() }
        }
    }

    private fun setAiStatus(text: String, color: Int) { visionAiStatus.text = text; visionAiStatus.setTextColor(color) }

    private fun requestCapture() {
        if (MonitorService.isRunning()) { updateMonitorButtons(true); status.text = "Monitoring already active • stop it before starting again"; return }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), audioRequest)
            return
        }
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), captureRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == audioRequest) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestCapture()
            else { status.text = "Microphone denied • text control still works"; requestCapture() }
        }
    }

    private fun stopMonitoring() {
        stopService(Intent(this, MonitorService::class.java))
        stopService(Intent(this, AssistantOverlayService::class.java))
        updateMonitorButtons(false)
        status.text = "Stopped"
    }

    private fun sendHudToggle() { if (MonitorService.isRunning()) startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_TOGGLE_HUD)) }

    @Deprecated("Activity result callback used for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != captureRequest || resultCode != RESULT_OK || data == null) {
            updateMonitorButtons(MonitorService.isRunning())
            status.text = "Screen capture permission was cancelled"
            return
        }
        val baseUrl = serverUrl.text.toString().trim().removeSuffix("/")
        val monitor = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_START
            putExtra(MonitorService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MonitorService.EXTRA_RESULT_DATA, data)
            putExtra(MonitorService.EXTRA_SERVER_URL, baseUrl)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(monitor) else startService(monitor)
        val assistant = Intent(this, AssistantOverlayService::class.java).apply {
            action = AssistantOverlayService.ACTION_START
            putExtra(AssistantOverlayService.EXTRA_SERVER_URL, baseUrl)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(assistant) else startService(assistant)
        mainHandler.postDelayed({ sendHudToggle() }, 1200L)
        updateMonitorButtons(true)
        status.text = "Monitoring started • screen AI + G/V assistant ready"
    }

    override fun onDestroy() { healthExecutor.shutdownNow(); super.onDestroy() }
}
