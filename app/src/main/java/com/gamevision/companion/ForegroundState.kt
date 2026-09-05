package com.gamevision.companion

/** Tracks whether GameVision's own Activity is currently visible. */
object ForegroundState {
    @Volatile
    var gameVisionActivityForeground: Boolean = false
}
