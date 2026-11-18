package app.sshterm

import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.util.Base64
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.sshterm.core.ConnectSpec
import app.sshterm.core.Prompter
import app.sshterm.core.SshConnection
import app.sshterm.core.SshSetup
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        SessionManager.init(this)
        Thread { SshSetup.configure() }.start()
    }
}

// ------------------------------------------------------------------ prompts

class PromptRequest(
    val title: String,
    val message: String,
    val kind: Kind,
    val future: CompletableFuture<String?> = CompletableFuture(),
) {
    enum class Kind { CONFIRM, TEXT, SECRET }
}

/** Shows questions from SSH (trust this server? password?) as dialogs and waits for the answer. */
object Prompts : Prompter {
    val current = MutableStateFlow<PromptRequest?>(null)
    private val lock = Any()

    private fun askUi(req: PromptRequest): String? = synchronized(lock) {
        current.value = req
        try {
            req.future.get(5, TimeUnit.MINUTES)
        } catch (_: Throwable) {
            null
        } finally {
            current.value = null
        }
    }

    override fun confirm(title: String, message: String): Boolean =
        askUi(PromptRequest(title, message, PromptRequest.Kind.CONFIRM)) == "yes"

    override fun ask(title: String, message: String, secret: Boolean): String? =
        askUi(PromptRequest(title, message, if (secret) PromptRequest.Kind.SECRET else PromptRequest.Kind.TEXT))
}

// ---------------------------------------------------------- extra keys state

object Modifiers {
    val ctrl = MutableStateFlow(false)
    val alt = MutableStateFlow(false)

    fun reset() {
        ctrl.value = false
        alt.value = false
    }
}

// ----------------------------------------------------------------- session

class TermSession(val server: Server, private val app: Context, private val oneTimePassword: String?) {
    val id: String = UUID.randomUUID().toString()
    val state = MutableStateFlow(SshConnection.State.CONNECTING)
    val title = MutableStateFlow(server.name)
    val webView: TermWebView

    private val main = Handler(Looper.getMainLooper())
    private val pending = ByteArrayOutputStream()
    private var flushScheduled = false
    private var pageReady = false
    private var started = false
    private var cols = 80
    private var rows = 24

    @Volatile
    private var conn: SshConnection? = null

    init {
        webView = TermWebView(MutableContextWrapper(app), this)
    }

    // ---- called from the page

    fun onPageReady() {
        pageReady = true
        scheduleFlush()
        if (!started) {
            started = true
            connect()
        }
    }

    fun onResize(c: Int, r: Int) {
        if (c <= 0 || r <= 0) return
        cols = c
        rows = r
        conn?.resize(c, r)
    }

    fun onTitle(t: String) {
        title.value = t.ifBlank { server.name }
    }

    // ---- connection

    fun connect() {
        val s = server
        local("\u001b[90mConnecting to ${s.address}…\u001b[0m\r\n")
        state.value = SshConnection.State.CONNECTING
        Thread {
            val key = if (s.auth == AuthType.KEY) Store.key(s.keyId) else null
            if (s.auth == AuthType.KEY && key == null) {
                fail("The key for this server was deleted. Edit the server and pick a key.")
                return@Thread
            }
            val spec = ConnectSpec(
                host = s.host,
                port = s.port,
                user = s.user,
                password = oneTimePassword ?: Secrets.decryptText(s.password),
                privateKey = key?.let { Secrets.decrypt(it.privateKey) },
                keyPassphrase = key?.let { Secrets.decryptText(it.passphrase) },
                knownHostsFile = Store.knownHosts,
                cols = cols,
                rows = rows,
                startupCommand = s.startupCommand,
            )
            val c = SshConnection(spec, Prompts, object : SshConnection.Listener {
                override fun onOutput(data: ByteArray, length: Int) = feed(data, length)
                override fun onState(st: SshConnection.State, message: String?) {
                    state.value = st
                    when (st) {
                        SshConnection.State.FAILED ->
                            local("\r\n\u001b[31m✖ ${message ?: "Connection failed"}\u001b[0m\r\n\u001b[90mPress Enter to try again.\u001b[0m\r\n")
                        SshConnection.State.CLOSED ->
                            local("\r\n\u001b[33m${message ?: "Disconnected"}.\u001b[0m \u001b[90mPress Enter to reconnect.\u001b[0m\r\n")
                        else -> Unit
                    }
                    SessionManager.changed()
                }
            })
            conn = c
            if (s.id.isNotEmpty()) Store.touch(s.id)
            c.connect()
        }.start()
    }

