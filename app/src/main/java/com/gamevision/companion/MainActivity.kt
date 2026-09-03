package com.gamevision.companion

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var serverUrl: EditText
    private val captureRequest = 4101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)
        serverUrl = findViewById(R.id.serverUrl)
        findViewById<Button>(R.id.overlayButton).setOnClickListener { openOverlaySettings() }
        findViewById<Button>(R.id.startButton).setOnClickListener { requestCapture() }
        findViewById<Button>(R.id.stopButton).setOnClickListener { stopService(Intent(this, MonitorService::class.java)); status.text = "Stopped" }
        findViewById<Button>(R.id.hudButton).setOnClickListener { sendHudToggle() }
    }

    private fun openOverlaySettings() {
        if (Settings.canDrawOverlays(this)) {
            status.text = "Display over other apps: ENABLED"
            return
        }

        status.text = "Opening Android overlay settings…"
        val appIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(appIntent)
        } catch (_: Exception) {
            // Some Android/OEM builds ignore the package-specific destination.
            // Fall back to the Special App Access screen instead of failing silently.
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (_: Exception) {
                status.text = "Open Settings → Special app access → Display over other apps → GameVision Companion"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) {
            status.text = if (Settings.canDrawOverlays(this)) {
                "Display over other apps: ENABLED"
            } else {
                "Display over other apps: NOT ENABLED — tap the button below"
            }
        }
    }

    private fun requestCapture() {
        val mgr = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mgr.createScreenCaptureIntent(), captureRequest)
    }

    private fun sendHudToggle() {
        startService(Intent(this, MonitorService::class.java).setAction(MonitorService.ACTION_TOGGLE_HUD))
    }

    @Deprecated("Activity result callback used for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != captureRequest || resultCode != RESULT_OK || data == null) {
            status.text = "Screen capture permission was cancelled"
            return
        }
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_START
            putExtra(MonitorService.EXTRA_RESULT_CODE, resultCode)
            putExtra(MonitorService.EXTRA_RESULT_DATA, data)
            putExtra(MonitorService.EXTRA_SERVER_URL, serverUrl.text.toString().trim().removeSuffix("/"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        status.text = "Monitoring started — switch to the game"
    }
}
