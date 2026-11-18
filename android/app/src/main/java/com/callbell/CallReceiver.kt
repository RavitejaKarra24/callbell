package com.callbell

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONObject
import java.util.UUID

/**
 * Woken by Android on every phone-state change (even when the app is closed).
 *   idle -> ringing                 => "ringing"
 *   ringing -> offhook              => "answered"
 *   ringing -> idle (never answered)=> "missed"
 *
 * Quirk: when the app holds READ_CALL_LOG, Android delivers every state change twice,
 * once without the number and once with it. We prefer the copy with the number and only
 * fall back to the number-less copy if the other one doesn't arrive quickly.
 */
class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val ctx = context.applicationContext
        val prefs = Prefs(ctx)
        if (!prefs.enabled || prefs.code.isBlank()) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val hasNumber = intent.hasExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val canReadNumber =
            ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

        val pending = goAsync()
        Thread {
            try {
                handle(ctx, prefs, state, hasNumber, number, canReadNumber)
            } catch (e: Exception) {
                Log.w(TAG, "failed to handle $state", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handle(
        ctx: Context, prefs: Prefs, state: String,
        hasNumber: Boolean, number: String?, canReadNumber: Boolean,
    ) {
        var event: JSONObject? = null
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val authoritative = hasNumber || !canReadNumber
                synchronized(LOCK) {
                    if (prefs.phase != Prefs.PHASE_RINGING) {
                        prefs.phase = Prefs.PHASE_RINGING
                        prefs.callId = UUID.randomUUID().toString()
                        prefs.callNumber = ""
                        prefs.ringSent = false
                    }
                    if (!number.isNullOrBlank()) prefs.callNumber = number
                    if (authoritative && !prefs.ringSent) {
                        prefs.ringSent = true
                        event = Events.make(ctx, Events.RINGING, prefs.callId, prefs.callNumber)
                    }
                }
                if (event == null && !authoritative) {
                    // Give the copy that carries the number a moment to arrive.
                    Thread.sleep(1500)
                    synchronized(LOCK) {
                        if (prefs.phase == Prefs.PHASE_RINGING && !prefs.ringSent) {
                            prefs.ringSent = true
                            event = Events.make(ctx, Events.RINGING, prefs.callId, prefs.callNumber)
                        }
                    }
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> synchronized(LOCK) {
                if (prefs.phase == Prefs.PHASE_RINGING) {
                    event = Events.make(ctx, Events.ANSWERED, prefs.callId, prefs.callNumber)
                }
                prefs.phase = Prefs.PHASE_OFFHOOK
            }

            TelephonyManager.EXTRA_STATE_IDLE -> synchronized(LOCK) {
                if (prefs.phase == Prefs.PHASE_RINGING) {
                    event = Events.make(ctx, Events.MISSED, prefs.callId, prefs.callNumber)
                }
                prefs.phase = Prefs.PHASE_IDLE
            }
        }
        event?.let {
            val r = Sender.send(ctx, it)
            Log.i(TAG, "${it.optString("type")}: ${r.message}")
        }
    }

    companion object {
        private const val TAG = "CallBell"
        private val LOCK = Any()
    }
}
