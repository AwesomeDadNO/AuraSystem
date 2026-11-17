package no.aura.os.v2.playback

import android.media.MediaDataSource
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** A sequential view over the existing authenticated, range-checked transport. Reopens are seeks,
 * not ownership transfers: only PlayerEngine closes the underlying source at session teardown. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ConfinedPlayerDataSource(private val source: MediaDataSource) : BaseDataSource(true) {
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    private var uri: Uri? = null
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        position = dataSpec.position
        val size = source.size
        if (size >= 0 && position > size) throw IOException("Invalid stream position")
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            size >= 0 -> size - position
            else -> C.LENGTH_UNSET.toLong()
        }
        uri = dataSpec.uri; opened = true; transferStarted(dataSpec)
        return remaining
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!opened) throw IOException("Source not open")
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = if (remaining < 0) length else minOf(length.toLong(), remaining).toInt()
        val read = source.readAt(position, buffer, offset, count)
        if (read < 0) return C.RESULT_END_OF_INPUT
        if (read == 0) throw IOException("Stream returned no data")
        position += read
        if (remaining >= 0) remaining -= read
        bytesTransferred(read)
        return read
    }
    override fun getUri(): Uri? = uri
    override fun close() { uri = null; if (opened) { opened = false; transferEnded() } }
}

