package no.aura.os.v2.livetv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicReference
import java.net.InetAddress
import java.net.URI
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/** User sources are HTTPS only; no credentials in authority, local hosts or numeric alias forms. */
object PublicHttpsPolicy {
    fun syntax(raw: String): Boolean = runCatching {
        if (raw.length !in 1..4096 || raw.any { it.isWhitespace() || it < ' ' }) return false
        val u = URI(raw); val h = u.host?.lowercase() ?: return false
        u.scheme.equals("https", true) && u.rawUserInfo == null && u.rawFragment == null &&
            (u.port == -1 || u.port in 1..65535) &&
            h.matches(Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")) && h.contains('.') &&
            !h.endsWith('.') && h.split('.').all { it.isNotEmpty() && it.length <= 63 } &&
            !h.last().isDigit() && !h.endsWith(".local") && !h.endsWith(".localhost") &&
            !h.endsWith(".internal") && !h.endsWith(".lan") && !h.endsWith(".home")
    }.getOrDefault(false)
    fun publicAddress(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address.map { it.toInt() and 255 }
        return when (b.size) {
            4 -> b[0] !in setOf(0,10,127) && b[0] < 224 &&
                !(b[0] == 100 && b[1] in 64..127) && !(b[0] == 169 && b[1] == 254) &&
                !(b[0] == 172 && b[1] in 16..31) && !(b[0] == 192 && b[1] in setOf(0,168)) &&
                !(b[0] == 198 && b[1] in 18..19) &&
                !(b[0] == 198 && b[1] == 51 && b[2] == 100) &&
                !(b[0] == 203 && b[1] == 0 && b[2] == 113) &&
                !(b[0] == 192 && b[1] == 88 && b[2] == 99)
            16 -> (b[0] and 0xe0) == 0x20 &&
                !(b[0] == 0x20 && b[1] == 0x02) && // 6to4 can encode nonpublic IPv4 destinations
                !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] in setOf(0,2,0x10,0x20)) &&
                !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8) // documentation

            else -> false
        }
    }
    fun resolve(raw: String): List<InetAddress> {
        require(syntax(raw)) { "Use a public HTTPS source without embedded login details." }
        val addresses = InetAddress.getAllByName(URI(raw).host).toList()
        require(addresses.isNotEmpty() && addresses.all(::publicAddress)) { "Source must resolve only to public addresses." }
        return addresses
    }
}

/** Rejects a DNS-rebound peer before TLS or HTTP credentials can be sent; default TLS verification remains active. */
private class PublicPeerTls(private val delegate: SSLSocketFactory, private val allowed: Set<InetAddress>) : SSLSocketFactory() {
    private fun check(socket: Socket): Socket { require(socket.inetAddress in allowed && PublicHttpsPolicy.publicAddress(socket.inetAddress)); return socket }
    override fun getDefaultCipherSuites() = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites() = delegate.supportedCipherSuites
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        try { check(s); return delegate.createSocket(s,host,port,autoClose) } catch(e: Exception) { s.close(); throw e }
    }
    override fun createSocket(host: String, port: Int): Socket = createSocket(Socket(allowed.first(),port),host,port,true)
    override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket = createSocket(Socket(allowed.first(),port,local,localPort),host,port,true)
    override fun createSocket(host: InetAddress, port: Int): Socket = delegate.createSocket(check(Socket(host,port)),host.hostAddress,port,true)
    override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket = delegate.createSocket(check(Socket(host,port,local,localPort)),host.hostAddress,port,true)
}

object LiveTvHttp {
    /** Caller owns disconnect. The factory preserves Android certificate and hostname verification. */
    fun open(raw: String): HttpsURLConnection {
        val addresses = PublicHttpsPolicy.resolve(raw)
        val con = URI(raw).toURL().openConnection() as HttpsURLConnection
        con.sslSocketFactory = PublicPeerTls(HttpsURLConnection.getDefaultSSLSocketFactory(),addresses.toSet())
        con.instanceFollowRedirects = false; con.connectTimeout = 8000; con.readTimeout = 12000
        con.setRequestProperty("Accept-Encoding","identity")
        return con
    }

    suspend fun read(raw: String, maxBytes: Int): String = fetchBounded(maxBytes) { open(raw) }

    /** Cancellation disconnects a blocked request and returns promptly; native DNS may finish on its IO worker. */
    internal suspend fun fetchBounded(maxBytes: Int, open: () -> HttpsURLConnection): String = suspendCancellableCoroutine { continuation ->
        val active = AtomicReference<HttpsURLConnection?>(null)
        continuation.invokeOnCancellation { runCatching { active.get()?.disconnect() } }
        Dispatchers.IO.dispatch(continuation.context, Runnable {
            var con: HttpsURLConnection? = null
            try {
                continuation.context.ensureActive()
                val connection = open()
                con = connection
                active.set(connection)
                continuation.context.ensureActive()
                require(connection.responseCode == 200) { "Source returned an unsuccessful response." }
                continuation.context.ensureActive()
                require(connection.contentLengthLong <= maxBytes) { "Source exceeded the size limit." }
                val text = connection.inputStream.use { stream ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while(true) {
                        continuation.context.ensureActive()
                        val count=stream.read(buffer)
                        continuation.context.ensureActive()
                        if(count<0)break
                        require(out.size()+count<=maxBytes) { "Source exceeded the size limit." }
                        out.write(buffer,0,count)
                    }
                    out.toString("UTF-8")
                }
                if(continuation.isActive)continuation.resume(text)
            } catch(error: Exception) {
                if(continuation.isActive)continuation.resumeWithException(error)
            } finally {
                active.set(null)
                runCatching { con?.disconnect() }
            }
        })
    }
}
