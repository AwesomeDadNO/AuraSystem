package no.aura.os.v2.simkl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

data class SimklPairingView(val userCode: String, val verificationUrl: String, val expiresAt: Long,
    val message: String = "Approve this code on your phone.", val checking: Boolean = false, val visible: Boolean = true)
/** Independent from generic Settings sheets: pending/errors/expiry never dismiss the public pairing code. */
class SimklPairingController(private val pin: SimklPin, private val poll: suspend () -> SimklPinStatus,
    private val save: suspend (String) -> Boolean, private val currentProfile: () -> Boolean,
    private val approved: () -> Unit, private val now: () -> Long = System::currentTimeMillis) {
    private val mutable = MutableStateFlow(SimklPairingView(pin.userCode, pin.verificationUrl, pin.expiresAtMs))
    val state: StateFlow<SimklPairingView> get() = mutable
    private val lock = Mutex()
    var intervalMs: Long = pin.intervalSeconds.coerceAtLeast(5) * 1000; private set
    private var nextCheck = 0L
    fun close() { mutable.value = mutable.value.copy(visible = false, checking = false) }
    suspend fun check() {
        if (!mutable.value.visible || !currentProfile() || !lock.tryLock()) return
        try {
            if (now() >= pin.expiresAtMs) { mutable.value = mutable.value.copy(message = "This code expired. Close and request a new code.", checking = false); return }
            if (now() < nextCheck) return
            mutable.value = mutable.value.copy(checking = true)
            val result = try { poll() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { SimklPinStatus.Failed("Connection unavailable. The code remains on screen; retry when connected.") }
            nextCheck = now() + intervalMs
            if (!mutable.value.visible || !currentProfile()) return
            when (result) {
                SimklPinStatus.Pending -> mutable.value = mutable.value.copy(message = "Waiting for approval on your phone.", checking = false)
                SimklPinStatus.SlowDown -> { intervalMs += 5000; nextCheck = now() + intervalMs; mutable.value = mutable.value.copy(message = "Waiting for approval on your phone.", checking = false) }
                is SimklPinStatus.Failed -> mutable.value = mutable.value.copy(message = result.reason, checking = false)
                is SimklPinStatus.Approved -> {
                    val stored = save(result.token)
                    if (!mutable.value.visible || !currentProfile()) return
                    if (stored) { close(); approved() }
                    else mutable.value = mutable.value.copy(message = "Secure storage failed. The account was not connected.", checking = false)
                }
            }
        } finally { lock.unlock() }
    }
    suspend fun run() {
        while (mutable.value.visible && currentProfile()) {
            delay(if (nextCheck == 0L) intervalMs else (nextCheck - now()).coerceAtLeast(250))
            check()
            if (now() >= pin.expiresAtMs) return
        }
    }
}
