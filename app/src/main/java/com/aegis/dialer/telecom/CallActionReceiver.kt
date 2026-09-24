package com.aegis.dialer.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER -> CallSession.answer()
            ACTION_REJECT -> CallSession.reject()
            ACTION_HANG_UP -> CallSession.disconnect()
            ACTION_OPEN -> context.startActivity(
                Intent(context, CallActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    companion object {
        const val ACTION_ANSWER = "com.aegis.dialer.action.ANSWER_CALL"
        const val ACTION_REJECT = "com.aegis.dialer.action.REJECT_CALL"
        const val ACTION_HANG_UP = "com.aegis.dialer.action.HANG_UP_CALL"
        const val ACTION_OPEN = "com.aegis.dialer.action.OPEN_CALL"
    }
}
