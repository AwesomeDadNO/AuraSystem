package no.aura.os.v2.images

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Cache-first byte source with **separate concurrency limits** for disk reads and network downloads: slow transfers can occupy every network
 * permit without delaying images that are already on disk. Cancellation propagates through the semaphores (withPermit releases on cancel).
 */
class TieredFetch(
    private val disk: suspend (String) -> ByteArray?, private val network: suspend (String) -> ByteArray?,
    private val onDiskWrite: (String, ByteArray) -> Unit = { _, _ -> },
    diskPermits: Int = DISK_PERMITS, networkPermits: Int = NETWORK_PERMITS,
) {
    private val diskGate = Semaphore(diskPermits)
    private val netGate = Semaphore(networkPermits)

    /** Where the bytes came from, so callers can account disk hits vs downloads. */
    enum class Source { DISK, NETWORK }
    class Result(val bytes: ByteArray, val source: Source)

    suspend fun fetch(url: String): Result? {
        diskGate.withPermit { disk(url) }?.let { return Result(it, Source.DISK) }
        val b = netGate.withPermit { network(url) } ?: return null
        onDiskWrite(url, b)
        return Result(b, Source.NETWORK)
    }

    companion object { const val DISK_PERMITS = 3; const val NETWORK_PERMITS = 4 }
}

/** Decode sizing: powers-of-two subsampling so a decoded bitmap is never much larger than the width the URL variant was meant for. */
object DecodeSizing {
    const val MAX_PIXELS = 2_400_000   // ~ 1920x1250: above this a subsample is forced regardless of target

    /** Largest power-of-two [sample] such that the result is still >= [targetWidth] wide (and under MAX_PIXELS); 1 = decode as is. */
    fun sampleSize(srcWidth: Int, srcHeight: Int, targetWidth: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || targetWidth <= 0) return 1
        var s = 1
        while (srcWidth / (s * 2) >= targetWidth) s *= 2
        while (srcWidth.toLong() / s * (srcHeight / s) > MAX_PIXELS) s *= 2
        return s
    }
}
