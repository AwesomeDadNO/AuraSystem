package no.aura.os.v2.tmdb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Shares one in-flight job per key between all callers (Home and AuraBox asking for the same feed make one request) and cancels the job when
 * the LAST caller stops waiting (e.g. the page was left), so obsolete requests do not keep running. Finished entries are removed at once:
 * results live in the caches, not here.
 */
class Dedup<K : Any, V>(private val scope: CoroutineScope) {
    private class Entry<V>(val job: Deferred<V>) { var refs = 0 }
    private val map = HashMap<K, Entry<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        val e = synchronized(map) { map.getOrPut(key) { Entry(scope.async(start = CoroutineStart.LAZY) { block() }) }.also { it.refs++ } }
        e.job.start()
        try {
            return e.job.await()
        } finally {
            synchronized(map) {
                e.refs--
                if (e.job.isCompleted) { if (map[key] === e) map.remove(key) }
                else if (e.refs == 0) { if (map[key] === e) map.remove(key); e.job.cancel() }
            }
        }
    }

    val inFlight: Int get() = synchronized(map) { map.size }
}
