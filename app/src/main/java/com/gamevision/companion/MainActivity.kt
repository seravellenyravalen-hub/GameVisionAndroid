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
    private val captureRequest = 4101
    private val audioRequest = 4102
    private val healthExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)
        visionAiStatus = findViewById(R.id.visionAiStatus)
        serverUrl = findViewById(R.id.serverUrl)
        findViewById<Button>(R.id.overlayButton).setOnClickListener { openOverlaySettings() }
        findViewById<Button>(R.id.startButton).setOnClickListener { requestCapture() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, MonitorService::class.java))
            stopService(Intent(this, AssistantOverlayService::class.java))
            status.text = "Stopped"
        }
        findViewById<Button>(R.id.hudButton).setOnClickListener { sendHudToggle() }
        checkAiConfiguration()
    }

    private fun openOverlaySettings() {
        if (Settings.canDrawOverlays(this)) {
            status.text = "Display over other apps: ENABLED"
            return
        }
        status.text = "Opening Android overlay settings…"
        val appIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { data = Uri.parse("package:$packageName") }
        try { startActivity(appIntent) } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
            catch (_: Exception) { status.text = "Open Settings → Special app access → Display over other apps → GameVision" }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) status.text = if (Settings.canDrawOverlays(this))
            "Display over other apps: ENABLED" else "Display over other apps: NOT ENABLED — tap the button below"
        if (::serverUrl.isInitialized) checkAiConfiguration()
    }

    private fun checkAiConfiguration() {
        val baseUrl = serverUrl.text.toString().trim().removeSuffix("/")
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) { setAiStatus("SERVER URL INVALID", Color.RED); return }
        visionAiStatus.text = "CHECKING…"
        visionAiStatus.setTextColor(getColorCompat(com.gamevision.companion.R.color.gv_lime))
        healthExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("$baseUrl/health").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("Accept", "application/json")
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode")
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val healthy = json.optString("status") == "healthy"
                val configured = json.optBoolean("aiConfigured", false)
                val model = json.optString("model", "AI")
                val primary = json.optString("primaryProvider", "ai").uppercase()
                runOnUiThread {
                    when { !healthy -> setAiStatus("SERVER UNHEALTHY", Color.RED); configured -> setAiStatus("READY • $primary", getColorCompat(com.gamevision.companion.R.color.gv_lime)); else -> setAiStatus("AI NOT CONFIGURED", Color.RED) }
                    status.text = if (configured) "AI connected — $model" else "Server online, but AI key is not configured"
                }
            } catch (error: Exception) {
                runOnUiThread { setAiStatus("OFFLINE", Color.RED); status.text = "AI server unavailable — ${error.message ?: "connection failed"}" }
            } finally { connection?.disconnect() }
        }
    }

    private fun setAiStatus(text: String, color: Int) { visionAiStatus.text = text; visionAiStatus.setTextColor(color) }
    @Suppress("DEPRECATION") private fun getColorCompat(id: Int): Int = getColor(id)

    private fun requestCapture() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), audioRequest)
            return
        }
        val mgr = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mgr.createScreenCaptureIntent(), captureRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == audioRequest) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestCapture()
            else { status.text = "Microphone denied — text assistant still works; enable microphone for voice."; requestCapture() }
        }
    }

    private fun sendHudToggle() { startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_TOGGLE_HUD)) }

    @Deprecated("Activity result callback used for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != captureRequest || resultCode != RESULT_OK || data == null) { status.text = "Screen capture permission was cancelled"; return }
        val baseUrl = serverUrl.text.toString().trim().removeSuffix("/")
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_START
            putExtra(MonitorService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MonitorService.EXTRA_RESULT_DATA, data)
            putExtra(MonitorService.EXTRA_SERVER_URL, baseUrl)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        val assistant = Intent(this, AssistantOverlayService::class.java).apply { action = AssistantOverlayService.ACTION_START; putExtra(AssistantOverlayService.EXTRA_SERVER_URL, baseUrl) }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(assistant) else startService(assistant)
        status.text = "Monitoring started — GameVision assistant is ready. Switch to the game."
    }

    override fun onDestroy() { healthExecutor.shutdownNow(); super.onDestroy() }
}
