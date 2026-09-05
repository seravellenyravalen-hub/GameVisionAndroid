package com.gamevision.companion

/** Controls local live-frame cadence and bounded server upload cadence. */
class LiveVisionPolicy(
    val captureIntervalMs: Long = 1000L / 12L,
    val uploadIntervalMs: Long = 200L,
    val heartbeatMs: Long = 1000L,
) {
    private var lastCaptureAt = Long.MIN_VALUE
    private var lastUploadAt = Long.MIN_VALUE
    private var lastFingerprint: String? = null

    fun shouldCapture(nowMs: Long): Boolean =
        lastCaptureAt == Long.MIN_VALUE || nowMs - lastCaptureAt >= captureIntervalMs

    fun markCaptured(nowMs: Long) {
        lastCaptureAt = nowMs
    }

    fun shouldUpload(fingerprint: String, nowMs: Long): Boolean {
        val changed = fingerprint != lastFingerprint
        val firstUpload = lastUploadAt == Long.MIN_VALUE
        val cadenceDue = firstUpload || nowMs - lastUploadAt >= uploadIntervalMs
        val heartbeatDue = !firstUpload && nowMs - lastUploadAt >= heartbeatMs
        if (firstUpload || (changed && cadenceDue) || heartbeatDue) {
            lastUploadAt = nowMs
            lastFingerprint = fingerprint
            return true
        }
        return false
    }
}
