package no.aura.os.v2.simkl

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** AUTH V2 RFC 8628 device grant. Device code and verifier stay in-memory only. */
data class SimklPin(
    val userCode: String,
    val verificationUrl: String,
    val expiresAtMs: Long,
    val intervalSeconds: Long,
    val deviceCode: String,
    val codeVerifier: String,
) {
    override fun toString(): String = "SimklPin(userCode=$userCode, deviceCode=<redacted>, codeVerifier=<redacted>)"
}

sealed interface SimklPinStatus {
    object Pending : SimklPinStatus
    object SlowDown : SimklPinStatus
    data class Approved(val token: String) : SimklPinStatus { // encrypted by caller: JSON access/refresh bundle
        override fun toString(): String = "Approved(token=<redacted>)"
    }
    data class Failed(val reason: String) : SimklPinStatus
}

sealed interface SimklSyncResult {
    data class Success(val tmdbKeys: Set<String>, val refreshedCredentials: String?) : SimklSyncResult {
        override fun toString(): String = "Success(tmdbKeys=${tmdbKeys.size}, credentials=<redacted>)"
    }
    data class Failed(val reason: String, val refreshedCredentials: String? = null) : SimklSyncResult {
        override fun toString(): String = "Failed(reason=$reason, credentials=<redacted>)"
    }
}

private data class HttpReply(val status: Int, val json: JSONObject)

/**
 * Real Simkl AUTH V2 (NOT the legacy /oauth/pin path).
 * The public V2 TV client ID has no secret; user tokens are persisted by SimklProfileTokens,
 * encrypted per AuraOS profile. Read-only media:read is intentional.
 */
class SimklV2Client(private val clientId: String) {
    val configured: Boolean get() = clientId.matches(Regex("[A-Za-z0-9_-]{16,128}"))

    suspend fun begin(): SimklPin? {
        if (!configured) return null
        // Simkl AUTH V2 requires S256 PKCE even for RFC 8628 TV device flows.
        // The verifier stays in memory; only its SHA-256 challenge travels in the device request.
        val verifier = Base64.encodeToString(
            ByteArray(48).also { SecureRandom().nextBytes(it) },
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val result = post("/oauth2/device", JSONObject().put("client_id", clientId)
            .put("scope", "media:read")
            .put("code_challenge", challenge)
            .put("code_challenge_method", "S256")) ?: return null
        if (result.status != 200) return null
        val j = result.json
        val code = j.optString("user_code")
        val device = j.optString("device_code")
        val verification = j.optString("verification_uri").ifBlank { "https://simkl.com/pin" }
        if (!code.matches(Regex("[A-Za-z0-9-]{6,12}")) || device.isBlank() ||
            !verification.startsWith("https://simkl.com/")) return null
        return SimklPin(code, verification,
            System.currentTimeMillis() + j.optLong("expires_in", 900).coerceIn(60, 900) * 1000L,
            j.optLong("interval", 5).coerceIn(5, 60), device, verifier)
    }

    suspend fun check(pin: SimklPin): SimklPinStatus {
        if (!configured || System.currentTimeMillis() >= pin.expiresAtMs)
            return SimklPinStatus.Failed("Pairing code expired. Request a new PIN.")
        val reply = post("/oauth2/token", JSONObject()
            .put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .put("client_id", clientId)
            .put("device_code", pin.deviceCode)
            .put("code_verifier", pin.codeVerifier))
            ?: return SimklPinStatus.Failed("Could not contact Simkl.")
        val j = reply.json
        if (reply.status == 200) {
            return if (validTokens(j)) SimklPinStatus.Approved(tokenBundle(j))
                else SimklPinStatus.Failed("Simkl did not return both V2 tokens.")
        }
        return when (j.optString("error")) {
            "authorization_pending" -> SimklPinStatus.Pending
            "slow_down" -> SimklPinStatus.SlowDown
            "expired_token", "access_denied" -> SimklPinStatus.Failed("Pairing was denied or expired. Request a new PIN.")
            "invalid_client", "unauthorized_client" -> SimklPinStatus.Failed("The Simkl client is not registered for AUTH V2 TV/device access.")
            else -> SimklPinStatus.Failed("Simkl could not complete pairing (HTTP ${reply.status}).")
        }
    }

    /** Fetch explicit Plan-to-Watch items with user's read-only grant. No remote mutations. */
    suspend fun watchlist(encryptedBundle: String): SimklSyncResult {
        if (!configured) return SimklSyncResult.Failed("Simkl V2 is not configured.")
        val saved = runCatching { JSONObject(encryptedBundle) }.getOrNull()
            ?: return SimklSyncResult.Failed("Invalid stored Simkl credentials.")
        var active = saved
        var changed: String? = null
        // Refresh beforehand: V2 access tokens expire after 7 days. Refresh immediately invalidates old access tokens.
        if (saved.optLong("expires_at") <= System.currentTimeMillis() + 86_400_000L) {
            val refresh = saved.optString("refresh_token")
            if (refresh.isBlank()) return SimklSyncResult.Failed("Simkl refresh token is missing. Reconnect.")
            val response = post("/oauth2/token", JSONObject()
                .put("grant_type", "refresh_token").put("client_id", clientId).put("refresh_token", refresh))
                ?: return SimklSyncResult.Failed("Simkl token refresh failed.")
            if (response.status != 200 || !validTokens(response.json))
                return SimklSyncResult.Failed("Simkl session expired or was revoked. Reconnect.")
            changed = tokenBundle(response.json)
            active = JSONObject(changed)
        }
        val token = active.optString("access_token").takeIf { it.startsWith("simkl_at_") }
            ?: return SimklSyncResult.Failed("Stored Simkl access token is invalid.", changed)
        // First check activities; no repeated full-library polling.
        val activities = get("/sync/activities", token)
            ?: return SimklSyncResult.Failed("Simkl activities request could not be completed.", changed)
        if (activities.status != 200) return SimklSyncResult.Failed("Simkl activities request was rejected.", changed)
        val found = linkedSetOf<String>()
        for ((type, keyType) in listOf("movies" to "movie", "shows" to "tv")) {
            val response = get("/sync/all-items/$type/plantowatch", token)
                ?: return SimklSyncResult.Failed("Simkl $type sync was unavailable.", changed)
            if (response.status != 200) return SimklSyncResult.Failed("Simkl $type sync was rejected.", changed)
            // Simkl returns {} for an empty status bucket.
            val arr = response.json.optJSONArray(type) ?: org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val row = arr.optJSONObject(i) ?: continue
                if (row.optString("status") != "plantowatch") continue
                val media = row.optJSONObject(if (type == "movies") "movie" else "show") ?: continue
                val id = media.optJSONObject("ids")?.optString("tmdb")?.toLongOrNull() ?: continue
                if (id in 1..999_999_999) found.add("tmdb:$keyType:$id")
            }
        }
        return SimklSyncResult.Success(found, changed)
    }

