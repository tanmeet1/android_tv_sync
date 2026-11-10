package com.tvsync

/** Live state shared from the capture service to the settings screen. */
object SyncStatus {
    @Volatile var running = false
    @Volatile var captureFps = 0
    @Volatile var avgLuma = 0
    @Volatile var blackForMs = 0L
    @Volatile var barsText = ""
    @Volatile var lastError: String? = null

    fun overlayText(): String = buildString {
        append("TV Sync: ")
        if (running) append("$captureFps fps, brightness $avgLuma") else append("stopped")
        if (running && blackForMs > BLACK_WARNING_MS) append("\nScreen is black (protected app or HDMI input)")
        if (barsText.isNotEmpty()) append('\n').append(barsText)
        lastError?.let { append("\nError: ").append(it) }
    }

    const val BLACK_WARNING_MS = 3000L

    fun reset() {
        running = false
        captureFps = 0
        avgLuma = 0
        blackForMs = 0
        barsText = ""
    }
}
