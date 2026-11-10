package com.tvsync

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Calls `light.turn_on` through the Home Assistant REST API. */
class HomeAssistantClient(baseUrl: String, private val token: String) : Closeable {
    private val serviceUrl = URL(baseUrl.trimEnd('/') + "/api/services/light/turn_on")
    private val executor = Executors.newFixedThreadPool(2)

    fun turnOn(entityId: String, rgb: IntArray, brightness: Int, transitionSeconds: Float, done: () -> Unit) {
        val body = JSONObject()
            .put("entity_id", entityId)
            .put("rgb_color", JSONArray().put(rgb[0]).put(rgb[1]).put(rgb[2]))
            .put("brightness", brightness)
            .put("transition", transitionSeconds.toDouble())
            .toString()
        executor.execute {
            try {
                post(body)
            } catch (e: Exception) {
                SyncStatus.lastError = "Home Assistant ($entityId): ${e.message ?: e.javaClass.simpleName}"
            } finally {
                done()
            }
        }
    }

    private fun post(body: String) {
        val conn = serviceUrl.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 3000
        conn.readTimeout = 5000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        val code = conn.responseCode
        // Drain the body so the connection can be reused (keep-alive).
        (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
        if (code >= 400) throw IOException("HTTP $code")
    }

    override fun close() {
        executor.shutdownNow()
    }
}

/**
 * Rate-limits updates for one lamp. Matter bulbs acknowledge each command, so only one request
 * is in flight at a time; colors that arrive meanwhile are dropped rather than queued.
 */
class LampSync(
    private val client: HomeAssistantClient,
    private val entityId: String,
    private val intervalMs: Long,
    private val minBrightnessPct: Int,
) {
    private val inFlight = AtomicBoolean(false)
    private var lastSentAt = 0L
    private var lastColor = -1

    fun update(color: Int, now: Long) {
        if (now - lastSentAt < intervalMs) return
        if (lastColor != -1 && LampColor.distance(color, lastColor) < MIN_CHANGE) return
        if (!inFlight.compareAndSet(false, true)) return
        lastSentAt = now
        lastColor = color
        val (rgb, brightness) = LampColor.toHomeAssistant(color, minBrightnessPct)
        client.turnOn(entityId, rgb, brightness, intervalMs / 1000f) { inFlight.set(false) }
    }

    private companion object {
        const val MIN_CHANGE = 8
    }
}

object LampColor {
    /** Splits a color into a full-intensity `rgb_color` and a separate 0-255 `brightness`. */
    fun toHomeAssistant(color: Int, minBrightnessPct: Int): Pair<IntArray, Int> {
        val r = (color shr 16) and 0xff
        val g = (color shr 8) and 0xff
        val b = color and 0xff
        val peak = max(r, max(g, b))
        val minBrightness = ((minBrightnessPct.coerceIn(1, 100) * 255) / 100f).roundToInt().coerceAtLeast(1)
        if (peak == 0) return intArrayOf(255, 255, 255) to minBrightness
        val scale = 255f / peak
        val rgb = intArrayOf((r * scale).roundToInt(), (g * scale).roundToInt(), (b * scale).roundToInt())
        return rgb to max(peak, minBrightness)
    }

    fun distance(a: Int, b: Int): Int = max(
        abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff)),
        max(abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff)), abs((a and 0xff) - (b and 0xff))),
    )
}
