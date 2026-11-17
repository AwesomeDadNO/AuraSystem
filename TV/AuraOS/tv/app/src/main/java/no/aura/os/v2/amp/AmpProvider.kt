package no.aura.os.v2.amp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun ampProfileKey(profile: String) = MessageDigest.getInstance("SHA-256").digest(profile.toByteArray()).joinToString("") { "%02x".format(it) }
object AmpProviderSafety {
    fun parseArl(raw: String): String? {
        val value = raw.trim()
        val candidate = if (value.matches(Regex("[a-fA-F0-9]{100,256}"))) value else runCatching {
            val u = URI(value)
            if (u.scheme != "https" || u.host != "eclipse.ultramax.vip" || u.port != -1 || u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null) return null
            Regex("/([a-fA-F0-9]{100,256})/manifest\\.json").matchEntire(u.path)?.groupValues?.get(1)
        }.getOrNull()
        return candidate?.takeIf { it.matches(Regex("[a-fA-F0-9]{100,256}")) }
    }
    fun publicHttps(raw: String): Boolean = runCatching {
        val u = URI(raw)
        u.scheme == "https" && u.host != null && u.rawUserInfo == null && u.rawFragment == null && (u.port == -1 || u.port == 443) &&
            u.host.lowercase().let { it != "localhost" && !it.endsWith(".local") && !it.endsWith(".localhost") && !it.contains(':') && !it.matches(Regex("[0-9.]+")) }
    }.getOrDefault(false)
    internal fun matchesTrack(track: AmpTrack, providerId: String, title: String, artist: String): Boolean {
        fun normalized(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)
        return artist.isNotBlank() && title.isNotBlank() && normalized(track.title) == normalized(title) && normalized(track.artist) == normalized(artist)
    }
    internal fun publicAddress(address: ByteArray): Boolean {
        if (address.size == 16) return (address[0].toInt() and 0xfe) != 0xfc
        if (address.size != 4) return false
        val a = address.map { it.toInt() and 255 }
        return when {
            a[0] == 0 || a[0] == 10 || a[0] == 127 || a[0] >= 224 -> false
            a[0] == 100 && a[1] in 64..127 -> false
            a[0] == 169 && a[1] == 254 -> false
            a[0] == 172 && a[1] in 16..31 -> false
            a[0] == 192 && a[1] == 168 -> false
            a[0] == 192 && a[1] == 0 && a[2] in listOf(0, 2) -> false
            a[0] == 198 && a[1] in 18..19 -> false
            a[0] == 198 && a[1] == 51 && a[2] == 100 -> false
            a[0] == 203 && a[1] == 0 && a[2] == 113 -> false
            else -> true
        }
    }
    internal fun validateNetwork(raw: String) {
        require(publicHttps(raw))
        require(InetAddress.getAllByName(URI(raw).host).all { !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isSiteLocalAddress && !it.isMulticastAddress && publicAddress(it.address) })
    }
}

class AmpArlVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("amp_credentials", Context.MODE_PRIVATE)
    private fun key(profile: String): SecretKey {
        val alias = "aura_amp_" + ampProfileKey(profile)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    internal fun read(profile: String): String? = runCatching {
        val encoded = prefs.getString(ampProfileKey(profile), null) ?: return null
        val parts = encoded.split(':')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(profile), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))).let(AmpProviderSafety::parseArl)
    }.getOrNull()
    fun has(profile: String) = read(profile) != null
    /**
     * Presence of the encrypted entry only: no Keystore call and no decryption, so it is safe on the main thread and during composition.
     * An entry that can no longer be decrypted still reads as set here; [has] / verify give the real answer off the main thread.
     */
    fun isSet(profile: String): Boolean = runCatching { prefs.contains(ampProfileKey(profile)) }.getOrDefault(false)
    fun save(profile: String, raw: String): Boolean = runCatching {
        val arl = AmpProviderSafety.parseArl(raw) ?: return false
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(profile)) }
        val result = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(arl.toByteArray()), Base64.NO_WRAP)
        prefs.edit().putString(ampProfileKey(profile), result).commit()
    }.getOrDefault(false)
    fun clear(profile: String) { prefs.edit().remove(ampProfileKey(profile)).commit() }
}

