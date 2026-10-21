package dev.minicam.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class CredentialKey(val id: String) {
    SESSION_COOKIE("minicam_session"),
    ACCESS_TOKEN("cf_access_token"),
    SERVER_URL("server_url"),
}

/**
 * Where the session cookie and the Cloudflare Access token live.
 * The password is never stored (docs/SECURITY.md).
 */
interface CredentialStore {
    fun get(key: CredentialKey): String?
    fun set(key: CredentialKey, value: String?)
    fun clearSession() = set(CredentialKey.SESSION_COOKIE, null)
}

class InMemoryCredentialStore : CredentialStore {
    private val values = mutableMapOf<CredentialKey, String>()

    @Synchronized
    override fun get(key: CredentialKey): String? = values[key]

    @Synchronized
    override fun set(key: CredentialKey, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/**
 * SharedPreferences holding AES-GCM ciphertext; the key never leaves the
 * Android Keystore. Excluded from backups (data_extraction_rules.xml).
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    @Synchronized
    override fun get(key: CredentialKey): String? {
        val stored = prefs.getString(key.id, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    override fun set(key: CredentialKey, value: String?) {
        if (value == null) {
            prefs.edit().remove(key.id).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key.id, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "minicam-credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
