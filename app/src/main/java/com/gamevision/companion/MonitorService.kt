package com.gamevision.companion

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class MonitorService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.gamevision.companion.START"
        const val ACTION_TOGGLE_HUD = "com.gamevision.companion.TOGGLE_HUD"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SERVER_URL = "server_url"

        private const val CHANNEL = "gamevision_monitor"
        private const val NOTIFICATION_ID = 77
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null

    private var overlay: LinearLayout? = null
    private var windowManager: WindowManager? = null

    private var tts: TextToSpeech? = null

    private var serverUrl =
        "https://gamevision-api-production.up.railway.app"

    private var lastUploadAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopProjection()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> startProjection(intent)
            ACTION_TOGGLE_HUD -> toggleOverlay()
        }
        return START_NOT_STICKY
    }

    private fun startProjection(intent: Intent) {
        if (running.get()) return

        serverUrl = intent
            .getStringExtra(EXTRA_SERVER_URL)
            ?.takeIf { it.startsWith("http") }
            ?: serverUrl

        val resultCode = intent.getIntExtra(
            EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        )

        val data = intent.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
            ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification("Monitoring visible game screen"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification("Monitoring visible game screen")
            )
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, data)
        projection?.registerCallback(projectionCallback, mainHandler)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        reader?.setOnImageAvailableListener(
            { imageReader -> onFrame(imageReader) },
            mainHandler
        )

        virtualDisplay = projection?.createVirtualDisplay(
            "GameVisionMonitor",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            null
        )

        running.set(true)

        if (Settings.canDrawOverlays(this)) {
            showOverlay("waiting")
        }
    }

    private fun onFrame(imageReader: ImageReader) {
        if (!running.get()) return

        val now = System.currentTimeMillis()
        if (now - lastUploadAt < 1500L) return

        val image = try {
            imageReader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return

        lastUploadAt = now

        executor.execute {
            try {
                val jpeg = imageToJpeg(image)
                uploadFrame(jpeg)
            } catch (e: Exception) {
                val message = when (e) {
                    is UnknownHostException -> "DNS/server not found"
                    is SocketTimeoutException -> "Connection timed out"
                    is IOException -> e.message ?: "Network error"
                    else -> e.message ?: e.javaClass.simpleName
                }
                updateOverlay("error", message.take(120))
            } finally {
                try {
                    image.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun imageToJpeg(image: Image): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmapWidth = image.width + rowPadding / pixelStride

        val bitmap = Bitmap.createBitmap(
            bitmapWidth,
            image.height,
            Bitmap.Config.ARGB_8888
        )

        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        val cropped = if (bitmap.width != image.width) {
            Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        } else {
            bitmap
        }

        val scaledWidth = minOf(cropped.width, 1600)
        val scaledHeight = (
            cropped.height.toFloat() * scaledWidth / cropped.width
        ).roundToInt().coerceAtLeast(1)

        val scaled = Bitmap.createScaledBitmap(
            cropped,
            scaledWidth,
            scaledHeight,
            true
        )

        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 78, output)

        bitmap.recycle()
        if (cropped !== bitmap) cropped.recycle()
        scaled.recycle()

        return output.toByteArray()
    }

    private fun uploadFrame(jpeg: ByteArray) {
        val url = URL("$serverUrl/api/analyze-frame")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "GameVision-Companion/1.0")
            connection.setRequestProperty("Origin", serverUrl)

            val base64 = android.util.Base64.encodeToString(
                jpeg,
                android.util.Base64.NO_WRAP
            )

            val body =
                "{\"image\":{\"data\":\"$base64\",\"mimeType\":\"image/jpeg\"}}"

            connection.outputStream.use {
                it.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = try {
                    connection.errorStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        .orEmpty()
                } catch (_: Exception) {
                    ""
                }

                val detail = errorBody
                    .replace("\n", " ")
                    .replace("\r", " ")
                    .trim()
                    .take(100)

                val message = if (detail.isNotBlank()) {
                    "HTTP $responseCode $detail"
                } else {
                    "HTTP $responseCode"
                }
                throw IllegalStateException(message)
            }

            val text = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            val json = org.json.JSONObject(text)
            val analysis = json.optJSONObject("analysis")

            val verified = analysis?.optBoolean("verified", false) ?: false
            val score = analysis?.optString("score", "—") ?: "—"
            val confidence = analysis?.optInt("confidence", 0) ?: 0
            val prediction = analysis?.optString("prediction", score) ?: score
            val risk = analysis?.optString(
                "risk",
                if (verified) "LOW RISK" else "REVIEW"
            ) ?: if (verified) "LOW RISK" else "REVIEW"
            val note = analysis
                ?.optJSONArray("notes")
                ?.optString(0)
                .orEmpty()

            updateOverlay(
                state = "live",
                score = score,
                confidence = confidence,
                verified = verified,
                prediction = prediction,
                risk = risk,
                note = note
            )

            if (note.isNotBlank()) speak(note)
        } finally {
            connection.disconnect()
        }
    }

    private fun showOverlay(state: String) {
        if (!Settings.canDrawOverlays(this) || overlay != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = panelBackground()
            elevation = dp(10).toFloat()
        }

        buildHeader(panel)
        addDivider(panel)
        addStatusRow(panel)
        addScore(panel, "—")
        addMetaRow(panel, 0, false, "LOW RISK")
        addPrediction(panel, "Waiting for AI analysis…")

        val params = WindowManager.LayoutParams(
            dp(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(86)
        }

        try {
            wm.addView(panel, params)
            windowManager = wm
            overlay = panel
        } catch (_: Exception) {
            overlay = null
            windowManager = null
        }
    }

    private fun buildHeader(panel: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val mark = TextView(this).apply {
            text = "G/V"
            textSize = 12f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = roundedBackground(0xFFB8FF3D.toInt(), 8)
            setPadding(dp(7), dp(6), dp(7), dp(6))
        }

        val brand = TextView(this).apply {
            text = "GAMEVISION"
            textSize = 12f
            setTextColor(0xFFF4F7FA.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setPadding(dp(10), 0, 0, 0)
        }

        val live = TextView(this).apply {
            text = "● LIVE AI"
            textSize = 10f
            setTextColor(0xFFB8FF3D.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = roundedBackground(0x3326B500, 20)
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }

        row.addView(mark, LinearLayout.LayoutParams(dp(38), dp(34)))
        row.addView(brand, LinearLayout.LayoutParams(0, dp(34), 1f))
        row.addView(live, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)))
        panel.addView(row)
    }

    private fun addDivider(panel: LinearLayout) {
        val divider = TextView(this).apply {
            setBackgroundColor(0x3327313D)
        }
        panel.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply {
            setMargins(0, dp(10), 0, dp(10))
        })
    }

    private fun addStatusRow(panel: LinearLayout) {
        val label = TextView(this).apply {
            text = "ANALYSIS STATUS"
            textSize = 9f
            setTextColor(0xFF8D9AAA.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        panel.addView(label)
    }

    private fun addScore(panel: LinearLayout, score: String) {
        val scoreView = TextView(this).apply {
            text = score
            textSize = 30f
            setTextColor(0xFFF4F7FA.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(2))
        }
        panel.addView(scoreView, LinearLayout.LayoutParams(-1, dp(46)))
    }

    private fun addMetaRow(
        panel: LinearLayout,
        confidence: Int,
        verified: Boolean,
        risk: String
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val confidenceText = TextView(this).apply {
            text = "CONFIDENCE  ${confidence.coerceIn(0, 100)}%"
            textSize = 9f
            setTextColor(0xFFB8FF3D.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = roundedBackground(0x3326B500, 16)
            setPadding(dp(8), dp(5), dp(8), dp(5))
        }

        val verificationText = TextView(this).apply {
            text = if (verified) "✓ VERIFIED" else "• UNVERIFIED"
            textSize = 9f
            setTextColor(if (verified) 0xFF63E6A4.toInt() else 0xFFFF5D67.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(5), dp(8), dp(5))
        }

        val riskText = TextView(this).apply {
            text = risk.uppercase(Locale.US).take(14)
            textSize = 9f
            setTextColor(if (verified) 0xFFB8FF3D.toInt() else 0xFFFF5D67.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        row.addView(confidenceText)
        row.addView(verificationText)
        row.addView(riskText, LinearLayout.LayoutParams(0, dp(28), 1f))
        panel.addView(row)
    }

    private fun addPrediction(panel: LinearLayout, prediction: String) {
        val label = TextView(this).apply {
            text = "PREDICTION"
            textSize = 9f
            setTextColor(0xFF8D9AAA.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setPadding(0, dp(10), 0, dp(3))
        }
        panel.addView(label)

        val value = TextView(this).apply {
            text = prediction
            textSize = 12f
            setTextColor(0xFFF4F7FA.toInt())
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        panel.addView(value)
    }

    private fun updateOverlay(
        state: String,
        score: String = "—",
        confidence: Int = 0,
        verified: Boolean = false,
        prediction: String = "Waiting for AI analysis…",
        risk: String = if (verified) "LOW RISK" else "REVIEW",
        note: String = ""
    ) {
        mainHandler.post {
            val panel = overlay ?: return@post
            panel.removeAllViews()

            buildHeader(panel)
            addDivider(panel)

            if (state == "error") {
                val title = TextView(this).apply {
                    text = "ANALYSIS OFFLINE"
                    textSize = 13f
                    setTextColor(0xFFFF5D67.toInt())
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                panel.addView(title)
                addPrediction(panel, prediction)
                return@post
            }

            addStatusRow(panel)
            addScore(panel, score)
            addMetaRow(panel, confidence, verified, risk)
            addPrediction(panel, prediction)

            if (note.isNotBlank()) {
                val details = TextView(this).apply {
                    text = note
                    textSize = 10f
                    setTextColor(0xFF8D9AAA.toInt())
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(7), 0, 0)
                }
                panel.addView(details)
            }
        }
    }

    private fun toggleOverlay() {
        if (overlay == null) {
            showOverlay("waiting")
        } else {
            removeOverlay()
        }
    }

    private fun removeOverlay() {
        val currentOverlay = overlay
        overlay = null

        if (currentOverlay != null) {
            try {
                windowManager?.removeView(currentOverlay)
            } catch (_: Exception) {
            }
        }
        windowManager = null
    }

    private fun panelBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(0xF010151D.toInt())
        setStroke(dp(1), 0x6627313D)
        cornerRadius = dp(18).toFloat()
    }

    private fun roundedBackground(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun speak(text: String) {
        mainHandler.post {
            tts?.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "gamevision"
            )
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US
    }

    private fun stopProjection() {
        if (!running.getAndSet(false)) {
            removeOverlay()
            return
        }

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null

        val currentProjection = projection
        projection = null

        if (currentProjection != null) {
            try {
                currentProjection.unregisterCallback(projectionCallback)
            } catch (_: Exception) {
            }
            try {
                currentProjection.stop()
            } catch (_: Exception) {
            }
        }

        removeOverlay()

        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        running.set(false)

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null

        val currentProjection = projection
        projection = null

        if (currentProjection != null) {
            try {
                currentProjection.unregisterCallback(projectionCallback)
            } catch (_: Exception) {
            }
            try {
                currentProjection.stop()
            } catch (_: Exception) {
            }
        }

        removeOverlay()

        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null

        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL)
        .setContentTitle("GameVision Companion")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setOngoing(true)
        .build()

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "GameVision monitoring",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }
}

private inline fun <reified T : Parcelable>
    Intent.getParcelableExtraCompat(key: String): T? {
    return if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
}
