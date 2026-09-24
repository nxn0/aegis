package com.aegis.dialer.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.aegis.dialer.data.VoiceRiskScore
import java.util.Locale

class OverlayAlertManager(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var alertView: View? = null

    fun show(score: VoiceRiskScore): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        val view = TextView(context).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(112, 40, 40))
            setPadding(40, 28, 40, 28)
            text = String.format(
                Locale.US,
                "WARNING: AI Voice Cloning / Deepfake Detected on Call\nConfidence %.0f%%",
                score.probability * 100
            )
            setOnClickListener { hide() }
        }
        hide()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 96
        }
        windowManager.addView(view, params)
        alertView = view
        return true
    }

    fun hide() {
        alertView?.let { view ->
            runCatching { windowManager.removeView(view) }
            alertView = null
        }
    }
}
