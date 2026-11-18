package com.callbell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Keeps CallBell's process alive with a quiet permanent notification, and listens for
 * phone-state changes itself.
 *
 * Some phones (realme, Oppo, OnePlus, Xiaomi, Vivo) freeze background apps a few minutes
 * after you leave them. Android then holds back the "phone is ringing" broadcast instead of
 * starting the app, so calls arrive late in a burst or never. A process with a foreground
 * service is not frozen, so the receiver registered here hears every call straight away.
 * The manifest receiver stays as a fallback for when this service isn't running.
 */
class CallService : Service() {

    private val receiver = CallReceiver(fromService = true)

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // PHONE_STATE is a protected broadcast only the system can send, so this receiver
        // doesn't need to be exported to other apps.
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        running = true
        Log.i(TAG, "listening for calls")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Prefs(this).enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Running in background", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps CallBell ready to tell your Mac about calls."
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("CallBell is active")
            .setContentText("Calls to this phone will show on your Mac.")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val TAG = "CallBell"
        private const val CHANNEL_ID = "service"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var running = false
            private set

        /** Starts the service if forwarding is set up. Safe to call from anywhere; never throws. */
        fun start(context: Context) {
            val prefs = Prefs(context)
            if (!prefs.enabled || prefs.code.isBlank()) return
            try {
                context.startForegroundService(Intent(context, CallService::class.java))
            } catch (e: Exception) {
                // Android 12+ refuses to start it from the background unless battery
                // optimisation is off. Opening the app starts it instead.
                Log.w(TAG, "could not start service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
