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
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var visionAiStatus: TextView
    private lateinit var headerStatus: TextView
    private lateinit var aiBadge: TextView
    private lateinit var monitorState: TextView
    private lateinit var monitorDetail: TextView
    private lateinit var homeMonitorState: TextView
    private lateinit var permissionSummary: TextView
    private lateinit var activityEmpty: TextView
    private lateinit var activityLog: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private val captureRequest = 4101
    private val audioRequest = 4102
    private val healthExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val defaultServerUrl = "https://gamevision-api-v2-production.up.railway.app"
    private val pages by lazy { listOf(R.id.homePage, R.id.assistantPage, R.id.monitorPage, R.id.activityPage, R.id.settingsPage) }
    private val activityEvents = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.statusText)
        visionAiStatus = findViewById(R.id.visionAiStatus)
        headerStatus = findViewById(R.id.headerStatus)
        aiBadge = findViewById(R.id.aiBadge)
        monitorState = findViewById(R.id.monitorState)
        monitorDetail = findViewById(R.id.monitorDetail)
        homeMonitorState = findViewById(R.id.homeMonitorState)
        permissionSummary = findViewById(R.id.permissionSummary)
        activityEmpty = findViewById(R.id.activityEmpty)
        activityLog = findViewById(R.id.activityLog)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        bindNavigation()
        findViewById<Button>(R.id.homeMonitorButton).setOnClickListener { showPage(R.id.monitorPage) }
        findViewById<Button>(R.id.homeAssistantButton).setOnClickListener { openAssistant() }
        findViewById<Button>(R.id.homeAutoButton).setOnClickListener { openAutoControl() }
        findViewById<Button>(R.id.assistantLaunchButton).setOnClickListener { openAssistant() }
        findViewById<Button>(R.id.monitorOverlayButton).setOnClickListener { openOverlaySettings() }
        findViewById<Button>(R.id.monitorAccessibilityButton).setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.overlayButton).setOnClickListener { openOverlaySettings() }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener { openAccessibilitySettings() }
        startButton.setOnClickListener { requestCapture() }
        stopButton.setOnClickListener { stopMonitoring() }
        findViewById<Button>(R.id.hudButton).setOnClickListener { sendHudToggle() }

        checkAiConfiguration()
        updateMonitorUi(MonitorService.isRunning())
        updatePermissionSummary()
        showPage(R.id.homePage)
    }

    private fun bindNavigation() {
        findViewById<LinearLayout>(R.id.navHome).setOnClickListener { showPage(R.id.homePage) }
        findViewById<LinearLayout>(R.id.navAssistant).setOnClickListener { showPage(R.id.assistantPage) }
        findViewById<LinearLayout>(R.id.navMonitor).setOnClickListener { showPage(R.id.monitorPage) }
        findViewById<LinearLayout>(R.id.navActivity).setOnClickListener { showPage(R.id.activityPage) }
        findViewById<LinearLayout>(R.id.navSettings).setOnClickListener { showPage(R.id.settingsPage) }
    }

    private fun showPage(pageId: Int) {
        pages.forEach { id -> findViewById<ScrollView>(id).visibility = if (id == pageId) View.VISIBLE else View.GONE }
        when (pageId) {
            R.id.homePage -> recordNavigation("Opened Home")
            R.id.assistantPage -> recordNavigation("Opened Assistant")
            R.id.monitorPage -> recordNavigation("Opened Monitor")
            R.id.activityPage -> renderActivity()
            R.id.settingsPage -> updatePermissionSummary()
        }
    }

    private fun recordNavigation(event: String) {
        if (activityEvents.isNotEmpty() && activityEvents.last() == event) return
        recordActivity(event)
    }

    private fun recordActivity(event: String) {
        activityEvents.add(0, event)
        while (activityEvents.size > 20) activityEvents.removeLast()
        renderActivity()
    }

    private fun renderActivity() {
        if (!::activityLog.isInitialized) return
        if (activityEvents.isEmpty()) {
            activityEmpty.visibility = View.VISIBLE
            activityLog.visibility = View.GONE
            return
        }
        activityEmpty.visibility = View.GONE
        activityLog.visibility = View.VISIBLE
        activityLog.text = activityEvents.joinToString("\n\n") { "• $it" }
    }

    private fun openAssistant() {
        if (!MonitorService.isRunning()) {
            recordActivity("Assistant requested — monitoring is not active")
            showPage(R.id.monitorPage)
            status.text = "Start monitoring first so the assistant can see the current screen."
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            recordActivity("Assistant requested — overlay access needed")
            openOverlaySettings()
            return
        }
        val assistant = Intent(this, AssistantOverlayService::class.java).apply {
            action = AssistantOverlayService.ACTION_START
            putExtra(AssistantOverlayService.EXTRA_SERVER_URL, defaultServerUrl)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(assistant) else startService(assistant)
        recordActivity("Opened floating G/V Assistant")
    }

    private fun openAutoControl() {
        if (!GameVisionAccessibilityService.isEnabled()) {
            recordActivity("AUTO Control requested — Accessibility access needed")
            openAccessibilitySettings()
            return
        }
        openAssistant()
    }

    private fun openOverlaySettings() {
        if (Settings.canDrawOverlays(this)) {
            status.text = "Display over other apps is enabled."
            updatePermissionSummary()
            return
        }
        status.text = "Opening Android overlay access…"
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply { data = Uri.parse("package:$packageName") }) }
            .onFailure { status.text = "Open Android Settings → Special app access → Display over other apps → GameVision." }
    }

    private fun openAccessibilitySettings() {
        status.text = if (GameVisionAccessibilityService.isEnabled()) "GameVision Auto Control is enabled." else "Opening Android Accessibility settings…"
        if (!GameVisionAccessibilityService.isEnabled()) runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    }

    override fun onResume() {
        super.onResume()
        if (::startButton.isInitialized) {
            updateMonitorUi(MonitorService.isRunning())
            updatePermissionSummary()
            checkAiConfiguration()
        }
    }

    private fun updatePermissionSummary() {
        if (!::permissionSummary.isInitialized) return
        val overlay = if (Settings.canDrawOverlays(this)) "Overlay ready" else "Overlay needs permission"
        val auto = if (GameVisionAccessibilityService.isEnabled()) "Auto Control ready" else "Auto Control needs permission"
        permissionSummary.text = "$overlay  •  $auto"
    }

    private fun updateMonitorUi(active: Boolean) {
        startButton.isEnabled = !active
        stopButton.isEnabled = active
        startButton.alpha = if (active) 0.45f else 1f
        stopButton.alpha = if (active) 1f else 0.45f
        monitorState.text = if (active) "LIVE" else "STOPPED"
        monitorState.setTextColor(if (active) getColor(R.color.gv_lime) else getColor(R.color.gv_text))
        monitorDetail.text = if (active) "Screen capture is running and the G/V assistant can use fresh frames." else "Waiting for screen sharing approval."
        homeMonitorState.text = if (active) "Monitoring live • screen vision active" else "Not monitoring"
        headerStatus.text = if (active) "LIVE" else "READY"
        headerStatus.setTextColor(if (active) getColor(R.color.gv_bg) else getColor(R.color.gv_bg))
    }

    private fun checkAiConfiguration() {
        visionAiStatus.text = "Checking AI readiness…"
        visionAiStatus.setTextColor(getColor(R.color.gv_muted))
        aiBadge.text = "● CHECKING"
        aiBadge.setTextColor(getColor(R.color.gv_muted))
        healthExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("$defaultServerUrl/health").openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val healthy = json.optString("status") == "healthy"
                val configured = json.optBoolean("aiConfigured", false)
                val available = json.optBoolean("aiAvailable", true)
                runOnUiThread {
                    when {
                        !healthy -> setAiUi("SERVER UNAVAILABLE", "Reconnect when the service returns", false)
                        configured && available -> setAiUi("● READY", "Free AI vision is available.", true)
                        configured -> setAiUi("● RECONNECTING", "AI is temporarily busy. GameVision will retry.", false)
                        else -> setAiUi("● SETUP REQUIRED", "AI service is not configured yet.", false)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { setAiUi("● OFFLINE", "AI service could not be reached.", false) }
            } finally { connection?.disconnect() }
        }
    }

    private fun setAiUi(badge: String, detail: String, ready: Boolean) {
        aiBadge.text = badge
        aiBadge.setTextColor(if (ready) getColor(R.color.gv_lime) else getColor(R.color.gv_muted))
        visionAiStatus.text = detail
        visionAiStatus.setTextColor(if (ready) getColor(R.color.gv_green) else getColor(R.color.gv_muted))
        headerStatus.text = if (MonitorService.isRunning()) "LIVE" else if (ready) "READY" else "WAIT"
        status.text = if (ready) "Ready to see, understand and act." else detail
    }

    private fun requestCapture() {
        if (MonitorService.isRunning()) {
            updateMonitorUi(true)
            status.text = "Monitoring is already active. Stop it before starting again."
            return
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), audioRequest)
            return
        }
        recordActivity("Started screen-sharing permission flow")
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), captureRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == audioRequest) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestCapture()
            else { status.text = "Microphone denied. Text control still works."; requestCapture() }
        }
    }

    private fun stopMonitoring() {
        stopService(Intent(this, MonitorService::class.java))
        stopService(Intent(this, AssistantOverlayService::class.java))
        updateMonitorUi(false)
        recordActivity("Stopped monitoring")
        status.text = "Monitoring stopped."
    }

    private fun sendHudToggle() {
        if (MonitorService.isRunning()) {
            startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_TOGGLE_HUD))
            recordActivity("Toggled monitoring HUD")
        } else status.text = "Start monitoring before changing the HUD."
    }

    @Deprecated("Activity result callback used for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != captureRequest || resultCode != RESULT_OK || data == null) {
            updateMonitorUi(MonitorService.isRunning())
            status.text = "Screen capture permission was cancelled."
            return
        }
        val monitor = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_START
            putExtra(MonitorService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MonitorService.EXTRA_RESULT_DATA, data)
            putExtra(MonitorService.EXTRA_SERVER_URL, defaultServerUrl)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(monitor) else startService(monitor)
        val assistant = Intent(this, AssistantOverlayService::class.java).apply {
            action = AssistantOverlayService.ACTION_START
            putExtra(AssistantOverlayService.EXTRA_SERVER_URL, defaultServerUrl)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(assistant) else startService(assistant)
        mainHandler.postDelayed({ sendHudToggle() }, 1200L)
        updateMonitorUi(true)
        recordActivity("Monitoring started")
        status.text = "Monitoring live • G/V assistant ready."
    }

    override fun onDestroy() {
        healthExecutor.shutdownNow()
        super.onDestroy()
    }
}
