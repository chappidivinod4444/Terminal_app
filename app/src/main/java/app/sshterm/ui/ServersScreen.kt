package app.sshterm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sshterm.AuthType
import app.sshterm.R
import app.sshterm.Server
import app.sshterm.SessionManager
import app.sshterm.Store
import app.sshterm.core.SshConnection

/** Parses "user@host", "user@host:2222" or "ssh -p 2222 user@host". */
fun parseQuick(text: String): Server? {
    var t = text.trim()
    var port = 22
    if (t.startsWith("ssh ")) {
        val parts = t.split(Regex("\\s+")).drop(1).toMutableList()
        val pi = parts.indexOf("-p")
        if (pi >= 0 && pi + 1 < parts.size) {
            port = parts[pi + 1].toIntOrNull() ?: return null
            parts.removeAt(pi + 1)
            parts.removeAt(pi)
        }
        t = parts.lastOrNull() ?: return null
    }
    val at = t.lastIndexOf('@')
    if (at <= 0) return null
    val user = t.substring(0, at)
    var host = t.substring(at + 1)
    if (host.startsWith("[")) {
        val end = host.indexOf(']')
        if (end < 0) return null
        val rest = host.substring(end + 1)
        host = host.substring(1, end)
        if (rest.startsWith(":")) port = rest.drop(1).toIntOrNull() ?: return null
    } else if (host.count { it == ':' } == 1) {
        port = host.substringAfter(':').toIntOrNull() ?: return null
        host = host.substringBefore(':')
    }
    if (user.isBlank() || host.isBlank() || port !in 1..65535) return null
    return Server(id = "", name = host, host = host, port = port, user = user)
}

@Composable
fun ServersScreen(
    onConnect: (Server) -> Unit,
    onEdit: (String?) -> Unit,
    onOpenTerminal: () -> Unit,
    say: (String) -> Unit,
) {
    val servers by Store.servers.collectAsState()
    val keys by Store.keys.collectAsState()
    val sessions by SessionManager.sessions.collectAsState()
    var quick by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<Server?>(null) }
    val sorted = remember(servers) { servers.sortedWith(compareByDescending<Server> { it.lastUsed }.thenBy { it.name.lowercase() }) }

    fun quickConnect() {
        val s = parseQuick(quick)
        if (s == null) {
            say("Type it like user@host or user@host:port")
            return
        }
        quick = ""
        onConnect(s)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenTitle("Servers", "Tap a server to open a terminal") }

        if (sessions.isNotEmpty()) {
            item {
                Card(
                    onClick = onOpenTerminal,
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBubble(R.drawable.ic_terminal, C.Green)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (sessions.size == 1) "1 session open" else "${sessions.size} sessions open",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                sessions.joinToString { it.server.name },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text("Resume", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Quick connect", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = quick,
                            onValueChange = { quick = it },
                            placeholder = { Text("user@host:port", fontFamily = Mono) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Mono),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go,
                                autoCorrectEnabled = false,
                            ),
                            keyboardActions = KeyboardActions(onGo = { quickConnect() }),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(onClick = { quickConnect() }, shape = RoundedCornerShape(14.dp)) { Text("Go") }
                    }
                }
            }
        }

        if (sorted.isEmpty()) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    IconBubble(R.drawable.ic_server, C.Blue, size = 64)
                    Spacer(Modifier.height(14.dp))
                    Text("No saved servers yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Save a server once and connect with one tap.\nPasswords and keys are stored encrypted.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                    )
                    Button(onClick = { onEdit(null) }) { Text("Add server") }
                }
            }
        } else {
            item {
                Text(
                    "Saved",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
            }
            items(sorted, key = { it.id }) { server ->
                val live = sessions.any { it.server.id == server.id && it.state.value == SshConnection.State.CONNECTED }
                ServerCard(
                    server = server,
                    authLabel = when {
                        server.auth == AuthType.KEY ->
                            "Key: " + (keys.firstOrNull { it.id == server.keyId }?.name ?: "missing")
                        server.password != null -> "Password saved"
                        else -> "Asks for password"
                    },
                    live = live,
                    onClick = { onConnect(server) },
                    onEdit = { onEdit(server.id) },
                    onDuplicate = {
                        Store.upsert(server.copy(id = java.util.UUID.randomUUID().toString(), name = server.name + " copy", lastUsed = 0))
                    },
                    onDelete = { confirmDelete = server },
                )
            }
        }
    }

    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${s.name}?") },
            text = { Text("The saved login for ${s.address} will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    Store.deleteServer(s.id)
                    confirmDelete = null
                }) { Text("Delete", color = C.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ServerCard(
    server: Server,
    authLabel: String,
    live: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBubble(R.drawable.ic_server, if (live) C.Green else C.Blue)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        server.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (live) {
                        Spacer(Modifier.width(8.dp))
                        StatusDot(C.Green)
                    }
                }
                MonoText(server.address)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(
                        androidx.compose.ui.res.painterResource(if (server.auth == AuthType.KEY) R.drawable.ic_key else R.drawable.ic_lock),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(authLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (server.startupCommand.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            androidx.compose.ui.res.painterResource(R.drawable.ic_bolt),
                            contentDescription = null,
                            tint = C.Yellow,
                            modifier = Modifier.size(13.dp),
                        )
                        Text("Startup command", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Connect") }, onClick = { menu = false; onClick() })
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Delete", color = C.Red) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
