package no.aura.os.v2.connect

import no.aura.connect.core.Approval
import no.aura.connect.core.ApprovalHandler
import no.aura.connect.core.CommandContext
import no.aura.connect.core.CommandHandler
import no.aura.connect.core.ConnectServer
import no.aura.connect.core.DeviceIdentity
import no.aura.connect.core.ErrorCode
import no.aura.connect.core.PendingPeer
import no.aura.connect.core.Permission
import no.aura.connect.core.ProtocolException
import no.aura.connect.core.ServerConfig
import no.aura.connect.core.TrustStore
import no.aura.os.v2.amp.AmpAccess
import no.aura.os.v2.amp.AmpProviderProblem
import no.aura.os.v2.amp.AmpProviderSafety
import no.aura.os.v2.amp.AmpVerification
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Connect via phone": the TV receives a Deezer sign-in (ARL) from a phone over AuraConnect (J-PAKE pairing with a one-time code, then a
 * TLS channel pinned to both devices' keys). Platform-free: the whole exchange is unit-tested over real loopback TLS.
 *
 * Wire contract of the one operation used here (`credential.offer`, which the core only authorizes for a peer that holds the
 * CREDENTIAL_RECEIVE permission): args `{ "kind": "deezer-arl", "value": "<arl>" }`; result `{ "stored": true, "access": "<level>",
 * "renew": <bool> }`. The credential is never echoed, logged or put in an error message.
 */
object ArlOffer {
    const val OP = "credential.offer"
    const val KIND = "deezer-arl"
    /** The only permission a phone gets from this flow: it can hand over a credential, nothing else (no playback, no sync). */
    val GRANT: Set<Permission> = setOf(Permission.CREDENTIAL_RECEIVE)
}

/** What happened to an offered credential, for the TV's own screen. */
data class ArlOfferOutcome(val stored: Boolean, val check: AmpVerification?)

/**
 * Accepts exactly one credential, for exactly one local profile.
 * - [targetProfile] is the profile the person chose ON THE TV before pairing. The core takes the profile from the trust record (the phone
 *   cannot pick it); this handler additionally requires that record to be for [targetProfile].
 * - [stillAllowed] is asked at the moment the credential arrives: the Administrator session must still be open and the same adult profile
 *   still active, exactly as for typing the ARL by hand.
 * - [store] stops any stream being prepared, then saves the ARL encrypted for the profile; [verify] runs the three-level Eclipse check.
 * - After one stored credential every further offer in this session is refused.
 */
class ArlOfferHandler(
    private val targetProfile: String, private val stillAllowed: () -> Boolean,
    private val store: (arl: String) -> Boolean, private val verify: () -> AmpVerification?, private val onOutcome: (ArlOfferOutcome) -> Unit,
) : CommandHandler {
    private val used = AtomicBoolean(false)

    override fun handle(context: CommandContext): Map<String, Any?> {
        if (context.op != ArlOffer.OP) throw ProtocolException(ErrorCode.UNKNOWN_OPERATION, "Not available in this session")
        if (context.profileId != targetProfile) throw ProtocolException(ErrorCode.NOT_AUTHORIZED, "This device was approved for another profile")
        if (!stillAllowed()) throw ProtocolException(ErrorCode.NOT_AUTHORIZED, "The TV is no longer ready to receive a sign-in")
        if (context.args["kind"] != ArlOffer.KIND) throw ProtocolException(ErrorCode.BAD_REQUEST, "Unsupported credential kind")
        val arl = (context.args["value"] as? String)?.takeIf { it.length <= 512 }?.let(AmpProviderSafety::parseArl)
            ?: throw ProtocolException(ErrorCode.BAD_REQUEST, "Not a valid Deezer ARL")
        if (!used.compareAndSet(false, true)) throw ProtocolException(ErrorCode.NOT_AUTHORIZED, "A sign-in was already received in this session")
        if (!store(arl)) { used.set(false); onOutcome(ArlOfferOutcome(false, null)); throw ProtocolException(ErrorCode.HANDLER_FAILED, "The TV could not store the sign-in") }
        val check = runCatching { verify() }.getOrNull()
        onOutcome(ArlOfferOutcome(true, check))
        return mapOf("stored" to true, "access" to (check?.access ?: AmpAccess.NOT_CONNECTED).name,
            "renew" to (check?.failure?.problem == AmpProviderProblem.CREDENTIAL_REJECTED))
    }
}

/**
 * One receive session: a listening AuraConnect server that exists only while the "Connect via phone" screen is open.
 * Nothing listens before [start] or after [close]; pairing must be enabled by the build ([pairingEnabled]) or [openCode] throws.
 * [approve] is the TV's own prompt: the person sees the phone's name and key fingerprint and allows or denies. Allowing grants
 * [ArlOffer.GRANT] for [targetProfile] only.
 */
class ArlReceiveSession(
    identity: DeviceIdentity, trustStore: TrustStore, deviceName: String, bindAddress: InetAddress, port: Int, pairingEnabled: Boolean,
    private val targetProfile: String, handler: ArlOfferHandler, approve: (PendingPeer) -> CompletableFuture<Boolean>,
) : Closeable {
    private val server = ConnectServer(ServerConfig(
        identity = identity, name = deviceName.take(48).ifBlank { "AuraOS TV" }, trustStore = trustStore,
        approvalHandler = ApprovalHandler { peer -> approve(peer).thenApply { ok -> if (ok) Approval.Approve(targetProfile, ArlOffer.GRANT) else Approval.Deny } },
        commandHandler = handler, pairingEnabled = pairingEnabled, bindAddress = bindAddress, port = port,
        maxUnauthenticated = 2, maxSessions = 2,
    ))
    private val closed = AtomicBoolean(false)

    /** Starts listening and returns the port. */
    fun start(): Int = server.start()
    /** Opens the two-minute pairing window and returns the six-digit code to show. Throws when pairing is disabled in this build. */
    fun openCode(): String? = server.openPairingWindow()
    override fun close() { if (closed.compareAndSet(false, true)) runCatching { server.pairingWindow.close() }.also { runCatching { server.close() } } }
}

/** Text for the TV screens of this flow (pure, unit-tested). */
object ArlReceiveText {
    fun waiting(address: String, port: Int, code: String): Pair<String, String> =
        "Connect via phone" to ("In AuraAmp on your phone choose Send Deezer sign-in to TV, then enter:\n" +
            "TV address  $address : $port\nCode  ${code.chunked(3).joinToString(" ")}\n" +
            "The code works once and for two minutes. It is not your Deezer sign-in; nothing is sent until you allow the phone on this TV.")
    fun approval(peer: PendingPeer, profileName: String): Pair<String, String> =
        "Allow ${peer.name.take(40)}?" to ("This phone entered the code. Allow it to send a Deezer sign-in to the profile \"$profileName\"?\n" +
            "Check that the same fingerprint is shown on the phone: ${peer.fingerprint}\nIt gets no other access to this TV.")
    const val DISABLED = "Connecting via phone is turned off in this build."
    const val NO_NETWORK = "This TV has no home-network address. Connect it to your network and try again."
}
