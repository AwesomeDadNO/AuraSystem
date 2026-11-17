package no.aura.os.v2.ultramax

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the UltraMax code lives. Never returns the code for display: callers only get an [UltraCode] to send it. */
interface UltraCodeStore {
    fun load(): UltraCode?
    fun save(code: UltraCode): Boolean
    fun clear()
    val isSet: Boolean get() = load() != null
}

class MemoryUltraCodeStore(private var code: UltraCode? = null) : UltraCodeStore {
    override fun load() = code
    override fun save(code: UltraCode): Boolean { this.code = code; return true }
    override fun clear() { code = null }
    override fun toString() = "MemoryUltraCodeStore(${if (code == null) "empty" else "set"})"
}

/**
 * AES-256-GCM with a non-exportable AndroidKeyStore key; only the ciphertext is in SharedPreferences (excluded from backup: allowBackup=false).
 * Fail closed: any keystore/decrypt problem reads as "no code" and a failed save returns false. Nothing is logged.
 */
class KeystoreUltraCodeStore(private val prefs: SharedPreferences) : UltraCodeStore {
    constructor(context: Context) : this(context.getSharedPreferences("aura_v2_ultramax", Context.MODE_PRIVATE))

    override fun load(): UltraCode? = runCatching {
        val raw = prefs.getString(KEY_BLOB, null) ?: return null
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        if (bytes.size < 13) return null
        val c = Cipher.getInstance(TRANSFORM); c.init(Cipher.DECRYPT_MODE, key(false) ?: return null, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        UltraCode.parse(String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8))
    }.getOrNull()

    override fun save(code: UltraCode): Boolean = runCatching {
        val c = Cipher.getInstance(TRANSFORM); c.init(Cipher.ENCRYPT_MODE, key(true) ?: return false)
        val enc = c.doFinal(code.value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(c.iv + enc, Base64.NO_WRAP)).commit()
    }.getOrDefault(false)

    override fun clear() { prefs.edit().remove(KEY_BLOB).apply() }

    /**
     * Presence of the ciphertext only: no Keystore IPC and no decryption, so it is safe on the main thread (startup, Settings composition).
     * A blob that can no longer be decrypted reads as "set" here; the real answer arrives with the first resolve/verify, which fails closed.
     */
    override val isSet: Boolean get() = runCatching { prefs.contains(KEY_BLOB) }.getOrDefault(false)

    private fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return g.generateKey()
    }
    private companion object { const val ALIAS = "aura_v2_ultramax"; const val KEY_BLOB = "blob"; const val TRANSFORM = "AES/GCM/NoPadding" }
}
