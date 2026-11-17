package no.aura.os.v2.connect

import no.aura.os.v2.mediaserver.ServerAccount
import no.aura.os.v2.mediaserver.ServerAddress
import no.aura.os.v2.mediaserver.ServerKind

/**
 * Receiving a personal media-server sign-in (Plex / Jellyfin / Emby) from an already-paired phone — the server-login twin of the Deezer ARL.
 *
 * A sign-in is a **secret** (an access token), so it never rides the ordinary pair: like the ARL it needs the per-transfer armed window
 * ([CredentialGrantWindow]) the person opens on the TV with "Receive a media-server sign-in". The phone then sends one `credential.offer`
 * with the verified [ServerAccount] it already set up, and the TV stores it for the chosen profile and re-verifies it against the server.
 *
 * Wire contract (`credential.offer`, authorised only for the armed CREDENTIAL_RECEIVE device): args
 * `{ "kind": "plex"|"jellyfin"|"emby", "account": { kind, serverId, serverName, base, lanHttp, userId, userName, token, deviceId } }`;
 * result `{ "stored": true, "verified": <bool>, "error": <string?> }`. The token is never echoed, logged or put in an error message.
 */
object MediaServerOffer {
    /** The `kind` values this handler accepts (lower-cased [ServerKind] names). */
    val KINDS: Set<String> = ServerKind.values().map { it.name.lowercase() }.toSet()
    private const val MAX_FIELD = 512
    private const val MAX_TOKEN = 4096

    /** Parses and validates the offered account from the wire, mirroring the stored-account policy (address must pass [ServerAddress.parse]). */
    fun parseAccount(args: Map<String, Any?>): ServerAccount? {
        val kind = ServerKind.values().firstOrNull { it.name.equals(args["kind"] as? String, ignoreCase = true) } ?: return null
        val o = args["account"] as? Map<*, *> ?: return null
        if ((o["kind"] as? String)?.equals(kind.name, ignoreCase = true) != true) return null
        fun field(name: String, max: Int = MAX_FIELD): String? = (o[name] as? String)?.takeIf { it.isNotBlank() && it.length <= max }
        val base = field("base") ?: return null
        val lanHttp = o["lanHttp"] as? Boolean ?: false
        if (ServerAddress.parse(base, lanHttp) !is ServerAddress.Parse.Ok) return null   // a tampered address can never point a token at a forbidden host
        return ServerAccount(
            kind = kind,
            serverId = field("serverId") ?: return null,
            serverName = field("serverName") ?: kind.title,
            base = base, lanHttpApproved = lanHttp,
            userId = field("userId") ?: return null,
            userName = field("userName") ?: "User",
            token = field("token", MAX_TOKEN) ?: return null,
            deviceId = field("deviceId") ?: return null,
        )
    }
}

/** What happened to an offered media-server sign-in, for the TV's own screen. */
data class MediaServerOutcome(val stored: Boolean, val verified: Boolean, val error: String?)

/**
 * The `credential.offer` handler for a media-server sign-in. [receive] does the real work on the shell side (store the account for the
 * profile, then verify it against the server) and is called off the protocol thread; this class only enforces the profile/consent rules and
 * shapes the reply. [stillAllowed] is checked when the sign-in arrives (Administrator session open, same adult profile active), exactly as
 * for the ARL.
 */
class MediaServerOfferHandler(
    private val targetProfile: String,
    private val stillAllowed: () -> Boolean,
    private val receive: (ServerAccount) -> MediaServerOutcome,
    private val onOutcome: (MediaServerOutcome) -> Unit,
) : CommandHandler {
    override fun handle(context: CommandContext): Map<String, Any?> {
        if (context.op != ArlOffer.OP) throw ProtocolException(ErrorCode.UNKNOWN_OPERATION, "Not available in this session")
        if (context.profileId != targetProfile) throw ProtocolException(ErrorCode.NOT_AUTHORIZED, "This device was approved for another profile")
        if (!stillAllowed()) throw ProtocolException(ErrorCode.NOT_AUTHORIZED, "The TV is no longer ready to receive a sign-in")
        val account = MediaServerOffer.parseAccount(context.args) ?: throw ProtocolException(ErrorCode.BAD_REQUEST, "Not a valid media-server sign-in")
        val outcome = receive(account)
        onOutcome(outcome)
        if (!outcome.stored) throw ProtocolException(ErrorCode.HANDLER_FAILED, "The TV could not store the sign-in")
        return linkedMapOf("stored" to true, "verified" to outcome.verified, "error" to outcome.error)
    }
}

/**
 * The single `credential.offer` handler on the persistent receiver: it checks the armed window once, then routes by `kind` to the Deezer ARL
 * handler or the media-server handler, and disarms after a credential is accepted (so the extra permission never outlives one transfer). A
 * malformed or unknown-kind offer does not disarm — it only fails — and the window still auto-expires on its own two-minute TTL.
 */
class CredentialReceiveHandler(
    private val window: CredentialGrantWindow,
    private val arl: ArlOfferHandler?,
    private val mediaServer: MediaServerOfferHandler?,
) : CommandHandler {
    override fun handle(context: CommandContext): Map<String, Any?> {
        if (context.op != ArlOffer.OP) throw ProtocolException(ErrorCode.UNKNOWN_OPERATION, "Not available in this session")
        if (window.armedDevice() == null) throw ProtocolException(ErrorCode.NOT_AUTHORIZED, "Choose 'Receive a sign-in' on the TV first")
        val inner = when {
            context.args["kind"] == ArlOffer.KIND -> arl
            (context.args["kind"] as? String)?.lowercase() in MediaServerOffer.KINDS -> mediaServer
            else -> null
        } ?: throw ProtocolException(ErrorCode.BAD_REQUEST, "Unsupported credential kind")
        val result = inner.handle(context)                 // throws on a rejected/failed credential; the window then stays armed until its TTL
        window.armedDevice()?.let(window::disarm)           // one accepted credential, then the extra permission is gone again
        return result
    }
}
