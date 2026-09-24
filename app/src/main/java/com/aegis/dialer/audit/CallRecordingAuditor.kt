package com.aegis.dialer.audit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.aegis.dialer.MainActivity
import com.aegis.dialer.data.VoiceRiskScore
import com.aegis.dialer.ml.InferenceEngine

class CallRecordingAuditor(private val context: Context) {
    fun auditLatestRecording() {
        val recording = CallRecordingStore(context).latest() ?: return
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getString(LAST_URI, null) == recording.uri.toString()) return
        val score = runCatching {
            InferenceEngine(context).use { engine ->
                context.contentResolver.openInputStream(recording.uri)?.use { input ->
                    engine.classify(input)
                }
            }
        }.getOrNull() ?: return
        preferences.edit().putString(LAST_URI, recording.uri.toString()).apply()
        notify(score, recording.uri)
    }

    private fun notify(score: VoiceRiskScore, uri: Uri) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Call recording audits", NotificationManager.IMPORTANCE_HIGH))
        val title = if (score.isSynthetic) "Synthetic voice detected" else "Call recording audited"
        val text = if (score.isSynthetic) {
            "A possible AI-generated voice was found (${(score.probability * 100).toInt()}% confidence)."
        } else {
            "No synthetic voice signature detected (${(score.probability * 100).toInt()}% confidence)."
        }
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(MainActivity.openIntent(context, uri))
            .build())
    }

    companion object {
        private const val CHANNEL_ID = "aegis_audits"
        private const val NOTIFICATION_ID = 7003
        private const val PREFERENCES = "audit_state"
        private const val LAST_URI = "last_analyzed_uri"
    }
}