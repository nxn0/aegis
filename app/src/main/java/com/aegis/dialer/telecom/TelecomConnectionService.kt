package com.aegis.dialer.telecom

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.InCallService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import android.util.Log
import com.aegis.dialer.ui.OverlayAlertManager

class TelecomConnectionService : ConnectionService() {
    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        return AegisConnection().apply {
            setInitializing()
            setActive()
        }
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        return AegisConnection().apply {
            setInitializing()
            setRinging()
        }
    }

    private class AegisConnection : Connection() {
        override fun onAnswer(videoState: Int) = setActive()
        override fun onDisconnect() = setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        override fun onReject() = setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
    }

    companion object {
        private const val TAG = "TelecomConnectionService"

        fun requestDefaultDialerRole(activity: Activity, requestCode: Int): Boolean {
            return runCatching {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    openDefaultAppsSettings(activity)
                    return false
                }
                val roleManager = activity.getSystemService(RoleManager::class.java)
                if (!roleManager.isRoleHeld(RoleManager.ROLE_DIALER) && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    activity.startActivityForResult(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER), requestCode)
                    true
                } else {
                    openDefaultAppsSettings(activity)
                    false
                }
            }.onFailure {
                Log.e(TAG, "Unable to open default dialer role request", it)
                Toast.makeText(activity, "Android did not open the dialer role screen. Use Settings > Apps > Default apps > Phone app.", Toast.LENGTH_LONG).show()
            }.getOrDefault(false)
        }

        fun openDefaultAppsSettings(context: Context) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
            }.onFailure {
                Log.e(TAG, "Unable to open default apps settings", it)
                Toast.makeText(context, "Open Settings > Apps > Default apps > Phone app, then choose Aegis-Dialer.", Toast.LENGTH_LONG).show()
            }
        }

        fun hasDefaultDialerRole(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_DIALER)
    }
}

class AegisInCallService : InCallService() {
    private lateinit var overlayAlertManager: OverlayAlertManager
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        CallSession.service = this
        createCallNotificationChannel()
        overlayAlertManager = OverlayAlertManager(this)
        AudioCaptureService.scoreListener = { score ->
            mainHandler.post {
                if (score.isSynthetic) overlayAlertManager.show(score)
                else overlayAlertManager.hide()
            }
        }
    }

    override fun onCallAdded(call: android.telecom.Call) {
        super.onCallAdded(call)
        CallSession.attach(call)
        call.registerCallback(callCallback)
        showCallNotification(call.state == android.telecom.Call.STATE_RINGING)
        startActivity(
            Intent(this, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        if (call.state == android.telecom.Call.STATE_ACTIVE) {
            startCapture()
        }
    }

    private val callCallback = object : android.telecom.Call.Callback() {
        override fun onStateChanged(call: android.telecom.Call, state: Int) {
            CallSession.update(call, state)
            showCallNotification(state == android.telecom.Call.STATE_RINGING)
            if (state == android.telecom.Call.STATE_ACTIVE) {
                startCapture()
            }
        }
    }

    private fun startCapture() {
        runCatching {
            startForegroundService(
                Intent(this, AudioCaptureService::class.java)
                    .setAction(AudioCaptureService.ACTION_START)
            )
        }.onFailure { Log.e(TAG, "Unable to start call analysis service", it) }
    }

    override fun onCallRemoved(call: android.telecom.Call) {
        call.unregisterCallback(callCallback)
        CallSession.clear(call)
        getSystemService(NotificationManager::class.java).cancel(CALL_NOTIFICATION_ID)
        stopService(Intent(this, AudioCaptureService::class.java))
        overlayAlertManager.hide()
        super.onCallRemoved(call)
    }

    override fun onDestroy() {
        CallSession.service = null
        AudioCaptureService.scoreListener = null
        overlayAlertManager.hide()
        super.onDestroy()
    }

    private fun createCallNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CALL_CHANNEL_ID, "Calls", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun showCallNotification(ringing: Boolean) {
        val openIntent = PendingIntent.getBroadcast(
            this, 1, Intent(this, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_OPEN),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CALL_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(if (ringing) "Incoming call" else "Aegis call active")
            .setContentText(CallSession.number)
            .setContentIntent(openIntent)
            .setOngoing(ringing)
            .setCategory(Notification.CATEGORY_CALL)
        if (ringing) {
            builder.addAction(notificationAction("Answer", CallActionReceiver.ACTION_ANSWER, 2))
                .addAction(notificationAction("Reject", CallActionReceiver.ACTION_REJECT, 3))
        } else {
            builder.addAction(notificationAction("End call", CallActionReceiver.ACTION_HANG_UP, 4))
        }
        getSystemService(NotificationManager::class.java).notify(CALL_NOTIFICATION_ID, builder.build())
    }

    private fun notificationAction(title: String, action: String, requestCode: Int): Notification.Action {
        val intent = PendingIntent.getBroadcast(
            this, requestCode, Intent(this, CallActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(null, title, intent).build()
    }

    companion object {
        private const val TAG = "AegisInCallService"
        private const val CALL_CHANNEL_ID = "aegis_calls"
        private const val CALL_NOTIFICATION_ID = 7002
    }
}
