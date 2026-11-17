package no.aura.os.v2.mediaserver

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import no.aura.os.v2.tmdb.Json
import no.aura.os.v2.tmdb.obj
import no.aura.os.v2.tmdb.str
import no.aura.os.v2.tmdb.int
import no.aura.os.v2.tmdb.dbl
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** Documented RS256 variant; private RSA keys remain non-exportable in Android Keystore, independently per profile/device. */
interface PlexDeviceSigningKey {
    val clientIdentifier: String
    val kid: String
    val jwk: Map<String,String>
    fun sign(input: ByteArray): ByteArray
}
object PlexDeviceJwt {
    private fun base64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun sign(key: PlexDeviceSigningKey, nonce: String?, nowMs: Long): String {
        require(key.kid.isNotBlank() && key.clientIdentifier.isNotBlank())
        require(nonce == null || nonce.length in 1..256)
        val header = JsonOut.obj("kid" to key.kid, "alg" to "RS256", "typ" to "JWT")
        val payload = linkedMapOf<String,Any>("aud" to "plex.tv", "iss" to key.clientIdentifier, "iat" to nowMs / 1000, "exp" to nowMs / 1000 + 60, "scope" to "username,friendly_name")
        if (nonce != null) payload["nonce"] = nonce
        val input = base64(header.toByteArray(Charsets.UTF_8)) + "." + base64(JsonOut.write(payload).toByteArray(Charsets.UTF_8))
        return input + "." + base64(key.sign(input.toByteArray(Charsets.US_ASCII)))
    }
    fun profileIdentifier(installDeviceId: String, profile: String): String {
        require(installDeviceId.isNotBlank() && profile.isNotBlank())
        return "aura-jwt-" + MessageDigest.getInstance("SHA-256").digest((installDeviceId + "\u0000" + profile).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
class KeystorePlexDeviceKeys(private val installDeviceId: String, private val isAdmin: () -> Boolean, private val activeProfile: () -> String?, private val profileExists: (String) -> Boolean = { true }) {
    private fun alias(profile: String) = PlexDeviceJwt.profileIdentifier(installDeviceId, profile)
    /** Call only after explicit admin-authorized modern sign-in choice. Unsupported crypto reports failure, never silently falls back. */
    fun open(profile: String, create: Boolean): PlexDeviceSigningKey? = runCatching {
        val alias = alias(profile)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            if (!create || !isAdmin() || activeProfile() != profile) return null
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build())
            }.generateKeyPair()
        }
        val publicKey = store.getCertificate(alias).publicKey as RSAPublicKey
        val privateKey = store.getKey(alias,null) as java.security.PrivateKey
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun unsigned(value: java.math.BigInteger) = value.toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1,it.size) else it }
        val kidValue = encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(publicKey.encoded))
        object : PlexDeviceSigningKey {
            override val clientIdentifier = alias
            override val kid = kidValue
            override val jwk = mapOf("kty" to "RSA", "n" to encoder.encodeToString(unsigned(publicKey.modulus)), "e" to encoder.encodeToString(unsigned(publicKey.publicExponent)), "kid" to kid, "alg" to "RS256", "use" to "sig")
            override fun sign(input: ByteArray) = Signature.getInstance("SHA256withRSA").run { initSign(privateKey); update(input); sign() }
        }
    }.getOrNull()
    /** Used only after ProfileService's successful Administrator-authorized deletion, including a nonactive profile. */
    fun purgeDeletedProfile(profile: String): Boolean = runCatching {
        if (!isAdmin() || profileExists(profile)) return false
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias(profile)) }; true
    }.getOrDefault(false)
    /** Local forget only. Plex Authorized Devices must be used for remote revocation; no endpoint is invented. */
    fun forget(profile: String): Boolean = runCatching {
        if (!isAdmin() || activeProfile() != profile) return false
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias(profile)) }; true
    }.getOrDefault(false)
}

interface PlexModernClient {
    val clientIdentifier: String
    suspend fun createPin(): MediaResult<PlexPin>
    fun approvalUrl(pin: PlexPin): String
    suspend fun checkPin(pin: PlexPin): MediaResult<String>
    suspend fun refresh(): MediaResult<String>
    suspend fun authorizedResources(token: String, identity: ClientIdentity): MediaResult<Pair<PlexUser,List<PlexServer>>>
}

