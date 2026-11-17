package no.aura.os.v2.livetv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import javax.net.ssl.HttpsURLConnection

/** Pure range policy: reject ignored ranges, malformed offsets, oversized resources and every redirect. */
object PublicMediaRange {
    private fun reject(): Nothing = throw IOException("Stream response was rejected by the public HTTPS policy.")
    fun header(position: Long, length: Long, maxBytes: Long): String? {
        if (position < 0 || length < -1 || length == 0L || length > maxBytes || (length > 0 && position > Long.MAX_VALUE - length)) reject()
        return if (position == 0L && length == -1L) null else "bytes=$position-" + if (length == -1L) "" else (position + length - 1).toString()
    }
    fun validate(code: Int, contentRange: String?, contentLength: Long, position: Long, length: Long, maxBytes: Long): Long {
        header(position,length,maxBytes)
        if (contentLength > maxBytes) reject()
        if (code == 200) {
            if (position != 0L || length != -1L) reject()
            return contentLength.takeIf { it >= 0 } ?: -1
        }
        if (code != 206) reject()
        val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+|\\*)").matchEntire(contentRange.orEmpty()) ?: reject()
        val start = match.groupValues[1].toLongOrNull() ?: reject()
        val end = match.groupValues[2].toLongOrNull() ?: reject()
        val total = if (match.groupValues[3] == "*") null else match.groupValues[3].toLongOrNull() ?: reject()
        if (start != position || end < start || end == Long.MAX_VALUE || (total != null && end >= total)) reject()
        val count = end - start + 1
        if (count > maxBytes || (length != -1L && count != length) || (contentLength >= 0 && contentLength != count)) reject()
        return count
    }
}

/** The sole HLS transport: every manifest, variant, segment and AES key is independently validated before connecting. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3PublicDataSource(private val maxBytes: Long = 64L * 1024 * 1024,
    private val openConnection: (String) -> HttpsURLConnection = LiveTvHttp::open,
    private val elapsed: () -> Long = android.os.SystemClock::elapsedRealtime) : BaseDataSource(true) {
    private var connection: HttpsURLConnection? = null
    private var stream: InputStream? = null
    private var uri: Uri? = null
    private var remaining = -1L
    private var received = 0L
    private var startedAt = 0L
    private var started = false
    override fun open(dataSpec: DataSpec): Long {
        close()
        transferInitializing(dataSpec)
        try {
            if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || dataSpec.httpBody != null || dataSpec.httpRequestHeaders.isNotEmpty()) throw IOException("Custom authentication is unsupported.")
            val raw = dataSpec.uri.toString()
            if (!PublicHttpsPolicy.syntax(raw)) throw IOException("Public HTTPS stream required.")
            val range = PublicMediaRange.header(dataSpec.position,dataSpec.length,maxBytes)
            startedAt = elapsed()
            val con = openConnection(raw)
            connection = con
            range?.let { con.setRequestProperty("Range",it) }
            con.setRequestProperty("Accept-Encoding","identity")
            val code = con.responseCode
            if (con.getHeaderField("Content-Encoding")?.let { !it.equals("identity",true) } == true) throw IOException("Encoded transport unsupported.")
            remaining = PublicMediaRange.validate(code,con.getHeaderField("Content-Range"),con.contentLengthLong,dataSpec.position,dataSpec.length,maxBytes)
            if (elapsed() - startedAt > 60_000) throw IOException("Stream request timed out.")
            stream = con.inputStream; uri = dataSpec.uri; received = 0
            started = true; transferStarted(dataSpec)
            return remaining
        } catch (_: Exception) {
            close()
            // Underlying network exceptions may contain credential-bearing URLs. Do not propagate those strings or causes.
            throw IOException("The stream could not be opened through the public HTTPS transport.")
        }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        try {
            if (elapsed() - startedAt > 60_000 || received >= maxBytes) throw IOException("Stream resource exceeded its limit.")
            val bounded = minOf(length.toLong(),maxBytes - received,if (remaining >= 0) remaining else Long.MAX_VALUE).toInt()
            val n = (stream ?: throw IOException("Stream is closed.")).read(buffer,offset,bounded)
            if (n < 0) { if (remaining > 0) throw EOFException(); return C.RESULT_END_OF_INPUT }
            received += n; if (remaining >= 0) remaining -= n
            bytesTransferred(n); return n
        } catch (_: Exception) { throw IOException("The stream was interrupted or exceeded its safe resource limit.") }
    }
    override fun getUri(): Uri? = uri
    override fun close() {
        try { stream?.close() } catch (_: Exception) { }
        stream = null; connection?.disconnect(); connection = null; uri = null
        if (started) { started = false; transferEnded() }
    }
    class Factory : DataSource.Factory { override fun createDataSource(): DataSource = Media3PublicDataSource() }
}
