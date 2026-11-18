package com.callbell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts [CallService] after the phone reboots or CallBell is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> CallService.start(context)
        }
    }
}
