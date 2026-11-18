package app.sshterm.ui

import android.content.ClipboardManager
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.sshterm.Modifiers
import app.sshterm.R
import app.sshterm.SessionManager
import app.sshterm.Settings
import app.sshterm.Store
import app.sshterm.TermSession
import app.sshterm.core.SshConnection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun stateColor(s: SshConnection.State): Color = when (s) {
    SshConnection.State.CONNECTED -> C.Green
    SshConnection.State.CONNECTING -> C.Yellow
    SshConnection.State.FAILED -> C.Red
    SshConnection.State.CLOSED -> C.Muted
}

@Composable
fun TerminalScreen(onBack: () -> Unit, onNewSession: () -> Unit, say: (String) -> Unit) {
    val sessions by SessionManager.sessions.collectAsState()
    val currentId by SessionManager.currentId.collectAsState()
    val session = sessions.firstOrNull { it.id == currentId } ?: sessions.lastOrNull()
    val context = LocalContext.current
    val view = LocalView.current

    LaunchedEffect(sessions.isEmpty()) { if (sessions.isEmpty()) onBack() }
    BackHandler { onBack() }

    DisposableEffect(Unit) {
        view.keepScreenOn = Settings.keepScreenOn(context)
        onDispose { view.keepScreenOn = false }
    }

    var menu by remember { mutableStateOf(false) }
    var copyDialog by remember { mutableStateOf<String?>(null) }
    var installKey by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf<TermSession?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(C.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // ---- top bar with session tabs
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Servers", tint = C.Muted)
            }
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                sessions.forEach { s -> SessionTab(s, selected = s.id == session?.id, onClose = { confirmClose = s }) }
            }
            IconButton(onClick = onNewSession) { Icon(Icons.Filled.Add, contentDescription = "New session", tint = C.Muted) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Menu", tint = C.Muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Paste") }, onClick = {
                        menu = false
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (text.isNullOrEmpty()) say("Clipboard is empty") else session?.paste(text)
                    })
                    DropdownMenuItem(text = { Text("Select & copy text") }, onClick = {
                        menu = false
                        session?.readAllText { copyDialog = it }
                    })
                    DropdownMenuItem(text = { Text("Install my key on this server") }, onClick = {
                        menu = false
                        if (Store.keys.value.isEmpty()) say("Create a key in the Keys tab first") else installKey = true
                    })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Bigger text") }, onClick = {
                        menu = false
                        session?.webView?.evaluateJavascript("setFont(${Settings.fontSize(context) + 1})", null)
                    })
                    DropdownMenuItem(text = { Text("Smaller text") }, onClick = {
                        menu = false
                        session?.webView?.evaluateJavascript("setFont(${Settings.fontSize(context) - 1})", null)
                    })
                    DropdownMenuItem(text = { Text("Clear screen") }, onClick = {
                        menu = false
                        session?.webView?.evaluateJavascript("clearTerm()", null)
                    })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Reconnect") }, onClick = { menu = false; session?.reconnect() })
                    DropdownMenuItem(text = { Text("Close session", color = C.Red) }, onClick = {
                        menu = false
                        session?.let { confirmClose = it }
                    })
                }
            }
        }

        // ---- the terminal itself
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (session != null) {
                key(session.id) {
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                val wv = session.webView
                                (wv.parent as? ViewGroup)?.removeView(wv)
                                addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                                wv.post { wv.showKeyboard() }
                            }
                        },
                        onRelease = { container -> container.removeAllViews() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // ---- extra keys
        if (session != null) ExtraKeys(session)
    }

    copyDialog?.let { text ->
        AlertDialog(
            onDismissRequest = { copyDialog = null },
            title = { Text("Select text") },
            text = {
                SelectionContainer {
                    Text(
                        text.ifEmpty { "(nothing on screen)" },
                        fontFamily = Mono,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState(Int.MAX_VALUE)),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { copyText(context, "Terminal", text); copyDialog = null; say("Copied everything") }) {
                    Text("Copy all")
                }
            },
            dismissButton = { TextButton(onClick = { copyDialog = null }) { Text("Done") } },
        )
    }

    if (installKey && session != null) {
        val keys = Store.keys.value
        AlertDialog(
            onDismissRequest = { installKey = false },
            title = { Text("Install a key on this server") },
            text = {
                Column {
                    Text(
                        "This types a command in the terminal that adds the key to ~/.ssh/authorized_keys. " +
                            "After that you can switch this server to key login.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    keys.forEach { k ->
                        Text(
                            "${k.name}  (${k.type})",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    installKey = false
                                    val pub = k.publicKey.replace("'", "")
                                    session.sendText(
                                        "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                                            "grep -qxF '$pub' ~/.ssh/authorized_keys 2>/dev/null || " +
                                            "echo '$pub' >> ~/.ssh/authorized_keys; chmod 600 ~/.ssh/authorized_keys && " +
                                            "echo 'Key installed: ${k.name.replace("'", "")}'\r"
                                    )
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { installKey = false }) { Text("Cancel") } },
        )
    }

    confirmClose?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmClose = null },
            title = { Text("Close ${s.server.name}?") },
            text = { Text("The connection will be ended. Anything running in this terminal stops unless you used tmux or screen.") },
            confirmButton = {
                TextButton(onClick = { SessionManager.close(s.id); confirmClose = null }) { Text("Close", color = C.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmClose = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SessionTab(s: TermSession, selected: Boolean, onClose: () -> Unit) {
    val state by s.state.collectAsState()
    val title by s.title.collectAsState()
    Row(
        Modifier
            .heightIn(min = 32.dp)
            .background(if (selected) C.SurfaceHigh else Color.Transparent, RoundedCornerShape(10.dp))
            .border(1.dp, if (selected) Color(0xFF2B3647) else Color(0xFF1C2433), RoundedCornerShape(10.dp))
            .clickable { SessionManager.select(s.id) }
            .padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(stateColor(state))
        Spacer(Modifier.width(6.dp))
        Text(
            title,
            fontSize = 13.sp,
            color = if (selected) Color(0xFFE6EDF3) else C.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 140.dp),
        )
        Icon(
            Icons.Filled.Close,
            contentDescription = "Close",
            tint = C.Muted,
            modifier = Modifier
                .size(26.dp)
                .clickable(onClick = onClose)
                .padding(5.dp),
        )
    }
}

@Composable
private fun ExtraKeys(session: TermSession) {
    val ctrl by Modifiers.ctrl.collectAsState()
    val alt by Modifiers.alt.collectAsState()
    val esc = "\u001b"
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F1520))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyButton(icon = R.drawable.ic_keyboard, onPress = { session.webView.toggleKeyboard() })
        KeyButton("ESC", onPress = { session.sendText(esc) })
        KeyButton("CTRL", active = ctrl, onPress = { Modifiers.ctrl.value = !ctrl })
        KeyButton("ALT", active = alt, onPress = { Modifiers.alt.value = !alt })
        KeyButton("TAB", onPress = { session.sendText("\t") })
        KeyButton("←", repeat = true, onPress = { session.arrow('D') })
        KeyButton("↓", repeat = true, onPress = { session.arrow('B') })
        KeyButton("↑", repeat = true, onPress = { session.arrow('A') })
        KeyButton("→", repeat = true, onPress = { session.arrow('C') })
        KeyButton("-", onPress = { session.sendText("-") })
        KeyButton("/", onPress = { session.sendText("/") })
        KeyButton("|", onPress = { session.sendText("|") })
        KeyButton("~", onPress = { session.sendText("~") })
        KeyButton("HOME", onPress = { session.sendText("$esc[H") })
        KeyButton("END", onPress = { session.sendText("$esc[F") })
        KeyButton("PGUP", repeat = true, onPress = { session.sendText("$esc[5~") })
        KeyButton("PGDN", repeat = true, onPress = { session.sendText("$esc[6~") })
        KeyButton("^C", onPress = { session.sendText("\u0003") })
        KeyButton("^D", onPress = { session.sendText("\u0004") })
        KeyButton("^Z", onPress = { session.sendText("\u001a") })
    }
}

@Composable
private fun KeyButton(
    label: String? = null,
    icon: Int? = null,
    active: Boolean = false,
    repeat: Boolean = false,
    onPress: () -> Unit,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val action by rememberUpdatedState(onPress)
    Box(
        Modifier
            .heightIn(min = 38.dp)
            .widthIn(min = 44.dp)
            .background(if (active) C.Green.copy(alpha = 0.25f) else C.SurfaceHigh, RoundedCornerShape(8.dp))
            .border(1.dp, if (active) C.Green else Color.Transparent, RoundedCornerShape(8.dp))
            .pointerInput(repeat) {
                detectTapGestures(onPress = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    action()
                    if (repeat) {
                        val job = scope.launch {
                            delay(400)
                            while (true) {
                                action()
                                delay(55)
                            }
                        }
                        tryAwaitRelease()
                        job.cancel()
                    }
                })
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = "Keyboard", tint = Color(0xFFE6EDF3), modifier = Modifier.size(20.dp))
        } else {
            Text(
                label ?: "",
                color = if (active) C.Green else Color(0xFFE6EDF3),
                fontSize = if ((label?.length ?: 0) > 2) 12.sp else 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = Mono,
            )
        }
    }
}
