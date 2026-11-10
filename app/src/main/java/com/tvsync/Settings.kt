package com.tvsync

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.Properties

enum class StartCorner(val label: String) {
    BOTTOM_LEFT("Bottom left"),
    TOP_LEFT("Top left"),
    TOP_RIGHT("Top right"),
    BOTTOM_RIGHT("Bottom right");

    companion object {
        fun parse(value: String): StartCorner = entries.firstOrNull { it.name == value } ?: BOTTOM_LEFT
    }
}

/** LED counts per edge and wiring direction, as seen when facing the TV. */
data class LedLayout(
    val top: Int,
    val right: Int,
    val bottom: Int,
    val left: Int,
    val startCorner: StartCorner,
    val clockwise: Boolean,
) {
    val total: Int get() = top + right + bottom + left
}

enum class FieldType { TEXT, SECRET, INT, DECIMAL, BOOL, CORNER }

data class Field(val key: String, val label: String, val type: FieldType, val default: String)

data class Section(val title: String, val fields: List<Field>)

object SettingsSchema {
    val sections = listOf(
        Section(
            "WLED light strip",
            listOf(
                Field("wled_enabled", "Send colors to WLED", FieldType.BOOL, "true"),
                Field("wled_host", "WLED IP address (e.g. 192.168.1.50)", FieldType.TEXT, ""),
                Field("led_top", "LEDs along the top", FieldType.INT, "30"),
                Field("led_right", "LEDs along the right side", FieldType.INT, "17"),
                Field("led_bottom", "LEDs along the bottom", FieldType.INT, "30"),
                Field("led_left", "LEDs along the left side", FieldType.INT, "17"),
                Field("led_start", "Corner where the strip starts (facing the TV)", FieldType.CORNER, StartCorner.BOTTOM_LEFT.name),
                Field("led_clockwise", "Strip runs clockwise (facing the TV)", FieldType.BOOL, "true"),
            ),
        ),
        Section(
            "Home Assistant lamps",
            listOf(
                Field("ha_enabled", "Send colors to Home Assistant", FieldType.BOOL, "true"),
                Field("ha_url", "Home Assistant URL (e.g. http://192.168.1.10:8123)", FieldType.TEXT, ""),
                Field("ha_token", "Long-lived access token", FieldType.SECRET, ""),
                Field("ha_left", "Left lamp entity (e.g. light.left_lamp)", FieldType.TEXT, ""),
                Field("ha_right", "Right lamp entity (e.g. light.right_lamp)", FieldType.TEXT, ""),
                Field("ha_interval_ms", "Lamp update interval in ms (Matter bulbs: 300-1000)", FieldType.INT, "400"),
                Field("ha_min_brightness", "Lamp minimum brightness (%)", FieldType.INT, "5"),
            ),
        ),
        Section(
            "Picture",
            listOf(
                Field("fps", "Strip updates per second", FieldType.INT, "30"),
                Field("smoothing", "Smoothing (0 = none, 0.9 = very smooth)", FieldType.DECIMAL, "0.5"),
                Field("saturation", "Saturation boost (1 = unchanged)", FieldType.DECIMAL, "1.2"),
                Field("brightness", "Brightness (0 to 1)", FieldType.DECIMAL, "1.0"),
                Field("edge_depth", "How far into the picture to sample (0.05 to 0.5)", FieldType.DECIMAL, "0.15"),
            ),
        ),
    )

    val fields: List<Field> = sections.flatMap { it.fields }

    fun default(key: String): String = fields.first { it.key == key }.default
}

data class SyncSettings(
    val wledEnabled: Boolean,
    val wledHost: String,
    val layout: LedLayout,
    val haEnabled: Boolean,
    val haUrl: String,
    val haToken: String,
    val haLeftEntity: String,
    val haRightEntity: String,
    val haIntervalMs: Long,
    val lampMinBrightnessPct: Int,
    val fps: Int,
    val smoothing: Float,
    val saturation: Float,
    val brightness: Float,
    val edgeDepth: Float,
) {
    companion object {
        private const val PREFS = "tv_sync"

        /** Dropped into the app's external files dir with `adb push`, imported on next launch, then deleted. */
        const val CONFIG_FILE = "config.properties"

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun load(context: Context): SyncSettings {
            val prefs = prefs(context)
            fun str(key: String) = (prefs.getString(key, null) ?: SettingsSchema.default(key)).trim()
            fun int(key: String) = str(key).toIntOrNull() ?: SettingsSchema.default(key).toInt()
            fun float(key: String) = str(key).toFloatOrNull() ?: SettingsSchema.default(key).toFloat()
            fun bool(key: String) = str(key).toBoolean()

            return SyncSettings(
                wledEnabled = bool("wled_enabled"),
                wledHost = str("wled_host"),
                layout = LedLayout(
                    top = int("led_top").coerceIn(0, 500),
                    right = int("led_right").coerceIn(0, 500),
                    bottom = int("led_bottom").coerceIn(0, 500),
                    left = int("led_left").coerceIn(0, 500),
                    startCorner = StartCorner.parse(str("led_start")),
                    clockwise = bool("led_clockwise"),
                ),
                haEnabled = bool("ha_enabled"),
                haUrl = str("ha_url"),
                haToken = str("ha_token"),
                haLeftEntity = str("ha_left"),
                haRightEntity = str("ha_right"),
                haIntervalMs = int("ha_interval_ms").coerceIn(100, 10_000).toLong(),
                lampMinBrightnessPct = int("ha_min_brightness").coerceIn(1, 100),
                fps = int("fps").coerceIn(1, 60),
                smoothing = float("smoothing").coerceIn(0f, 0.95f),
                saturation = float("saturation").coerceIn(0f, 3f),
                brightness = float("brightness").coerceIn(0f, 1f),
                edgeDepth = float("edge_depth").coerceIn(0.05f, 0.5f),
            )
        }

        /** Imports and deletes [CONFIG_FILE] if present. Returns true when something was imported. */
        fun importConfigFile(context: Context): Boolean {
            val dir = context.getExternalFilesDir(null) ?: return false
            val file = File(dir, CONFIG_FILE)
            if (!file.isFile) return false
            val props = Properties().apply { file.reader().use { load(it) } }
            val editor = prefs(context).edit()
            for (field in SettingsSchema.fields) {
                props.getProperty(field.key)?.let { editor.putString(field.key, it.trim()) }
            }
            editor.apply()
            file.delete()
            return true
        }
    }
}
