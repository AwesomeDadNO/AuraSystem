package no.aura.os.v2.profiles

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PIN hashing contract. The real implementation is salted PBKDF2; tests may use a fast fake. */
interface PinHasher {
    fun create(pin: String): PinCredential
    fun verify(pin: String, credential: PinCredential): Boolean
}

class Pbkdf2PinHasher(private val iterations: Int = 60_000, private val random: SecureRandom = SecureRandom()) : PinHasher {
    override fun create(pin: String): PinCredential {
        val salt = ByteArray(16).also(random::nextBytes)
        return PinCredential(hex(salt), hex(derive(pin, salt)))
    }
    override fun verify(pin: String, credential: PinCredential): Boolean {
        val salt = unhex(credential.salt) ?: return false
        val expected = unhex(credential.hash) ?: return false
        return MessageDigest.isEqual(derive(pin, salt), expected)   // constant time
    }
    private fun derive(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, 256)).encoded

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String): ByteArray? = if (s.length % 2 != 0 || !s.all { it in '0'..'9' || it in 'a'..'f' }) null else ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

object PinPolicy {
    const val MIN = 4; const val MAX = 6
    fun isValid(pin: String) = pin.length in MIN..MAX && pin.all { it in '0'..'9' }
}
