package app.sshterm

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class AuthType { PASSWORD, KEY }

data class Server(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val auth: AuthType = AuthType.PASSWORD,
    /** Encrypted; null when the password should be asked each time. */
    val password: String? = null,
    val keyId: String? = null,
    val startupCommand: String = "",
    val lastUsed: Long = 0,
) {
    val address: String get() = if (port == 22) "$user@$host" else "$user@$host:$port"
}

data class SshKey(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String,
    val publicKey: String,
    /** Encrypted private key bytes (base64 inside). */
    val privateKey: String,
    /** Encrypted passphrase, if the key has one. */
    val passphrase: String? = null,
    val created: Long = System.currentTimeMillis(),
)

/** Encrypts small secrets with a key that never leaves Android's secure key store. */
object Secrets {
    private const val ALIAS = "sshterm_secrets_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(data: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(data)
        return Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun decrypt(text: String): ByteArray? = try {
        val (iv, ct) = text.split(":", limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        c.doFinal(Base64.decode(ct, Base64.NO_WRAP))
    } catch (_: Throwable) {
        null
    }

    fun encryptText(s: String): String = encrypt(s.toByteArray())
    fun decryptText(s: String?): String? = s?.let { decrypt(it)?.toString(Charsets.UTF_8) }
}

object Store {
    private lateinit var dir: File
    val servers = MutableStateFlow<List<Server>>(emptyList())
    val keys = MutableStateFlow<List<SshKey>>(emptyList())

    val knownHosts: File get() = File(dir, "known_hosts")

    fun init(context: Context) {
        dir = context.filesDir
        servers.value = loadServers()
        keys.value = loadKeys()
    }

    // ------------------------------------------------------------- servers

    private fun loadServers(): List<Server> = try {
        val arr = JSONArray(File(dir, "servers.json").readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Server(
                id = o.getString("id"),
                name = o.getString("name"),
                host = o.getString("host"),
                port = o.optInt("port", 22),
                user = o.getString("user"),
                auth = runCatching { AuthType.valueOf(o.optString("auth")) }.getOrDefault(AuthType.PASSWORD),
                password = o.optString("password").takeIf { it.isNotEmpty() },
                keyId = o.optString("keyId").takeIf { it.isNotEmpty() },
                startupCommand = o.optString("startup"),
                lastUsed = o.optLong("lastUsed"),
            )
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun saveServers() {
        val arr = JSONArray()
        for (s in servers.value) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("host", s.host)
                put("port", s.port)
                put("user", s.user)
                put("auth", s.auth.name)
                put("password", s.password ?: "")
                put("keyId", s.keyId ?: "")
                put("startup", s.startupCommand)
                put("lastUsed", s.lastUsed)
            })
        }
        write("servers.json", arr.toString())
    }

    fun upsert(server: Server) {
        val list = servers.value.toMutableList()
        val i = list.indexOfFirst { it.id == server.id }
        if (i >= 0) list[i] = server else list.add(server)
        servers.value = list
        saveServers()
    }

    fun deleteServer(id: String) {
        servers.value = servers.value.filterNot { it.id == id }
        saveServers()
    }

    fun touch(id: String) {
        servers.value.firstOrNull { it.id == id }?.let { upsert(it.copy(lastUsed = System.currentTimeMillis())) }
    }

    fun server(id: String?): Server? = servers.value.firstOrNull { it.id == id }

    // ---------------------------------------------------------------- keys

    private fun loadKeys(): List<SshKey> = try {
        val arr = JSONArray(File(dir, "keys.json").readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SshKey(
                id = o.getString("id"),
                name = o.getString("name"),
                type = o.optString("type"),
                publicKey = o.getString("pub"),
                privateKey = o.getString("prv"),
                passphrase = o.optString("pass").takeIf { it.isNotEmpty() },
                created = o.optLong("created"),
            )
        }
    } catch (_: Throwable) {
        emptyList()
    }

    private fun saveKeys() {
        val arr = JSONArray()
        for (k in keys.value) {
            arr.put(JSONObject().apply {
                put("id", k.id)
                put("name", k.name)
                put("type", k.type)
                put("pub", k.publicKey)
                put("prv", k.privateKey)
                put("pass", k.passphrase ?: "")
                put("created", k.created)
            })
        }
        write("keys.json", arr.toString())
    }

    fun addKey(name: String, type: String, publicKey: String, privateKey: ByteArray, passphrase: String?): SshKey {
        val k = SshKey(
            name = name,
            type = type,
            publicKey = publicKey,
            privateKey = Secrets.encrypt(privateKey),
            passphrase = passphrase?.takeIf { it.isNotEmpty() }?.let { Secrets.encryptText(it) },
        )
        keys.value = keys.value + k
        saveKeys()
        return k
    }

    fun deleteKey(id: String) {
        keys.value = keys.value.filterNot { it.id == id }
        saveKeys()
    }

    fun key(id: String?): SshKey? = keys.value.firstOrNull { it.id == id }

    private fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(dir, name))
    }
}

object Settings {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun fontSize(c: Context) = sp(c).getInt("font", 14)
    fun setFontSize(c: Context, v: Int) = sp(c).edit().putInt("font", v).apply()
    fun keepScreenOn(c: Context) = sp(c).getBoolean("screenOn", true)
    fun setKeepScreenOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("screenOn", v).apply()
    fun vibrate(c: Context) = sp(c).getBoolean("vibrate", true)
    fun setVibrate(c: Context, v: Boolean) = sp(c).edit().putBoolean("vibrate", v).apply()
}