    private fun fail(message: String) {
        state.value = SshConnection.State.FAILED
        local("\r\n\u001b[31m✖ $message\u001b[0m\r\n")
        SessionManager.changed()
    }

    fun reconnect() {
        conn?.disconnect()
        conn = null
        local("\r\n")
        connect()
    }

    fun disconnect() {
        conn?.disconnect()
    }

    val isLive: Boolean
        get() = state.value == SshConnection.State.CONNECTED || state.value == SshConnection.State.CONNECTING

    // ---- input from the keyboard / extra keys

    fun sendText(text: String) {
        if (text.isEmpty()) return
        if (!isLive) {
            if (text.contains('\r') || text.contains('\n')) reconnect()
            return
        }
        var t = text.replace("\r\n", "\r").replace('\n', '\r')
        val ctrl = Modifiers.ctrl.value
        val alt = Modifiers.alt.value
        if (ctrl) t = t.map { ctrlChar(it) }.joinToString("")
        if (alt) t = "\u001b" + t
        if (ctrl || alt) Modifiers.reset()
        conn?.write(t.toByteArray(Charsets.UTF_8))
        webView.post { webView.evaluateJavascript("scrollBottom()", null) }
    }

    /** Raw bytes that bypass modifiers (terminal replies, arrows, paste). */
    fun sendRaw(bytes: ByteArray) {
        if (state.value == SshConnection.State.CONNECTED) conn?.write(bytes)
    }

    fun arrow(dir: Char, extraMod: Int = 0) {
        var mod = 1 + extraMod
        if (Modifiers.alt.value) mod += 2
        if (Modifiers.ctrl.value) mod += 4
        if (Modifiers.ctrl.value || Modifiers.alt.value) Modifiers.reset()
        val m = if (mod > 1) mod.toString() else "0"
        webView.evaluateJavascript("arrow('$dir', $m)", null)
    }

    fun paste(text: String) {
        val quoted = org.json.JSONObject.quote(text)
        webView.evaluateJavascript("pasteText($quoted)", null)
    }

    fun readAllText(callback: (String) -> Unit) {
        webView.evaluateJavascript("allText()") { result ->
            val text = try {
                JSONTokener(result).nextValue() as? String ?: ""
            } catch (_: Throwable) {
                ""
            }
            callback(text)
        }
    }

    // ---- output to the screen

    private fun local(text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        feed(b, b.size)
    }

    private fun feed(data: ByteArray, length: Int) {
        synchronized(pending) {
            pending.write(data, 0, length)
            if (pending.size() > 8 * 1024 * 1024) {
                // Far too much unseen output: keep the newest part.
                val all = pending.toByteArray()
                pending.reset()
                pending.write(all, all.size - 4 * 1024 * 1024, 4 * 1024 * 1024)
            }
        }
        main.post { scheduleFlush() }
    }

    private fun scheduleFlush() {
        if (flushScheduled || !pageReady) return
        flushScheduled = true
        main.postDelayed({ flush() }, 12)
    }

    private fun flush() {
        flushScheduled = false
        val chunk: ByteArray
        var more = false
        synchronized(pending) {
            if (pending.size() == 0) return
            val all = pending.toByteArray()
            val max = 192 * 1024
            if (all.size > max) {
                chunk = all.copyOfRange(0, max)
                pending.reset()
                pending.write(all, max, all.size - max)
                more = true
            } else {
                chunk = all
                pending.reset()
            }
        }
        webView.evaluateJavascript("w('" + Base64.encodeToString(chunk, Base64.NO_WRAP) + "')", null)
        if (more) scheduleFlush()
    }

