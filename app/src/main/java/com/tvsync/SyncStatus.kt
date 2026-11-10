package com.tvsync

/** Live state shared from the capture service to the settings screen. */
object SyncStatus {
    @Volatile var running = false
    @Volatile var captureFps = 0
    @Volatile var avgLuma = 0
    @Volatile var blackForMs = 0L
    @Volatile var barsText = ""
    @Volatile var lastError: String? = null

    fun reset() {
        running = false
        captureFps = 0
        avgLuma = 0
        blackForMs = 0
        barsText = ""
    }
}
