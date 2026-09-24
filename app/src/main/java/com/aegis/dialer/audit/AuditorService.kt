package com.aegis.dialer.audit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

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
        private const val CHANNEL_ID = "aegis_background_auditor"
        private const val NOTIFICATION_ID = 7004
    }
}