    fun destroy() {
        disconnect()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    companion object {
        fun ctrlChar(c: Char): Char = when (c) {
            in 'a'..'z' -> (c - 'a' + 1).toChar()
            in 'A'..'Z' -> (c - 'A' + 1).toChar()
            ' ', '@', '2' -> 0.toChar()
            '[', '3' -> 27.toChar()
            '\\', '4' -> 28.toChar()
            ']', '5' -> 29.toChar()
            '^', '6' -> 30.toChar()
            '_', '-', '7' -> 31.toChar()
            '?', '8' -> 127.toChar()
            else -> c
        }
    }
}

// ------------------------------------------------------------------ webview

/** The terminal screen: xterm.js inside a WebView, with keyboard input handled natively. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class TermWebView(context: Context, private val session: TermSession) : WebView(context) {

    init {
        setBackgroundColor(0xFF0B0F17.toInt())
        isFocusable = true
        isFocusableInTouchMode = true
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportZoom(false)
        settings.textZoom = 100
        addJavascriptInterface(Bridge(), "Bridge")
        loadUrl("file:///android_asset/term/index.html")
    }

    fun showKeyboard() {
        requestFocus()
        val imm = context.getSystemService(InputMethodManager::class.java)
        imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun toggleKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        val insets = rootWindowInsets
        val visible = insets != null && Build.VERSION.SDK_INT >= 30 &&
            insets.isVisible(android.view.WindowInsets.Type.ime())
        if (visible) imm.hideSoftInputFromWindow(windowToken, 0) else showKeyboard()
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Plain text keyboard without autocorrect or suggestions, like a real terminal.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                sendEditable()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                sendEditable()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val e = editable
                if (e != null && e.isNotEmpty()) return super.deleteSurroundingText(beforeLength, afterLength)
                repeat(beforeLength.coerceAtLeast(1).coerceAtMost(64)) { session.sendText("\u007f") }
                return true
            }

            private fun sendEditable() {
                val e = editable ?: return
                val text = e.toString()
                e.clear()
                if (text.isNotEmpty()) session.sendText(text)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_VOLUME_UP ||
            code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_MULTIPLE && code == KeyEvent.KEYCODE_UNKNOWN) {
            event.characters?.let { session.sendText(it) }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        handleKey(event)
        return true
    }

    private fun handleKey(e: KeyEvent) {
        val shift = e.isShiftPressed
        val hwCtrl = e.isCtrlPressed
        val hwAlt = e.isAltPressed
        val esc = "\u001b"
        val seq: String? = when (e.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007f"
            KeyEvent.KEYCODE_FORWARD_DEL -> "$esc[3~"
            KeyEvent.KEYCODE_TAB -> if (shift) "$esc[Z" else "\t"
            KeyEvent.KEYCODE_ESCAPE -> esc
            KeyEvent.KEYCODE_MOVE_HOME -> "$esc[H"
            KeyEvent.KEYCODE_MOVE_END -> "$esc[F"
            KeyEvent.KEYCODE_PAGE_UP -> "$esc[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "$esc[6~"
            KeyEvent.KEYCODE_INSERT -> "$esc[2~"
            KeyEvent.KEYCODE_F1 -> "${esc}OP"
            KeyEvent.KEYCODE_F2 -> "${esc}OQ"
            KeyEvent.KEYCODE_F3 -> "${esc}OR"
            KeyEvent.KEYCODE_F4 -> "${esc}OS"
            KeyEvent.KEYCODE_F5 -> "$esc[15~"
            KeyEvent.KEYCODE_F6 -> "$esc[17~"
            KeyEvent.KEYCODE_F7 -> "$esc[18~"
            KeyEvent.KEYCODE_F8 -> "$esc[19~"
            KeyEvent.KEYCODE_F9 -> "$esc[20~"
            KeyEvent.KEYCODE_F10 -> "$esc[21~"
            KeyEvent.KEYCODE_F11 -> "$esc[23~"
            KeyEvent.KEYCODE_F12 -> "$esc[24~"
            else -> null
        }
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val d = when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> 'A'
                    KeyEvent.KEYCODE_DPAD_DOWN -> 'B'
                    KeyEvent.KEYCODE_DPAD_RIGHT -> 'C'
                    else -> 'D'
                }
                var extra = 0
                if (shift) extra += 1
                if (hwAlt) extra += 2
                if (hwCtrl) extra += 4
                session.arrow(d, extra)
                return
            }
        }
        if (seq != null) {
            if (hwAlt) Modifiers.alt.value = true
            session.sendText(seq)
            return
        }
        val meta = e.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val ch = e.getUnicodeChar(meta)
        if (ch == 0) return
        if (hwCtrl) Modifiers.ctrl.value = true
        if (hwAlt) Modifiers.alt.value = true
        session.sendText(String(Character.toChars(ch)))
    }

    /** Calls from the terminal page (on a background thread). */
    private inner class Bridge {
        private val main = Handler(Looper.getMainLooper())

        @JavascriptInterface
        fun input(data: String) = session.sendRaw(data.toByteArray(Charsets.UTF_8))

        @JavascriptInterface
        fun binary(data: String) = session.sendRaw(ByteArray(data.length) { data[it].code.toByte() })

        @JavascriptInterface
        fun resize(cols: Int, rows: Int) {
            main.post { session.onResize(cols, rows) }
        }

        @JavascriptInterface
        fun title(t: String) {
            session.onTitle(t)
        }

        @JavascriptInterface
        fun tap() {
            main.post { showKeyboard() }
        }

        @JavascriptInterface
        fun ready() {
            main.post { session.onPageReady() }
        }

        @JavascriptInterface
        fun bell() {
            if (!Settings.vibrate(context)) return
            try {
                val v = context.getSystemService(Vibrator::class.java)
                v?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
            } catch (_: Throwable) {
            }
        }

        @JavascriptInterface
        fun openLink(url: String) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) return
            main.post {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Throwable) {
                }
            }
        }

        @JavascriptInterface
        fun fontChanged(size: Int) = Settings.setFontSize(context, size)

        @JavascriptInterface
        fun initialFontSize(): String = Settings.fontSize(context).toString()
    }
}

