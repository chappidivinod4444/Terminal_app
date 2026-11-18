package app.sshterm.core

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Executors

/**
 * Plain-JVM SSH layer (no Android classes) so it can be tested against a real
 * SSH server on the build machine.
 */

/** Asks the person something while connecting. Implementations may block. */
interface Prompter {
    /** Yes/no question, e.g. "trust this server's fingerprint?". */
    fun confirm(title: String, message: String): Boolean

    /** Asks for text such as a password. Returns null when cancelled. */
    fun ask(title: String, message: String, secret: Boolean): String?
}

enum class KeyType(val label: String) { ED25519("Ed25519"), RSA("RSA 4096"), ECDSA("ECDSA 256") }

class GeneratedKey(val privateKey: ByteArray, val publicKey: String, val type: String)

class KeyInfo(val publicKey: String, val type: String, val encrypted: Boolean)

data class ConnectSpec(
    val host: String,
    val port: Int,
    val user: String,
    val password: String?,
    val privateKey: ByteArray?,
    val keyPassphrase: String?,
    val knownHostsFile: File,
    val cols: Int,
    val rows: Int,
    val startupCommand: String? = null,
    val connectTimeoutMs: Int = 15_000,
)

object SshSetup {
    @Volatile
    private var done = false

    /** Makes JSch use Bouncy Castle for modern algorithms (needed on Android). */
    fun configure() {
        if (done) return
        synchronized(this) {
            if (done) return
            try {
                val bc = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
                    .getDeclaredConstructor().newInstance() as java.security.Provider
                // Android ships a cut-down "BC" provider; replace it with the full one.
                java.security.Security.removeProvider("BC")
                java.security.Security.addProvider(bc)
            } catch (_: Throwable) {
            }
            setIfPresent("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
            setIfPresent("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
            setIfPresent("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
            setIfPresent("xdh", "com.jcraft.jsch.bc.XDH")
            setIfPresent("chacha20-poly1305@openssh.com", "com.jcraft.jsch.bc.ChaCha20Poly1305")
            // Accept older servers too, but keep modern algorithms first.
            appendConfig("server_host_key", "ssh-rsa")
            appendConfig("PubkeyAcceptedAlgorithms", "ssh-rsa")
            done = true
        }
    }

    private fun setIfPresent(key: String, className: String) {
        try {
            Class.forName(className)
            JSch.setConfig(key, className)
        } catch (_: Throwable) {
        }
    }

    private fun appendConfig(key: String, value: String) {
        val current = JSch.getConfig(key) ?: return
        if (current.split(",").none { it == value }) JSch.setConfig(key, "$current,$value")
    }

    fun generateKey(type: KeyType, comment: String): GeneratedKey {
        configure()
        if (type == KeyType.ED25519) return generateEd25519(comment)
        val jsch = JSch()
        val pair = when (type) {
            KeyType.ED25519 -> error("handled above")
            KeyType.RSA -> KeyPair.genKeyPair(jsch, KeyPair.RSA, 4096)
            KeyType.ECDSA -> KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256)
        }
        try {
            val prv = ByteArrayOutputStream()
            pair.writePrivateKey(prv)
            val pub = ByteArrayOutputStream()
            pair.writePublicKey(pub, comment)
            return GeneratedKey(prv.toByteArray(), pub.toString("UTF-8").trim(), type.label)
        } finally {
            pair.dispose()
        }
    }

    /** Creates an Ed25519 key and writes it in the standard OpenSSH private key format. */
    private fun generateEd25519(comment: String): GeneratedKey {
        val priv = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(java.security.SecureRandom())
        val seed = priv.encoded
        val pub = priv.generatePublicKey().encoded

        fun ByteArrayOutputStream.u32(v: Int) {
            write(v ushr 24 and 0xff); write(v ushr 16 and 0xff); write(v ushr 8 and 0xff); write(v and 0xff)
        }
        fun ByteArrayOutputStream.str(b: ByteArray) { u32(b.size); write(b) }
        fun ByteArrayOutputStream.str(t: String) = str(t.toByteArray())

        val blob = ByteArrayOutputStream().apply { str("ssh-ed25519"); str(pub) }.toByteArray()
        val check = java.security.SecureRandom().nextInt()
        val secret = ByteArrayOutputStream().apply {
            u32(check); u32(check)
            str("ssh-ed25519"); str(pub); str(seed + pub); str(comment)
            var pad = 1
            while (size() % 8 != 0) write(pad++)
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            write("openssh-key-v1".toByteArray()); write(0)
            str("none"); str("none"); str(ByteArray(0))
            u32(1); str(blob); str(secret)
        }.toByteArray()
        val b64 = java.util.Base64.getEncoder().encodeToString(body).chunked(70).joinToString("\n")
        val pem = "-----BEGIN OPENSSH PRIVATE KEY-----\n$b64\n-----END OPENSSH PRIVATE KEY-----\n"
        java.util.Arrays.fill(seed, 0)
        val publicLine = "ssh-ed25519 " + java.util.Base64.getEncoder().encodeToString(blob) + " " + comment
        return GeneratedKey(pem.toByteArray(), publicLine, KeyType.ED25519.label)
    }

    /** Reads a private key. Throws IllegalArgumentException with a friendly message on problems. */
    fun inspectKey(privateKey: ByteArray, passphrase: String?, comment: String): KeyInfo {
        configure()
        val pair = try {
            KeyPair.load(JSch(), privateKey, null)
        } catch (t: Throwable) {
            throw IllegalArgumentException("This doesn't look like a private key the app can read.")
        }
        try {
            val encrypted = pair.isEncrypted
            if (encrypted) {
                if (passphrase.isNullOrEmpty()) throw IllegalArgumentException("This key has a passphrase. Enter it.")
                if (!pair.decrypt(passphrase.toByteArray())) throw IllegalArgumentException("Wrong passphrase.")
            }
            val pub = ByteArrayOutputStream()
            pair.writePublicKey(pub, comment)
            val line = pub.toString("UTF-8").trim()
            return KeyInfo(line, line.substringBefore(' ').removePrefix("ssh-"), encrypted)
        } finally {
            pair.dispose()
        }
    }

    fun friendlyError(t: Throwable): String {
        var e: Throwable? = t
        while (e != null) {
            when (e) {
                is UnknownHostException -> return "Server not found. Check the address and your internet."
                is ConnectException -> return "Connection refused. Check the port and that SSH is running."
                is NoRouteToHostException -> return "Can't reach the server. Check your network."
                is SocketTimeoutException -> return "Timed out. The server didn't answer."
            }
            e = e.cause
        }
        val msg = t.message ?: t.javaClass.simpleName
        return when {
            msg.contains("Auth fail", true) || msg.contains("Auth cancel", true) ->
                "Login failed. Check the username, password or key."
            msg.contains("reject HostKey", true) || msg.contains("HostKey has been changed", true) ->
                "Connection stopped: the server's identity wasn't accepted."
            msg.contains("timeout", true) -> "Timed out. The server didn't answer."
            msg.contains("Algorithm negotiation fail", true) ->
                "This server uses settings the app doesn't support."
            else -> msg
        }
    }
}

/** One interactive shell on a server. */
class SshConnection(
    private val spec: ConnectSpec,
    private val prompter: Prompter,
    private val listener: Listener,
) {
    enum class State { CONNECTING, CONNECTED, CLOSED, FAILED }

    interface Listener {
        fun onOutput(data: ByteArray, length: Int)
        fun onState(state: State, message: String?)
    }

    @Volatile
    private var session: Session? = null

    @Volatile
    private var channel: ChannelShell? = null

    @Volatile
    private var out: OutputStream? = null

    @Volatile
    var state: State = State.CONNECTING
        private set

    @Volatile
    private var closedByUser = false

    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "ssh-writer").apply { isDaemon = true } }

