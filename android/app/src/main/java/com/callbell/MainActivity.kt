package com.callbell

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.UUID

/** One-time setup screen. The actual work happens in [CallReceiver]. */
class MainActivity : Activity() {

    private val perms = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
    )

    private lateinit var prefs: Prefs
    private lateinit var permStatus: TextView
    private lateinit var permButton: Button
    private lateinit var testStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var batteryButton: Button
    private lateinit var lastResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
        styleSystemBars()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(40))
        }

        col.addView(text("CallBell", 30f, bold = true))
        col.addView(text("Tells your Mac when this phone rings, and who is calling. Keep both on the same Wi-Fi.", 15f, muted = true))

        col.addView(heading("1. Permissions"))
        col.addView(text("Phone (to notice calls), Call logs (Android only shares the caller's number with this), Contacts (to show names).", 14f, muted = true))
        permStatus = text("", 15f).also(col::addView)
        permButton = button("Grant permissions") { askPermissions() }.also(col::addView)

        col.addView(heading("2. Pairing code"))
        col.addView(text("Click the phone icon in your Mac's menu bar and type the code shown there.", 14f, muted = true))
        col.addView(EditText(this).apply {
            setText(prefs.code)
            hint = "ABCD-EFGH"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            typeface = Typeface.MONOSPACE
            textSize = 24f
            isSingleLine = true
            onChange { prefs.code = it.trim() }
        })

        col.addView(heading("3. Test"))
        col.addView(button("Send a test to the Mac") { sendTest() })
        testStatus = text("", 15f).also(col::addView)

        col.addView(heading("4. Keep it working in the background"))
        batteryStatus = text("", 14f, muted = true).also(col::addView)
        batteryButton = button("Turn off battery optimisation") { askBatteryExemption() }.also(col::addView)

        col.addView(heading("Settings"))
        @Suppress("DEPRECATION")
        col.addView(Switch(this).apply {
            text = "Forward calls to the Mac"
            textSize = 16f
            isChecked = prefs.enabled
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, on -> prefs.enabled = on }
        })
        col.addView(text("Mac IP address (optional, only if the test fails, e.g. 192.168.1.20)", 14f, muted = true))
        col.addView(EditText(this).apply {
            setText(prefs.manualIp)
            hint = "auto-detect"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            onChange { prefs.manualIp = it.trim() }
        })
        lastResult = text("", 13f, muted = true).also {
            it.setPadding(0, dp(16), 0, 0)
            col.addView(it)
        }

        return ScrollView(this).apply {
            fitsSystemWindows = true
            setBackgroundColor(Color.WHITE)
            addView(col)
        }
    }

    private fun refresh() {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            permStatus.text = "✓ All permissions granted"
            permButton.visibility = View.GONE
        } else {
            permStatus.text = "Missing: " + missing.joinToString { it.substringAfterLast('.') }
            permButton.visibility = View.VISIBLE
        }

        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            batteryStatus.text = "✓ CallBell can run while the phone sleeps."
            batteryButton.visibility = View.GONE
        } else {
            batteryStatus.text = "Android may delay CallBell while the phone sleeps. Allowing unrestricted battery use makes calls arrive reliably (it uses no battery when no call comes in)."
            batteryButton.visibility = View.VISIBLE
        }
        lastResult.text = prefs.lastResult
    }

    private fun askPermissions() {
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val blocked = prefs.askedPermissions && missing.none { shouldShowRequestPermissionRationale(it) }
        if (blocked) {
            // Android won't show the dialog again; send the user to the app's settings page.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } else {
            prefs.askedPermissions = true
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    private fun askBatteryExemption() {
        try {
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun sendTest() {
        testStatus.text = "Sending…"
        val appCtx = applicationContext
        Thread {
            val r = Sender.send(appCtx, Events.make(appCtx, Events.TEST, UUID.randomUUID().toString(), null))
            runOnUiThread {
                testStatus.text = (if (r.ok) "✓ " else "✗ ") + r.message
                testStatus.setTextColor(if (r.ok) Color.rgb(46, 125, 91) else Color.rgb(198, 40, 40))
                refresh()
            }
        }.start()
    }

    // --- small UI helpers ---------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, bold: Boolean = false, muted: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(if (muted) Color.rgb(95, 99, 104) else Color.rgb(32, 33, 36))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun heading(s: String) = text(s, 18f, bold = true).apply { setPadding(0, dp(24), 0, dp(4)) }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun EditText.onChange(f: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = f(s?.toString() ?: "")
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    })

    @Suppress("DEPRECATION")
    private fun styleSystemBars() {
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = Color.WHITE
            window.navigationBarColor = Color.WHITE
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(light, light)
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }
}
