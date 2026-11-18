package com.callbell

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("callbell", Context.MODE_PRIVATE)

    private fun str(key: String) = sp.getString(key, "") ?: ""
    private fun put(key: String, value: String?) = sp.edit().putString(key, value).apply()

    // Settings
    var code: String
        get() = str("code")
        set(v) = put("code", v)
    var manualIp: String
        get() = str("manual_ip")
        set(v) = put("manual_ip", v)
    var cachedIp: String
        get() = str("cached_ip")
        set(v) = put("cached_ip", v)
    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()
    var lastResult: String
        get() = str("last_result")
        set(v) = put("last_result", v)
    var askedPermissions: Boolean
        get() = sp.getBoolean("asked_perms", false)
        set(v) = sp.edit().putBoolean("asked_perms", v).apply()

    // State of the current call, kept here because every broadcast gets a fresh receiver.
    var phase: String
        get() = sp.getString("phase", PHASE_IDLE) ?: PHASE_IDLE
        set(v) = sp.edit().putString("phase", v).putLong("phase_at", System.currentTimeMillis()).apply()
    /** When [phase] last changed, in epoch millis. */
    val phaseAt: Long
        get() = sp.getLong("phase_at", 0L)
    var callId: String
        get() = str("call_id")
        set(v) = put("call_id", v)
    var callNumber: String
        get() = str("call_number")
        set(v) = put("call_number", v)
    var ringSent: Boolean
        get() = sp.getBoolean("ring_sent", false)
        set(v) = sp.edit().putBoolean("ring_sent", v).apply()

    companion object {
        const val PHASE_IDLE = "idle"
        const val PHASE_RINGING = "ringing"
        const val PHASE_OFFHOOK = "offhook"
    }
}