enum class AmpProviderStage(val label: String) { CONNECTION("connection"), MANIFEST("manifest"), ISRC("recording lookup"), RESOLVE("recording resolution"), SEARCH("search"), STREAM("stream resolution"), AUDIO("audio playback") }
enum class AmpProviderProblem {
    NOT_CONNECTED, SESSION_CHANGED, NETWORK, HTTP, INVALID_RESPONSE, UNSUPPORTED_CAPABILITIES, NO_MATCH, UNSAFE_URL, UNSUPPORTED_FORMAT, EXPIRED,
    /** Eclipse answered 401: the Deezer credential (ARL) is invalid, expired or revoked. Only a fresh ARL fixes this. */
    CREDENTIAL_REJECTED,
    /** 429: too many requests. Says nothing about the credential. */
    RATE_LIMITED,
    /** 5xx: the service failed. Says nothing about the credential. */
    SERVER,
}
data class AmpProviderFailure(val stage: AmpProviderStage, val problem: AmpProviderProblem, val httpStatus: Int? = null) {
    val safeMessage: String get() = when (problem) {
        AmpProviderProblem.NOT_CONNECTED -> "Connect Eclipse in Settings."
        AmpProviderProblem.CREDENTIAL_REJECTED -> "The Deezer connection must be renewed. Enter a fresh ARL; playlists, favourites and history are kept."
        AmpProviderProblem.RATE_LIMITED -> "Eclipse is limiting requests right now. Try again in a moment."
        AmpProviderProblem.SERVER -> "Eclipse ${stage.label} reported a server problem (HTTP $httpStatus). Try again later."
        AmpProviderProblem.SESSION_CHANGED -> "Eclipse connection changed. Select the track again."
        AmpProviderProblem.HTTP -> "Eclipse ${stage.label} returned HTTP $httpStatus. Try again later."
        AmpProviderProblem.NETWORK -> "Eclipse ${stage.label} could not reach the service."
        AmpProviderProblem.NO_MATCH -> "Eclipse could not match this recording."
        AmpProviderProblem.UNSUPPORTED_CAPABILITIES -> "Eclipse manifest does not advertise streaming and a supported recording lookup."
        AmpProviderProblem.UNSAFE_URL -> "Eclipse returned an unsafe audio address."
        AmpProviderProblem.UNSUPPORTED_FORMAT -> "Eclipse returned an unsupported or protected audio format."
        AmpProviderProblem.EXPIRED -> "Eclipse returned an expired audio address. Select the track again."
        AmpProviderProblem.INVALID_RESPONSE -> "Eclipse ${stage.label} returned an unexpected response."
    }
}
/** How far a connection check got. Neither level claims that audio plays: that is confirmed only by the player. */
enum class AmpAccess { NOT_CONNECTED, MANIFEST_AVAILABLE, MUSIC_VERIFIED }
/** [musicProbed] is false when the addon has no search capability, so music access could not be tested without playing a track. */
data class AmpVerification(val access: AmpAccess, val failure: AmpProviderFailure?, val musicProbed: Boolean = true)

/** Everything Settings and the sheets say about the Eclipse connection, derived from facts only (pure, unit-tested). */
data class AmpConnection(val stored: Boolean, val check: AmpVerification? = null, val checkedAt: Long? = null,
    val playbackConfirmedAt: Long? = null, val playbackFailure: AmpProviderFailure? = null)