    private fun setState(s: State, message: String? = null) {
        state = s
        listener.onState(s, message)
    }

    /** Connects and starts reading. Blocks until connected or failed; call from a background thread. */
    fun connect() {
        SshSetup.configure()
        setState(State.CONNECTING)
        try {
            if (!spec.knownHostsFile.exists()) {
                spec.knownHostsFile.parentFile?.mkdirs()
                spec.knownHostsFile.createNewFile()
            }
            val jsch = JSch()
            jsch.setKnownHosts(spec.knownHostsFile.absolutePath)
            if (spec.privateKey != null) {
                jsch.addIdentity(
                    "key",
                    spec.privateKey,
                    null,
                    spec.keyPassphrase?.takeIf { it.isNotEmpty() }?.toByteArray(),
                )
            }
            val s = jsch.getSession(spec.user, spec.host, spec.port)
            s.userInfo = Asker()
            if (!spec.password.isNullOrEmpty()) s.setPassword(spec.password)
            s.setConfig("StrictHostKeyChecking", "ask")
            s.setConfig("HashKnownHosts", "no")
            s.setConfig(
                "PreferredAuthentications",
                if (spec.privateKey != null) "publickey,keyboard-interactive,password"
                else "keyboard-interactive,password,publickey",
            )
            s.setServerAliveInterval(15_000)
            s.setServerAliveCountMax(4)
            session = s
            s.connect(spec.connectTimeoutMs)

            val ch = s.openChannel("shell") as ChannelShell
            ch.setPtyType("xterm-256color", spec.cols.coerceAtLeast(20), spec.rows.coerceAtLeast(5), 0, 0)
            ch.setEnv("COLORTERM", "truecolor")
            val input = ch.inputStream
            out = ch.outputStream
            channel = ch
            ch.connect(10_000)
            setState(State.CONNECTED)
            startReader(input)
            spec.startupCommand?.trim()?.takeIf { it.isNotEmpty() }?.let { write((it + "\r").toByteArray()) }
        } catch (t: Throwable) {
            cleanup()
            if (closedByUser) setState(State.CLOSED) else setState(State.FAILED, SshSetup.friendlyError(t))
        }
    }