// ----------------------------------------------------------------- manager

object SessionManager {
    private lateinit var app: Context
    val sessions = MutableStateFlow<List<TermSession>>(emptyList())
    val currentId = MutableStateFlow<String?>(null)
    val version = MutableStateFlow(0)

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun current(): TermSession? = sessions.value.firstOrNull { it.id == currentId.value }

    fun open(server: Server, oneTimePassword: String? = null): TermSession {
        val s = TermSession(server, app, oneTimePassword)
        sessions.value = sessions.value + s
        currentId.value = s.id
        SessionService.update(app)
        return s
    }

    fun select(id: String) {
        currentId.value = id
    }

    fun close(id: String) {
        val s = sessions.value.firstOrNull { it.id == id } ?: return
        s.destroy()
        val rest = sessions.value.filterNot { it.id == id }
        sessions.value = rest
        if (currentId.value == id) currentId.value = rest.lastOrNull()?.id
        SessionService.update(app)
    }

    fun closeAll() {
        for (s in sessions.value.toList()) close(s.id)
    }

    fun changed() {
        version.value = version.value + 1
        SessionService.update(app)
    }
}

// ----------------------------------------------------------------- service

/** Keeps open sessions alive while the app is in the background. */
class SessionService : Service() {
    companion object {
        private const val CH = "sessions"
        private const val NID = 1
        private const val ACTION_CLOSE_ALL = "app.sshterm.CLOSE_ALL"

        fun update(c: Context) {
            val main = Handler(Looper.getMainLooper())
            main.post {
                val count = SessionManager.sessions.value.size
                try {
                    if (count > 0) {
                        ContextCompat.startForegroundService(c, Intent(c, SessionService::class.java))
                    } else {
                        c.stopService(Intent(c, SessionService::class.java))
                    }
                } catch (_: Throwable) {
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, "Open sessions", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE_ALL) {
            SessionManager.closeAll()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NID, notification(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (_: Throwable) {
            stopSelf()
        }
        if (SessionManager.sessions.value.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val list = SessionManager.sessions.value
        val live = list.count { it.state.value == SshConnection.State.CONNECTED }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra("openTerminal", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val closeAll = PendingIntent.getService(
            this, 1,
            Intent(this, SessionService::class.java).setAction(ACTION_CLOSE_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val names = list.joinToString { it.server.name }
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle(
                if (list.size == 1) "1 session open" else "${list.size} sessions open"
            )
            .setContentText(if (live == list.size) names else "$live connected · $names")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFF4ADE80.toInt())
            .setContentIntent(open)
            .addAction(0, "Disconnect all", closeAll)
            .build()
    }
}
