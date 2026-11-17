package no.aura.os.v2.google

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Google TV device OAuth 2.0: user authorizes on a second device.
 * Cannot silently borrow Google's account tokens from Android TV.
 * Reference: https://developers.google.com/identity/protocols/oauth2/limited-input-device
 */
data class GoogleTvCode(
    val userCode: String,
    val verificationUrl: String,
    val deviceCode: String,
    val expiresAtMs: Long,
    val intervalSeconds: Long,
) {
    override fun toString(): String = "GoogleTvCode(userCode=$userCode, deviceCode=<redacted>)"
}
sealed interface GoogleTvPoll {
    object Pending : GoogleTvPoll
    object SlowDown : GoogleTvPoll
    data class Authorized(val tokenJson: String) : GoogleTvPoll {
        override fun toString(): String = "Authorized(<redacted>)"
    }
    data class Failed(val message: String) : GoogleTvPoll
}
private data class Reply(val status: Int, val body: JSONObject)

/** Minimal identity + read-only YouTube permissions. Google API keys cannot replace these scopes. */
class GoogleTvDeviceClient(private val clientId: String, private val clientSecret: String = "") : GoogleTvApi {
    override val configured get() = clientId.isNotBlank() && clientId.endsWith(".apps.googleusercontent.com")
    private val scopes = "openid email profile https://www.googleapis.com/auth/youtube.readonly"

    override suspend fun begin(): GoogleTvCode? {
        if (!configured) return null
        val reply = send("https://oauth2.googleapis.com/device/code", mapOf("client_id" to clientId, "scope" to scopes))
            ?: return null
        if (reply.status != 200) return null
        val j = reply.body
        val url = j.optString("verification_url")
        val code = j.optString("user_code")
        val device = j.optString("device_code")
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        if (uri?.scheme != "https" || uri.host !in setOf("www.google.com", "google.com", "accounts.google.com") || uri.userInfo != null || code.isBlank() || device.isBlank()) return null
        return GoogleTvCode(code, url, device,
            System.currentTimeMillis() + j.optLong("expires_in", 1800).coerceIn(60, 3600) * 1000L,
            j.optLong("interval", 5).coerceIn(5, 120))
    }

    override suspend fun check(code: GoogleTvCode): GoogleTvPoll {
        if (!configured || System.currentTimeMillis() >= code.expiresAtMs)
            return GoogleTvPoll.Failed("Google TV sign-in expired.")
        val p = mutableMapOf(
            "client_id" to clientId,
            "device_code" to code.deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        )
        if (clientSecret.isNotBlank()) p["client_secret"] = clientSecret
        val r = send("https://oauth2.googleapis.com/token", p)
            ?: return GoogleTvPoll.Failed("Could not reach Google's token service.")
        val j = r.body
        return if (r.status == 200) {
            if (j.optString("access_token").isBlank() || j.optString("refresh_token").isBlank())
                GoogleTvPoll.Failed("Google did not return renewable credentials.")
            else GoogleTvPoll.Authorized(JSONObject()
                .put("access_token", j.getString("access_token"))
                .put("refresh_token", j.getString("refresh_token"))
                .put("expires_at", System.currentTimeMillis() + j.optLong("expires_in") * 1000L)
                .put("scope", j.optString("scope"))
                .toString())
        } else when (j.optString("error")) {
            "authorization_pending" -> GoogleTvPoll.Pending
            "slow_down" -> GoogleTvPoll.SlowDown
            "access_denied", "expired_token" -> GoogleTvPoll.Failed("Google sign-in denied or expired.")
            "invalid_client" -> GoogleTvPoll.Failed("Google OAuth client is not registered as a TV device client.")
            else -> GoogleTvPoll.Failed("Google sign-in returned HTTP ${r.status}.")
        }
    }

    override suspend fun refresh(saved: String): String? {
        val j = runCatching { JSONObject(saved) }.getOrNull() ?: return null
        val token = j.optString("refresh_token").takeIf { it.isNotBlank() } ?: return null
        val p = mutableMapOf(
            "client_id" to clientId, "refresh_token" to token, "grant_type" to "refresh_token",
        )
        if (clientSecret.isNotBlank()) p["client_secret"] = clientSecret
        val reply = send("https://oauth2.googleapis.com/token", p) ?: return null
        if (reply.status != 200 || reply.body.optString("access_token").isBlank()) return null
        return JSONObject(saved)
            .put("access_token", reply.body.getString("access_token"))
            .put("expires_at", System.currentTimeMillis() + reply.body.optLong("expires_in") * 1000L)
            .toString()
    }

