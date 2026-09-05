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
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class MonitorService : Service() {
    companion object {
        const val ACTION_START = "com.gamevision.companion.START"
        const val ACTION_TOGGLE_HUD = "com.gamevision.companion.TOGGLE_HUD"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SERVER_URL = "server_url"
        private const val CHANNEL = "gamevision_monitor"
        private const val NOTIFICATION_ID = 77
        @Volatile private var active = false
        @Volatile private var latestJpeg: ByteArray? = null
        @Volatile private var latestSequence = 0L
        @Volatile private var latestCapturedAt = 0L
        @Volatile private var latestSourceChanged = false
        fun isRunning(): Boolean = active
        fun latestFrameJpeg(): ByteArray? = latestJpeg?.copyOf()
        fun latestFrameSequence(): Long = latestSequence
        fun latestFrameAgeMs(now: Long = System.currentTimeMillis()): Long = if (latestCapturedAt <= 0L) -1L else (now - latestCapturedAt).coerceAtLeast(0L)
        fun latestFrameChanged(): Boolean = latestSourceChanged
    }

    private val frameExecutor = Executors.newSingleThreadExecutor()
    private val uploadExecutor = Executors.newSingleThreadExecutor()
    private val pendingUpload = AtomicReference<List<EncodedImage>?>(null)
    private val uploadBusy = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var serverUrl = "https://gamevision-api.onrender.com"
    private var overlay: LinearLayout? = null
    private var wm: WindowManager? = null
    private val livePolicy = LiveVisionPolicy()

    private val projectionCallback = object : MediaProjection.Callback() { override fun onStop() { stopProjection() } }

    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startProjection(intent)
            ACTION_TOGGLE_HUD -> toggleHud()
        }
        return START_NOT_STICKY
    }

    private fun startProjection(intent: Intent) {
        if (running.get()) return
        serverUrl = intent.getStringExtra(EXTRA_SERVER_URL)?.trim()?.removeSuffix("/")?.takeIf { it.isNotBlank() } ?: serverUrl
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intent.getParcelableExtraCompat<Intent>(EXTRA_RESULT_DATA) ?: return
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification("Live screen vision is active"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification("Live screen vision is active"))

        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, data)
        projection?.registerCallback(projectionCallback, mainHandler)
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader?.setOnImageAvailableListener({ onFrame(it) }, mainHandler)
        virtualDisplay = projection?.createVirtualDisplay("GameVisionLiveVision", width, height, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, null)
        running.set(true)
        active = true
        if (Settings.canDrawOverlays(this)) showHud()
        updateHud("LIVE", "Continuous screen vision • 12 FPS local buffer")
    }

    private fun onFrame(imageReader: ImageReader) {
        if (!running.get()) return
        val image = runCatching { imageReader.acquireLatestImage() }.getOrNull() ?: return
        if (ForegroundState.gameVisionActivityForeground) {
            runCatching { image.close() }
            return
        }
        val now = System.currentTimeMillis()
        if (!livePolicy.shouldCapture(now)) {
            runCatching { image.close() }
            return
        }
        livePolicy.markCaptured(now)
        val snapshot = try { imageToBitmap(image) } catch (_: Exception) { null } finally { runCatching { image.close() } }
        if (snapshot == null) return
        frameExecutor.execute {
            try {
                val frameSet = bitmapToFrameSet(snapshot)
                val full = frameSet.firstOrNull() ?: return@execute
                val jpeg = full.data.copyOf()
                val fingerprint = fingerprint(jpeg)
                val previous = latestJpeg
                latestSourceChanged = previous == null || fingerprint(previous) != fingerprint
                latestJpeg = jpeg
                latestCapturedAt = System.currentTimeMillis()
                latestSequence += 1L
                if (livePolicy.shouldUpload(fingerprint, latestCapturedAt)) enqueueUpload(frameSet)
            } catch (e: Exception) {
                updateHud("OFFLINE", "${e.javaClass.simpleName}: ${e.message ?: "vision pipeline failed"}")
            } finally { snapshot.recycle() }
        }
    }

    private data class EncodedImage(val data: ByteArray, val role: String, val width: Int, val height: Int, val x: Int = 0, val y: Int = 0)

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val bitmapWidth = image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride
        val bitmap = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        bitmap.copyPixelsFromBuffer(plane.buffer)
        return if (bitmap.width != image.width) {
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            bitmap.recycle(); cropped
        } else bitmap
    }

    private fun bitmapToFrameSet(source: Bitmap): List<EncodedImage> {
        val maxWidth = minOf(source.width, 1400)
        val scaledHeight = (source.height.toFloat() * maxWidth / source.width).roundToInt().coerceAtLeast(1)
        val full = if (source.width != maxWidth) Bitmap.createScaledBitmap(source, maxWidth, scaledHeight, true) else source
        val output = mutableListOf<EncodedImage>()
        output += EncodedImage(jpeg(full, 76), "full", full.width, full.height)
        if (full.height > full.width * 12 / 10) {
            val regionHeight = (full.height * 0.46f).roundToInt().coerceAtLeast(1)
            val starts = listOf(0, ((full.height - regionHeight) / 2), (full.height - regionHeight).coerceAtLeast(0)).distinct()
            listOf("top", "middle", "bottom").zip(starts).forEach { (role, start) ->
                val crop = Bitmap.createBitmap(full, 0, start, full.width, regionHeight)
                output += EncodedImage(jpeg(crop, 82), role, crop.width, crop.height, 0, start)
                crop.recycle()
            }
        } else {
            val half = (full.height / 2).coerceAtLeast(1)
            listOf("top" to 0, "bottom" to (full.height - half).coerceAtLeast(0)).forEach { (role, start) ->
                val crop = Bitmap.createBitmap(full, 0, start, full.width, half)
                output += EncodedImage(jpeg(crop, 82), role, crop.width, crop.height, 0, start)
                crop.recycle()
            }
        }
        if (full !== source) full.recycle()
        return output
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out); out.toByteArray() }
    private fun fingerprint(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).take(8).joinToString("") { "%02x".format(it) }

    private fun enqueueUpload(images: List<EncodedImage>) {
        pendingUpload.set(images)
        if (!uploadBusy.compareAndSet(false, true)) return
        uploadExecutor.execute {
            try {
                while (running.get()) {
                    val next = pendingUpload.getAndSet(null) ?: break
                    try { uploadFrame(next) }
                    catch (e: Exception) { updateHud("OFFLINE", "${e.javaClass.simpleName}: ${e.message ?: "upload failed"}") }
                }
            } finally {
                uploadBusy.set(false)
                if (pendingUpload.get() != null && running.get()) enqueuePendingUpload()
            }
        }
    }

    private fun enqueuePendingUpload() {
        if (!uploadBusy.compareAndSet(false, true)) return
        uploadExecutor.execute {
            try {
                while (running.get()) {
                    val next = pendingUpload.getAndSet(null) ?: break
                    try { uploadFrame(next) }
                    catch (e: Exception) { updateHud("OFFLINE", "${e.javaClass.simpleName}: ${e.message ?: "upload failed"}") }
                }
            } finally { uploadBusy.set(false) }
        }
    }

    private fun uploadFrame(images: List<EncodedImage>) {
        val connection = URL("$serverUrl/api/frame").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 2500; connection.readTimeout = 2500; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("User-Agent", "GameVision-Companion/4.0-LiveVision")
            val token = AuthStore.token(this).orEmpty()
            if (token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $token")
            val jsonImages = images.joinToString(",") { item ->
                val data = android.util.Base64.encodeToString(item.data, android.util.Base64.NO_WRAP)
                "{\"data\":\"$data\",\"mimeType\":\"image/jpeg\",\"role\":\"${item.role}\",\"width\":${item.width},\"height\":${item.height},\"x\":${item.x},\"y\":${item.y}}"
            }
            connection.outputStream.use { it.write("{\"images\":[${jsonImages}]}".toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${body.take(120)}")
            val frame = org.json.JSONObject(body).optJSONObject("frame")
            val sequence = frame?.optLong("sequence", 0L) ?: 0L
            updateHud("LIVE", if (sequence > 0) "Vision stream • server frame #$sequence" else "Vision stream • frame uploaded")
        } finally { connection.disconnect() }
    }

    private fun showHud() {
        if (overlay != null || !Settings.canDrawOverlays(this)) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(0xF010141B.toInt(), 20); elevation = dp(8).toFloat() }
        val header = TextView(this).apply { text = "G/V  GAMEVISION     ● LIVE VISION"; textSize = 11f; setTextColor(0xFFB8FF3D.toInt()); typeface = android.graphics.Typeface.DEFAULT_BOLD }
        val status = TextView(this).apply { text = "WAITING FOR LIVE FRAME"; textSize = 12f; setTextColor(Color.WHITE); setPadding(0, dp(8), 0, 0) }
        root.addView(header); root.addView(status)
        val params = WindowManager.LayoutParams(dp(300), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_SECURE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = dp(12); y = dp(86) }
        runCatching { wm?.addView(root, params); overlay = root }
    }

    private fun updateHud(title: String, detail: String) { mainHandler.post { overlay?.let { panel -> (panel.getChildAt(1) as? TextView)?.apply { text = "$title\n$detail"; setTextColor(if (title == "OFFLINE") 0xFFFF5D67.toInt() else 0xFFF4F7FA.toInt()) } } } }
    private fun toggleHud() { mainHandler.post { if (overlay == null) showHud() else { runCatching { wm?.removeView(overlay) }; overlay = null } } }
    private fun stopProjection() { running.set(false); active = false; pendingUpload.set(null); uploadBusy.set(false); latestJpeg = null; latestSequence = 0L; latestCapturedAt = 0L; latestSourceChanged = false; runCatching { virtualDisplay?.release() }; virtualDisplay = null; runCatching { reader?.close() }; reader = null; runCatching { projection?.unregisterCallback(projectionCallback) }; projection = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { stopProjection(); frameExecutor.shutdownNow(); uploadExecutor.shutdownNow(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)?.createNotificationChannel(NotificationChannel(CHANNEL, "GameVision Monitor", NotificationManager.IMPORTANCE_LOW)) }
    private fun notification(text: String) = Notification.Builder(this, CHANNEL).setContentTitle("GameVision").setContentText(text).setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

@Suppress("DEPRECATION")
private inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(name: String): T? = if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, T::class.java) else getParcelableExtra(name)