    /** Proactively revoke the user's access token at Simkl when disconnecting. */
    suspend fun revoke(storedBundle: String): Boolean {
        val bundle = runCatching { JSONObject(storedBundle) }.getOrNull() ?: return false
        val token = bundle.optString("access_token")
        if (token.isBlank()) return false
        return post("/oauth2/revoke", JSONObject().put("client_id", clientId).put("token", token))
            ?.status?.let { it in 200..299 } == true
    }

    private fun validTokens(j: JSONObject): Boolean =
        j.optString("access_token").startsWith("simkl_at_") &&
            j.optString("refresh_token").startsWith("simkl_rt_") &&
            j.optLong("expires_in") > 0

    private fun tokenBundle(j: JSONObject): String = JSONObject()
        .put("access_token", j.getString("access_token"))
        .put("refresh_token", j.getString("refresh_token"))
        .put("expires_at", System.currentTimeMillis() + j.optLong("expires_in") * 1000L)
        .put("scope", j.optString("scope")).toString()

    private suspend fun get(path: String, token: String) = request("GET", path, null, token)
    private suspend fun post(path: String, body: JSONObject) = request("POST", path, body, null)

    private suspend fun request(method: String, path: String, body: JSONObject?, token: String?): HttpReply? =
        withContext(Dispatchers.IO) {
            val sep = if ('?' in path) '&' else '?'
            val url = URL("https://api.simkl.com$path" + sep + "client_id=" + enc(clientId) +
                "&app-name=AuraOS&app-version=0.1")
            val c = runCatching { url.openConnection() as HttpURLConnection }.getOrNull() ?: return@withContext null
            try {
                c.requestMethod = method
                c.connectTimeout = 6_000
                c.readTimeout = 12_000
                c.instanceFollowRedirects = false
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("User-Agent", "AuraOS/0.1")
                c.setRequestProperty("simkl-api-key", clientId)
                if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    val encoded = buildString {
                        for (key in body.keys()) {
                            if (isNotEmpty()) append('&')
                            append(enc(key)).append('=').append(enc(body.getString(key)))
                        }
                    }
                    c.outputStream.use { it.write(encoded.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream ?: return@withContext HttpReply(code, JSONObject())
                val out = ByteArrayOutputStream()
                stream.use { src ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = src.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > 4 * 1024 * 1024) return@withContext null
                        out.write(buffer, 0, n)
                    }
                }
                HttpReply(code, runCatching { JSONObject(out.toString("UTF-8")) }.getOrDefault(JSONObject()))
            } catch (_: Exception) { null } finally { c.disconnect() }
        }

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")
}
