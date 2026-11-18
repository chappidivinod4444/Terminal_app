package app.sshterm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.sshterm.ui.EditServerScreen
import app.sshterm.ui.KeysScreen
import app.sshterm.ui.PromptHost
import app.sshterm.ui.ServersScreen
import app.sshterm.ui.SettingsScreen
import app.sshterm.ui.SshTheme
import app.sshterm.ui.TerminalScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

object UiEvents {
    val openTerminal = MutableStateFlow(false)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            SshTheme { Root() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra("openTerminal", false) == true && SessionManager.sessions.value.isNotEmpty()) {
            UiEvents.openTerminal.value = true
        }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data class Edit(val id: String?) : Screen
    data object Terminal : Screen
}

@Composable
private fun Root() {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val say: (String) -> Unit = { msg ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }
    val openTerminal by UiEvents.openTerminal.collectAsState()
    LaunchedEffect(openTerminal) {
        if (openTerminal) {
            screen = Screen.Terminal
            UiEvents.openTerminal.value = false
        }
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun connect(server: Server) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        SessionManager.open(server)
        screen = Screen.Terminal
    }

    PromptHost()

    when (val s = screen) {
        Screen.Terminal -> {
            TerminalScreen(
                onBack = { screen = Screen.Home },
                onNewSession = { screen = Screen.Home; tab = 0 },
                say = say,
            )
            // Snackbars on the terminal screen.
            Box(Modifier.fillMaxSize()) { SnackbarHost(snackbar, Modifier.padding(bottom = 64.dp)) }
        }

        is Screen.Edit -> {
            BackHandler { screen = Screen.Home }
            Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = MaterialTheme.colorScheme.background) { pad ->
                Box(Modifier.padding(pad)) {
                    EditServerScreen(
                        serverId = s.id,
                        onBack = { screen = Screen.Home },
                        onSaved = { server, andConnect ->
                            if (andConnect) connect(server) else {
                                screen = Screen.Home
                                say("Saved ${server.name}")
                            }
                        },
                        onGenerateKey = { screen = Screen.Home; tab = 1 },
                        say = say,
                    )
                }
            }
        }

        Screen.Home -> {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbar) },
                floatingActionButton = {
                    if (tab == 0) {
                        ExtendedFloatingActionButton(
                            onClick = { screen = Screen.Edit(null) },
                            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            text = { Text("Add server") },
                        )
                    }
                },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        NavigationBarItem(
                            selected = tab == 0, onClick = { tab = 0 },
                            icon = { Icon(painterResource(R.drawable.ic_server), contentDescription = null) },
                            label = { Text("Servers") },
                        )
                        NavigationBarItem(
                            selected = tab == 1, onClick = { tab = 1 },
                            icon = { Icon(painterResource(R.drawable.ic_key), contentDescription = null) },
                            label = { Text("Keys") },
                        )
                        NavigationBarItem(
                            selected = tab == 2, onClick = { tab = 2 },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            label = { Text("Settings") },
                        )
                    }
                },
            ) { pad ->
                Box(
                    Modifier
                        .padding(pad)
                        .fillMaxSize()
                ) {
                    when (tab) {
                        0 -> ServersScreen(
                            onConnect = { connect(it) },
                            onEdit = { screen = Screen.Edit(it) },
                            onOpenTerminal = { screen = Screen.Terminal },
                            say = say,
                        )
                        1 -> KeysScreen(say = say)
                        else -> SettingsScreen(say = say)
                    }
                }
            }
        }
    }
}
