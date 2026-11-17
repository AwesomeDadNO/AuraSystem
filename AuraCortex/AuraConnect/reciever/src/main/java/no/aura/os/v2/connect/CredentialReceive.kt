package no.aura.os.v2.connect

import no.aura.connect.android.FileTrustStore
import no.aura.connect.core.CommandContext
import no.aura.connect.core.CommandHandler
import no.aura.connect.core.ErrorCode
import no.aura.connect.core.Permission
import no.aura.connect.core.ProtocolException
import no.aura.os.v2.amp.AmpVerification

/**
 * Receiving a Deezer ARL (or, later, a media-server login) from an already-paired phone over the persistent channel.
 *
 * A pair never carries CREDENTIAL_RECEIVE (see [PairingReceiver]), so a phone cannot push a credential just by being paired. The person first
 * chooses, on the TV, to receive a sign-in from a specific device; that **arms** a short window which adds CREDENTIAL_RECEIVE to that one
 * device's grant. The phone then sends one `credential.offer`; [ArmedCredentialHandler] stores it, runs the Eclipse check, and **disarms** at
 * once, so the permission is gone again. All the rest (profile match, Administrator session, one-per-session, ARL validation, the Eclipse
 * check) is the F6i [ArlOfferHandler]; this only toggles the extra permission around a single transfer.
 */
class CredentialGrantWindow(private val store: FileTrustStore, private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile private var armedDeviceId: String? = null
    @Volatile private var armedUntilMs: Long = 0L

    /** The device currently allowed to send a credential, or null. Expires after the arm TTL. */
    fun armedDevice(): String? = armedDeviceId?.takeIf { clock() < armedUntilMs }

    /** Adds CREDENTIAL_RECEIVE to [deviceId]'s grant for [ttlMs]. Returns false if the device is unknown or revoked. */
    fun arm(deviceId: String, ttlMs: Long = 120_000L): Boolean {
        val record = store.list().firstOrNull { it.deviceId == deviceId && !it.isRevoked } ?: return false
        if (!store.setPermissions(deviceId, record.permissions + Permission.CREDENTIAL_RECEIVE)) return false
        armedDeviceId = deviceId; armedUntilMs = clock() + ttlMs
        return true
    }

    /** Removes CREDENTIAL_RECEIVE from [deviceId]'s grant (back to an ordinary pair). Safe to call more than once. */
    fun disarm(deviceId: String) {
        store.list().firstOrNull { it.deviceId == deviceId && !it.isRevoked }?.let { store.setPermissions(deviceId, it.permissions - Permission.CREDENTIAL_RECEIVE) }
        if (armedDeviceId == deviceId) { armedDeviceId = null; armedUntilMs = 0L }
    }
}

/**
 * The `credential.offer` handler for the persistent channel: accepts the ARL only while a window is armed, and disarms as soon as a
 * credential is stored (success or storage failure), so the extra permission never outlives one transfer.
 */
class ArmedCredentialHandler(
    private val window: CredentialGrantWindow,
    targetProfile: String, stillAllowed: () -> Boolean,
    store: (arl: String) -> Boolean, verify: () -> AmpVerification?, onOutcome: (ArlOfferOutcome) -> Unit,
) : CommandHandler {
    private val inner = ArlOfferHandler(targetProfile, stillAllowed, store, verify) { outcome ->
        window.armedDevice()?.let(window::disarm)      // one credential, then the permission is gone again
        onOutcome(outcome)
    }
    override fun handle(context: CommandContext): Map<String, Any?> {
        if (window.armedDevice() == null) throw ProtocolException(ErrorCode.NOT_AUTHORIZED, "Choose 'Receive a sign-in' on the TV first")
        return inner.handle(context)
    }
}