    private fun startReader(input: InputStream) {
        Thread({
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) listener.onOutput(buf, n)
                }
            } catch (_: Throwable) {
            }
            val exit = channel?.exitStatus ?: -1
            cleanup()
            if (state != State.FAILED) {
                setState(State.CLOSED, if (closedByUser) null else if (exit >= 0) "Session ended (exit $exit)" else "Connection closed")
            }
        }, "ssh-reader").apply { isDaemon = true }.start()
    }

    fun write(data: ByteArray) {
        val o = out ?: return
        writer.execute {
            try {
                o.write(data)
                o.flush()
            } catch (_: Throwable) {
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        val ch = channel ?: return
        writer.execute {
            try {
                ch.setPtySize(cols, rows, 0, 0)
            } catch (_: Throwable) {
            }
        }
    }

    fun disconnect() {
        closedByUser = true
        Thread { cleanup() }.start()
    }

    private fun cleanup() {
        try {
            channel?.disconnect()
        } catch (_: Throwable) {
        }
        try {
            session?.disconnect()
        } catch (_: Throwable) {
        }
        channel = null
        session = null
        out = null
    }

    /** Answers JSch's questions through the [Prompter]. */
    private inner class Asker : UserInfo, UIKeyboardInteractive {
        private var password: String? = spec.password
        private var passphrase: String? = spec.keyPassphrase
        private var triedSaved = false

        override fun getPassphrase(): String? = passphrase
        override fun getPassword(): String? = password

        override fun promptPassword(message: String?): Boolean {
            if (!password.isNullOrEmpty() && !triedSaved) {
                triedSaved = true
                return true
            }
            val p = prompter.ask("Password", "Password for ${spec.user}@${spec.host}", true) ?: return false
            password = p
            return true
        }

        override fun promptPassphrase(message: String?): Boolean {
            if (!passphrase.isNullOrEmpty()) return true
            val p = prompter.ask("Key passphrase", message ?: "Passphrase for your key", true) ?: return false
            passphrase = p
            return true
        }

        override fun promptYesNo(message: String?): Boolean {
            val text = message ?: return false
            val changed = text.contains("HAS CHANGED", true) || text.contains("changed", true)
            return prompter.confirm(
                if (changed) "Server identity changed!" else "New server",
                text,
            )
        }

        override fun showMessage(message: String?) {}

        override fun promptKeyboardInteractive(
            destination: String?,
            name: String?,
            instruction: String?,
            prompt: Array<out String>?,
            echo: BooleanArray?,
        ): Array<String>? {
            val prompts = prompt ?: return arrayOf()
            val answers = ArrayList<String>()
            for (i in prompts.indices) {
                val q = prompts[i]
                val isPassword = q.contains("password", true) && (echo == null || !echo[i])
                if (isPassword && !password.isNullOrEmpty() && !triedSaved) {
                    triedSaved = true
                    answers.add(password!!)
                    continue
                }
                val title = name?.takeIf { it.isNotBlank() } ?: "Server question"
                val text = listOfNotNull(instruction?.takeIf { it.isNotBlank() }, q.trim()).joinToString("\n")
                val a = prompter.ask(title, text, echo == null || !echo[i]) ?: return null
                if (isPassword) password = a
                answers.add(a)
            }
            return answers.toTypedArray()
        }
    }
}
