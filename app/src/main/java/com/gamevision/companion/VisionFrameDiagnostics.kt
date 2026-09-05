package com.gamevision.companion

object VisionFrameDiagnostics {
    fun classify(sequence: Long, ageMs: Long, fresh: Boolean, frozen: Boolean = false): String = when {
        sequence <= 0L || ageMs < 0L -> "NO CAPTURE"
        frozen -> "FRAME FROZEN"
        !fresh -> "FRAME STALE"
        else -> "VISION READY"
    }

    fun commandProof(dispatched: Boolean, verified: Boolean): String = when {
        !dispatched -> "FAILED"
        verified -> "VERIFIED"
        else -> "DISPATCHED"
    }
}
