package com.aegis.eidolon

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aegis.eidolon.audit.AuditorService

class MainActivity : ComponentActivity() {
    private var permissionStatus by mutableStateOf("Checking permissions...")
    private var permissionsGranted by mutableStateOf(false)
    private var backgroundRunning by mutableStateOf(false)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updatePermissionStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        backgroundRunning = AuditorService.isRunning(this)
        setContent {
            MaterialTheme(colorScheme = AegisColors.scheme) {
                LaunchedEffect(Unit) { requestMissingPermissions() }
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("Aegis", style = MaterialTheme.typography.headlineMedium)
                    Text("Aegis waits for a call to end, then checks the newest system recording for synthetic voice signatures.")
                    Text(permissionStatus)
                    Button(
                        onClick = ::requestMissingPermissions,
                        enabled = !permissionsGranted,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Grant required permissions") }
                    Button(
                        onClick = ::toggleBackground,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (backgroundRunning) "Stop running in background" else "Start running in background")
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        backgroundRunning = AuditorService.isRunning(this)
    }

    private fun toggleBackground() {
        if (backgroundRunning) {
            AuditorService.setEnabled(this, false)
            stopService(Intent(this, AuditorService::class.java))
            backgroundRunning = false
            finishAndRemoveTask()
        } else {
            AuditorService.setEnabled(this, true)
            ContextCompat.startForegroundService(this, Intent(this, AuditorService::class.java))
            backgroundRunning = true
        }
    }

    private fun requestMissingPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter { !hasPermission(it) }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            permissionStatus = "Auditor is ready. All required permissions are granted."
        }
    }

    private fun updatePermissionStatus() {
        permissionsGranted = hasRequiredPermissions()
        permissionStatus = if (permissionsGranted) "Auditor is ready." else "Phone state, audio library, and notification permissions are required."
    }

    private fun hasRequiredPermissions(): Boolean = REQUIRED_PERMISSIONS.all(::hasPermission)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startBackgroundService() {
        AuditorService.setEnabled(this, true)
        ContextCompat.startForegroundService(this, Intent(this, AuditorService::class.java))
        backgroundRunning = true
    }

    companion object {
        private val REQUIRED_PERMISSIONS = buildList {
            add(Manifest.permission.READ_PHONE_STATE)
            add(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }

        fun openIntent(context: Context, recordingUri: Uri): PendingIntent = PendingIntent.getActivity(
            context,
            recordingUri.hashCode(),
            Intent(context, MainActivity::class.java).setData(recordingUri),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private object AegisColors {
        val scheme = lightColorScheme(
            primary = Color(0xFFB85C70),
            onPrimary = Color(0xFFFFFFFF),
            secondary = Color(0xFFE8A982),
            onSecondary = Color(0xFF3D211B),
            background = Color(0xFFFFE7D6),
            onBackground = Color(0xFF3D211B),
            surface = Color(0xFFFFE7D6),
            onSurface = Color(0xFF3D211B),
            surfaceVariant = Color(0xFFF4C7B0),
            onSurfaceVariant = Color(0xFF5B3830)
        )
    }
}