    /** A consented account can read its own YouTube playlists; Watch Later is NOT exposed by this API. */
    override suspend fun myPlaylists(saved: String): List<Pair<String, String>>? {
        val rows = allItems("https://www.googleapis.com/youtube/v3/playlists?part=snippet&mine=true&maxResults=50", saved) ?: return null
        return rows.mapNotNull { row ->
            val id = row.optString("id").takeIf { Regex("[A-Za-z0-9_-]{1,200}").matches(it) } ?: return@mapNotNull null
            val title = row.optJSONObject("snippet")?.optString("title")?.take(140)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to title
        }.distinctBy { it.first }
    }
    override suspend fun subscriptions(saved: String): List<Pair<String, String>>? {
        val rows = allItems("https://www.googleapis.com/youtube/v3/subscriptions?part=snippet&mine=true&maxResults=50", saved) ?: return null
        return rows.mapNotNull { row ->
            val snippet = row.optJSONObject("snippet") ?: return@mapNotNull null
            val id = snippet.optJSONObject("resourceId")?.optString("channelId")?.takeIf { Regex("UC[A-Za-z0-9_-]{22}").matches(it) } ?: return@mapNotNull null
            val title = snippet.optString("title").take(140).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to title
        }.distinctBy { it.first }
    }
    private suspend fun allItems(target: String, saved: String): List<JSONObject>? {
        val result = ArrayList<JSONObject>()
        var next: String? = null
        val seen = HashSet<String>()
        repeat(10) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val reply = getJson(target + (next?.let { "&pageToken=" + enc(it) } ?: ""), saved) ?: return null
            val rows = reply.optJSONArray("items") ?: return null
            for (index in 0 until rows.length().coerceAtMost(50)) rows.optJSONObject(index)?.let { result.add(it) }
            val token = reply.optString("nextPageToken").takeIf { it.isNotBlank() && it.length <= 1000 } ?: return result
            if (!seen.add(token)) return result
            next = token
        }
        return result
    }

    /** Stable subject comes from TLS-authenticated Google's UserInfo endpoint, never decoded JWT claims. */
    override suspend fun identity(saved: String): no.aura.os.v2.identity.GoogleIdentity? {
        val j = getJson("https://openidconnect.googleapis.com/v1/userinfo", saved) ?: return null
        return no.aura.os.v2.identity.GoogleIdentity(j.optString("sub"), j.optString("name").takeIf { it.isNotBlank() }, j.optString("picture").takeIf { it.startsWith("https://") }).takeIf { it.isValid }
    }
    override suspend fun revoke(saved: String): Boolean {
        val j = runCatching { JSONObject(saved) }.getOrNull() ?: return false
        val token = j.optString("refresh_token").ifBlank { j.optString("access_token") }
        if (token.isBlank()) return false
        return send("https://oauth2.googleapis.com/revoke", mapOf("token" to token))?.status == 200
    }
    override suspend fun playlistItems(saved: String, playlist: String): List<Pair<String, String>>? {
        if (!Regex("[A-Za-z0-9_-]{1,200}").matches(playlist)) return null
        val rows = allItems("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet&maxResults=50&playlistId=" + enc(playlist), saved) ?: return null
        return rows.mapNotNull { row ->
            val snippet = row.optJSONObject("snippet") ?: return@mapNotNull null
            val id = snippet.optJSONObject("resourceId")?.optString("videoId").orEmpty()
            val title = snippet.optString("title")
            if (Regex("[A-Za-z0-9_-]{11}").matches(id) && title.isNotBlank()) id to title.take(140) else null
        }.distinctBy { it.first }
    }
    private suspend fun getJson(target: String, saved: String): JSONObject? = withContext(Dispatchers.IO) {
        val token = runCatching { JSONObject(saved).optString("access_token") }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@withContext null
        val c = URL(target).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000; c.readTimeout = 10000; c.instanceFollowRedirects = false
            c.setRequestProperty("Authorization", "Bearer $token"); c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) return@withContext null
            val bytes = readLimited(c, 512 * 1024) ?: return@withContext null
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) { null } finally { c.disconnect() }
    }

    private suspend fun send(target: String, params: Map<String, String>): Reply? = withContext(Dispatchers.IO) {
        val c = URL(target).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 6000; c.readTimeout = 10000
            c.instanceFollowRedirects = false; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = params.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = c.responseCode
            val src = if (status in 200..299) c.inputStream else c.errorStream ?: return@withContext Reply(status, JSONObject())
            val bytes = readLimited(c, 64 * 1024, src) ?: return@withContext null
            Reply(status, runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrDefault(JSONObject()))
        } catch (_: Exception) { null } finally { c.disconnect() }
    }

    private fun readLimited(c: HttpURLConnection, max: Int, stream: java.io.InputStream = c.inputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        stream.use {
            val buf = ByteArray(8192)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                if (out.size() + n > max) return null
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