/** Documented wire client only. Host must retain the same key/client id through PIN approval and future nonce refresh. */
class PlexDeviceJwtClient(private val http: MediaHttp, private val key: PlexDeviceSigningKey, private val clock: () -> Long = System::currentTimeMillis) : PlexModernClient {
    override val clientIdentifier get() = key.clientIdentifier
    private val base = "https://clients.plex.tv/api/v2"
    private fun headers() = mapOf("X-Plex-Client-Identifier" to key.clientIdentifier, "X-Plex-Product" to "AuraOS", "Accept" to "application/json", "Content-Type" to "application/json")
    private suspend fun call(method: String, path: String, body: String? = null): MediaResult<Map<String,Any?>> {
        val response = http.send(MediaRequest(method, base + path, headers(), body))
        if (response.code !in 200..299) return MediaResult.Failure(when(response.code) { 0 -> MediaError.UNREACHABLE; 401,403,498 -> MediaError.AUTH_FAILED; 404 -> MediaError.EXPIRED; 429 -> MediaError.SERVER; else -> MediaError.BAD_RESPONSE })
        return Json.parse(response.body).obj()?.let { MediaResult.Ok(it) } ?: MediaResult.Failure(MediaError.BAD_RESPONSE)
    }
    override suspend fun createPin(): MediaResult<PlexPin> = when(val result = call("POST", "/pins", JsonOut.obj("jwk" to key.jwk, "strong" to true))) {
        is MediaResult.Failure -> result
        is MediaResult.Ok -> {
            val id = result.value.dbl("id")?.toLong(); val code = result.value.str("code")
            if (id == null || id <= 0 || code == null || !Regex("[A-Za-z0-9]{4,32}").matches(code)) MediaResult.Failure(MediaError.BAD_RESPONSE)
            else MediaResult.Ok(PlexPin(id, code, clock() + (result.value.int("expiresIn") ?: 900).coerceIn(1,1800) * 1000L))
        }
    }
    override fun approvalUrl(pin: PlexPin): String = "https://app.plex.tv/auth#?clientID=" + key.clientIdentifier + "&code=" + pin.code + "&context%5Bdevice%5D%5Bproduct%5D=AuraOS"
    override suspend fun checkPin(pin: PlexPin): MediaResult<String> {
        if (clock() >= pin.expiresAtMs) return MediaResult.Failure(MediaError.EXPIRED)
        val proof = runCatching { PlexDeviceJwt.sign(key,null,clock()) }.getOrNull() ?: return MediaResult.Failure(MediaError.AUTH_FAILED)
        return when(val result = call("GET", "/pins/${pin.id}?deviceJWT=$proof")) {
            is MediaResult.Failure -> result
            is MediaResult.Ok -> result.value.str("authToken")?.takeIf { it.length in 20..16384 && it.none(Char::isWhitespace) }?.let { MediaResult.Ok(it) } ?: MediaResult.Failure(MediaError.PENDING)
        }
    }
    override suspend fun refresh(): MediaResult<String> {
        val nonce = when(val result = call("GET", "/auth/nonce")) { is MediaResult.Failure -> return result; is MediaResult.Ok -> result.value.str("nonce")?.takeIf { it.length in 1..256 } ?: return MediaResult.Failure(MediaError.BAD_RESPONSE) }
        val proof = runCatching { PlexDeviceJwt.sign(key,nonce,clock()) }.getOrNull() ?: return MediaResult.Failure(MediaError.AUTH_FAILED)
        return when(val result = call("POST", "/auth/token", JsonOut.obj("jwt" to proof))) {
            is MediaResult.Failure -> result
            is MediaResult.Ok -> result.value.str("auth_token")?.takeIf { it.length in 20..16384 && it.none(Char::isWhitespace) }?.let { MediaResult.Ok(it) } ?: MediaResult.Failure(MediaError.BAD_RESPONSE)
        }
    }
    /** Reuses existing resource parsing/identity-checked connections; only Plex's returned server accessToken reaches a server. */
    override suspend fun authorizedResources(token: String, identity: ClientIdentity): MediaResult<Pair<PlexUser,List<PlexServer>>> {
        val accountClient = PlexClient(http, identity.copy(deviceId = key.clientIdentifier), clock)
        return when(val user = accountClient.user(token)) {
            is MediaResult.Failure -> user
            is MediaResult.Ok -> when(val servers = accountClient.servers(user.value)) {
                is MediaResult.Failure -> servers
                is MediaResult.Ok -> MediaResult.Ok(user.value to servers.value)
            }
        }
    }

}
