package com.aegis.dialer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aegis.dialer.telecom.TelecomConnectionService

class MainActivity : ComponentActivity() {
    private var isDefaultDialer by mutableStateOf(false)
    private var statusMessage by mutableStateOf<String?>(null)
    private var phoneNumber by mutableStateOf("")
    private var contactQuery by mutableStateOf("")
    private var contacts by mutableStateOf(emptyList<ContactEntry>())
    private var openPanel by mutableStateOf<Panel?>(null)
    private var roleRequestInProgress = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filterValues { !it }.keys
        statusMessage = if (denied.isEmpty()) "Permissions granted." else "Permissions still required: ${denied.joinToString()}"
        loadContacts(contactQuery)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isDefaultDialer = TelecomConnectionService.hasDefaultDialerRole(this)
        loadContacts("")
        val missingPermissions = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) permissionLauncher.launch(missingPermissions.toTypedArray())
        setContent {
            MaterialTheme {
                AegisHome(
                    isDefaultDialer = isDefaultDialer,
                    phoneNumber = phoneNumber,
                    statusMessage = statusMessage,
                    contactQuery = contactQuery,
                    contacts = contacts,
                    panel = openPanel,
                    onDigit = { phoneNumber += it },
                    onDelete = { phoneNumber = phoneNumber.dropLast(1) },
                    onCall = ::placeCall,
                    onContactQuery = { query -> contactQuery = query; loadContacts(query) },
                    onContactSelected = { number -> phoneNumber = number; openPanel = Panel.KEYPAD },
                    onToggleKeypad = { openPanel = if (openPanel == Panel.KEYPAD) null else Panel.KEYPAD },
                    onToggleCallBook = { openPanel = if (openPanel == Panel.CALL_BOOK) null else Panel.CALL_BOOK },
                    onRequestRole = {
                        roleRequestInProgress = true
                        statusMessage = "Opening Android dialer approval..."
                        TelecomConnectionService.requestDefaultDialerRole(this, ROLE_REQUEST_CODE)
                    },
                    onOpenDefaultApps = {
                        statusMessage = "Opening Default Apps settings..."
                        TelecomConnectionService.openDefaultAppsSettings(this)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isDefaultDialer = TelecomConnectionService.hasDefaultDialerRole(this)
        if (roleRequestInProgress) {
            roleRequestInProgress = false
            statusMessage = if (isDefaultDialer) "Aegis-Dialer is now the default dialer."
            else "Choose Aegis-Dialer under Settings > Apps > Default apps > Phone app."
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() }?.let {
            phoneNumber = it
            openPanel = Panel.KEYPAD
        }
    }

    private fun placeCall() {
        val normalized = phoneNumber.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (normalized.isBlank()) {
            statusMessage = "Enter a phone number first."
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            statusMessage = "Phone permission is required to place calls."
            return
        }
        runCatching { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$normalized"))) }
            .onSuccess { statusMessage = "Call started. Aegis analysis begins when active." }
            .onFailure { statusMessage = "Could not place the call: ${it.message ?: "phone service unavailable"}" }
    }

    private fun loadContacts(query: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return
        val result = mutableListOf<ContactEntry>()
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val selection = if (query.isBlank()) null else
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        val args = if (query.isBlank()) null else arrayOf("%$query%", "%$query%")
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, selection, args,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC"
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) result += ContactEntry(cursor.getString(nameIndex) ?: "Unknown", cursor.getString(numberIndex) ?: "")
        }
        contacts = result.distinctBy { "${it.name}|${it.number}" }
    }

    companion object {
        private const val ROLE_REQUEST_CODE = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.POST_NOTIFICATIONS
        )
    }
}

private enum class Panel { KEYPAD, CALL_BOOK }
private data class ContactEntry(val name: String, val number: String)

@Composable
private fun AegisHome(
    isDefaultDialer: Boolean,
    phoneNumber: String,
    statusMessage: String?,
    contactQuery: String,
    contacts: List<ContactEntry>,
    panel: Panel?,
    onDigit: (String) -> Unit,
    onDelete: () -> Unit,
    onCall: () -> Unit,
    onContactQuery: (String) -> Unit,
    onContactSelected: (String) -> Unit,
    onToggleKeypad: () -> Unit,
    onToggleCallBook: () -> Unit,
    onRequestRole: () -> Unit,
    onOpenDefaultApps: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(top = 24.dp, start = 24.dp, end = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Aegis-Dialer", style = MaterialTheme.typography.headlineMedium)
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (panel) {
                Panel.KEYPAD -> Keypad(phoneNumber, onDigit, onDelete, onCall, Modifier.fillMaxSize())
                Panel.CALL_BOOK -> ContactBook(contactQuery, contacts, onContactQuery, onContactSelected, Modifier.fillMaxSize())
                null -> Unit
            }
        }
        if (!isDefaultDialer) {
            Text("Default dialer role required")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRequestRole, modifier = Modifier.weight(1f)) { Text("Set as default") }
                OutlinedButton(onClick = onOpenDefaultApps, modifier = Modifier.weight(1f)) { Text("Default apps") }
            }
        }
        statusMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onToggleKeypad, modifier = Modifier.weight(1f)) { Text("Keypad") }
            OutlinedButton(onClick = onToggleCallBook, modifier = Modifier.weight(1f)) { Text("Call book") }
        }
    }
}

@Composable
private fun Keypad(phoneNumber: String, onDigit: (String) -> Unit, onDelete: () -> Unit, onCall: () -> Unit, modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(phoneNumber.ifBlank { "Enter a phone number" }, style = MaterialTheme.typography.headlineSmall)
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#").chunked(3).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { key -> OutlinedButton(onClick = { onDigit(key) }, modifier = Modifier.weight(1f)) { Text(key) } }
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCall, modifier = Modifier.weight(1f)) { Text("Call") }
            OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) { Text("Backspace") }
        }
    }
}

@Composable
private fun ContactBook(query: String, contacts: List<ContactEntry>, onQuery: (String) -> Unit, onSelected: (String) -> Unit, modifier: Modifier) {
    val alphabet = ('A'..'Z').toList()
    var railSize by androidx.compose.runtime.remember { mutableStateOf(IntSize.Zero) }
    Column(modifier = modifier) {
        Text("Call book", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(value = query, onValueChange = onQuery, label = { Text("Search contacts") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(contacts, key = { "${it.name}|${it.number}" }) { entry ->
                    OutlinedButton(onClick = { onSelected(entry.number) }, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth()) { Text(entry.name); Text(entry.number, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            Column(
                modifier = Modifier.width(24.dp).fillMaxHeight().onSizeChanged { railSize = it }.pointerInput(alphabet) {
                    detectVerticalDragGestures { change, _ ->
                        if (railSize.height > 0) {
                            val index = ((change.position.y / railSize.height) * alphabet.size).toInt().coerceIn(0, alphabet.lastIndex)
                            onQuery(alphabet[index].toString())
                        }
                    }
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) { alphabet.forEach { Text(it.toString(), style = MaterialTheme.typography.labelSmall) } }
        }
    }
}
