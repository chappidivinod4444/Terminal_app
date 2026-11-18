package app.sshterm

import app.sshterm.core.ConnectSpec
import app.sshterm.core.KeyType
import app.sshterm.core.Prompter
import app.sshterm.core.SshConnection
import app.sshterm.core.SshSetup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real logins against an SSH server started by the build workflow.
 * Skipped when SSH_TEST_HOST is not set (for example on a laptop).
 */
class SshCoreTest {
    private val host = System.getenv("SSH_TEST_HOST").orEmpty()
    private val port = (System.getenv("SSH_TEST_PORT") ?: "2222").toInt()
    private val user = System.getenv("SSH_TEST_USER").orEmpty()
    private val password = System.getenv("SSH_TEST_PASSWORD").orEmpty()
    private val authorizedKeys = File(System.getenv("SSH_TEST_AUTHORIZED_KEYS") ?: "/nonexistent")
    private val knownHosts = File(Files.createTempDirectory("kh").toFile(), "known_hosts")

    private class CountingPrompter(private val password: String?) : Prompter {
        var confirms = 0
        var asks = 0
        override fun confirm(title: String, message: String): Boolean {
            confirms++
            println("PROMPT confirm: $title / ${message.lines().firstOrNull()}")
            return true
        }

        override fun ask(title: String, message: String, secret: Boolean): String? {
            asks++
            println("PROMPT ask: $title / $message")
            return password
        }
    }

    /** Connects, runs one command and returns everything the server printed. */
    private fun runShell(spec: ConnectSpec, prompter: Prompter, command: String, expect: String): String {
        val output = StringBuilder()
        val done = CountDownLatch(1)
        var failure: String? = null
        val conn = SshConnection(spec, prompter, object : SshConnection.Listener {
            override fun onOutput(data: ByteArray, length: Int) {
                synchronized(output) { output.append(String(data, 0, length)) }
                if (synchronized(output) { output.contains(expect) }) done.countDown()
            }

            override fun onState(state: SshConnection.State, message: String?) {
                println("STATE $state ${message ?: ""}")
                if (state == SshConnection.State.FAILED) {
                    failure = message
                    done.countDown()
                }
            }
        })
        conn.connect()
        if (conn.state == SshConnection.State.CONNECTED) conn.write((command + "\r").toByteArray())
        done.await(20, TimeUnit.SECONDS)
        conn.disconnect()
        assertEquals("connection failed: $failure", null, failure)
        val text = synchronized(output) { output.toString() }
        assertTrue("expected '$expect' in output:\n$text", text.contains(expect))
        return text
    }

    private fun spec(password: String?, key: ByteArray?, passphrase: String? = null) = ConnectSpec(
        host = host, port = port, user = user, password = password, privateKey = key,
        keyPassphrase = passphrase, knownHostsFile = knownHosts, cols = 100, rows = 30,
    )

    @Test
    fun passwordLoginAndKnownHosts() {
        assumeTrue(host.isNotEmpty())
        val p1 = CountingPrompter(password)
        runShell(spec(password, null), p1, "echo pw-ok-\$((40+2))", "pw-ok-42")
        assertEquals("first connect asks to trust the server", 1, p1.confirms)

        val p2 = CountingPrompter(password)
        runShell(spec(password, null), p2, "echo again-\$((1+1))", "again-2")
        assertEquals("second connect remembers the server", 0, p2.confirms)
    }

    @Test
    fun passwordPromptedWhenNotSaved() {
        assumeTrue(host.isNotEmpty())
        val p = CountingPrompter(password)
        runShell(spec(null, null), p, "echo asked-\$((2+3))", "asked-5")
        assertTrue("password was asked for", p.asks >= 1)
    }

    @Test
    fun wrongPasswordGivesFriendlyError() {
        assumeTrue(host.isNotEmpty())
        var message: String? = null
        val conn = SshConnection(spec("definitely-wrong", null), object : Prompter {
            override fun confirm(title: String, message: String) = true
            override fun ask(title: String, message: String, secret: Boolean): String? = null
        }, object : SshConnection.Listener {
            override fun onOutput(data: ByteArray, length: Int) {}
            override fun onState(state: SshConnection.State, msg: String?) {
                if (state == SshConnection.State.FAILED) message = msg
            }
        })
        conn.connect()
        println("wrong password message: $message")
        assertEquals(SshConnection.State.FAILED, conn.state)
        assertTrue(message!!.startsWith("Login failed"))
    }

    private fun keyLogin(type: KeyType) {
        assumeTrue(host.isNotEmpty())
        val key = SshSetup.generateKey(type, "test-${type.name}")
        println("generated ${type.label}: ${key.publicKey.take(40)}...")
        val info = SshSetup.inspectKey(key.privateKey, null, "again")
        assertTrue(info.publicKey.startsWith(key.publicKey.substringBefore(' ')))
        authorizedKeys.parentFile.mkdirs()
        authorizedKeys.appendText(key.publicKey + "\n")
        val p = CountingPrompter(null)
        runShell(spec(null, key.privateKey), p, "echo key-${type.name}-\$((3*3))", "key-${type.name}-9")
        assertEquals("no password needed with a key", 0, p.asks)
    }

    @Test
    fun ed25519KeyLogin() = keyLogin(KeyType.ED25519)

    @Test
    fun rsaKeyLogin() = keyLogin(KeyType.RSA)

    @Test
    fun ecdsaKeyLogin() = keyLogin(KeyType.ECDSA)

    @Test
    fun importsOpenSshKeyWithPassphrase() {
        val path = System.getenv("SSH_TEST_PROTECTED_KEY") ?: ""
        assumeTrue(path.isNotEmpty())
        val bytes = File(path).readBytes()
        val info = SshSetup.inspectKey(bytes, "secret-pass", "imported")
        assertTrue(info.encrypted)
        assertTrue(info.publicKey.startsWith("ssh-ed25519 "))
        authorizedKeys.appendText(info.publicKey + "\n")
        val p = CountingPrompter(null)
        runShell(spec(null, bytes, "secret-pass"), p, "echo imported-\$((5+5))", "imported-10")
        var wrong = false
        try {
            SshSetup.inspectKey(bytes, "nope", "x")
        } catch (e: IllegalArgumentException) {
            wrong = e.message == "Wrong passphrase."
        }
        assertTrue(wrong)
    }
}
