package no.aura.os.v2.simkl

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One shared lock for foreground/background imports and refresh-token rotation. */
object SimklSyncLock { val mutex = Mutex() }

sealed interface SimklImportResult {
    data class Success(val inserted: Int) : SimklImportResult
    data class Failed(val reason: String) : SimklImportResult
    object Skipped : SimklImportResult
}

/** Read-only additive import. Revalidates profile + exact credential generation after network I/O. */
class SimklSyncCoordinator(
    private val eligible: () -> Boolean,
    private val credentials: () -> String?,
    private val fetch: suspend (String) -> SimklSyncResult,
    private val apply: (String, SimklSyncResult.Success) -> Int?,
    private val status: (SimklImportResult) -> Unit = {},
    private val preserveRefresh: (String, String) -> Boolean = { _, _ -> false },
) {
    suspend fun sync(): SimklImportResult = SimklSyncLock.mutex.withLock {
        if (!eligible()) return@withLock SimklImportResult.Skipped
        val initial = credentials() ?: return@withLock SimklImportResult.Failed("Stored session could not be decrypted.").also(status)
        val result = fetch(initial)
        if (!eligible() || credentials() != initial) return@withLock SimklImportResult.Skipped
        when (result) {
            is SimklSyncResult.Failed -> {
                if (result.refreshedCredentials != null) preserveRefresh(initial, result.refreshedCredentials)
                SimklImportResult.Failed(result.reason)
            }
            is SimklSyncResult.Success -> apply(initial, result)?.let { SimklImportResult.Success(it) } ?: SimklImportResult.Skipped
        }.also(status)
    }
}
