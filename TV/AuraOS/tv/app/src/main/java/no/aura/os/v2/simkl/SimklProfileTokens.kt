package no.aura.os.v2.simkl

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Simkl user tokens are NOT the public client id. They are encrypted independently of UltraMax
 * using a non-exportable Android Keystore AES-GCM key. A token belongs to one AuraOS profile.
 * No user token, authentication code or secret is ever logged.
 */
class SimklProfileTokens(context: Context) {
    private val prefs = context.getSharedPreferences("aura_v2_simkl_tokens", Context.MODE_PRIVATE)

    fun has(profileId: String): Boolean = profileId.isNotBlank() && prefs.contains(prefKey(profileId))

    fun get(profileId: String): String? = runCatching {
        if (profileId.isBlank()) return null
        val raw = prefs.getString(prefKey(profileId), null) ?: return null
        val bytes = Base64.decode(raw, Base64.NO_WRAP)
        if (bytes.size <= 28) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(false) ?: return null, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8).takeIf { it.length in 20..1024 }
    }.getOrNull()

    fun save(profileId: String, token: String): Boolean = synchronized(mutationLock) { runCatching {
        if (profileId.isBlank() || token.length !in 20..1024) return false
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey(true) ?: return false)
        val blob = cipher.iv + cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(prefKey(profileId), Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
    }.getOrDefault(false) }

    fun clear(profileId: String) = synchronized(mutationLock) {
        if (profileId.isNotBlank()) prefs.edit().remove(prefKey(profileId)).apply()
    }

    private fun prefKey(id: String) = "profile:$id"

    private fun getKey(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }

    companion object {
        internal val mutationLock = Any()
        private const val ALIAS = "aura_v2_simkl_tokens"
    }
}
