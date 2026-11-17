package no.aura.os.v2.mediaserver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Shared foundation for the personal media servers (Plex, Jellyfin, Emby): address policy, HTTP transport, models and JSON output.
 * Everything here is platform-free Kotlin so the security rules are unit-tested on the JVM. Nothing in this package logs.
 */
enum class ServerKind(val title: String) { PLEX("Plex"), JELLYFIN("Jellyfin"), EMBY("Emby") }

enum class MediaError {
    /** The address is not allowed by [ServerAddress] (credentials in the URL, loopback, plain HTTP that was not approved, ...). */
    INSECURE_ADDRESS,
    UNREACHABLE, AUTH_FAILED, FORBIDDEN, NOT_FOUND, SERVER, BAD_RESPONSE,
    /** The server answered, but it is not the server (or the user) this account was verified against. */
    IDENTITY_MISMATCH,
    QUICK_CONNECT_DISABLED, PENDING, EXPIRED;

    fun message(): String = when (this) {
        INSECURE_ADDRESS -> "This server address is not allowed."
        UNREACHABLE -> "Could not reach the server."
        AUTH_FAILED -> "The server rejected the sign-in."
        FORBIDDEN -> "This user is not allowed to do that on the server."
        NOT_FOUND -> "The server does not offer this."
        SERVER -> "The server reported a problem."
        BAD_RESPONSE -> "The server sent a response this app could not read."
        IDENTITY_MISMATCH -> "This is not the server or user that was connected. Nothing was sent to it."
        QUICK_CONNECT_DISABLED -> "Quick Connect is turned off on this server. Use the manual sign-in."
        PENDING -> "Waiting for approval."
        EXPIRED -> "The code expired. Start again."
    }
}

sealed interface MediaResult<out T> {
    data class Ok<T>(val value: T) : MediaResult<T>
    data class Failure(val error: MediaError) : MediaResult<Nothing>
}

inline fun <T, R> MediaResult<T>.map(f: (T) -> R): MediaResult<R> = when (this) { is MediaResult.Ok -> MediaResult.Ok(f(value)); is MediaResult.Failure -> this }

/**
 * A validated server base address. Rules (contract A, "Server URL security"):
 * - http or https only; no user info, query or fragment (credentials never travel in a URL we store);
 * - loopback, unspecified and link-local hosts are rejected (the TV is never its own media server; blocks rebinding-style tricks);
 * - HTTPS is always allowed; plain HTTP only for a private-LAN host AND only when the person explicitly approved it ([lanHttpApproved]);
 *   plain HTTP to a public host is never allowed.
 * [base] has no trailing slash and may carry a path prefix (reverse proxies).
 */
class ServerAddress private constructor(val base: String, val host: String, val https: Boolean, val lan: Boolean) {
    /** scheme://host:port, the origin a token for this server may be sent to. */
    val origin: String = URI(base).let { u -> "${u.scheme}://${u.host.lowercase()}:${if (u.port > 0) u.port else if (https) 443 else 80}" }
    fun url(path: String): String = base + (if (path.startsWith("/")) path else "/$path")
    override fun toString() = base
    override fun equals(other: Any?) = other is ServerAddress && other.base == base
    override fun hashCode() = base.hashCode()

    sealed interface Parse {
        data class Ok(val address: ServerAddress) : Parse
        /** Plain HTTP on the local network: allowed only after the person approves the warning. */
        data class NeedsLanHttpApproval(val host: String) : Parse
        data class Rejected(val reason: String) : Parse
    }

    companion object {
        const val MAX = 512
        fun parse(raw: String, lanHttpApproved: Boolean = false): Parse {
            val text = raw.trim()
            if (text.isEmpty() || text.length > MAX || text.any { it.isWhitespace() || it < ' ' }) return Parse.Rejected("Enter a server address.")
            val withScheme = if ("://" in text) text else "https://$text"
            val u = runCatching { URI(withScheme) }.getOrNull() ?: return Parse.Rejected("This is not a valid address.")
            val scheme = u.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return Parse.Rejected("Only http and https addresses are supported.")
            val host = u.host?.lowercase()?.trim('[', ']') ?: return Parse.Rejected("This is not a valid address.")
            if (u.rawUserInfo != null) return Parse.Rejected("Do not put a user name or password in the address.")
            if (u.rawQuery != null || u.rawFragment != null) return Parse.Rejected("The address must not contain ? or #.")
            if (u.port == 0 || u.port > 65535) return Parse.Rejected("This is not a valid port.")
            if (isLoopbackOrUnspecified(host)) return Parse.Rejected("This device cannot be its own media server.")
            if (isLinkLocal(host)) return Parse.Rejected("Link-local addresses are not supported.")
            val lan = isPrivateLan(host)
            val https = scheme == "https"
            if (!https && !lan) return Parse.Rejected("Plain http is only possible on your home network. Use https for this server.")
            if (!https && !lanHttpApproved) return Parse.NeedsLanHttpApproval(host)
            val path = (u.rawPath ?: "").trimEnd('/')
            if (path.contains("..") || path.contains("//")) return Parse.Rejected("This is not a valid address.")
            val port = if (u.port > 0) ":${u.port}" else ""
            val hostText = if (host.contains(':')) "[$host]" else host
            return Parse.Ok(ServerAddress("$scheme://$hostText$port$path", host, https, lan))
        }

        fun isLoopbackOrUnspecified(host: String) = host == "localhost" || host.endsWith(".localhost") || host == "localhost.localdomain" ||
            host.startsWith("127.") || host == "0.0.0.0" || host == "::1" || host == "::" || host == "0:0:0:0:0:0:0:1"
        fun isLinkLocal(host: String) = host.startsWith("169.254.") || host.startsWith("fe80:")
        /** HTTP is restricted to canonical RFC 1918 / unique-local IPv6 literals.
         * Names can resolve outside the LAN, and numeric aliases can resolve to loopback. Use HTTPS for named servers.
         */
        fun isPrivateLan(host: String): Boolean {
            if (host.startsWith("10.") || host.startsWith("192.168.")) return ipv4(host)
            if (host.startsWith("172.")) return ipv4(host) && (host.split('.')[1].toIntOrNull() ?: 0) in 16..31
            if (host.startsWith("fc") || host.startsWith("fd")) return host.contains(':')
            return false
        }
        private fun ipv4(host: String) = host.split('.').let { p -> p.size == 4 && p.all { (it.toIntOrNull() ?: -1) in 0..255 && it.toIntOrNull()?.toString() == it } }
    }
}

