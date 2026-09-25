package com.aegis.eidolon.audit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aegis.eidolon.data.VoiceRiskScore
import com.aegis.eidolon.ml.AudioDecoder
import com.aegis.eidolon.ml.InferenceEngine

class CallRecordingAuditor(private val context: Context) {
    fun notifyPickingUpVoice() {
        notifyStatus("Picking up voice", "Aegis is reading the recorded call audio.")
    }

    fun notifyRunningInference() {
        notifyStatus("Running inference", "Aegis is checking the first 10 seconds for synthetic voice.")
    }

    fun auditLatestRecording() {
        val recording = CallRecordingStore(context).latest() ?: return
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getString(LAST_URI, null) == recording.uri.toString()) return
        val score = try {
            val samples = AudioDecoder(context).decode(recording.uri, ANALYSIS_SAMPLES)
            InferenceEngine(context).use { engine ->
                engine.classifyRecording(samples)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Voice analysis failed for ${recording.uri}", error)
            notifyFailure(recording.uri, error)
            return
        }
        preferences.edit().putString(LAST_URI, recording.uri.toString()).apply()
        notify(score, recording.uri)
    }

    private fun notifyFailure(uri: Uri, error: Exception) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val contentIntent = PendingIntent.getActivity(
            context,
            uri.hashCode(),
            Intent(context, com.aegis.eidolon.MainActivity::class.java).setData(uri),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Voice analysis unavailable")
                .setContentText(error.message ?: "The recording could not be decoded")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build()
        )
    }

    private fun notify(score: VoiceRiskScore, uri: Uri) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Call recording audits", NotificationManager.IMPORTANCE_HIGH))
        val title = if (score.isSynthetic) "Aegis: synthetic voice detected" else "Aegis: human voice detected"
        val text = if (score.isSynthetic) {
            "A possible AI-generated voice was found (${(score.probability * 100).toInt()}% confidence)."
        } else {
            "Human voice detected (${(score.probability * 100).toInt()}% confidence)."
        }
        val contentIntent = PendingIntent.getActivity(context, uri.hashCode(), Intent(context, com.aegis.eidolon.MainActivity::class.java).setData(uri), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build())
    }

    private fun notifyStatus(title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Call recording audits", NotificationManager.IMPORTANCE_HIGH))
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        )
    }

    companion object {
        private const val CHANNEL_ID = "aegis_audits"
        private const val NOTIFICATION_ID = 7003
        private const val PREFERENCES = "audit_state"
        private const val LAST_URI = "last_analyzed_uri"
        private const val TAG = "CallRecordingAuditor"
        private const val ANALYSIS_SAMPLES = 160_000
    }
}