object AmpConnectionPresenter {
    const val RENEW = "The Deezer connection must be renewed."
    const val KEPT = "Playlists, favourites and history are kept."
    fun needsRenewal(c: AmpConnection) = c.stored && (c.playbackFailure?.problem == AmpProviderProblem.CREDENTIAL_REJECTED || c.check?.failure?.problem == AmpProviderProblem.CREDENTIAL_REJECTED)
    fun status(c: AmpConnection): no.aura.os.v2.settings.ConnectionStatus {
        val id = no.aura.os.v2.settings.ConnectionId.ECLIPSE
        fun plain(state: no.aura.os.v2.settings.ConnectionState, message: String) = no.aura.os.v2.settings.ConnectionStatus(id, state, message = message)
        val failure = c.playbackFailure ?: c.check?.failure
        return when {
            !c.stored -> plain(no.aura.os.v2.settings.ConnectionState.NOT_CONFIGURED, "Connect with your Deezer ARL.")
            needsRenewal(c) -> plain(no.aura.os.v2.settings.ConnectionState.AUTH_FAILED, "$RENEW Enter a fresh ARL. $KEPT")
            c.playbackConfirmedAt != null && c.playbackFailure == null -> no.aura.os.v2.settings.ConnectionStatus(id, no.aura.os.v2.settings.ConnectionState.CONNECTED, c.playbackConfirmedAt, "Playback confirmed: audio played on this device.")
            c.check?.access == AmpAccess.MUSIC_VERIFIED && c.checkedAt != null && c.playbackFailure == null ->
                no.aura.os.v2.settings.ConnectionStatus(id, no.aura.os.v2.settings.ConnectionState.CONNECTED, c.checkedAt, "Music access verified (search and stream address). Audio playback is not confirmed until a track has played.")
            failure != null -> plain(when (failure.problem) {
                AmpProviderProblem.NETWORK -> no.aura.os.v2.settings.ConnectionState.OFFLINE
                else -> no.aura.os.v2.settings.ConnectionState.ERROR
            }, (if (c.check?.access == AmpAccess.MANIFEST_AVAILABLE) "Manifest available, music access not verified. " else "") + failure.safeMessage)
            c.check?.access == AmpAccess.MANIFEST_AVAILABLE -> plain(no.aura.os.v2.settings.ConnectionState.NOT_CONFIGURED, "Manifest available. Music access is not verified: this addon has no search to test with. Play a track to confirm.")
            else -> plain(no.aura.os.v2.settings.ConnectionState.NOT_CONFIGURED, "ARL saved; choose Verify to check Eclipse.")
        }
    }
    /** Lines for the sheet shown after Save or Verify. */
    fun resultLines(saved: Boolean, check: AmpVerification?): List<String> = when {
        !saved -> listOf("Invalid ARL or secure storage unavailable.")
        check == null -> listOf("ARL saved encrypted. The connection has not been checked.")
        check.failure?.problem == AmpProviderProblem.CREDENTIAL_REJECTED -> listOf(RENEW, "Eclipse rejected this ARL (it is invalid, expired or revoked). Enter a fresh one.", KEPT)
        check.access == AmpAccess.MUSIC_VERIFIED -> listOf("Music access verified: search and a stream address work with this ARL.", "Audio playback is confirmed only when a track has played. Choose a song in AuraAmp.")
        check.access == AmpAccess.MANIFEST_AVAILABLE && check.failure != null -> listOf("Manifest available, but music access is NOT verified.", check.failure.safeMessage)
        check.access == AmpAccess.MANIFEST_AVAILABLE -> listOf("Manifest available.", "Music access could not be tested (this addon has no search). Play a track to confirm.")
        else -> listOf(check.failure?.safeMessage ?: "Eclipse did not confirm the connection. The saved ARL is unchanged.")
    }
}

class AmpResolvedStream(val url: String, val mimeType: String?, val format: String?, val manifest: String?, val codec: String?, val expiresAt: Long?)
private class AmpProviderException(val failure: AmpProviderFailure) : Exception(failure.safeMessage)

