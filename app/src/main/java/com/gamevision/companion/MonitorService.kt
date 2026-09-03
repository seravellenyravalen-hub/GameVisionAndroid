package com.gamevision.companion

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcelable
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
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

    private var overlay: TextView? = null
    private var windowManager: WindowManager? = null

    private var tts: TextToSpeech? = null

    private var serverUrl =
        "https://gamevision-monitor-0s07wr.v2.appdeploy.ai"

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

        if (running.get()) {
            return
        }

        serverUrl = intent
            .getStringExtra(EXTRA_SERVER_URL)
            ?.takeIf { it.startsWith("http") }
            ?: serverUrl

        val resultCode = intent.getIntExtra(
            EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        )

        val data =
            intent.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA)
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

        val manager =
            getSystemService(MediaProjectionManager::class.java)

        projection = manager.getMediaProjection(
            resultCode,
            data
        )

        projection?.registerCallback(
            projectionCallback,
            mainHandler
        )

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
            { imageReader ->
                onFrame(imageReader)
            },
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
            showOverlay("GameVision\nMonitoring…")
        }
    }

    private fun onFrame(imageReader: ImageReader) {

        if (!running.get()) {
            return
        }

        val now = System.currentTimeMillis()

        if (now - lastUploadAt < 1500L) {
            return
        }

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

            } catch (_: Exception) {

                updateOverlay(
                    "GameVision\nFrame upload failed"
                )

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

        val rowPadding =
            rowStride - pixelStride * image.width

        val bitmapWidth =
            image.width + rowPadding / pixelStride

        val bitmap = Bitmap.createBitmap(
            bitmapWidth,
            image.height,
            Bitmap.Config.ARGB_8888
        )

        buffer.rewind()

        bitmap.copyPixelsFromBuffer(buffer)

        val cropped =
            if (bitmap.width != image.width) {
                Bitmap.createBitmap(
                    bitmap,
                    0,
                    0,
                    image.width,
                    image.height
                )
            } else {
                bitmap
            }

        val scaledWidth =
            minOf(cropped.width, 1600)

        val scaledHeight =
            (
                cropped.height.toFloat() *
                    scaledWidth /
                    cropped.width
                )
                .roundToInt()
                .coerceAtLeast(1)

        val scaled = Bitmap.createScaledBitmap(
            cropped,
            scaledWidth,
            scaledHeight,
            true
        )

        val output =
            ByteArrayOutputStream()

        scaled.compress(
            Bitmap.CompressFormat.JPEG,
            78,
            output
        )

        bitmap.recycle()

        if (cropped !== bitmap) {
            cropped.recycle()
        }

        scaled.recycle()

        return output.toByteArray()
    }

    private fun uploadFrame(jpeg: ByteArray) {

        val url =
            URL("$serverUrl/api/analyze-frame")

        val connection =
            url.openConnection() as HttpURLConnection

        try {

            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 12000
            connection.doOutput = true

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            val base64 =
                android.util.Base64.encodeToString(
                    jpeg,
                    android.util.Base64.NO_WRAP
                )

            val body =
                "{\"image\":{\"data\":\"$base64\",\"mimeType\":\"image/jpeg\"}}"

            connection.outputStream.use {
                it.write(
                    body.toByteArray(
                        Charsets.UTF_8
                    )
                )
            }

            val responseCode =
                connection.responseCode

            if (responseCode !in 200..299) {
                throw IllegalStateException(
                    "Server returned HTTP $responseCode"
                )
            }

            val text =
                connection.inputStream
                    .bufferedReader()
                    .use { it.readText() }

            val json =
                org.json.JSONObject(text)

            val analysis =
                json.optJSONObject("analysis")

            val verified =
                analysis?.optBoolean(
                    "verified",
                    false
                ) ?: false

            val score =
                analysis?.optString(
                    "score",
                    "Visible frame"
                ) ?: "Visible frame"

            val confidence =
                analysis?.optInt(
                    "confidence",
                    0
                ) ?: 0

            val note =
                analysis
                    ?.optJSONArray("notes")
                    ?.optString(0)
                    .orEmpty()

            val label =
                if (verified) {
                    "GameVision\n$score · $confidence%\nVERIFIED"
                } else {
                    "GameVision\n$score · review"
                }

            updateOverlay(label)

            if (note.isNotBlank()) {
                speak(note)
            }

        } finally {
            connection.disconnect()
        }
    }

    private fun showOverlay(text: String) {

        if (!Settings.canDrawOverlays(this)) {
            return
        }

        if (overlay != null) {
            return
        }

        val wm =
            getSystemService(WINDOW_SERVICE)
                    as WindowManager

        val view =
            TextView(this).apply {

                this.text = text

                textSize = 13f

                setTextColor(
                    0xFFFFFFFF.toInt()
                )

                setBackgroundColor(
                    0xCC101828.toInt()
                )

                setPadding(
                    20,
                    12,
                    20,
                    12
                )

                isClickable = false
            }

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {

                gravity =
                    Gravity.TOP or Gravity.END

                x = 16
                y = 100
            }

        try {

            wm.addView(
                view,
                params
            )

            windowManager = wm
            overlay = view

        } catch (_: Exception) {

            overlay = null
            windowManager = null
        }
    }

    private fun updateOverlay(text: String) {

        mainHandler.post {

            overlay?.text = text
        }
    }

    private fun toggleOverlay() {

        if (overlay == null) {

            showOverlay(
                "GameVision\nWaiting for frame…"
            )

        } else {

            removeOverlay()
        }
    }

    private fun removeOverlay() {

        val currentOverlay = overlay

        overlay = null

        if (currentOverlay != null) {

            try {
                windowManager?.removeView(
                    currentOverlay
                )
            } catch (_: Exception) {
            }
        }

        windowManager = null
    }

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

        if (status == TextToSpeech.SUCCESS) {

            tts?.language = Locale.US
        }
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
                currentProjection.unregisterCallback(
                    projectionCallback
                )
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
                currentProjection.unregisterCallback(
                    projectionCallback
                )
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

    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    private fun notification(
        text: String
    ): Notification {

        return Notification.Builder(
            this,
            CHANNEL
        )
            .setContentTitle(
                "GameVision Companion"
            )
            .setContentText(text)
            .setSmallIcon(
                android.R.drawable.ic_menu_view
            )
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

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
    Intent.getParcelableExtraCompat(
        key: String
    ): T? {

    return if (
        Build.VERSION.SDK_INT >= 33
    ) {

        getParcelableExtra(
            key,
            T::class.java
        )

    } else {

        @Suppress("DEPRECATION")
        getParcelableExtra(key)
    }
    }
