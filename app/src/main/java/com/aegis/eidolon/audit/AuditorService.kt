@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.aegis.eidolon.audit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class AuditorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var auditJob: Job? = null
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var callStateListener: PhoneStateListener
    private var lastCallState = TelephonyManager.CALL_STATE_IDLE

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Aegis", NotificationManager.IMPORTANCE_HIGH)
        )
        startForeground(NOTIFICATION_ID, notification())
        registerCallStateListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isEnabled(this)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::telephonyManager.isInitialized && ::callStateListener.isInitialized) {
            telephonyManager.listen(callStateListener, PhoneStateListener.LISTEN_NONE)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun registerCallStateListener() {
        telephonyManager = getSystemService(TelephonyManager::class.java)
        callStateListener = object : PhoneStateListener() {
            @Suppress("DEPRECATION")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                if (state == TelephonyManager.CALL_STATE_IDLE && lastCallState != TelephonyManager.CALL_STATE_IDLE) {
                    auditJob?.cancel()
                    val auditor = CallRecordingAuditor(applicationContext)
                    auditor.notifyPickingUpVoice()
                    auditJob = serviceScope.launch {
                        delay(RECORDING_FLUSH_DELAY_MS)
                        auditor.notifyRunningInference()
                        auditor.auditLatestRecording()
                    }
                }
                lastCallState = state
            }
        }
        @Suppress("DEPRECATION")
        telephonyManager.listen(callStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setContentTitle("Aegis active")
        .setContentText("Waiting for completed calls to audit their recordings")
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setOnlyAlertOnce(true)
        .setAutoCancel(false)
        .setOngoing(true)
        .build()

    companion object {
        private const val PREFERENCES = "audit_state"
        private const val ENABLED = "background_enabled"
        private const val CHANNEL_ID = "aegis_background_auditor_v2"
        private const val NOTIFICATION_ID = 7004
        private const val RECORDING_FLUSH_DELAY_MS = 5_000L

        fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(ENABLED, false)

        @Suppress("DEPRECATION")
        fun isRunning(context: Context): Boolean = context.getSystemService(ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == AuditorService::class.java.name }

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(ENABLED, enabled).apply()
        }
    }
}