/** Pure decisions shared by the JSON adapter and documented-schema fixture tests. */
internal object AmpEclipseProtocol {
    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun searchRoute(track: AmpTrack) = "search?q=${encoded(track.artist + " " + track.title)}"
    fun streamRoute(id: String) = "stream/${encoded(id)}"
    /** A neutral, fixed search used only to prove that the credential can really reach music (never shown, never stored). */
    const val PROBE_QUERY = "music"
    fun probeRoute() = "search?q=${encoded(PROBE_QUERY)}"
    /**
     * What an HTTP status means. 401 is the documented rejection of the Deezer credential ("Invalid or expired Deezer credential"), at any
     * stage. 403 is NOT read as a credential verdict (it is also what a blocked network or region returns), nor are 429 and 5xx.
     */
    fun httpProblem(status: Int): AmpProviderProblem = when {
        status == 401 -> AmpProviderProblem.CREDENTIAL_REJECTED
        status == 429 -> AmpProviderProblem.RATE_LIMITED
        status in 500..599 -> AmpProviderProblem.SERVER
        else -> AmpProviderProblem.HTTP
    }
    fun isrcRoute(isrc: String) = "resolve-isrc?isrc=${encoded(isrc)}"
    fun resolveRoute(track: AmpTrack, isrc: String?): String = "resolve?title=${encoded(track.title)}&artist=${encoded(track.artist)}&durationMs=${track.duration.toLong().coerceAtLeast(0) * 1000}" + (isrc?.let { "&isrc=${encoded(it)}" } ?: "")
    fun capabilities(resources: List<String>): Set<String> = resources.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
    fun canPlay(resources: Set<String>) = "stream" in resources && resources.any { it in setOf("search", "isrc", "resolve") }
    fun normalizedIsrc(value: String?): String? = value?.replace(Regex("[-\\s]"), "")?.uppercase(java.util.Locale.ROOT)?.takeIf { it.matches(Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")) }
    fun matches(track: AmpTrack, item: Map<String, Any?>): Boolean {
        val requested = normalizedIsrc(track.isrc)
        val found = normalizedIsrc(item["isrc"] as? String)
        if (requested != null && found != null) return requested == found
        val artist = item["artist"] as? String ?: ""
        return AmpProviderSafety.matchesTrack(track, item["id"]?.toString().orEmpty(), item["title"] as? String ?: "", artist)
    }
    fun lookupId(item: Map<String, Any?>): String? = (item["trackId"] ?: item["id"])?.toString()?.takeIf { it.isNotBlank() && it != "null" }
    fun streamUrl(item: Map<String, Any?>): String? = (item["streamURL"] as? String)?.takeIf(String::isNotBlank) ?: (item["url"] as? String)?.takeIf(String::isNotBlank)
    /**
     * May the audio transport follow a redirect to [next]? Yes when it is a public https address and does not carry the credential to a host
     * other than Eclipse's own. Audio is commonly redirected to a CDN host; the transport sends no credential headers, so the only way a
     * credential could leave is inside the URL itself, which is exactly what this checks. (The transport also re-validates DNS for [next].)
     */
    fun redirectAllowed(next: String, credential: String?): Boolean =
        if (credential == null) AmpProviderSafety.publicHttps(next) else safeStreamUrl(next, credential)
    fun safeStreamUrl(url: String, credential: String): Boolean = AmpProviderSafety.publicHttps(url) && (!url.contains(credential, true) || URI(url).host == "eclipse.ultramax.vip")
    fun mime(item: Map<String, Any?>): String? = when {
        streamUrl(item)?.let { runCatching { URI(it).path.lowercase().endsWith(".m3u8") }.getOrDefault(false) } == true -> "application/x-mpegURL"
        (item["manifest"] as? String)?.lowercase() == "hls" -> "application/x-mpegURL"
        else -> when ((item["format"] as? String)?.lowercase()) {
            "mp3" -> "audio/mpeg"; "flac" -> "audio/flac"; "aac" -> "audio/aac"; "m4a", "mp4" -> "audio/mp4"; "ogg", "opus", "vorbis" -> "audio/ogg"; "wav" -> "audio/wav"; "hls", "m3u8" -> "application/x-mpegURL"; else -> null
        }
    }
}

class AmpProvider(private val vault: AmpArlVault, private val profile: String) {
    @Volatile var lastFailure: AmpProviderFailure? = null; private set
    private fun fail(stage: AmpProviderStage, problem: AmpProviderProblem, status: Int? = null): Nothing = throw AmpProviderException(AmpProviderFailure(stage, problem, status))
    private fun json(url: String, stage: AmpProviderStage): JSONObject {
        try {
            AmpProviderSafety.validateNetwork(url)
            require(URI(url).host == "eclipse.ultramax.vip")
        } catch (_: Exception) { fail(stage, AmpProviderProblem.UNSAFE_URL) }
        val connection = try { URL(url).openConnection() as HttpURLConnection } catch (_: Exception) { fail(stage, AmpProviderProblem.NETWORK) }
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000; connection.readTimeout = 15000
            val status = connection.responseCode
            if (status != 200) fail(stage, AmpEclipseProtocol.httpProblem(status), status)
            val bytes = connection.inputStream.use { it.readBytesBounded(2 * 1024 * 1024) }
            return try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { fail(stage, AmpProviderProblem.INVALID_RESPONSE) }
        } catch (e: AmpProviderException) { throw e }
        catch (_: Exception) { fail(stage, AmpProviderProblem.NETWORK) }
        finally { connection.disconnect() }
    }
    private fun resourceSet(manifest: JSONObject): Set<String> {
        val array = manifest.optJSONArray("resources") ?: return emptySet()
        return AmpEclipseProtocol.capabilities((0 until array.length()).mapNotNull { array.opt(it) as? String })
    }
    private fun fields(item: JSONObject): Map<String, Any?> = item.keys().asSequence().associateWith { key -> item.opt(key).takeUnless { it == JSONObject.NULL } }
    private suspend fun <T> guarded(block: suspend () -> T): T? {
        lastFailure = null
        return try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: AmpProviderException) { lastFailure = e.failure; null }
        catch (_: Exception) { lastFailure = AmpProviderFailure(AmpProviderStage.CONNECTION, AmpProviderProblem.NETWORK); null }
    }
    suspend fun verify(): Boolean = withContext(Dispatchers.IO) {
        guarded {
            val credential = vault.read(profile) ?: fail(AmpProviderStage.CONNECTION, AmpProviderProblem.NOT_CONNECTED)
            val resources = resourceSet(json("https://eclipse.ultramax.vip/$credential/manifest.json", AmpProviderStage.MANIFEST))
            if (!AmpEclipseProtocol.canPlay(resources)) fail(AmpProviderStage.MANIFEST, AmpProviderProblem.UNSUPPORTED_CAPABILITIES)
            if (vault.read(profile) != credential) fail(AmpProviderStage.CONNECTION, AmpProviderProblem.SESSION_CHANGED)
            true
        } ?: false
    }
    /**
     * The real connection check, in the three steps that mean different things:
     *  1. the manifest answers and advertises streaming            -> [AmpAccess.MANIFEST_AVAILABLE] (proves the address, NOT the account:
     *     an expired ARL still gets a 200 manifest);
     *  2. a search returns tracks and a stream address is issued   -> [AmpAccess.MUSIC_VERIFIED];
     *  3. audio actually playing is confirmed only by the player (AmpPlayback.audioConfirmedAt), never here: no audio is fetched.
     * A failure after step 1 keeps MANIFEST_AVAILABLE and reports why (rejected credential, rate limit, server, network are distinct).
     */
    suspend fun verifyAccess(): AmpVerification = withContext(Dispatchers.IO) {
        var reached = AmpAccess.NOT_CONNECTED
        var probed = true
        guarded {
            val credential = vault.read(profile) ?: fail(AmpProviderStage.CONNECTION, AmpProviderProblem.NOT_CONNECTED)
            val base = "https://eclipse.ultramax.vip/$credential/"
            fun session() { if (vault.read(profile) != credential) fail(AmpProviderStage.CONNECTION, AmpProviderProblem.SESSION_CHANGED) }
            val resources = resourceSet(json(base + "manifest.json", AmpProviderStage.MANIFEST))
            if (!AmpEclipseProtocol.canPlay(resources)) fail(AmpProviderStage.MANIFEST, AmpProviderProblem.UNSUPPORTED_CAPABILITIES)
            session(); reached = AmpAccess.MANIFEST_AVAILABLE
            if ("search" !in resources) { probed = false; return@guarded }      // no generic probe exists without search; playing a track decides
            val tracks = json(base + AmpEclipseProtocol.probeRoute(), AmpProviderStage.SEARCH).optJSONArray("tracks") ?: fail(AmpProviderStage.SEARCH, AmpProviderProblem.INVALID_RESPONSE)
            val first = (0 until tracks.length()).firstNotNullOfOrNull { tracks.optJSONObject(it)?.let(::fields)?.takeIf { f -> AmpEclipseProtocol.lookupId(f) != null } }
                ?: fail(AmpProviderStage.SEARCH, AmpProviderProblem.NO_MATCH)
            val stream = if (AmpEclipseProtocol.streamUrl(first) != null) first else fields(json(base + AmpEclipseProtocol.streamRoute(AmpEclipseProtocol.lookupId(first)!!), AmpProviderStage.STREAM))
            val url = AmpEclipseProtocol.streamUrl(stream) ?: fail(AmpProviderStage.STREAM, AmpProviderProblem.INVALID_RESPONSE)
            if (!AmpEclipseProtocol.safeStreamUrl(url, credential)) fail(AmpProviderStage.STREAM, AmpProviderProblem.UNSAFE_URL)
            session(); reached = AmpAccess.MUSIC_VERIFIED
        }
        AmpVerification(reached, lastFailure, probed)
    }
    suspend fun resolve(track: AmpTrack): String? = resolveStream(track)?.url
    suspend fun resolveStream(track: AmpTrack): AmpResolvedStream? = withContext(Dispatchers.IO) {
        guarded {
            val credential = vault.read(profile) ?: fail(AmpProviderStage.CONNECTION, AmpProviderProblem.NOT_CONNECTED)
            val taskContext = currentCoroutineContext()
            val base = "https://eclipse.ultramax.vip/$credential/"
            fun session() { taskContext.ensureActive(); if (vault.read(profile) != credential) fail(AmpProviderStage.CONNECTION, AmpProviderProblem.SESSION_CHANGED) }
            fun request(route: String, stage: AmpProviderStage): JSONObject { session(); return json(base + route, stage).also { session() } }
            val resources = resourceSet(request("manifest.json", AmpProviderStage.MANIFEST))
            if (!AmpEclipseProtocol.canPlay(resources)) fail(AmpProviderStage.MANIFEST, AmpProviderProblem.UNSUPPORTED_CAPABILITIES)
            var selected: JSONObject? = null
            var providerId: String? = null
            val isrc = AmpEclipseProtocol.normalizedIsrc(track.isrc)
            if (isrc != null && "isrc" in resources) {
                val lookup = try { request(AmpEclipseProtocol.isrcRoute(isrc), AmpProviderStage.ISRC) } catch (e: AmpProviderException) { if (e.failure.httpStatus == 404) null else throw e }
                providerId = lookup?.let { AmpEclipseProtocol.lookupId(fields(it)) }
            }
            if (providerId == null && "resolve" in resources) {
                selected = request(AmpEclipseProtocol.resolveRoute(track, isrc), AmpProviderStage.RESOLVE).optJSONObject("item")
                providerId = selected?.let { AmpEclipseProtocol.lookupId(fields(it)) }
            }
            if (providerId == null && "search" in resources) {
                val response = request(AmpEclipseProtocol.searchRoute(track), AmpProviderStage.SEARCH)
                val items = response.optJSONArray("tracks") ?: fail(AmpProviderStage.SEARCH, AmpProviderProblem.INVALID_RESPONSE)
                val candidates = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
                selected = candidates.firstOrNull { row -> isrc != null && AmpEclipseProtocol.normalizedIsrc(row.optString("isrc")) == isrc }
                    ?: candidates.firstOrNull { AmpEclipseProtocol.matches(track, fields(it)) }
                providerId = selected?.let { AmpEclipseProtocol.lookupId(fields(it)) }
            }
            val id = providerId ?: fail(AmpProviderStage.SEARCH, AmpProviderProblem.NO_MATCH)
            val selectedFields = selected?.let(::fields).orEmpty()
            val stream = if (AmpEclipseProtocol.streamUrl(selectedFields) != null) selectedFields else fields(request(AmpEclipseProtocol.streamRoute(id), AmpProviderStage.STREAM))
            val url = AmpEclipseProtocol.streamUrl(stream) ?: fail(AmpProviderStage.STREAM, AmpProviderProblem.INVALID_RESPONSE)
            if (!AmpEclipseProtocol.safeStreamUrl(url, credential)) fail(AmpProviderStage.STREAM, AmpProviderProblem.UNSAFE_URL)
            if (stream["encrypted"] == true || (stream["manifest"] as? String)?.lowercase() == "dash") fail(AmpProviderStage.STREAM, AmpProviderProblem.UNSUPPORTED_FORMAT)
            val expiresAt = (stream["expiresAt"] as? Number)?.toLong()
            if (expiresAt != null && expiresAt <= System.currentTimeMillis() / 1000) fail(AmpProviderStage.STREAM, AmpProviderProblem.EXPIRED)
            session()
            AmpResolvedStream(url, AmpEclipseProtocol.mime(stream), stream["format"] as? String, stream["manifest"] as? String, stream["codec"] as? String, expiresAt)
        }
    }
}
internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val count = read(buffer); if (count < 0) break; require(out.size() + count <= limit); out.write(buffer, 0, count) }
    return out.toByteArray()
}
