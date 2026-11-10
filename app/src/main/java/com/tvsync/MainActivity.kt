package com.tvsync

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/** Settings form plus start/stop controls. Built in code so the form follows [SettingsSchema]. */
class MainActivity : Activity() {
    private val inputs = LinkedHashMap<String, View>()
    private lateinit var statusView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var loading = false

    private val refresh = object : Runnable {
        override fun run() {
            statusView.text = statusText()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildForm())
    }

    override fun onResume() {
        super.onResume()
        if (SyncSettings.importConfigFile(this)) toast("Imported settings from ${SyncSettings.CONFIG_FILE}")
        loadForm()
        ui.post(refresh)
    }

    override fun onPause() {
        saveForm()
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    private fun startSync() {
        saveForm()
        val s = SyncSettings.load(this)
        val wledReady = s.wledEnabled && s.wledHost.isNotBlank()
        val haReady = s.haEnabled && s.haUrl.isNotBlank() && s.haToken.isNotBlank() &&
            (s.haLeftEntity.isNotBlank() || s.haRightEntity.isNotBlank())
        if (!wledReady && !haReady) {
            toast("Set a WLED IP address, or a Home Assistant URL, token and lamp, first.")
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
        } catch (e: ActivityNotFoundException) {
            toast("This TV has no screen-capture permission screen, so capture isn't available.")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            SyncService.start(this, resultCode, data)
        } else {
            toast("Screen capture permission was denied.")
        }
    }

    private fun statusText(): String {
        val lines = mutableListOf<String>()
        if (!SyncStatus.running) {
            lines += "Stopped."
        } else {
            lines += "Running: ${SyncStatus.captureFps} new screen frames/s, average brightness ${SyncStatus.avgLuma}/255."
            if (SyncStatus.barsText.isNotEmpty()) lines += SyncStatus.barsText
            if (SyncStatus.blackForMs > SyncStatus.BLACK_WARNING_MS) {
                lines += "The screen reads as solid black. Protected apps (Netflix, Prime Video...) " +
                    "and HDMI inputs can't be captured."
            }
        }
        SyncStatus.lastError?.let { lines += "Last error: $it" }
        return lines.joinToString("\n")
    }

    private fun buildForm(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // TV overscan-safe margins.
            setPadding(dp(48), dp(27), dp(48), dp(27))
        }
        column.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
        })
        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(8), 0, dp(8))
        }
        column.addView(statusView)

        val buttons = LinearLayout(this)
        buttons.addView(Button(this).apply {
            text = "Start sync"
            setOnClickListener { startSync() }
        })
        buttons.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener { SyncService.stop(this@MainActivity) }
        })
        column.addView(buttons)

        for (section in SettingsSchema.sections) {
            column.addView(TextView(this).apply {
                text = section.title
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(24), 0, dp(4))
            })
            for (field in section.fields) column.addView(fieldView(field))
        }
        return ScrollView(this).apply { addView(column) }
    }

    private fun fieldView(field: Field): View {
        if (field.type == FieldType.BOOL) {
            val box = CheckBox(this).apply { text = field.label }
            if (field.key == SettingsSchema.KEY_SHOW_OVERLAY) {
                box.setOnCheckedChangeListener { _, checked -> if (!loading) onOverlayToggled(checked) }
            }
            inputs[field.key] = box
            return box
        }
        val input: View = if (field.type == FieldType.CORNER) {
            Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@MainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    StartCorner.entries.map { it.label },
                )
            }
        } else {
            EditText(this).apply {
                isSingleLine = true
                inputType = when (field.type) {
                    FieldType.INT -> InputType.TYPE_CLASS_NUMBER
                    FieldType.DECIMAL -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                    FieldType.SECRET -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
            }
        }
        inputs[field.key] = input
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(TextView(this@MainActivity).apply { text = field.label })
            addView(input)
        }
    }

    /** Applied right away (not on save) so the overlay can be toggled while sync is running. */
    private fun onOverlayToggled(checked: Boolean) {
        SyncSettings.prefs(this).edit().putString(SettingsSchema.KEY_SHOW_OVERLAY, checked.toString()).apply()
        if (!checked || Settings.canDrawOverlays(this)) return
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: ActivityNotFoundException) {
            toast("Allow the overlay from a computer: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow")
        }
    }

    private fun loadForm() {
        loading = true
        val prefs = SyncSettings.prefs(this)
        for (field in SettingsSchema.fields) {
            val value = prefs.getString(field.key, null) ?: field.default
            when (val view = inputs[field.key]) {
                is CheckBox -> view.isChecked = value.toBoolean()
                is Spinner -> view.setSelection(StartCorner.parse(value).ordinal)
                is EditText -> view.setText(value)
            }
        }
        loading = false
    }

    private fun saveForm() {
        val editor = SyncSettings.prefs(this).edit()
        for (field in SettingsSchema.fields) {
            val value = when (val view = inputs[field.key]) {
                is CheckBox -> view.isChecked.toString()
                is Spinner -> StartCorner.entries[view.selectedItemPosition].name
                is EditText -> view.text.toString().trim()
                else -> continue
            }
            editor.putString(field.key, value)
        }
        editor.apply()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_CAPTURE = 1
    }
}
