package com.aegis.dialer.audit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CallEndedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val stateStore = context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_IDLE) {
            stateStore.edit().putString(LAST_STATE, state).apply()
            return
        }
        val previousState = stateStore.getString(LAST_STATE, null)
        stateStore.edit().putString(LAST_STATE, state).apply()
        if (previousState == TelephonyManager.EXTRA_STATE_IDLE) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(RECORDING_FLUSH_DELAY_MS)
                CallRecordingAuditor(appContext).auditLatestRecording()
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val STATE_PREFERENCES = "call_state"
        private const val LAST_STATE = "last_state"
        private const val RECORDING_FLUSH_DELAY_MS = 5_000L
    }
}