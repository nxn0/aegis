package com.aegis.dialer.audit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AuditorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AuditorService.isEnabled(context)) return
        ContextCompat.startForegroundService(
            context,
            Intent(context, AuditorService::class.java).setAction(AuditorService.ACTION_START)
        )
    }
}
