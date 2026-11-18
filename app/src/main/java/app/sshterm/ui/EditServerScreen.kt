package app.sshterm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.sshterm.AuthType
import app.sshterm.Secrets
import app.sshterm.Server
import app.sshterm.Store

@Composable
fun EditServerScreen(
    serverId: String?,
    onBack: () -> Unit,
    onSaved: (Server, Boolean) -> Unit,
    onGenerateKey: () -> Unit,
    say: (String) -> Unit,
) {
    val existing = remember(serverId) { Store.server(serverId) }
    val keys by Store.keys.collectAsState()

    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var host by rememberSaveable { mutableStateOf(existing?.host ?: "") }
    var port by rememberSaveable { mutableStateOf((existing?.port ?: 22).toString()) }
    var user by rememberSaveable { mutableStateOf(existing?.user ?: "") }
    var auth by rememberSaveable { mutableStateOf(existing?.auth ?: AuthType.PASSWORD) }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var savePassword by rememberSaveable { mutableStateOf(existing?.password != null || existing == null) }
    var keyId by rememberSaveable { mutableStateOf(existing?.keyId ?: keys.firstOrNull()?.id) }
    var startup by rememberSaveable { mutableStateOf(existing?.startupCommand ?: "") }
    var keyMenu by remember { mutableStateOf(false) }
    val hasSaved = existing?.password != null

    fun build(): Server? {
        val p = port.trim().toIntOrNull()
        if (host.isBlank()) { say("Enter the server address"); return null }
        if (user.isBlank()) { say("Enter the username"); return null }
        if (p == null || p !in 1..65535) { say("Port must be a number from 1 to 65535"); return null }
        if (auth == AuthType.KEY && Store.key(keyId) == null) { say("Pick a key, or create one in the Keys tab"); return null }
        val storedPassword = when {
            auth != AuthType.PASSWORD || !savePassword -> null
            password.isNotEmpty() -> Secrets.encryptText(password)
            else -> existing?.password
        }
        val h = host.trim().removePrefix("ssh://").substringBefore('/')
        return Server(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { h },
            host = h,
            port = p,
            user = user.trim(),
            auth = auth,
            password = storedPassword,
            keyId = if (auth == AuthType.KEY) keyId else null,
            startupCommand = startup.trim(),
            lastUsed = existing?.lastUsed ?: 0,
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(
                if (existing == null) "Add server" else "Edit server",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }

        Section("Server") {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("Name (optional)") }, placeholder = { Text("My VPS") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Row {
                OutlinedTextField(
                    value = host, onValueChange = { host = it.trim() },
                    label = { Text("Address") }, placeholder = { Text("192.168.1.10 or example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                OutlinedTextField(
                    value = port, onValueChange = { v -> port = v.filter { it.isDigit() }.take(5) },
                    label = { Text("Port") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(96.dp),
                )
            }
            OutlinedTextField(
                value = user, onValueChange = { user = it.trim() },
                label = { Text("Username") }, placeholder = { Text("root") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Section("Login") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = auth == AuthType.PASSWORD,
                    onClick = { auth = AuthType.PASSWORD },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Password") }
                SegmentedButton(
                    selected = auth == AuthType.KEY,
                    onClick = { auth = AuthType.KEY },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("SSH key") }
            }
            if (auth == AuthType.PASSWORD) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Save password", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (savePassword) "Stored encrypted on this phone" else "You'll be asked each time",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = savePassword, onCheckedChange = { savePassword = it })
                }
                if (savePassword) {
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text(if (hasSaved) "New password (leave empty to keep)" else "Password") },
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                if (keys.isEmpty()) {
                    Text(
                        "You don't have any keys yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = onGenerateKey) { Text("Create or import a key") }
                } else {
                    val selected = keys.firstOrNull { it.id == keyId }
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(onClick = { keyMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(selected?.let { "${it.name}  (${it.type})" } ?: "Choose a key")
                        }
                        DropdownMenu(expanded = keyMenu, onDismissRequest = { keyMenu = false }) {
                            keys.forEach { k ->
                                DropdownMenuItem(
                                    text = { Text("${k.name}  (${k.type})") },
                                    onClick = { keyId = k.id; keyMenu = false },
                                )
                            }
                        }
                    }
                    Text(
                        "The server must have this key's public part in ~/.ssh/authorized_keys. " +
                            "You can add it from a terminal: ⋮ menu > Install my key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Section("After login (optional)") {
            OutlinedTextField(
                value = startup, onValueChange = { startup = it },
                label = { Text("Run this command") },
                placeholder = { Text("cd /var/www && ls", fontFamily = Mono) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { build()?.let { Store.upsert(it); onSaved(it, false) } },
                modifier = Modifier.weight(1f),
            ) { Text("Save") }
            Button(
                onClick = { build()?.let { Store.upsert(it); onSaved(it, true) } },
                modifier = Modifier.weight(1f),
            ) { Text("Save & connect") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
