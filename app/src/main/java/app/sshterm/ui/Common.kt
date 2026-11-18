package app.sshterm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sshterm.PromptRequest
import app.sshterm.Prompts

object C {
    val Bg = Color(0xFF0B0F17)
    val Surface = Color(0xFF131A26)
    val SurfaceHigh = Color(0xFF1A2333)
    val Green = Color(0xFF4ADE80)
    val Blue = Color(0xFF60A5FA)
    val Yellow = Color(0xFFFACC15)
    val Red = Color(0xFFFF6B6B)
    val Muted = Color(0xFF8B98AD)
}

val Mono = FontFamily.Monospace

private val Colors = darkColorScheme(
    primary = C.Green,
    onPrimary = Color(0xFF052E16),
    primaryContainer = Color(0xFF14532D),
    onPrimaryContainer = Color(0xFFBBF7D0),
    secondary = C.Blue,
    onSecondary = Color(0xFF0B2545),
    secondaryContainer = Color(0xFF1E3A5F),
    onSecondaryContainer = Color(0xFFDBEAFE),
    background = C.Bg,
    onBackground = Color(0xFFE6EDF3),
    surface = C.Bg,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = C.SurfaceHigh,
    onSurfaceVariant = C.Muted,
    surfaceContainerLowest = Color(0xFF080B12),
    surfaceContainerLow = Color(0xFF0F1520),
    surfaceContainer = C.Surface,
    surfaceContainerHigh = C.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF222C3F),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1F2937),
    error = C.Red,
    onError = Color(0xFF3B0A0F),
    errorContainer = Color(0xFF4A1620),
    onErrorContainer = Color(0xFFFFD9DC),
)

@Composable
fun SshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}

@Composable
fun IconBubble(icon: Int, tint: Color, modifier: Modifier = Modifier, size: Int = 42) {
    Box(
        modifier
            .size(size.dp)
            .background(tint.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size((size / 2).dp))
    }
}

@Composable
fun StatusDot(color: Color) {
    Box(
        Modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun MonoText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, modifier = modifier, fontFamily = Mono, fontSize = 13.sp, color = color, maxLines = 1)
}

/** Shows whatever the SSH connection is asking (trust server, password, ...). */
@Composable
fun PromptHost() {
    val req by Prompts.current.collectAsState()
    val r = req ?: return
    var text by remember(r) { mutableStateOf("") }
    val warn = r.title.contains("changed", true)
    AlertDialog(
        onDismissRequest = {},
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (warn) {
                    Text("⚠", color = C.Red, fontSize = 22.sp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(r.title)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val shown = if (r.kind == PromptRequest.Kind.CONFIRM) friendlyHostMessage(r.message) else r.message
                Text(shown, style = MaterialTheme.typography.bodyMedium)
                if (r.kind != PromptRequest.Kind.CONFIRM) {
                    Spacer(Modifier.size(12.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        visualTransformation = if (r.kind == PromptRequest.Kind.SECRET) PasswordVisualTransformation()
                        else androidx.compose.ui.text.input.VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (r.kind == PromptRequest.Kind.SECRET) KeyboardType.Password else KeyboardType.Text,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                r.future.complete(if (r.kind == PromptRequest.Kind.CONFIRM) "yes" else text)
            }) {
                Text(
                    when {
                        r.kind != PromptRequest.Kind.CONFIRM -> "OK"
                        warn -> "Trust new key"
                        else -> "Trust & connect"
                    },
                    color = if (warn) C.Red else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { r.future.complete(null) }) { Text("Cancel") }
        },
    )
}

/** Turns JSch's long host-key text into something readable. */
private fun friendlyHostMessage(raw: String): String {
    val fp = Regex("""fingerprint is\s+(\S+)""", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)
    val type = Regex("""(\S+) key fingerprint""").find(raw)?.groupValues?.get(1)
    val host = Regex("""authenticity of host '([^']+)'""").find(raw)?.groupValues?.get(1)
    if (raw.contains("CHANGED", true)) {
        return "The server's identity key is different from last time. This happens if the server was " +
            "reinstalled, but it can also mean someone is intercepting the connection.\n\n" +
            (if (fp != null) "New fingerprint:\n$fp\n\n" else "") +
            "Only continue if you know why it changed."
    }
    if (fp == null) return raw
    return "First time connecting to ${host ?: "this server"}.\n\n" +
        "${type ?: "Key"} fingerprint:\n$fp\n\n" +
        "If it matches the server, tap Trust. The app will remember it and warn you if it ever changes."
}
