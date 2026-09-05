package com.gamevision.companion

/** Controls the local live-frame cadence and the adaptive server upload cadence. */
class LiveVisionPolicy(
    val captureIntervalMs: Long = 1000L / 12L,
    val uploadIntervalMs: Long = 200L,
    val heartbeatMs: Long = 1000L,
) {
    private var lastUploadAt = Long.MIN_VALUE
    private var lastFingerprint: String? = null

    fun shouldCapture(nowMs: Long): Boolean =
        lastCaptureAt == Long.MIN_VALUE || nowMs - lastCaptureAt >= captureIntervalMs

    fun markCaptured(nowMs: Long) {
        lastCaptureAt = nowMs
    }

    fun shouldUpload(fingerprint: String, nowMs: Long): Boolean {
        val changed = fingerprint != lastFingerprint
        val due = lastUploadAt == Long.MIN_VALUE || nowMs - lastUploadAt >= uploadIntervalMs
        val heartbeat = lastUploadAt != Long.MIN_VALUE && nowMs - lastUploadAt >= heartbeatMs
        if (changed || due && heartbeat || lastUploadAt == Long.MIN_VALUE) {
            lastUploadAt = nowMs
            lastFingerprint = fingerprint
            return true
        }
        return false
    }

    private var lastCaptureAt = Long.MIN_VALUE
}
