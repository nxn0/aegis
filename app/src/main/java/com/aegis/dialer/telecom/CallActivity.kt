package com.aegis.dialer.telecom

import android.content.Intent
import android.provider.ContactsContract
import android.telecom.Call
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class CallActivity : ComponentActivity() {
    private var callState by mutableStateOf(CallSession.state)
    private var muted by mutableStateOf(false)
    private var speaker by mutableStateOf(false)
    private var held by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        callState = CallSession.state
        render()
    }

    private fun render() {
        setContent {
            MaterialTheme {
                CallScreen(
                    number = CallSession.number,
                    state = callState,
                    muted = muted,
                    speaker = speaker,
                    held = held,
                    onAnswer = { CallSession.answer(); callState = Call.STATE_ACTIVE },
                    onReject = { CallSession.reject(); finish() },
                    onHangUp = { CallSession.disconnect(); finish() },
                    onMute = { muted = !muted; CallSession.setMuted(muted) },
                    onSpeaker = { speaker = !speaker; CallSession.setSpeaker(speaker) },
                    onHold = { held = !held; CallSession.hold(held) },
                    onAddCall = { openDialer() },
                    onContacts = { openContacts() }
                )
            }
        }
    }
}

@Composable
private fun CallScreen(
    number: String,
    state: Int,
    muted: Boolean,
    speaker: Boolean,
    held: Boolean,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onHangUp: () -> Unit,
    onMute: () -> Unit,
    onSpeaker: () -> Unit,
    onHold: () -> Unit,
    onAddCall: () -> Unit,
    onContacts: () -> Unit
) {
    val ringing = state == Call.STATE_RINGING
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(if (ringing) "Incoming call" else "Call", style = MaterialTheme.typography.headlineMedium)
        Text(number, style = MaterialTheme.typography.headlineSmall)
        Text(if (ringing) "Ringing" else "Connected")
        if (ringing) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onAnswer, modifier = Modifier.weight(1f)) { Text("Answer") }
                Button(onClick = onReject, modifier = Modifier.weight(1f)) { Text("Reject") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onMute, modifier = Modifier.weight(1f)) { Text(if (muted) "Unmute" else "Mute") }
                OutlinedButton(onClick = onSpeaker, modifier = Modifier.weight(1f)) { Text(if (speaker) "Earpiece" else "Speaker") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onAddCall, modifier = Modifier.weight(1f)) { Text("Add call") }
                OutlinedButton(onClick = onContacts, modifier = Modifier.weight(1f)) { Text("Contacts") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onHold, modifier = Modifier.weight(1f)) { Text(if (held) "Resume" else "Hold") }
                Button(
                    onClick = onHangUp,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("End call") }
            }
        }
    }
}

private fun CallActivity.openDialer() {
    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
}

private fun CallActivity.openContacts() {
    startActivity(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI))
}
