package app.sshterm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sshterm.SessionManager
import app.sshterm.Settings
import app.sshterm.Store

@Composable
fun SettingsScreen(say: (String) -> Unit) {
    val context = LocalContext.current
    var font by remember { mutableFloatStateOf(Settings.fontSize(context).toFloat()) }
    var screenOn by remember { mutableStateOf(Settings.keepScreenOn(context)) }
    var vibrate by remember { mutableStateOf(Settings.vibrate(context)) }
    var confirmForget by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle("Settings")
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("Text size: ${font.toInt()}", fontWeight = FontWeight.SemiBold)
                Text(
                    "user@server:~$ ls -la",
                    fontFamily = Mono,
                    fontSize = font.sp,
                    color = C.Green,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                Slider(
                    value = font,
                    onValueChange = { font = it },
                    onValueChangeFinished = {
                        Settings.setFontSize(context, font.toInt())
                        SessionManager.sessions.value.forEach { s ->
                            s.webView.evaluateJavascript("setFont(${font.toInt()})", null)
                        }
                    },
                    valueRange = 8f..28f,
                    steps = 19,
                )
                Text(
                    "Tip: pinch the terminal with two fingers to zoom.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column {
                ToggleRow("Keep screen on", "While a terminal is open", screenOn) {
                    screenOn = it
                    Settings.setKeepScreenOn(context, it)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ToggleRow("Vibrate on bell", "When the server rings the terminal bell", vibrate) {
                    vibrate = it
                    Settings.setVibrate(context, it)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Trusted servers", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Forget remembered server fingerprints",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { confirmForget = true }) { Text("Reset", color = C.Red) }
                }
            }
        }
        Text(
            "SSH Terminal · terminal by xterm.js · SSH by JSch",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget trusted servers?") },
            text = { Text("You'll be asked to check each server's fingerprint again next time you connect.") },
            confirmButton = {
                TextButton(onClick = {
                    Store.knownHosts.writeText("")
                    confirmForget = false
                    say("Trusted servers cleared")
                }) { Text("Forget", color = C.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