/** One verified sign-in on one server, owned by one AuraOS profile. [token] is a credential: it is never printed. */
data class ServerAccount(
    val kind: ServerKind, val serverId: String, val serverName: String, val base: String, val lanHttpApproved: Boolean,
    val userId: String, val userName: String, val token: String, val deviceId: String,
) {
    val key: String get() = "${kind.name}:$serverId:$userId"
    override fun toString() = "ServerAccount(${kind.name}, server=$serverName, user=$userName, token=<redacted>)"
}

enum class LibraryType { MOVIES, SHOWS, MUSIC, OTHER }
data class MediaLibrary(val id: String, val name: String, val type: LibraryType)
enum class EntryType { MOVIE, SERIES, EPISODE, ARTIST, ALBUM, TRACK, OTHER }

/** One library item. [imagePath] is a server-relative artwork path (never contains a token); positions are milliseconds. */
data class MediaEntry(
    val id: String, val title: String, val type: EntryType, val year: Int? = null, val imagePath: String? = null,
    val durationMs: Long? = null, val resumeMs: Long? = null, val played: Boolean = false, val seriesTitle: String? = null,
    val artist: String? = null, val album: String? = null,
)
data class MediaPage(val items: List<MediaEntry>, val total: Int)

// ---- HTTP -------------------------------------------------------------------------------------------------------------------------------------

/** [headers] may carry credentials, so a request is never printed with them. */
class MediaRequest(val method: String, val url: String, val headers: Map<String, String> = emptyMap(), val jsonBody: String? = null) {
    override fun toString() = "MediaRequest($method ${url.substringBefore('?')})"
}
data class MediaResponse(val code: Int, val body: String?)

interface MediaHttp { suspend fun send(request: MediaRequest): MediaResponse }

/**
 * Blocking HttpURLConnection on the IO dispatcher. No redirects are followed (a redirect could carry a token to another host), bodies are
 * capped, timeouts are fixed, TLS verification is the platform default and is never relaxed. code 0 = transport failure.
 */
class UrlConnectionMediaHttp(private val maxBody: Int = 4 * 1024 * 1024) : MediaHttp {
    override suspend fun send(request: MediaRequest): MediaResponse = withContext(Dispatchers.IO) {
        val conn = try { URL(request.url).openConnection() as HttpURLConnection } catch (_: Exception) { return@withContext MediaResponse(0, null) }
        try {
            conn.requestMethod = request.method; conn.connectTimeout = 6_000; conn.readTimeout = 12_000
            conn.instanceFollowRedirects = false; conn.useCaches = false
            conn.setRequestProperty("Accept", "application/json")
            request.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            request.jsonBody?.let { body ->
                conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = (if (code in 200..299) conn.inputStream else conn.errorStream) ?: return@withContext MediaResponse(code, null)
            val out = ByteArrayOutputStream(); val buf = ByteArray(8192)
            stream.use { s -> while (true) { val n = s.read(buf); if (n < 0) break; if (out.size() + n > maxBody) return@withContext MediaResponse(code, null); out.write(buf, 0, n) } }
            MediaResponse(code, out.toString("UTF-8"))
        } catch (_: Exception) { MediaResponse(0, null) } finally { try { conn.disconnect() } catch (_: Exception) {} }
    }
}

internal fun MediaResponse.error(): MediaError? = when {
    code == 0 -> MediaError.UNREACHABLE
    code == 401 -> MediaError.AUTH_FAILED
    code == 403 -> MediaError.FORBIDDEN
    code == 404 -> MediaError.NOT_FOUND
    code in 300..399 -> MediaError.BAD_RESPONSE          // redirects are never followed
    code !in 200..299 -> MediaError.SERVER
    else -> null
}

/** Minimal JSON writer for request bodies and the encrypted account store (strings, numbers, booleans, null, lists, maps). */
object JsonOut {
    fun obj(vararg pairs: Pair<String, Any?>): String = write(linkedMapOf(*pairs))
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()
    private fun append(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Number -> sb.append(if (v is Double && (v.isNaN() || v.isInfinite())) "null" else v.toString())
            is Map<*, *> -> { sb.append('{'); var first = true; v.forEach { (k, x) -> if (!first) sb.append(','); first = false; quote(sb, k.toString()); sb.append(':'); append(sb, x) }; sb.append('}') }
            is Iterable<*> -> { sb.append('['); var first = true; v.forEach { x -> if (!first) sb.append(','); first = false; append(sb, x) }; sb.append(']') }
            else -> quote(sb, v.toString())
        }
    }
    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\""); c == '\\' -> sb.append("\\\\"); c == '\n' -> sb.append("\\n"); c == '\r' -> sb.append("\\r"); c == '\t' -> sb.append("\\t")
            c < ' ' || c == ' ' || c == ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
        sb.append('"')
    }
}
