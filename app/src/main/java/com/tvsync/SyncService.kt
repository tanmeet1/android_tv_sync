package com.tvsync

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import kotlin.math.ceil
import kotlin.math.floor

/** Foreground service that mirrors the screen into a tiny buffer and drives the lights from it. */
class SyncService : Service() {

    @Volatile
    private var session: Session? = null

    private lateinit var overlay: StatusOverlay

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SettingsSchema.KEY_SHOW_OVERLAY) applyOverlaySetting()
    }

    override fun onCreate() {
        super.onCreate()
        overlay = StatusOverlay(this)
        SyncSettings.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_START) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android 14+ requires the foreground service to be running before getMediaProjection().
        startInForeground()
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = intent.captureData()
        if (resultCode != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        session?.stop()
        SyncStatus.lastError = null
        val next = Session(SyncSettings.load(this))
        session = next
        if (!next.start(resultCode, data)) {
            SyncStatus.lastError = "Screen capture could not be started."
            stopSelf()
            return START_NOT_STICKY
        }
        applyOverlaySetting()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        SyncSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        overlay.hide()
        session?.stop()
        session = null
        SyncStatus.reset()
        super.onDestroy()
    }

    private fun applyOverlaySetting() {
        if (session == null || !SyncSettings.load(this).showOverlay) {
            overlay.hide()
        } else if (!overlay.show()) {
            SyncStatus.lastError = "Overlay needs permission: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow"
        }
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen sync", NotificationManager.IMPORTANCE_LOW),
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, SyncService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("TV Sync is running")
            .setContentText("Sending screen colors to your lights")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.captureData(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            getParcelableExtra(EXTRA_DATA)
        }

    /** One capture run. Everything except start/stop runs on the session's own thread. */
    private inner class Session(private val settings: SyncSettings) {
        private val thread = HandlerThread("tv-sync-capture").apply { start() }
        private val handler = Handler(thread.looper)
        private val frameIntervalMs = 1000L / settings.fps
        private val analyzer = FrameAnalyzer(
            layout = settings.layout,
            edgeDepth = settings.edgeDepth,
            smoothing = settings.smoothing,
            saturation = settings.saturation,
            brightness = settings.brightness,
        )
        private val pixels = IntArray(CAPTURE_WIDTH * CAPTURE_HEIGHT)
        private var havePixels = false
        private var wled: WledDdpSender? = null
        private val ha: HomeAssistantClient? = createHomeAssistantClient()
        private val leftLamp = ha?.let { lamp(it, settings.haLeftEntity) }
        private val rightLamp = ha?.let { lamp(it, settings.haRightEntity) }

        private var projection: MediaProjection? = null
        private var display: VirtualDisplay? = null
        private var reader: ImageReader? = null

        private var framesInWindow = 0
        private var windowStart = SystemClock.uptimeMillis()
        private var blackSince = 0L

        private val projectionCallback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (session === this@Session) stopSelf()
            }
        }

        private val render = object : Runnable {
            override fun run() {
                if (havePixels) renderFrame(SystemClock.uptimeMillis())
                handler.postDelayed(this, frameIntervalMs)
            }
        }

        fun start(resultCode: Int, data: Intent): Boolean {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val mp = manager.getMediaProjection(resultCode, data) ?: return false
            projection = mp
            // Android 14+ requires a callback to be registered before createVirtualDisplay().
            mp.registerCallback(projectionCallback, handler)

            val imageReader = ImageReader.newInstance(CAPTURE_WIDTH, CAPTURE_HEIGHT, PixelFormat.RGBA_8888, 2)
            imageReader.setOnImageAvailableListener({ onImage(it) }, handler)
            reader = imageReader
            display = mp.createVirtualDisplay(
                "tv_sync", CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_DPI,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, handler,
            )

            handler.post {
                wled = openWled()
                render.run()
            }
            SyncStatus.running = true
            return true
        }

        fun stop() {
            handler.removeCallbacksAndMessages(null)
            projection?.let {
                it.unregisterCallback(projectionCallback)
                it.stop()
            }
            val d = display
            val r = reader
            handler.post {
                d?.release()
                r?.close()
                wled?.close()
                ha?.close()
                thread.quitSafely()
            }
        }

        private fun onImage(r: ImageReader) {
            val image = try {
                r.acquireLatestImage()
            } catch (e: IllegalStateException) {
                null
            } ?: return
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                var o = 0
                for (y in 0 until CAPTURE_HEIGHT) {
                    var i = y * rowStride
                    for (x in 0 until CAPTURE_WIDTH) {
                        pixels[o++] = ((buffer.get(i).toInt() and 0xff) shl 16) or
                            ((buffer.get(i + 1).toInt() and 0xff) shl 8) or
                            (buffer.get(i + 2).toInt() and 0xff)
                        i += pixelStride
                    }
                }
                havePixels = true
                framesInWindow++
            } finally {
                image.close()
            }
        }

        /**
         * Runs at a fixed rate even when the screen is static: the virtual display only produces
         * frames on change, but WLED drops out of realtime mode if packets stop.
         */
        private fun renderFrame(now: Long) {
            val hidden = overlay.bounds
            if (hidden == null) {
                analyzer.clearIgnoredArea()
            } else {
                analyzer.setIgnoredArea(
                    floor(hidden.left * CAPTURE_WIDTH).toInt(),
                    floor(hidden.top * CAPTURE_HEIGHT).toInt(),
                    ceil(hidden.right * CAPTURE_WIDTH).toInt(),
                    ceil(hidden.bottom * CAPTURE_HEIGHT).toInt(),
                )
            }
            analyzer.analyze(pixels, CAPTURE_WIDTH, CAPTURE_HEIGHT)
            wled?.let {
                try {
                    it.send(analyzer.ledBytes)
                } catch (e: Exception) {
                    SyncStatus.lastError = "WLED: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            leftLamp?.update(analyzer.leftLamp, now)
            rightLamp?.update(analyzer.rightLamp, now)
            publishStatus(now)
        }

        private fun publishStatus(now: Long) {
            val elapsed = now - windowStart
            if (elapsed >= 1000) {
                SyncStatus.captureFps = (framesInWindow * 1000L / elapsed).toInt()
                framesInWindow = 0
                windowStart = now
            }
            SyncStatus.avgLuma = analyzer.avgLuma
            if (analyzer.avgLuma <= 1) {
                if (blackSince == 0L) blackSince = now
                SyncStatus.blackForMs = now - blackSince
            } else {
                blackSince = 0L
                SyncStatus.blackForMs = 0
            }
            val bars = analyzer.letterbox
            SyncStatus.barsText = when {
                bars.rows > 0 -> "Letterbox detected: ignoring ${bars.rows * 100 / CAPTURE_HEIGHT}% top and bottom."
                bars.cols > 0 -> "Pillarbox detected: ignoring ${bars.cols * 100 / CAPTURE_WIDTH}% left and right."
                else -> ""
            }
        }

        private fun openWled(): WledDdpSender? {
            if (!settings.wledEnabled || settings.wledHost.isBlank() || settings.layout.total == 0) return null
            return try {
                WledDdpSender(settings.wledHost)
            } catch (e: Exception) {
                SyncStatus.lastError = "WLED: ${e.message ?: e.javaClass.simpleName}"
                null
            }
        }

        private fun createHomeAssistantClient(): HomeAssistantClient? {
            if (!settings.haEnabled || settings.haUrl.isBlank() || settings.haToken.isBlank()) return null
            return try {
                HomeAssistantClient(settings.haUrl, settings.haToken)
            } catch (e: Exception) {
                SyncStatus.lastError = "Home Assistant URL: ${e.message}"
                null
            }
        }

        private fun lamp(client: HomeAssistantClient, entityId: String): LampSync? =
            if (entityId.isBlank()) null
            else LampSync(client, entityId, settings.haIntervalMs, settings.lampMinBrightnessPct)
    }

    companion object {
        private const val ACTION_START = "com.tvsync.action.START"
        private const val ACTION_STOP = "com.tvsync.action.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "sync"
        private const val NOTIFICATION_ID = 1

        /** 16:9 and tiny on purpose: the GPU does the downscaling, and edge colors need little detail. */
        const val CAPTURE_WIDTH = 128
        const val CAPTURE_HEIGHT = 72
        private const val CAPTURE_DPI = 160

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, SyncService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }
}
