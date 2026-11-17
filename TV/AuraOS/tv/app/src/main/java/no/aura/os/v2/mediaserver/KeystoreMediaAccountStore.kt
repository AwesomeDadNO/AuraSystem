package no.aura.os.v2.mediaserver

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Server sign-ins (tokens) encrypted with their own non-exportable Android Keystore AES-256-GCM key; only ciphertext reaches
 * SharedPreferences (allowBackup=false keeps it out of backups). One entry per AuraOS profile. Fail closed: an unreadable entry reads as
 * "no accounts", a failed write returns false. Nothing is logged. Decryption is a Keystore call: use [load]/[save] off the main thread.
 */
class KeystoreMediaAccountStore(context: Context) : MediaAccountStore {
    private val prefs = context.getSharedPreferences("aura_v2_media_servers", Context.MODE_PRIVATE)

    override fun has(profileId: String): Boolean = profileId.isNotBlank() && runCatching { prefs.contains(key(profileId)) }.getOrDefault(false)

    override fun load(profileId: String): List<ServerAccount> = runCatching {
        if (profileId.isBlank()) return emptyList()
        val bytes = Base64.decode(prefs.getString(key(profileId), null) ?: return emptyList(), Base64.NO_WRAP)
        if (bytes.size <= 28) return emptyList()
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(false) ?: return emptyList(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        AccountCodec.decode(String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8))
    }.getOrDefault(emptyList())

    override fun save(profileId: String, accounts: List<ServerAccount>): Boolean = runCatching {
        if (profileId.isBlank()) return false
        if (accounts.isEmpty()) return prefs.edit().remove(key(profileId)).commit()
        val text = AccountCodec.encode(accounts)
        if (text.length > AccountCodec.MAX_CHARS) return false
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(true) ?: return false)
        val blob = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key(profileId), Base64.encodeToString(blob, Base64.NO_WRAP)).commit()
    }.getOrDefault(false)

    override fun clear(profileId: String) { if (profileId.isNotBlank()) prefs.edit().remove(key(profileId)).apply() }

    private fun key(profileId: String) = "profile:$profileId"

    private fun secretKey(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }

    companion object {
        private const val ALIAS = "aura_v2_media_servers"
        private const val TRANSFORM = "AES/GCM/NoPadding"

        /**
         * The identifier this installation presents to media servers (Plex requires a stable one). A random UUID created once and kept in
         * ordinary preferences: it is not a secret and not derived from any hardware or account identifier.
         */
        fun deviceId(context: Context): String {
            val p = context.getSharedPreferences("aura_v2_media_device", Context.MODE_PRIVATE)
            p.getString("id", null)?.takeIf { Regex("[a-f0-9-]{36}").matches(it) }?.let { return it }
            return UUID.randomUUID().toString().also { p.edit().putString("id", it).apply() }
        }
    }
}
