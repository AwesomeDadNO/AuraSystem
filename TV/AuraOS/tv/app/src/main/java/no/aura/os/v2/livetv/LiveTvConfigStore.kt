package no.aura.os.v2.livetv

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Profile identity is authenticated with the ciphertext to prevent swapping another profile's source credentials. */
internal class LiveTvConfigStore(context: Context, private val profileId: String) {
    private val prefs=context.getSharedPreferences("aura_v2_livetv_encrypted",Context.MODE_PRIVATE)
    private val slot=java.security.MessageDigest.getInstance("SHA-256").digest(profileId.toByteArray()).joinToString("") { "%02x".format(it) }
    fun has(): Boolean = prefs.contains(slot)
    fun read(): String? = runCatching {
        val bytes=Base64.decode(prefs.getString(slot,null) ?:return null,Base64.NO_WRAP)
        if(bytes.size<28 || bytes.size>512*1024)return null
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(false) ?:return null,GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        cipher.updateAAD(profileId.toByteArray(Charsets.UTF_8))
        String(cipher.doFinal(bytes,12,bytes.size-12),Charsets.UTF_8)
    }.getOrNull()
    fun save(value: String): Boolean = runCatching {
        require(profileId.isNotBlank() && value.toByteArray().size<=256*1024)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true))
        cipher.updateAAD(profileId.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(slot,Base64.encodeToString(cipher.iv+cipher.doFinal(value.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)).commit()
    }.getOrDefault(false)
    private fun key(create: Boolean): SecretKey? {
        val ks=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS,null) as? SecretKey)?.let { return it };if(!create)return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());generateKey()
        }
    }
    companion object {private const val ALIAS="aura_v2_livetv_sources"}
}
