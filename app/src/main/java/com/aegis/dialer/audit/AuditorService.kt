package com.aegis.dialer.audit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

class AuditorService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Aegis background auditor", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isEnabled(this)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setContentTitle("Aegis auditor active")
        .setContentText("Waiting for completed calls to audit their recordings")
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOngoing(true)
        .build()

    companion object {
        const val ACTION_START = "com.aegis.dialer.action.START_AUDITOR"
        private const val PREFERENCES = "audit_state"
        private const val ENABLED = "background_enabled"
        private const val CHANNEL_ID = "aegis_background_auditor"
        private const val NOTIFICATION_ID = 7004

        fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(ENABLED, enabled).apply()
        }
    }
}
