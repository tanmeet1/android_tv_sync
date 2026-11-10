package com.tvsync

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Small always-on-top status box, so sync can be checked while another app is in front. */
class StatusOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val ui = Handler(Looper.getMainLooper())
    private var view: TextView? = null

    /**
     * Where the overlay sits, as fractions of the screen. Screen capture includes the overlay,
     * so the analyzer skips this area.
     */
    @Volatile
    var bounds: RectF? = null
        private set

    private val tick = object : Runnable {
        override fun run() {
            view?.text = SyncStatus.overlayText()
            ui.postDelayed(this, 500)
        }
    }

    /** Returns false when the "display over other apps" permission is missing. */
    fun show(): Boolean {
        if (view != null) return true
        if (!Settings.canDrawOverlays(context)) return false

        val (screenW, screenH) = screenSize()
        val density = context.resources.displayMetrics.density
        val pad = (8 * density).toInt()
        val text = TextView(context).apply {
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xB0000000.toInt())
            setPadding(pad, pad / 2, pad, pad / 2)
            maxWidth = (screenW * MAX_WIDTH).toInt()
            text = SyncStatus.overlayText()
        }
        text.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            bounds = RectF(
                loc[0].toFloat() / screenW,
                loc[1].toFloat() / screenH,
                (loc[0] + v.width).toFloat() / screenW,
                (loc[1] + v.height).toFloat() / screenH,
            )
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Below the band sampled for the top LEDs, between the areas sampled for the lamps.
            y = (screenH * TOP_OFFSET).toInt()
        }
        windowManager.addView(text, params)
        view = text
        ui.post(tick)
        return true
    }

    fun hide() {
        ui.removeCallbacks(tick)
        view?.let { windowManager.removeView(it) }
        view = null
        bounds = null
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }

    private companion object {
        const val TOP_OFFSET = 0.27f
        const val MAX_WIDTH = 0.3f
    }
}
