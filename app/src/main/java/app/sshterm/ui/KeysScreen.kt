package app.sshterm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sshterm.R
import app.sshterm.SshKey
import app.sshterm.Store
import app.sshterm.core.KeyType
import app.sshterm.core.SshSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

fun copyText(context: Context, label: String, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm?.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun shareText(context: Context, text: String) {
    try {
        context.startActivity(
            Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share public key")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Throwable) {
    }
}

@Composable
fun KeysScreen(say: (String) -> Unit) {
    val context = LocalContext.current
    val keys by Store.keys.collectAsState()
    var generate by remember { mutableStateOf(false) }
    var import by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<SshKey?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenTitle("SSH keys", "Log in without typing a password") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { generate = true }, modifier = Modifier.weight(1f)) { Text("Create key") }
                OutlinedButton(onClick = { import = true }, modifier = Modifier.weight(1f)) { Text("Import key") }
            }
        }
        if (keys.isEmpty()) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconBubble(R.drawable.ic_key, C.Yellow, size = 64)
                    Spacer(Modifier.height(14.dp))
                    Text("No keys yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Create a key here, add its public part to your server once,\nthen log in with one tap.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        items(keys, key = { it.id }) { k ->
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(R.drawable.ic_key, C.Yellow)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(k.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                k.type + " · " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(k.created)) +
                                    if (k.passphrase != null) " · passphrase" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        k.publicKey,
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                    )
                    Row {
                        TextButton(onClick = { copyText(context, "Public key", k.publicKey); say("Public key copied") }) {
                            Text("Copy public key")
                        }
                        TextButton(onClick = { shareText(context, k.publicKey) }) { Text("Share") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { confirmDelete = k }) { Text("Delete", color = C.Red) }
                    }
                }
            }
        }
    }

    if (generate) GenerateDialog(onClose = { generate = false }, say = say)
    if (import) ImportDialog(onClose = { import = false }, say = say)
    confirmDelete?.let { k ->
        val users = Store.servers.value.count { it.keyId == k.id }
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${k.name}?") },
            text = {
                Text(
                    if (users > 0) "$users saved server(s) use this key and won't be able to log in with it."
                    else "This can't be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = { Store.deleteKey(k.id); confirmDelete = null }) { Text("Delete", color = C.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GenerateDialog(onClose: () -> Unit, say: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("phone-key") }
    var type by remember { mutableStateOf(KeyType.ED25519) }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Create a key") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                KeyType.entries.forEach { t ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = type == t, onClick = { type = t }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = type == t, onClick = { type = t })
                        Column {
                            Text(t.label)
                            if (t == KeyType.ED25519) {
                                Text("Recommended", style = MaterialTheme.typography.bodySmall, color = C.Green)
                            }
                            if (t == KeyType.RSA) {
                                Text("For very old servers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Creating…")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        runCatching { SshSetup.generateKey(type, name.trim().ifEmpty { "phone-key" }.replace(' ', '-')) }
                    }
                    busy = false
                    result.onSuccess {
                        Store.addKey(name.trim().ifEmpty { "phone-key" }, it.type, it.publicKey, it.privateKey, null)
                        say("Key created. Copy its public key to your server.")
                        onClose()
                    }.onFailure { say("Couldn't create the key: ${it.message}") }
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun ImportDialog(onClose: () -> Unit, say: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            if (bytes.size > 64 * 1024) {
                error = "That file is too big to be a key."
            } else {
                text = String(bytes)
                if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "imported"
                error = null
            }
        } catch (t: Throwable) {
            error = "Couldn't read the file."
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("Import a private key") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Choose key file (id_ed25519, id_rsa, .pem)")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("…or paste the private key") },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----", fontFamily = Mono, fontSize = 11.sp) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 110.dp, max = 180.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = passphrase, onValueChange = { passphrase = it },
                    label = { Text("Passphrase (if the key has one)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(it, color = C.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && text.isNotBlank(), onClick = {
                busy = true
                val bytes = text.trim().replace("\r\n", "\n").plus("\n").toByteArray()
                val keyName = name.trim().ifEmpty { "imported" }
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        runCatching { SshSetup.inspectKey(bytes, passphrase.ifEmpty { null }, keyName) }
                    }
                    busy = false
                    result.onSuccess { info ->
                        Store.addKey(keyName, info.type, info.publicKey, bytes, passphrase.ifEmpty { null })
                        say("Key imported")
                        onClose()
                    }.onFailure { error = it.message ?: "Couldn't read this key." }
                }
            }) { Text("Import") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onClose) { Text("Cancel") } },
    )
}
