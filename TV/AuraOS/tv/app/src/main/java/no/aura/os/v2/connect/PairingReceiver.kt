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
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The TV's persistent AuraConnect endpoint: pair once with a QR/code, and the phone stays a trusted device that can reconnect on its pinned
 * key. Unlike the one-shot ARL receiver (F6i), the trust is written to [trustStore] (a FileTrustStore on the device), so it survives restarts
 * and shows up in "Paired devices".
 *
 * Pairing grants the everyday controller permissions ([grant], default [Permission.DEFAULT_CONTROLLER]: playback, queue, playlist sync) for
 * the profile the person picks on the TV. It never grants CREDENTIAL_RECEIVE: sending an ARL or a media-server login is a separate,
 * per-transfer consent (handled elsewhere), not something a scan authorizes.
 *
 * [handlers] maps an operation name to its implementation. Operations that are granted but have no handler yet answer "not available",
 * never a silent success — so this receiver is safe to run before the sync engine exists. Nothing listens before [start] or after [close].
 */
class PairingReceiver(
    identity: DeviceIdentity, trustStore: TrustStore, private val deviceName: String,
    bindAddress: InetAddress, port: Int, pairingEnabled: Boolean,
    targetProfile: () -> String, approve: (PendingPeer) -> CompletableFuture<Boolean>,
    grant: Set<Permission> = Permission.DEFAULT_CONTROLLER,
    handlers: Map<String, (CommandContext) -> Map<String, Any?>> = emptyMap(),
) : Closeable {
    private val server = ConnectServer(ServerConfig(
        identity = identity, name = deviceName.take(48).ifBlank { "AuraOS TV" }, trustStore = trustStore,
        approvalHandler = ApprovalHandler { peer -> approve(peer).thenApply { ok -> if (ok) Approval.Approve(targetProfile(), grant) else Approval.Deny } },
        commandHandler = CommandHandler { ctx ->
            (handlers[ctx.op] ?: throw ProtocolException(ErrorCode.HANDLER_FAILED, "This operation is not available on the TV yet")).invoke(ctx)
        },
        pairingEnabled = pairingEnabled, bindAddress = bindAddress, port = port, maxUnauthenticated = 2, maxSessions = 4,
    ))
    private val closed = AtomicBoolean(false)
    @Volatile private var boundPort = 0

    val deviceId: String = identity.deviceId

    fun start(): Int = server.start().also { boundPort = it }
    /** Opens the two-minute pairing window and returns the six-digit code. Throws when pairing is disabled in this build. */
    fun openCode(): String? = server.openPairingWindow()
    /** The payload for the QR / manual entry, given the TV's home-network [host] and the current pairing [code]. */
    fun payload(host: String, code: String?): PairingPayload = PairingPayload(host, boundPort, deviceId, code)
    /** Revokes a device and drops its live sessions at once. */
    fun revoke(deviceId: String): Boolean = server.revoke(deviceId)
    override fun close() { if (closed.compareAndSet(false, true)) { runCatching { server.pairingWindow.close() }; runCatching { server.close() } } }
}
