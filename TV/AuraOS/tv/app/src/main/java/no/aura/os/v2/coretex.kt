// =====================================================================================
// AuraCortex – felles hjerne for alle Aura-apper (coretex.kt)
// =====================================================================================
// Slipp denne filen inn i appen og bygg. Appene er skall som kaller AuraCortex – all logikk bor her.
// Kun Java/Kotlin-standardbibliotek (ingen android.*, ingen eksterne biblioteker):
// virker på Android 8+ (minSdk 26) og vanlig JVM (Desktop).
//
// Skallet ved oppstart (f.eks. TV):
//   AuraCortex.start(AuraCortex.Config(
//       dataDir = context.filesDir,
//       deviceName = "Stue-TV",
//       deviceType = AuraConnect.DeviceType.TV,
//       callbackExecutor = ContextCompat.getMainExecutor(context),   // valgfritt: callbacks på UI-tråden
//   ))
//   AuraCortex.connect.registerCommand("playback.pause", Permission.PLAYBACK_CONTROL) { ctx, respond ->
//       spiller.pause(); respond(CommandResult.ok())
//   }
//   AuraCortex.connect.onApprovePairing = { req, decide -> vis dialog; decide(Approval.Approve(profilId, Permission.DEFAULT_CONTROLLER)) }
//   AuraCortex.connect.onShowSas = { tall -> vis kontrolltall }
//   val pairing = AuraCortex.connect.startAdvertisingAsReceiver()      // vis pairing.qrData som QR og pairing.code som tekst
//
// Mobil:
//   AuraCortex.connect.pairWithQr(skannetTekst) { result -> }        // eller pairWithDevice(device, kode) + onConfirmSas
//   AuraCortex.connect.call(tvId, "playback.pause") { result -> }
//
// Paring skjer én gang. Etterpå kobler enhetene seg til hverandre automatisk, også etter restart og ny IP.
// Alle callbacks leveres på callbackExecutor (standard: AuraCortex sin egen tråd – hold dem korte).

package no.aura.cortex

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

object AuraCortex {
    const val DEFAULT_PORT = 47131
    private const val KEY_DEVICE_ID = "aura.cortex.deviceId"

    val guard = AuraGuard()
    val connect = AuraConnect(guard)
    val music = AuraMusicEngine()
    val video = AuraVideoEngine()
    val os = AuraOSEngine()
    val tv = AuraTVEngine()
    val sports = AuraSports()

    class Config(
        /** Appens private mappe (Android: context.filesDir). Nøkler og parede enheter lagres her. */
        val dataDir: File,
        val deviceName: String,
        val deviceType: AuraConnect.DeviceType,
        val port: Int = DEFAULT_PORT,
        /** Adressen serveren lytter på. Null = enhetens private hjemmenett-adresse (aldri alle grensesnitt). */
        val bindAddress: InetAddress? = null,
        val policy: AuraGuard.GuardPolicy = AuraGuard.GuardPolicy(),
        /** Valgfritt: egen lagring (f.eks. Android Keystore). Standard: fil i [dataDir]. */
        val storage: AuraGuard.SecureStorage? = null,
        /** Hvor callbacks til appen kjøres (f.eks. UI-tråden). Standard: AuraCortex sin egen tråd. */
        val callbackExecutor: Executor? = null,
    )

    private var transport: TcpTransport? = null
    private var loop: ExecutorService? = null

    val isStarted: Boolean @Synchronized get() = transport != null

    /** Starter AuraCortex. Trygt å kalle flere ganger. */
    @Synchronized
    fun start(config: Config) {
        if (transport != null) return
        val storage = config.storage ?: FileSecureStorage(File(config.dataDir, "aura-cortex"))
        guard.configure(JcaGuardCrypto(), storage, config.policy)
        val deviceId = storage.get(KEY_DEVICE_ID) ?: guard.randomId().also { storage.put(KEY_DEVICE_ID, it) }
        val loopExecutor = Executors.newSingleThreadExecutor(daemonThreads("aura-cortex"))
        val t = TcpTransport(config.port, config.bindAddress) { guard.policy.maxMessageBytes }
        connect.configure(
            t, AuraConnect.DeviceIdentity(deviceId, config.deviceName.take(48), config.deviceType),
            loopExecutor, config.callbackExecutor ?: loopExecutor,
        )
        t.open()
        transport = t
        loop = loopExecutor
        connect.startAdvertisingForPairedDevices()
        connect.reconnectAll()
    }

    /** Kall når nettverket endres (ny Wi-Fi, ny IP): lytter på nytt og kobler til parede enheter igjen. */
    @Synchronized
    fun restartNetwork() {
        val t = transport ?: return
        t.close()
        t.open()
        connect.startAdvertisingForPairedDevices()
        connect.reconnectAll()
    }

    @Synchronized
    fun stop() {
        transport?.close()
        loop?.shutdown()
        transport = null
        loop = null
    }
}

// =========================================================
// Tillatelser og godkjenning
// =========================================================
enum class Permission {
    PLAYBACK_STATUS, PLAYBACK_CONTROL, QUEUE_EDIT, PLAYLIST_SYNC, WATCHLIST_SYNC, MEDIA_SEND, USER_SYNC,
    /** Aldri en del av standard-tilgangen; kun via eget samtykke. */
    CREDENTIAL_RECEIVE;

    companion object {
        /** Det godkjenningsdialogen forhåndsvelger for en mobil-kontroller. */
        val DEFAULT_CONTROLLER: Set<Permission> = setOf(PLAYBACK_STATUS, PLAYBACK_CONTROL, QUEUE_EDIT, PLAYLIST_SYNC)
    }
}

sealed class Approval {
    /** [profileId] er den LOKALE profilen brukeren valgte på denne enheten – motparten kan ikke velge eller endre den. */
    data class Approve(val profileId: String, val permissions: Set<Permission>) : Approval()
    object Deny : Approval()
}

enum class CallError { UNREACHABLE, NOT_PAIRED, NOT_ALLOWED, UNKNOWN_OPERATION, BAD_REQUEST, DENIED, TIMEOUT, FAILED }

data class CommandResult(
    val ok: Boolean,
    val data: Map<String, Any?> = emptyMap(),
    val error: CallError? = null,
    val message: String? = null,
) {
    companion object {
        fun ok(data: Map<String, Any?> = emptyMap()) = CommandResult(true, data)
        fun fail(error: CallError, message: String? = null) = CommandResult(false, error = error, message = message)
    }
}

/** [args] inneholder kun JSON-kompatible verdier (String, Long, Double, Boolean, null, List, Map). */
class CommandContext(
    val device: AuraConnect.PairedDevice,
    val profileId: String,
    val op: String,
    val args: Map<String, Any?>,
)

fun interface CommandHandler {
    /** Kall [respond] nøyaktig én gang, gjerne senere fra en annen tråd. Data må være JSON-kompatible. */
    fun handle(context: CommandContext, respond: (CommandResult) -> Unit)
}

/** Stable AuraAmp/AuraOS command names and wire values shared by every app importing Coretex. */
object AuraConnectContract {
    const val PLAYBACK_STATUS = "playback.status"
    const val PLAYBACK_PLAY = "playback.play"
    const val PLAYBACK_PAUSE = "playback.pause"
    const val PLAYBACK_NEXT = "playback.next"
    const val PLAYBACK_PREVIOUS = "playback.previous"
    const val PLAYBACK_SEEK = "playback.seek"
    const val PLAYBACK_SHUFFLE = "playback.shuffle"
    const val PLAYBACK_REPEAT = "playback.repeat"
    const val PLAYBACK_HANDOFF = "playback.handoff"
    const val QUEUE_ADD = "queue.add"
    const val QUEUE_REMOVE = "queue.remove"
    const val QUEUE_MOVE = "queue.move"
    const val QUEUE_CLEAR = "queue.clear"
    const val PLAYLIST_SYNC = "sync.playlists"
    const val PLAYLIST_PUSH = "playlists.push"
    const val CREDENTIAL_OFFER = "credential.offer"
    const val DEEZER_ARL_KIND = "deezer-arl"

    val requiredPermissions: Map<String, Permission> = mapOf(
        PLAYBACK_STATUS to Permission.PLAYBACK_STATUS,
        PLAYBACK_PLAY to Permission.PLAYBACK_CONTROL,
        PLAYBACK_PAUSE to Permission.PLAYBACK_CONTROL,
        PLAYBACK_NEXT to Permission.PLAYBACK_CONTROL,
        PLAYBACK_PREVIOUS to Permission.PLAYBACK_CONTROL,
        PLAYBACK_SEEK to Permission.PLAYBACK_CONTROL,
        PLAYBACK_SHUFFLE to Permission.PLAYBACK_CONTROL,
        PLAYBACK_REPEAT to Permission.PLAYBACK_CONTROL,
        PLAYBACK_HANDOFF to Permission.PLAYBACK_CONTROL,
        QUEUE_ADD to Permission.QUEUE_EDIT,
        QUEUE_REMOVE to Permission.QUEUE_EDIT,
        QUEUE_MOVE to Permission.QUEUE_EDIT,
        QUEUE_CLEAR to Permission.QUEUE_EDIT,
        PLAYLIST_SYNC to Permission.PLAYLIST_SYNC,
        PLAYLIST_PUSH to Permission.PLAYLIST_SYNC,
        CREDENTIAL_OFFER to Permission.CREDENTIAL_RECEIVE,
    )

    fun permissionFor(op: String): Permission? = requiredPermissions[op]
}

/** Coretex-owned, one-device/one-transfer credential grant used by receiving shells. */
class AuraCredentialGrantWindow(private val clock: () -> Long = System::currentTimeMillis) {
    private val connect get() = AuraCortex.connect
    @Volatile private var armedDeviceId: String? = null
    @Volatile private var armedUntilMs: Long = 0L

    fun armedDevice(): String? = armedDeviceId?.takeIf { clock() < armedUntilMs }

    fun arm(deviceId: String, ttlMs: Long = 120_000L): Boolean {
        val paired = connect.pairedDevice(deviceId) ?: return false
        connect.setPermissions(deviceId, paired.permissions + Permission.CREDENTIAL_RECEIVE)
        armedDeviceId = deviceId
        armedUntilMs = clock() + ttlMs.coerceAtLeast(1L)
        return true
    }

    fun disarm(deviceId: String) {
        connect.pairedDevice(deviceId)?.let { connect.setPermissions(deviceId, it.permissions - Permission.CREDENTIAL_RECEIVE) }
        if (armedDeviceId == deviceId) {
            armedDeviceId = null
            armedUntilMs = 0L
        }
    }
}

/** Metadata-only handoff contract. A stream URL, local path, or credential must never cross AuraConnect in this object. */
data class AuraTrackReference(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val isrc: String? = null,
    val cover: String? = null,
) {
    fun toWire(): Map<String, Any?> = linkedMapOf(
        "id" to id, "title" to title, "artist" to artist, "durationMs" to durationMs, "isrc" to isrc, "cover" to cover,
    )

    companion object {
        fun fromWire(value: Any?): AuraTrackReference? {
            val map = value as? Map<*, *> ?: return null
            val id = (map["id"] as? String)?.takeIf { it.isNotBlank() && it.length <= 512 } ?: return null
            val title = (map["title"] as? String).orEmpty().take(512)
            val artist = (map["artist"] as? String).orEmpty().take(512)
            val durationMs = (map["durationMs"] as? Number)?.toLong()?.takeIf { it in 0L..86_400_000L } ?: 0L
            val isrc = (map["isrc"] as? String)?.take(64)
            val cover = (map["cover"] as? String)?.take(2048)
            return AuraTrackReference(id, title, artist, durationMs, isrc, cover)
        }
    }
}

/** Platform adapter implemented by the receiving app around its own live music player. */
interface AuraPlaybackTarget {
    fun resume(); fun pause(); fun next(); fun previous()
    fun seek(positionMs: Long)
    fun setShuffle(on: Boolean?); fun setRepeat(on: Boolean?)
    fun add(track: AuraTrackReference, position: String)
    fun removeFromQueue(index: Int); fun moveInQueue(from: Int, to: Int); fun clearQueue()
    fun handoff(track: AuraTrackReference, upcoming: List<AuraTrackReference>)
    fun snapshot(): Map<String, Any?>
}

class AuraConnectCommandException(val callError: CallError, message: String) : Exception(message)

/** Shared validation and dispatch for the AuraAmp remote; shells provide only the platform playback adapter. */
class AuraPlaybackCommandHandler(private val target: () -> AuraPlaybackTarget?) {
    fun execute(context: CommandContext): Map<String, Any?> {
        val op = context.op
        if (op !in PLAYBACK_OPERATIONS) throw AuraConnectCommandException(CallError.UNKNOWN_OPERATION, "Not available in this session")
        val player = target() ?: throw AuraConnectCommandException(CallError.FAILED, "No player is running for this profile")
        val args = context.args
        when (op) {
            AuraConnectContract.PLAYBACK_STATUS -> Unit
            AuraConnectContract.PLAYBACK_PLAY -> player.resume()
            AuraConnectContract.PLAYBACK_PAUSE -> player.pause()
            AuraConnectContract.PLAYBACK_NEXT -> player.next()
            AuraConnectContract.PLAYBACK_PREVIOUS -> player.previous()
            AuraConnectContract.PLAYBACK_SEEK -> player.seek((args["positionMs"] as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L)
            AuraConnectContract.PLAYBACK_SHUFFLE -> player.setShuffle(args["on"] as? Boolean)
            AuraConnectContract.PLAYBACK_REPEAT -> player.setRepeat(args["on"] as? Boolean)
            AuraConnectContract.QUEUE_ADD -> {
                val track = AuraTrackReference.fromWire(args["track"])
                    ?: (args["trackId"] as? String)?.takeIf { it.isNotBlank() }?.let { AuraTrackReference(it, "", "", 0L) }
                    ?: throw AuraConnectCommandException(CallError.BAD_REQUEST, "trackId required")
                val position = (args["position"] as? String ?: "end").lowercase()
                if (position !in setOf("end", "next")) throw AuraConnectCommandException(CallError.BAD_REQUEST, "position must be end or next")
                player.add(track, position)
            }
            AuraConnectContract.QUEUE_REMOVE -> player.removeFromQueue(((args["index"] as? Number)?.toInt() ?: 0).coerceAtLeast(0))
            AuraConnectContract.QUEUE_MOVE -> player.moveInQueue(
                ((args["from"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                ((args["to"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
            )
            AuraConnectContract.QUEUE_CLEAR -> player.clearQueue()
            AuraConnectContract.PLAYBACK_HANDOFF -> {
                val track = AuraTrackReference.fromWire(args["track"])
                    ?: throw AuraConnectCommandException(CallError.BAD_REQUEST, "A track is required")
                val rawUpcoming = args["upcoming"] as? List<*> ?: emptyList<Any?>()
                if (rawUpcoming.size > MAX_HANDOFF_TRACKS) throw AuraConnectCommandException(CallError.BAD_REQUEST, "Too many upcoming tracks")
                val upcoming = rawUpcoming.map { AuraTrackReference.fromWire(it) }
                if (upcoming.any { it == null }) throw AuraConnectCommandException(CallError.BAD_REQUEST, "Invalid upcoming track metadata")
                player.handoff(track, upcoming.filterNotNull())
            }
        }
        return player.snapshot()
    }

    companion object {
        const val MAX_HANDOFF_TRACKS = 200
        val PLAYBACK_OPERATIONS = setOf(
            AuraConnectContract.PLAYBACK_STATUS, AuraConnectContract.PLAYBACK_PLAY, AuraConnectContract.PLAYBACK_PAUSE,
            AuraConnectContract.PLAYBACK_NEXT, AuraConnectContract.PLAYBACK_PREVIOUS, AuraConnectContract.PLAYBACK_SEEK,
            AuraConnectContract.PLAYBACK_SHUFFLE, AuraConnectContract.PLAYBACK_REPEAT, AuraConnectContract.PLAYBACK_HANDOFF,
            AuraConnectContract.QUEUE_ADD, AuraConnectContract.QUEUE_REMOVE, AuraConnectContract.QUEUE_MOVE, AuraConnectContract.QUEUE_CLEAR,
        )
    }
}

data class AuraPlaylistReference(
    val id: String,
    val title: String,
    val description: String,
    val tracks: List<AuraTrackReference>,
) {
    fun toWire(): Map<String, Any?> = linkedMapOf(
        "id" to id, "title" to title, "description" to description, "tracks" to tracks.map(AuraTrackReference::toWire),
    )
}

/** App-shell persistence seam for playlists pushed by a paired AuraConnect phone. */
interface AuraPlaylistSink {
    fun canReceive(): Boolean
    fun importOne(playlist: AuraPlaylistReference)
}

/** Shared playlist payload validation and import result handling. Storage remains in the receiving app shell. */
class AuraPlaylistPushHandler(private val sink: () -> AuraPlaylistSink?) {
    fun execute(context: CommandContext): Map<String, Any?> {
        if (context.op != AuraConnectContract.PLAYLIST_PUSH)
            throw AuraConnectCommandException(CallError.UNKNOWN_OPERATION, "Not available in this session")
        val target = sink() ?: throw AuraConnectCommandException(CallError.FAILED, "Playlist storage is unavailable")
        if (!target.canReceive()) throw AuraConnectCommandException(CallError.NOT_ALLOWED, "This profile cannot receive playlists")
        val bodies = context.args["playlists"] as? List<*>
            ?: throw AuraConnectCommandException(CallError.BAD_REQUEST, "Playlists must be a list")
        if (bodies.size > MAX_PLAYLISTS) throw AuraConnectCommandException(CallError.BAD_REQUEST, "Send at most $MAX_PLAYLISTS playlists per call")

        var imported = 0
        val skipped = ArrayList<Map<String, Any?>>()
        for (raw in bodies) {
            val body = raw as? Map<*, *>
            val id = (body?.get("id") as? String)?.takeIf { it.isNotBlank() && it.length <= 512 }
            if (body == null || id == null) {
                skipped.add(linkedMapOf("id" to (id ?: ""), "reason" to "Invalid playlist metadata"))
                continue
            }
            val rows = body["tracks"] as? List<*>
            if (rows == null || rows.size > MAX_TRACKS) {
                skipped.add(linkedMapOf("id" to id, "reason" to "Invalid or over-limit playlist tracks"))
                continue
            }
            val tracks = rows.map { AuraTrackReference.fromWire(it) }
            if (tracks.any { it == null }) {
                skipped.add(linkedMapOf("id" to id, "reason" to "Invalid track metadata"))
                continue
            }
            val playlist = AuraPlaylistReference(
                id = id,
                title = (body["title"] as? String ?: body["name"] as? String ?: "Playlist").take(512),
                description = (body["description"] as? String ?: "").take(2048),
                tracks = tracks.filterNotNull(),
            )
            try {
                target.importOne(playlist)
                imported++
            } catch (e: IllegalArgumentException) {
                skipped.add(linkedMapOf("id" to id, "reason" to (e.message ?: "rejected")))
            }
        }
        return linkedMapOf("imported" to imported.toLong(), "skipped" to skipped)
    }

    companion object {
        const val MAX_PLAYLISTS = 25
        const val MAX_TRACKS = 2000
    }
}

// =========================================================
// AuraGuard – sikkerhetssystemet for alle apper
// =========================================================
//  1. Identitet:    hver enhet har et eget nøkkelpar (P-256) som lagres – paring gjelder "en gang for alt".
//  2. Paring:       nøkkelutveksling beskyttet mot mellomleddangrep (QR-fingeravtrykk eller kontrolltall + engangskode),
//                   pluss godkjenning, profil og tillatelser valgt på mottakeren.
//  3. Sikker kanal: all trafikk etter paring er kryptert og autentisert (AES-256-GCM), nye nøkler per økt
//                   (forward secrecy) og beskyttelse mot replay.
//  4. Brannmur:     kun lokalnett, rate-limit, maks meldingsstørrelse, automatisk blokkering etter gjentatte feil.
//  5. Innhold:      validering av innkommende data, URL-allowlist, filinspeksjon (hash/kjørbare filer), maskering i logger.
//
// MERK: Ekte antivirus (skanning av hele enheten) kan ikke gjøres fra app-kode; OS-sandkassen tar seg av det.
// AuraGuard sørger for at ingenting skadelig eller ukjent slipper inn GJENNOM Aura-appene.
class AuraGuard {

    // ---- Plattform-grensesnitt (standard: JcaGuardCrypto / FileSecureStorage nederst i filen) ----
    interface GuardCrypto {
        fun randomBytes(size: Int): ByteArray
        /** (privat, offentlig) – kodet (PKCS#8 / X.509). */
        fun generateKeyPair(): Pair<ByteArray, ByteArray>
        /** Delt hemmelighet. Kaster ved ugyldig nøkkel. */
        fun agree(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray
        fun sha256(data: ByteArray): ByteArray
        fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
        /** AEAD: ciphertext || tag. */
        fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray
        /** Null hvis meldingen er manipulert. */
        fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray?
    }

    interface SecureStorage {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
    }

    class InMemoryStorage : SecureStorage {
        private val map = mutableMapOf<String, String>()
        @Synchronized override fun get(key: String) = map[key]
        @Synchronized override fun put(key: String, value: String) { map[key] = value }
        @Synchronized override fun remove(key: String) { map.remove(key) }
    }

    data class GuardPolicy(
        val allowOnlyLocalNetwork: Boolean = true,
        val maxMessageBytes: Int = 2 * 1024 * 1024,
        val messagesPerSecond: Double = 20.0,
        val burst: Int = 40,
        val maxFailuresBeforeBlock: Int = 10,
        val failureWindow: Duration = 10.minutes,
        val blockDuration: Duration = 15.minutes,
        val sessionLifetime: Duration = 60.minutes,
        val pairingCodeLifetime: Duration = 5.minutes,
        val maxPairingAttempts: Int = 5,
        val allowedUrlHosts: Set<String> = setOf("deezer.com", "dzcdn.net"),
        val blockedSha256: Set<String> = emptySet(),
    )

    enum class EventType {
        BLOCKED_ADDRESS, RATE_LIMITED, OVERSIZED_MESSAGE, NON_LOCAL_ADDRESS, MALFORMED_MESSAGE,
        UNKNOWN_DEVICE, TAMPERED_MESSAGE, REPLAYED_MESSAGE, PAIRING_FAILED, PAIRING_LOCKED,
        KEY_MISMATCH, DEVICE_PAIRED, DEVICE_UNPAIRED, UNSAFE_CONTENT, NOT_ALLOWED, SUSPICIOUS,
    }

    data class SecurityEvent(val type: EventType, val address: String?, val detail: String, val atMillis: Long)

    data class TrustedDevice(
        val deviceId: String,
        val name: String,
        val type: String,
        val publicKeyHex: String,
        val fingerprint: String,
        val lastAddress: String,
        /** Slettet lokalt, men den andre enheten har ikke fått beskjed ennå. Kan ikke brukes til noe annet. */
        val pendingUnpair: Boolean = false,
        /** Lokal profil motparten er knyttet til (tom på enheten som startet paringen). */
        val profileId: String = "",
        /** Hva motparten har lov til å gjøre på DENNE enheten. */
        val permissions: Set<Permission> = emptySet(),
        val pairedAtMs: Long = 0,
    )

    /** [port] = porten denne enheten lytter på (sendes kryptert under paring, så motparten kan nå oss tilbake). */
    data class PeerInfo(val deviceId: String, val name: String, val type: String, val port: Int? = null)
    data class PairingOffer(val code: String, val keyFingerprint: String)
    class SealedMessage(val sid: String, val counter: Long, val cipherHex: String)

    /** En enhet som har bevist riktig kode og venter på godkjenning på mottakeren. */
    class PendingApproval internal constructor(
        val deviceId: String, val name: String, val type: String, val fingerprint: String, val address: String,
        internal val publicKeyHex: String, internal val pairing: Any, internal val peer: Any,
    )

    sealed class Step<out T> {
        data class Ok<T>(val value: T) : Step<T>()
        data class Fail(val error: AuraConnect.PairingError) : Step<Nothing>()
    }

    enum class ContentVerdict { SAFE, HASH_MISMATCH, KNOWN_MALWARE, EXECUTABLE, ARCHIVE }

    // ---- Tilstand ----
    private var cryptoOrNull: GuardCrypto? = null
    private val crypto: GuardCrypto
        get() = cryptoOrNull ?: error("AuraGuard er ikke konfigurert: kall AuraCortex.start(...) ved oppstart")
    private var storage: SecureStorage = InMemoryStorage()
    @Volatile var policy: GuardPolicy = GuardPolicy()
        private set
    val isConfigured: Boolean get() = cryptoOrNull != null

    private val epoch = TimeSource.Monotonic.markNow()
    var clock: () -> Long = { epoch.elapsedNow().inWholeMilliseconds }
    var wallClock: () -> Long = { System.currentTimeMillis() }
    var onSecurityEvent: (SecurityEvent) -> Unit = {}
    private val events = ArrayDeque<SecurityEvent>()

    private var identityPrivate = ByteArray(0)
    var identityPublic = ByteArray(0)
        private set
    val identityFingerprint: String get() = fingerprintOf(identityPublic)

    private val trusted = mutableMapOf<String, TrustedDevice>()

    @Synchronized
    fun configure(crypto: GuardCrypto, storage: SecureStorage = InMemoryStorage(), policy: GuardPolicy = GuardPolicy()) {
        cryptoOrNull = crypto
        this.storage = storage
        this.policy = policy
        sessions.clear(); outgoing.clear(); trusted.clear(); receiverPairing = null

        val saved = storage.get(KEY_IDENTITY)?.split(':')
        val priv = saved?.getOrNull(0)?.hexToBytes()
        val pub = saved?.getOrNull(1)?.hexToBytes()
        if (priv != null && pub != null && priv.isNotEmpty() && pub.isNotEmpty()) {
            identityPrivate = priv; identityPublic = pub
        } else {
            val (p, q) = crypto.generateKeyPair()
            identityPrivate = p; identityPublic = q
            storage.put(KEY_IDENTITY, p.toHex() + ":" + q.toHex())
        }
        storage.get(KEY_TRUSTED)?.let { loadTrusted(it) }
    }

    @Synchronized fun randomId(): String = crypto.randomBytes(16).toHex()

    // =====================================================
    // 1. Tillitsregister (parede enheter, lagres permanent)
    // =====================================================
    /** Aktive parede enheter (uten de som venter på å få beskjed om sletting). */
    @Synchronized fun trustedDevices(): List<TrustedDevice> = trusted.values.filter { !it.pendingUnpair }
    @Synchronized fun trustedDevice(deviceId: String): TrustedDevice? = trusted[deviceId]?.takeIf { !it.pendingUnpair }
    @Synchronized internal fun anyDevice(deviceId: String): TrustedDevice? = trusted[deviceId]

    @Synchronized
    private fun trust(device: TrustedDevice) {
        trusted[device.deviceId] = device
        dropSessionsFor(device.deviceId)
        saveTrusted()
        emit(EventType.DEVICE_PAIRED, device.lastAddress, "${device.name} (${device.fingerprint.take(16)})")
    }

    /** Endrer hva en paret enhet har lov til på denne enheten. */
    @Synchronized
    fun setPermissions(deviceId: String, permissions: Set<Permission>) {
        val d = trusted[deviceId] ?: return
        trusted[deviceId] = d.copy(permissions = permissions)
        saveTrusted()
    }

    @Synchronized
    fun setProfile(deviceId: String, profileId: String) {
        val d = trusted[deviceId] ?: return
        trusted[deviceId] = d.copy(profileId = profileId)
        saveTrusted()
    }

    /** Sletter paringen lokalt; enheten beholdes som "venter på beskjed" til den andre siden er informert. */
    @Synchronized
    fun markUnpaired(deviceId: String) {
        val d = trusted[deviceId] ?: return
        trusted[deviceId] = d.copy(pendingUnpair = true, permissions = emptySet())
        saveTrusted()
        emit(EventType.DEVICE_UNPAIRED, d.lastAddress, d.name)
    }

    /** Fjerner alt om enheten for godt (nøkler og økter). */
    @Synchronized
    fun forget(deviceId: String) {
        val d = trusted.remove(deviceId) ?: return
        dropSessionsFor(deviceId)
        saveTrusted()
        if (!d.pendingUnpair) emit(EventType.DEVICE_UNPAIRED, d.lastAddress, d.name)
    }

    /** Oppdaterer adressen. Beholder portnummeret hvis den nye adressen ikke har eget. */
    @Synchronized
    internal fun updateAddress(deviceId: String, address: String) {
        val d = trusted[deviceId] ?: return
        val newPort = portOf(address)
        if (hostOf(d.lastAddress) == hostOf(address) && (newPort == null || d.lastAddress == address)) return
        val oldPort = portOf(d.lastAddress)
        val newAddress = if (newPort == null && oldPort != null) withPort(address, oldPort) else address
        trusted[deviceId] = d.copy(lastAddress = newAddress)
        saveTrusted()
    }

    private fun saveTrusted() {
        storage.put(KEY_TRUSTED, trusted.values.joinToString("\n") {
            ConnectCodec.joinFields(listOf(
                it.deviceId, it.name, it.type, it.publicKeyHex, it.lastAddress, it.pendingUnpair.toString(),
                it.profileId, it.permissions.joinToString(",") { p -> p.name }, it.pairedAtMs.toString(),
            ))
        })
    }

    private fun loadTrusted(data: String) {
        for (line in data.split('\n')) {
            if (line.isEmpty()) continue
            val f = try { ConnectCodec.splitFields(line) } catch (e: IllegalArgumentException) { continue }
            if (f.size < 9) continue
            val pub = f[3].hexToBytes() ?: continue
            val perms = f[7].split(',').mapNotNull { n -> Permission.values().firstOrNull { it.name == n } }.toSet()
            trusted[f[0]] = TrustedDevice(f[0], f[1], f[2], f[3], fingerprintOf(pub), f[4], f[5] == "true", f[6], perms, f[8].toLongOrNull() ?: 0)
        }
    }

    // =====================================================
    // 2. Brannmur
    // =====================================================
    private class Bucket(var tokens: Double, var lastMs: Long, var limited: Boolean = false)
    private val buckets = mutableMapOf<String, Bucket>()
    private val failures = mutableMapOf<String, ArrayDeque<Long>>()
    private val blockedUntil = mutableMapOf<String, Long>()

    /** Kalles for HVER innkommende melding før den behandles. */
    @Synchronized
    fun admitIncoming(address: String, sizeBytes: Int): Boolean {
        val host = hostOf(address)
        if (isBlocked(host)) return false
        if (policy.allowOnlyLocalNetwork && !isLocalAddress(host)) {
            emit(EventType.NON_LOCAL_ADDRESS, address, "Avvist tilkobling utenfra lokalnettet")
            return false
        }
        if (sizeBytes > policy.maxMessageBytes) {
            reportSuspicious(address, EventType.OVERSIZED_MESSAGE, "$sizeBytes byte")
            return false
        }
        return takeToken(host, address)
    }

    /** Sjekker at vi har lov å snakke med adressen (utgående). */
    @Synchronized
    fun isAllowedPeer(address: String): Boolean {
        val host = hostOf(address)
        return !isBlocked(host) && (!policy.allowOnlyLocalNetwork || isLocalAddress(host))
    }

    /** Registrerer mistenkelig aktivitet. Etter for mange hendelser blokkeres adressen midlertidig. */
    @Synchronized
    fun reportSuspicious(address: String, type: EventType = EventType.SUSPICIOUS, detail: String = "") {
        emit(type, address, detail)
        val host = hostOf(address)
        val now = clock()
        val list = failures.getOrPut(host) { ArrayDeque() }
        list.addLast(now)
        while (list.isNotEmpty() && now - list.first() > policy.failureWindow.inWholeMilliseconds) list.removeFirst()
        if (list.size >= policy.maxFailuresBeforeBlock) {
            blockedUntil[host] = now + policy.blockDuration.inWholeMilliseconds
            failures.remove(host)
            emit(EventType.BLOCKED_ADDRESS, address, "Blokkert i ${policy.blockDuration}")
        }
        if (failures.size > 1024) failures.entries.removeAll { now - (it.value.lastOrNull() ?: 0) > policy.failureWindow.inWholeMilliseconds }
    }

    @Synchronized fun unblock(address: String) { blockedUntil.remove(hostOf(address)) }

    private fun isBlocked(host: String): Boolean {
        val until = blockedUntil[host] ?: return false
        if (clock() < until) return true
        blockedUntil.remove(host)
        return false
    }

    private fun takeToken(host: String, address: String): Boolean {
        val now = clock()
        if (buckets.size > 1024) buckets.entries.removeAll { now - it.value.lastMs > 60_000 }
        val b = buckets.getOrPut(host) { Bucket(policy.burst.toDouble(), now) }
        b.tokens = minOf(policy.burst.toDouble(), b.tokens + (now - b.lastMs) / 1000.0 * policy.messagesPerSecond)
        b.lastMs = now
        if (b.tokens < 1.0) {
            if (!b.limited) { b.limited = true; emit(EventType.RATE_LIMITED, address, "For mange meldinger") }
            return false
        }
        b.limited = false
        b.tokens -= 1.0
        return true
    }

    @Synchronized fun recentEvents(): List<SecurityEvent> = events.toList()

    @Synchronized
    internal fun emit(type: EventType, address: String?, detail: String) {
        val e = SecurityEvent(type, address, redact(detail), clock())
        events.addLast(e)
        while (events.size > 200) events.removeFirst()
        try { onSecurityEvent(e) } catch (ignored: Exception) { }
    }

    // =====================================================
    // 3. Paring (engangskode + nøkkelutveksling, MITM-sikker)
    // =====================================================
    // Sender (S) -> Mottaker (M):
    //   S: HELLO(commit = H(eS, nS))          M: KEY(eM, nM)        – S har forpliktet seg før M svarer
    //   S: REVEAL(eS, nS)                     M: sjekker commit, viser kontrolltall
    //   S: (QR: eM må matche fingeravtrykk)  / (manuell: bruker bekrefter at kontrolltallet er likt)
    //   S: CONFIRM(kryptert: kode + identitet) M: godkjenning på skjermen -> DONE(kryptert: identitet)
    // En angriper i midten kan ikke lese koden, og har 1:1 000 000 sjanse per forsøk for å matche kontrolltallet.
    private class PairKeys(val transcript: ByteArray, val i2r: ByteArray, val r2i: ByteArray, val sas: String)
    private class PendingPeer(val address: String, val deviceId: String, val commit: ByteArray, val nonce: ByteArray) {
        var keys: PairKeys? = null
    }
    private class ReceiverPairing(val code: String, val privateKey: ByteArray, val publicKey: ByteArray, val createdAt: Long) {
        var attempts = 0
        var pending: PendingPeer? = null
        var awaitingApproval = false
    }
    private var receiverPairing: ReceiverPairing? = null

    @Synchronized
    fun beginPairingAsReceiver(): PairingOffer {
        val (priv, pub) = crypto.generateKeyPair()
        val code = randomDigits(6)
        receiverPairing = ReceiverPairing(code, priv, pub, clock())
        return PairingOffer(code, fingerprintOf(pub))
    }

    @Synchronized
    fun getOrBeginPairingAsReceiver(): PairingOffer {
        val rp = receiverPairing
        if (rp != null && clock() - rp.createdAt <= policy.pairingCodeLifetime.inWholeMilliseconds) {
            return PairingOffer(rp.code, fingerprintOf(rp.publicKey))
        }
        return beginPairingAsReceiver()
    }

    @Synchronized fun cancelPairing() { receiverPairing = null }
    val isPairingOpen: Boolean @Synchronized get() = receiverPairing != null

    private fun openPairing(): Step<ReceiverPairing> {
        val rp = receiverPairing ?: return Step.Fail(AuraConnect.PairingError.NOT_ADVERTISING)
        if (clock() - rp.createdAt > policy.pairingCodeLifetime.inWholeMilliseconds) {
            receiverPairing = null
            return Step.Fail(AuraConnect.PairingError.CODE_EXPIRED)
        }
        if (rp.awaitingApproval) return Step.Fail(AuraConnect.PairingError.BUSY)
        return Step.Ok(rp)
    }

    @Synchronized
    fun receiverHello(address: String, deviceId: String, commitHex: String): Step<Pair<String, String>> {
        val rp = when (val s = openPairing()) { is Step.Ok -> s.value; is Step.Fail -> return s }
        val commit = commitHex.hexToBytes()?.takeIf { it.size == 32 }
            ?: return Step.Fail(AuraConnect.PairingError.BAD_RESPONSE)
        if (!isSafeId(deviceId)) return Step.Fail(AuraConnect.PairingError.BAD_RESPONSE)
        if (rp.attempts >= policy.maxPairingAttempts) {
            receiverPairing = null
            emit(EventType.PAIRING_LOCKED, address, "For mange paringsforsøk – ny kode kreves")
            return Step.Fail(AuraConnect.PairingError.TOO_MANY_ATTEMPTS)
        }
        rp.attempts++
        val nonce = crypto.randomBytes(32)
        rp.pending = PendingPeer(address, deviceId, commit, nonce)
        return Step.Ok(rp.publicKey.toHex() to nonce.toHex())
    }

    /** Returnerer kontrolltallet som mottakeren skal vise på skjermen. */
    @Synchronized
    fun receiverReveal(address: String, keyHex: String, nonceHex: String): Step<String> {
        val rp = when (val s = openPairing()) { is Step.Ok -> s.value; is Step.Fail -> return s }
        val p = rp.pending?.takeIf { it.address == address } ?: return Step.Fail(AuraConnect.PairingError.BAD_RESPONSE)
        val eS = parseKey(keyHex)
        val nS = nonceHex.hexToBytes()?.takeIf { it.size == 32 }
        if (eS == null || nS == null || !constantTimeEquals(commitOf(eS, nS), p.commit)) {
            rp.pending = null
            reportSuspicious(address, EventType.PAIRING_FAILED, "Ugyldig forpliktelse")
            return Step.Fail(AuraConnect.PairingError.KEY_MISMATCH)
        }
        val keys = derivePairKeys(p.commit, rp.publicKey, p.nonce, eS, nS, dh(rp.privateKey, eS))
            ?: return Step.Fail(AuraConnect.PairingError.KEY_MISMATCH)
        p.keys = keys
        return Step.Ok(keys.sas)
    }

    /** Sjekker koden. Ved riktig kode venter paringen på [receiverComplete] (godkjenning på skjermen). */
    @Synchronized
    fun receiverVerify(address: String, cipherHex: String): Step<PendingApproval> {
        val rp = when (val s = openPairing()) { is Step.Ok -> s.value; is Step.Fail -> return s }
        val p = rp.pending?.takeIf { it.address == address } ?: return Step.Fail(AuraConnect.PairingError.BAD_RESPONSE)
        val keys = p.keys ?: return Step.Fail(AuraConnect.PairingError.BAD_RESPONSE)
        val plain = cipherHex.hexToBytes()?.let { openAead(keys.i2r, 0, it, keys.transcript) }
        val f = plain?.let { try { ConnectCodec.splitFields(it.decodeToString()) } catch (e: IllegalArgumentException) { null } }
        if (f == null || f.size != 6) {
            rp.pending = null
            reportSuspicious(address, EventType.TAMPERED_MESSAGE, "Ugyldig paringsbekreftelse")
            return Step.Fail(AuraConnect.PairingError.BAD_RESPONSE)
        }
        if (!constantTimeEquals(f[0], rp.code)) {
            rp.pending = null
            reportSuspicious(address, EventType.PAIRING_FAILED, "Feil paringskode")
            if (rp.attempts >= policy.maxPairingAttempts) {
                receiverPairing = null
                emit(EventType.PAIRING_LOCKED, address, "For mange paringsforsøk – ny kode kreves")
                return Step.Fail(AuraConnect.PairingError.TOO_MANY_ATTEMPTS)
            }
            return Step.Fail(AuraConnect.PairingError.WRONG_CODE)
        }
        val peerKey = parseKey(f[4])
        if (f[1] != p.deviceId || peerKey == null) return Step.Fail(AuraConnect.PairingError.BAD_RESPONSE)
        rp.awaitingApproval = true
        val peerPort = f[5].toIntOrNull()?.takeIf { it in 1..65535 }
        val peerAddress = if (peerPort != null) withPort(address, peerPort) else address
        return Step.Ok(PendingApproval(f[1], cleanName(f[2]), f[3], fingerprintOf(peerKey), peerAddress, f[4], rp, p))
    }

    /** Fullfører paringen etter godkjenning (eller avslag) på skjermen. */
    @Synchronized
    fun receiverComplete(pending: PendingApproval, approval: Approval, me: PeerInfo): Step<Pair<TrustedDevice, String>> {
        val rp = receiverPairing
        val p = pending.peer as PendingPeer
        if (rp == null || rp !== pending.pairing || rp.pending !== p) return Step.Fail(AuraConnect.PairingError.CODE_EXPIRED)
        receiverPairing = null // koden er brukt uansett utfall
        if (approval !is Approval.Approve) {
            emit(EventType.PAIRING_FAILED, pending.address, "Paring avvist på skjermen: ${pending.name}")
            return Step.Fail(AuraConnect.PairingError.DENIED)
        }
        val keys = p.keys ?: return Step.Fail(AuraConnect.PairingError.BAD_RESPONSE)
        val device = TrustedDevice(
            pending.deviceId, pending.name, pending.type, pending.publicKeyHex, pending.fingerprint, pending.address,
            profileId = approval.profileId, permissions = approval.permissions, pairedAtMs = wallClock(),
        )
        trust(device)
        val done = sealAead(keys.r2i, 0, ConnectCodec.joinFields(listOf(me.deviceId, me.name, me.type, identityPublic.toHex())).encodeToByteArray(), keys.transcript)
        return Step.Ok(device to done.toHex())
    }

    @Synchronized
    fun startPairingAsInitiator(expectedFingerprint: String? = null) = OutgoingPairing(expectedFingerprint?.lowercase())

    inner class OutgoingPairing internal constructor(private val expectedFingerprint: String?) {
        private val keyPair = crypto.generateKeyPair()
        private val nonce = crypto.randomBytes(32)
        private val commit = commitOf(keyPair.second, nonce)
        private var keys: PairKeys? = null
        val commitHex: String = commit.toHex()
        val publicKeyHex: String = keyPair.second.toHex()
        val nonceHex: String = nonce.toHex()
        /** Kontrolltallet som brukeren skal sammenligne med mottakerens skjerm. */
        val sas: String get() = keys?.sas ?: error("Nøkkel ikke mottatt ennå")

        fun receiveKey(address: String, peerKeyHex: String, peerNonceHex: String): Boolean = synchronized(this@AuraGuard) {
            val eM = parseKey(peerKeyHex) ?: return false
            val nM = peerNonceHex.hexToBytes()?.takeIf { it.size == 32 } ?: return false
            if (expectedFingerprint != null && !constantTimeEquals(fingerprintOf(eM), expectedFingerprint)) {
                emit(EventType.KEY_MISMATCH, address, "Mottakerens nøkkel matcher ikke QR-koden – mulig angrep")
                return false
            }
            keys = derivePairKeys(commit, eM, nM, keyPair.second, nonce, dh(keyPair.first, eM)) ?: return false
            return true
        }

        fun sealConfirm(code: String, me: PeerInfo): String = synchronized(this@AuraGuard) {
            val k = keys ?: error("Nøkkel ikke mottatt ennå")
            val plain = ConnectCodec.joinFields(listOf(code, me.deviceId, me.name, me.type, identityPublic.toHex(), me.port?.toString() ?: ""))
            sealAead(k.i2r, 0, plain.encodeToByteArray(), k.transcript).toHex()
        }

        fun openDone(address: String, cipherHex: String, grant: Set<Permission>): TrustedDevice? = synchronized(this@AuraGuard) {
            val k = keys ?: return null
            val plain = cipherHex.hexToBytes()?.let { openAead(k.r2i, 0, it, k.transcript) } ?: return null
            val f = try { ConnectCodec.splitFields(plain.decodeToString()) } catch (e: IllegalArgumentException) { return null }
            if (f.size != 4 || !isSafeId(f[0])) return null
            val peerKey = parseKey(f[3]) ?: return null
            val device = TrustedDevice(f[0], cleanName(f[1]), f[2], f[3], fingerprintOf(peerKey), address, permissions = grant, pairedAtMs = wallClock())
            trust(device)
            device
        }
    }

    private fun commitOf(key: ByteArray, nonce: ByteArray) = crypto.sha256("aura-commit".encodeToByteArray() + key + nonce)

    private fun derivePairKeys(commit: ByteArray, eM: ByteArray, nM: ByteArray, eS: ByteArray, nS: ByteArray, shared: ByteArray?): PairKeys? {
        if (shared == null) return null
        val transcript = crypto.sha256("aura-pair-v3".encodeToByteArray() + commit + eM + nM + eS + nS)
        val prk = crypto.hmacSha256(transcript, shared)
        val sasBytes = expand(prk, "pair sas", 4)
        val sasNum = ((sasBytes[0].toLong() and 0xFF) shl 24) or ((sasBytes[1].toLong() and 0xFF) shl 16) or
            ((sasBytes[2].toLong() and 0xFF) shl 8) or (sasBytes[3].toLong() and 0xFF)
        return PairKeys(transcript, expand(prk, "pair i2r", 32), expand(prk, "pair r2i", 32), (sasNum % 1_000_000).toString().padStart(6, '0'))
    }

    // =====================================================
    // 4. Sikker kanal mellom parede enheter (automatisk, ingen kode)
    // =====================================================
    // Nøkler = HKDF(DH(eI,eR) | DH(sI,eR) | DH(eI,sR)): gjensidig autentisering med lagrede identitetsnøkler
    // og nye engangsnøkler per økt (forward secrecy).
    private class Session(
        val sid: String, val peerDeviceId: String, val sendKey: ByteArray, val recvKey: ByteArray,
        val createdAt: Long, var sendCounter: Long,
    ) {
        var highest = -1L
        var window = 0L
        fun notReplayed(c: Long) = c > highest || (highest - c < 64 && (window ushr (highest - c).toInt()) and 1L == 0L)
        fun markSeen(c: Long) {
            if (c > highest) {
                val shift = c - highest
                window = if (shift >= 64) 1L else (window shl shift.toInt()) or 1L
                highest = c
            } else {
                window = window or (1L shl (highest - c).toInt())
            }
        }
    }
    private class SessionKeys(val sid: String, val i2r: ByteArray, val r2i: ByteArray)

    private val sessions = mutableMapOf<String, Session>()
    private val outgoing = mutableMapOf<String, String>() // deviceId -> sid

    @Synchronized
    fun startSession(deviceId: String): SessionHandshake? = trusted[deviceId]?.let { SessionHandshake(it) }

    inner class SessionHandshake internal constructor(private val device: TrustedDevice) {
        private val eph = crypto.generateKeyPair()
        val publicKeyHex: String = eph.second.toHex()

        /** Returnerer økt-ID hvis motparten beviste at den er den parede enheten. */
        fun complete(address: String, peerKeyHex: String, sid: String, proofHex: String): String? = synchronized(this@AuraGuard) {
            val eR = parseKey(peerKeyHex) ?: return null
            val sR = device.publicKeyHex.hexToBytes() ?: return null
            val keys = deriveSessionKeys(
                dh(eph.first, eR), dh(identityPrivate, eR), dh(eph.first, sR),
                identityPublic, sR, eph.second, eR,
            ) ?: return null
            val proof = proofHex.hexToBytes()?.let { openAead(keys.r2i, 0, it, keys.sid.encodeToByteArray()) }
            if (!constantTimeEquals(keys.sid, sid) || proof?.decodeToString() != PROOF) {
                reportSuspicious(address, EventType.TAMPERED_MESSAGE, "Ugyldig øktbevis fra ${device.name}")
                return null
            }
            val s = Session(keys.sid, device.deviceId, keys.i2r, keys.r2i, clock(), 0)
            s.markSeen(0)
            addSession(s)
            outgoing[device.deviceId] = s.sid
            s.sid
        }
    }

    /** Mottakersiden av økt-håndtrykket. Kun parede enheter får svar. */
    @Synchronized
    fun acceptSession(address: String, deviceId: String, keyHex: String): Triple<String, String, String>? {
        val device = trusted[deviceId]
        if (device == null) {
            reportSuspicious(address, EventType.UNKNOWN_DEVICE, "Ukjent enhet forsøkte å koble til")
            return null
        }
        val eI = parseKey(keyHex)
        val sI = device.publicKeyHex.hexToBytes()
        if (eI == null || sI == null) {
            reportSuspicious(address, EventType.MALFORMED_MESSAGE, "Ugyldig nøkkel")
            return null
        }
        val eph = crypto.generateKeyPair()
        val keys = deriveSessionKeys(
            dh(eph.first, eI), dh(eph.first, sI), dh(identityPrivate, eI),
            sI, identityPublic, eI, eph.second,
        ) ?: return null
        addSession(Session(keys.sid, deviceId, keys.r2i, keys.i2r, clock(), 1))
        val proof = sealAead(keys.r2i, 0, PROOF.encodeToByteArray(), keys.sid.encodeToByteArray())
        return Triple(eph.second.toHex(), keys.sid, proof.toHex())
    }

    @Synchronized
    fun activeSessionFor(deviceId: String): String? {
        val sid = outgoing[deviceId] ?: return null
        if (live(sid) == null) { outgoing.remove(deviceId); return null }
        return sid
    }

    @Synchronized
    fun dropSession(sid: String) {
        val s = sessions.remove(sid) ?: return
        if (outgoing[s.peerDeviceId] == sid) outgoing.remove(s.peerDeviceId)
    }

    private fun dropSessionsFor(deviceId: String) {
        sessions.values.filter { it.peerDeviceId == deviceId }.map { it.sid }.forEach { dropSession(it) }
    }

    @Synchronized
    fun seal(sid: String, plaintext: String): SealedMessage? {
        val s = live(sid) ?: return null
        val c = s.sendCounter++
        return SealedMessage(sid, c, sealAead(s.sendKey, c, plaintext.encodeToByteArray(), sid.encodeToByteArray()).toHex())
    }

    /** Dekrypterer og autentiserer. Returnerer (avsenderens deviceId, klartekst) eller null. */
    @Synchronized
    fun open(address: String, sid: String, counter: Long, cipherHex: String): Pair<String, String>? {
        val s = live(sid) ?: return null
        if (counter < 0 || !s.notReplayed(counter)) {
            reportSuspicious(address, EventType.REPLAYED_MESSAGE, "Gjentatt melding avvist")
            return null
        }
        val plain = cipherHex.hexToBytes()?.let { openAead(s.recvKey, counter, it, sid.encodeToByteArray()) }
        if (plain == null) {
            reportSuspicious(address, EventType.TAMPERED_MESSAGE, "Melding feilet autentisering")
            return null
        }
        s.markSeen(counter)
        return s.peerDeviceId to plain.decodeToString()
    }

    private fun live(sid: String): Session? {
        val s = sessions[sid] ?: return null
        val expired = clock() - s.createdAt > policy.sessionLifetime.inWholeMilliseconds || s.sendCounter > 1L shl 40
        if (expired) { dropSession(sid); return null }
        return s
    }

    private fun addSession(s: Session) {
        sessions.keys.toList().forEach { live(it) }
        while (sessions.size >= 256) dropSession(sessions.values.minByOrNull { it.createdAt }!!.sid)
        sessions[s.sid] = s
    }

    private fun deriveSessionKeys(
        dh1: ByteArray?, dh2: ByteArray?, dh3: ByteArray?,
        sI: ByteArray, sR: ByteArray, eI: ByteArray, eR: ByteArray,
    ): SessionKeys? {
        if (dh1 == null || dh2 == null || dh3 == null) return null
        val salt = crypto.sha256("aura-session-v3".encodeToByteArray() + sI + sR + eI + eR)
        val prk = crypto.hmacSha256(salt, dh1 + dh2 + dh3)
        return SessionKeys(expand(prk, "session id", 16).toHex(), expand(prk, "session i2r", 32), expand(prk, "session r2i", 32))
    }

    // =====================================================
    // 5. Innholdskontroll og lekkasjevern
    // =====================================================
    private val safeId = Regex("^[A-Za-z0-9._:\\-]{1,128}$")

    fun isSafeId(value: String): Boolean = safeId.matches(value)

    /** Validerer alt som kommer inn før det rutes videre i appen. */
    fun validatePayload(payload: ConnectPayload): Boolean = when (payload) {
        is ConnectPayload.PlayPlaylist -> isSafeId(payload.playlistId)
        is ConnectPayload.RemoteAction -> true
        is ConnectPayload.SyncUser -> payload.userInfo.size <= 64 && payload.userInfo.all { (k, v) ->
            isSafeId(k) && v.length <= 1024 && v.none { it.isISOControl() && it != '\n' }
        }
    }

    /** Kun https og kun godkjente domener (inkl. underdomener). */
    fun isAllowedUrl(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("https://", ignoreCase = true)) return false
        val authority = u.substring(8).takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.isEmpty() || '@' in authority || '\\' in authority) return false
        val host = authority.substringBefore(':').lowercase().trimEnd('.')
        return policy.allowedUrlHosts.any { h -> host == h || host.endsWith(".$h") }
    }

    /** Sjekker nedlastet innhold: kjent skadevare, feil hash, kjørbare filer og arkiver. */
    fun inspectContent(
        bytes: ByteArray,
        expectedSha256Hex: String? = null,
        allowExecutables: Boolean = false,
        allowArchives: Boolean = false,
    ): ContentVerdict {
        val hash = crypto.sha256(bytes).toHex()
        val verdict = when {
            hash in policy.blockedSha256 -> ContentVerdict.KNOWN_MALWARE
            expectedSha256Hex != null && !constantTimeEquals(hash, expectedSha256Hex.lowercase()) -> ContentVerdict.HASH_MISMATCH
            !allowExecutables && looksExecutable(bytes) -> ContentVerdict.EXECUTABLE
            !allowArchives && bytes.startsWith(0x50, 0x4B, 0x03, 0x04) -> ContentVerdict.ARCHIVE
            else -> ContentVerdict.SAFE
        }
        if (verdict != ContentVerdict.SAFE) emit(EventType.UNSAFE_CONTENT, null, "$verdict sha256=$hash")
        return verdict
    }

    private fun looksExecutable(b: ByteArray) =
        b.startsWith(0x4D, 0x5A) ||                     // Windows PE (MZ)
        b.startsWith(0x7F, 0x45, 0x4C, 0x46) ||         // ELF (Linux/Android native)
        b.startsWith(0xFE, 0xED, 0xFA, 0xCE) || b.startsWith(0xFE, 0xED, 0xFA, 0xCF) ||
        b.startsWith(0xCE, 0xFA, 0xED, 0xFE) || b.startsWith(0xCF, 0xFA, 0xED, 0xFE) ||
        b.startsWith(0xCA, 0xFE, 0xBA, 0xBE) ||         // Mach-O / Java class
        b.startsWith(0x64, 0x65, 0x78, 0x0A) ||         // Android DEX
        b.startsWith(0x23, 0x21)                        // skript (#!)

    /** Fjerner hemmeligheter (ARL, tokens, passord, lange nøkler) før tekst logges eller vises. */
    fun redact(text: String): String = text
        .replace(Regex("(?i)(arl|token|password|passord|secret|key)(\\s*[=:]\\s*)[^\\s&|,]+"), "$1$2***")
        .replace(Regex("[A-Za-z0-9+/_\\-]{40,}"), "***")

    /** Sikker lagring av hemmeligheter (f.eks. Deezer ARL) – aldri i vanlige innstillinger. */
    fun storeSecret(name: String, value: String) = storage.put("aura.secret.$name", value)
    fun readSecret(name: String): String? = storage.get("aura.secret.$name")
    fun deleteSecret(name: String) = storage.remove("aura.secret.$name")

    // =====================================================
    // Hjelpefunksjoner
    // =====================================================
    fun fingerprintOf(publicKey: ByteArray): String = crypto.sha256(publicKey).toHex()

    private fun parseKey(hex: String): ByteArray? = hex.takeIf { it.length <= 400 }?.hexToBytes()?.takeIf { it.size >= 32 }

    private fun cleanName(name: String) = name.filter { !it.isISOControl() }.take(48).ifBlank { "Aura-enhet" }

    private fun dh(priv: ByteArray, pub: ByteArray): ByteArray? = try {
        crypto.agree(priv, pub).takeIf { r -> r.size >= 32 && r.any { it != 0.toByte() } }
    } catch (e: Exception) {
        null
    }

    private fun nonceFor(counter: Long) = ByteArray(12).also { n ->
        for (i in 0 until 8) n[11 - i] = (counter ushr (8 * i)).toByte()
    }

    private fun sealAead(key: ByteArray, counter: Long, plain: ByteArray, aad: ByteArray) =
        crypto.seal(key, nonceFor(counter), plain, aad)

    private fun openAead(key: ByteArray, counter: Long, cipher: ByteArray, aad: ByteArray): ByteArray? = try {
        crypto.open(key, nonceFor(counter), cipher, aad)
    } catch (e: Exception) {
        null
    }

    private fun expand(prk: ByteArray, info: String, length: Int): ByteArray {
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < length) {
            t = crypto.hmacSha256(prk, t + info.encodeToByteArray() + byteArrayOf(i.toByte()))
            val n = minOf(t.size, length - pos)
            t.copyInto(out, pos, 0, n)
            pos += n; i++
        }
        return out
    }

    /** Jevnt fordelte sifre fra kryptografisk tilfeldighet. */
    private fun randomDigits(count: Int): String {
        var mod = 1L
        repeat(count) { mod *= 10 }
        val limit = (1L shl 32) - (1L shl 32) % mod
        while (true) {
            val b = crypto.randomBytes(4)
            val v = ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or
                ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
            if (v < limit) return (v % mod).toString().padStart(count, '0')
        }
    }

    companion object {
        private const val KEY_IDENTITY = "aura.guard.identity.p256"
        private const val KEY_TRUSTED = "aura.guard.trusted.v3"
        private const val PROOF = "aura-ok"

        fun hostOf(address: String): String {
            val a = address.trim()
            val host = when {
                a.startsWith("[") -> a.substring(1).substringBefore(']')
                a.count { it == ':' } == 1 -> a.substringBefore(':')
                else -> a
            }
            return host.substringBefore('%').lowercase()
        }

        fun portOf(address: String): Int? {
            val a = address.trim()
            return when {
                a.startsWith("[") -> a.substringAfter("]", "").removePrefix(":").toIntOrNull()
                a.count { it == ':' } == 1 -> a.substringAfter(':').toIntOrNull()
                else -> null
            }
        }

        fun withPort(address: String, port: Int): String {
            val a = address.trim()
            val host = when {
                a.startsWith("[") -> a.substring(1).substringBefore(']')
                a.count { it == ':' } == 1 -> a.substringBefore(':')
                else -> a
            }
            return if (':' in host) "[$host]:$port" else "$host:$port"
        }

        /** Privat/lokalt nettverk: 10/8, 172.16/12, 192.168/16, 169.254/16, 127/8, ::1, fe80::/10, fc00::/7. */
        fun isLocalAddress(host: String): Boolean {
            val h = host.removePrefix("::ffff:")
            val parts = h.split('.')
            if (parts.size == 4) {
                val o = parts.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return false }
                return o[0] == 10 || o[0] == 127 || (o[0] == 172 && o[1] in 16..31) ||
                    o[0] == 192 || (o[0] == 169 && o[1] == 254)
            }
            if (':' !in h) return false
            if (h == "::1") return true
            val first = h.substringBefore(':').padStart(4, '0').toIntOrNull(16) ?: return false
            return (first and 0xFFC0) == 0xFE80 || (first and 0xFE00) == 0xFC00
        }
    }
}

// ---------------------------------------------------------
// AuraConnect – paring, automatisk tilkobling, kommandoer og ruting
// ---------------------------------------------------------
//
// Mottaker (f.eks. TV):   val session = connect.startAdvertisingAsReceiver()  -> vis session.qrData (og session.code)
//                         connect.onShowSas = { tall -> vis "Kontrolltall: $tall" }
//                         connect.onApprovePairing = { req, decide -> dialog -> decide(Approval.Approve(profil, tillatelser)) }
//                         connect.registerCommand("op", Permission.X) { ctx, respond -> ... }
// Sender (f.eks. mobil):  connect.pairWithQr(skannetQr) { result -> ... }                          // anbefalt
//                         connect.pairWithDevice(device, kodeFraSkjerm) { result -> ... }           // manuell kode:
//                         connect.onConfirmSas = { tall, svar -> spør "Viser TV-en $tall?" -> svar(true/false) }
// Etter paring:           connect.call(deviceId, "op", mapOf(...)) { result -> ... }
//                         Parede enheter kobles til automatisk – aldri ny kode.
// Slette paring:          connect.unpair(deviceId) – fra hvilken som helst side; den andre enheten får beskjed.
class AuraConnect(private val guard: AuraGuard) {

    data class DiscoveredDevice(val deviceId: String, val name: String, val ipAddress: String, val type: DeviceType)
    data class PairingSession(val code: String, val qrData: String, val isPaired: Boolean, val keyFingerprint: String)
    data class DeviceIdentity(val deviceId: String, val name: String, val type: DeviceType)
    data class PairedDevice(
        val deviceId: String, val name: String, val ipAddress: String, val type: DeviceType, val fingerprint: String,
        val profileId: String, val permissions: Set<Permission>, val pairedAtMs: Long,
    ) {
        /** Kort, lesbar form av nøkkelen (vises på begge enheter for sammenligning). */
        val shortFingerprint: String get() = fingerprint.take(16).uppercase().chunked(4).joinToString("-")
    }
    /** Vises i godkjenningsdialogen på mottakeren. */
    data class PairingRequest(val deviceId: String, val name: String, val type: DeviceType, val shortFingerprint: String)

    enum class DeviceType {
        PHONE, TABLET, TV, DESKTOP, AMP
    }

    enum class PairingError {
        NOT_ADVERTISING, WRONG_CODE, CODE_EXPIRED, TOO_MANY_ATTEMPTS, UNREACHABLE, BAD_RESPONSE,
        KEY_MISMATCH, SAS_REJECTED, BLOCKED, NOT_CONFIGURED, DENIED, BUSY,
    }

    class PairingException(val error: PairingError) : Exception(error.name)

    // Nettverkslaget (standard: TcpTransport nederst i filen).
    interface ConnectTransport {
        fun open() {}
        fun close() {}
        fun startAdvertising(identity: DeviceIdentity)
        fun stopAdvertising()
        fun startDiscovery(onFound: (DiscoveredDevice) -> Unit)
        fun stopDiscovery()
        /** Sender en melding og leverer svaret (null ved feil/timeout). */
        fun send(address: String, message: String, onReply: (String?) -> Unit)
        /** Registrerer mottak av meldinger. `reply` må kalles nøyaktig én gang (kan være senere). */
        fun setIncomingHandler(handler: (fromAddress: String, message: String, reply: (String) -> Unit) -> Unit)
        /** Adressen andre enheter når denne på (brukes i QR-koden), eller null. */
        fun localEndpoint(): Pair<String, Int>? = null
    }

    // --- Konfigurasjon og tilstand ---
    @Volatile var identity: DeviceIdentity = DeviceIdentity("aura-unknown", "Aura-enhet", DeviceType.PHONE)
        private set
    private var transport: ConnectTransport = NoOpTransport
    private var loop: Executor = DIRECT
    private var callbacks: Executor = DIRECT

    /** Koble automatisk til parede enheter når de oppdages. */
    @Volatile var autoConnect: Boolean = true

    /** Tillatelser enheter vi selv parer OSS mot (f.eks. TV-en) får på denne enheten. Standard: ingen. */
    @Volatile var grantForReceivers: Set<Permission> = emptySet()

    /** Settes av senderen. Får forespørrende enhet og returnerer ARL, eller null for å avslå. Standard: avslå. */
    @Volatile var arlProvider: (requester: PairedDevice) -> String? = { null }
    /** Ny enhet paret (begge sider). */
    @Volatile var onDevicePaired: (PairedDevice) -> Unit = {}
    /** Paret enhet er tilkoblet og klar (automatisk). */
    @Volatile var onDeviceConnected: (PairedDevice) -> Unit = {}
    /** Paringen ble slettet – lokalt eller av den andre enheten. */
    @Volatile var onDeviceUnpaired: (deviceId: String) -> Unit = {}
    /** Appen kan lytte på brukersynk. */
    @Volatile var onUserSync: (Map<String, String>) -> Unit = {}
    /** Sender, manuell kode: vis tallet og la brukeren bekrefte at mottakeren viser det samme. Standard: avvis. */
    @Volatile var onConfirmSas: (sas: String, decide: (Boolean) -> Unit) -> Unit = { _, decide -> decide(false) }
    /** Mottaker: vis dette kontrolltallet på skjermen under paring. */
    @Volatile var onShowSas: (sas: String) -> Unit = {}
    /**
     * Mottaker: godkjenn paring, velg lokal profil og tillatelser. Ubesvart etter 60 s = avslått.
     * Standard (ingen dialog): godkjenn med standardprofil og [Permission.DEFAULT_CONTROLLER] – koden beviser at brukeren ser skjermen.
     */
    @Volatile var onApprovePairing: (request: PairingRequest, decide: (Approval) -> Unit) -> Unit =
        { _, decide -> decide(Approval.Approve("default", Permission.DEFAULT_CONTROLLER)) }
    /** Samtykke per kall for kommandoer registrert med requiresConsent (f.eks. mottak av innlogging). Standard: avslå. */
    @Volatile var onCommandConsent: (context: CommandContext, decide: (Boolean) -> Unit) -> Unit = { _, decide -> decide(false) }

    private class Registration(val permission: Permission?, val requiresConsent: Boolean, val handler: CommandHandler)
    private val commands = ConcurrentHashMap<String, Registration>()

    private val scheduler: ScheduledExecutorService by lazy { Executors.newSingleThreadScheduledExecutor(daemonThreads("aura-cortex-timer")) }

    fun configure(transport: ConnectTransport, identity: DeviceIdentity, loop: Executor = DIRECT, callbacks: Executor = DIRECT) {
        this.transport.stopAdvertising()
        this.transport.stopDiscovery()
        this.transport = transport
        this.identity = identity
        this.loop = loop
        this.callbacks = callbacks
        transport.setIncomingHandler { from, message, reply -> onLoop { handleIncoming(from, message, reply) } }
    }

    private fun onLoop(block: () -> Unit) = loop.execute { block() }
    private fun deliver(block: () -> Unit) = callbacks.execute {
        try { block() } catch (e: Exception) { guard.emit(AuraGuard.EventType.SUSPICIOUS, null, "Feil i app-callback: ${e.message}") }
    }

    fun pairedDevices(): List<PairedDevice> = guard.trustedDevices().map { it.toPaired() }
    fun pairedDevice(deviceId: String): PairedDevice? = guard.trustedDevice(deviceId)?.toPaired()
    fun devicesForProfile(profileId: String): List<PairedDevice> = pairedDevices().filter { it.profileId == profileId }
    fun isConnected(deviceId: String): Boolean = guard.activeSessionFor(deviceId) != null
    fun setPermissions(deviceId: String, permissions: Set<Permission>) = guard.setPermissions(deviceId, permissions)
    fun setProfile(deviceId: String, profileId: String) = guard.setProfile(deviceId, profileId)

    // --- Kommandoer (generelt API mellom appene) ---
    /** Registrerer en kommando andre parede enheter kan kalle. [permission] = null betyr alle parede enheter. */
    fun registerCommand(op: String, permission: Permission?, requiresConsent: Boolean = false, handler: CommandHandler) {
        require(guard.isSafeId(op)) { "Ugyldig kommandonavn: $op" }
        commands[op] = Registration(permission, requiresConsent, handler)
    }

    /** Enkel variant: returner data direkte, eller kast for å svare med feil. */
    fun registerSimpleCommand(op: String, permission: Permission?, block: (CommandContext) -> Map<String, Any?>) =
        registerCommand(op, permission) { ctx, respond -> respond(CommandResult.ok(block(ctx))) }

    fun unregisterCommand(op: String) { commands.remove(op) }

    /** Kaller en kommando på en paret enhet (kobler til automatisk). Svaret kommer på callbackExecutor. */
    fun call(deviceId: String, op: String, args: Map<String, Any?> = emptyMap(), onResult: (CommandResult) -> Unit) = onLoop {
        val json = try {
            require(guard.isSafeId(op))
            CortexJson.encode(args)
        } catch (e: IllegalArgumentException) {
            deliver { onResult(CommandResult.fail(CallError.BAD_REQUEST, "Ugyldig kommando eller data")) }
            return@onLoop
        }
        if (guard.trustedDevice(deviceId) == null) {
            deliver { onResult(CommandResult.fail(CallError.NOT_PAIRED)) }
            return@onLoop
        }
        secureRequest(deviceId, ConnectMessage.Call(op, json)) { reply ->
            val result = when (reply) {
                is ConnectMessage.CallResult -> reply.toResult()
                null -> CommandResult.fail(CallError.UNREACHABLE)
                else -> CommandResult.fail(CallError.FAILED)
            }
            deliver { onResult(result) }
        }
    }

    // --- 1. Paring & Universell Søkbarhet ---
    /** Åpner paring på denne enheten (mottaker). Vis qrData som QR og code som tekst. */
    fun startAdvertisingAsReceiver(deviceType: DeviceType = identity.type): PairingSession {
        identity = identity.copy(type = deviceType)
        val offer = guard.getOrBeginPairingAsReceiver()
        val endpoint = transport.localEndpoint()
        val qrPayload = buildString {
            append("auraconnect://pair?v=$QR_VERSION&code=${offer.code}&type=$deviceType")
            append("&id=${encodeUri(identity.deviceId)}&fp=${offer.keyFingerprint}")
            if (endpoint != null) append("&host=${encodeUri(endpoint.first)}&port=${endpoint.second}")
        }

        // Starter lokal lytter tilpasset enhetstypen
        broadcastPresence()

        return PairingSession(code = offer.code, qrData = qrPayload, isPaired = false, keyFingerprint = offer.keyFingerprint)
    }

    /** Synlig på nettet for parede enheter uten å åpne for ny paring. */
    fun startAdvertisingForPairedDevices() = broadcastPresence()

    /** Lukker paringsvinduet (enheten er fortsatt synlig for parede enheter). */
    fun closePairing() = guard.cancelPairing()

    fun stopAdvertising() {
        guard.cancelPairing()
        transport.stopAdvertising()
    }

    private fun broadcastPresence() {
        // Kringkasting på lokalnettet – utføres av transporten. Koden sendes ALDRI; den vises kun på skjermen/QR.
        transport.startAdvertising(identity)
    }

    fun scanForAuraDevices(onDeviceFound: (DiscoveredDevice) -> Unit) {
        // Oppdager aktive enheter på nettverket (ignorerer seg selv)
        transport.startDiscovery { device ->
            onLoop {
                if (device.deviceId == identity.deviceId) return@onLoop
                deliver { onDeviceFound(device) }
                handleDiscoveredPairedDevice(device)
            }
        }
    }

    fun stopScanning() {
        transport.stopDiscovery()
    }

    /** Kobler til alle parede enheter (ved oppstart / nettverksbytte), og leter etter de som har fått ny adresse. */
    fun reconnectAll() = onLoop {
        guard.trustedDevices().forEach { d -> connect(d.deviceId) }
        scanForAuraDevices { }
    }

    /** Parede enheter: koble til automatisk, og lever ventende slette-beskjeder. */
    private fun handleDiscoveredPairedDevice(device: DiscoveredDevice) {
        val known = guard.anyDevice(device.deviceId) ?: return
        if (known.pendingUnpair) {
            notifyUnpair(device.deviceId, device.ipAddress)
            return
        }
        if (!autoConnect || (isConnected(device.deviceId) && known.lastAddress == device.ipAddress)) return
        // Adressen oppdateres først når enheten har bevist identiteten sin på den nye adressen.
        secureRequest(device.deviceId, ConnectMessage.Ping, address = device.ipAddress) { reply ->
            if (reply == ConnectMessage.Ack) {
                guard.updateAddress(device.deviceId, device.ipAddress)
                guard.trustedDevice(device.deviceId)?.let { d -> deliver { onDeviceConnected(d.toPaired()) } }
            }
        }
    }

    /** Kobler til en paret enhet manuelt. */
    fun connect(deviceId: String, onResult: (Boolean) -> Unit = {}) = onLoop {
        secureRequest(deviceId, ConnectMessage.Ping) { reply ->
            val ok = reply == ConnectMessage.Ack
            if (ok) guard.trustedDevice(deviceId)?.let { d -> deliver { onDeviceConnected(d.toPaired()) } }
            deliver { onResult(ok) }
        }
    }

    fun requestPairingWithDevice(device: DiscoveredDevice, inputCode: String, onResult: (Boolean) -> Unit) {
        pairWithDevice(device, inputCode) { result -> onResult(result.isSuccess) }
    }

    /** Paring via skannet QR-kode – sikrest, ingen kontrolltall nødvendig. Adressen hentes fra QR-koden. */
    fun pairWithQr(qrData: String, onResult: (Result<PairedDevice>) -> Unit) {
        val params = parseQr(qrData)
        val host = params["host"]
        val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 }
        val type = DeviceType.values().firstOrNull { it.name == params["type"] } ?: DeviceType.TV
        if (host == null || port == null) {
            deliver { onResult(Result.failure(PairingException(PairingError.BAD_RESPONSE))) }
            return
        }
        pairWithQr(DiscoveredDevice(params["id"] ?: "", "", AuraGuard.withPort(host, port), type), qrData, onResult)
    }

    /** Paring via skannet QR-kode mot en enhet funnet i søk. */
    fun pairWithQr(device: DiscoveredDevice, qrData: String, onResult: (Result<PairedDevice>) -> Unit) {
        val params = parseQr(qrData)
        val code = params["code"]
        val fp = params["fp"]
        if (code == null || fp == null || (params["id"] != null && params["id"] != device.deviceId)) {
            deliver { onResult(Result.failure(PairingException(PairingError.BAD_RESPONSE))) }
            return
        }
        pairWithDevice(device, code, fp, onResult)
    }

    /** Paring med kode. Uten [expectedFingerprint] må brukeren bekrefte kontrolltallet via [onConfirmSas]. */
    fun pairWithDevice(
        device: DiscoveredDevice,
        inputCode: String,
        expectedFingerprint: String? = null,
        onResult: (Result<PairedDevice>) -> Unit,
    ) = onLoop {
        fun fail(e: PairingError) = deliver { onResult(Result.failure(PairingException(e))) }
        if (!guard.isConfigured) return@onLoop fail(PairingError.NOT_CONFIGURED)
        val code = inputCode.trim()
        if (code.length != 6 || !code.all { it.isDigit() }) return@onLoop fail(PairingError.WRONG_CODE)
        val address = device.ipAddress
        if (!guard.isAllowedPeer(address)) return@onLoop fail(PairingError.BLOCKED)

        val pairing = guard.startPairingAsInitiator(expectedFingerprint)
        val me = AuraGuard.PeerInfo(identity.deviceId, identity.name, identity.type.name, transport.localEndpoint()?.second)

        fun confirm() = exchange(address, ConnectMessage.PairConfirm(pairing.sealConfirm(code, me))) { r ->
            when (r) {
                is ConnectMessage.PairDone -> {
                    val trusted = pairing.openDone(address, r.cipherHex, grantForReceivers)
                        ?: return@exchange fail(PairingError.BAD_RESPONSE)
                    val paired = trusted.toPaired()
                    deliver { onDevicePaired(paired) }
                    deliver { onResult(Result.success(paired)) }
                }
                is ConnectMessage.PairRejected -> fail(r.reason)
                null -> fail(PairingError.UNREACHABLE)
                else -> fail(PairingError.BAD_RESPONSE)
            }
        }

        exchange(address, ConnectMessage.PairHello(identity.deviceId, pairing.commitHex)) { r1 ->
            when (r1) {
                is ConnectMessage.PairKey -> {
                    if (!pairing.receiveKey(address, r1.keyHex, r1.nonceHex)) return@exchange fail(PairingError.KEY_MISMATCH)
                    exchange(address, ConnectMessage.PairReveal(pairing.publicKeyHex, pairing.nonceHex)) { r2 ->
                        when (r2) {
                            ConnectMessage.PairRevealOk ->
                                if (expectedFingerprint != null) confirm()
                                else {
                                    val sas = pairing.sas
                                    deliver {
                                        onConfirmSas(sas) { same -> onLoop { if (same) confirm() else fail(PairingError.SAS_REJECTED) } }
                                    }
                                }
                            is ConnectMessage.PairRejected -> fail(r2.reason)
                            null -> fail(PairingError.UNREACHABLE)
                            else -> fail(PairingError.BAD_RESPONSE)
                        }
                    }
                }
                is ConnectMessage.PairRejected -> fail(r1.reason)
                null -> fail(PairingError.UNREACHABLE)
                else -> fail(PairingError.BAD_RESPONSE)
            }
        }
    }

    /** Sletter paringen fra denne siden. Den andre enheten får beskjed nå, eller neste gang den er på nettet. */
    fun unpair(deviceId: String) = onLoop {
        val device = guard.anyDevice(deviceId) ?: return@onLoop
        guard.markUnpaired(deviceId)
        deliver { onDeviceUnpaired(deviceId) }
        notifyUnpair(deviceId, device.lastAddress)
    }

    private fun notifyUnpair(deviceId: String, address: String) {
        secureRequest(deviceId, ConnectMessage.Unpair, address = address, allowPendingUnpair = true) { reply ->
            if (reply == ConnectMessage.Ack || reply == ConnectMessage.Unpair) guard.forget(deviceId)
        }
    }

    // --- 2. On-Demand Henting (Pull-modell for ARL) ---
    // Mottakeren ringer denne for å hente Deezer ARL fra senderen når det trengs (f.eks. på en musikkspiller-enhet)
    fun requestDeezerArlFromSender(senderIp: String, onResult: (String?) -> Unit) {
        // Krever paring. ARL sendes kun kryptert. Returnerer null hvis enheten ikke tillater det eller ikke har det.
        val sender = guard.trustedDevices().firstOrNull { AuraGuard.hostOf(it.lastAddress) == AuraGuard.hostOf(senderIp) }
            ?: return deliver { onResult(null) }
        requestDeezerArl(sender.deviceId, onResult)
    }

    fun requestDeezerArl(deviceId: String, onResult: (String?) -> Unit) = onLoop {
        secureRequest(deviceId, ConnectMessage.ArlRequest) { reply ->
            val arl = (reply as? ConnectMessage.ArlResponse)?.arl
            deliver { onResult(arl) }
        }
    }

    // --- 3. Smart Ruting av Kommandoer og Innhold ---
    /** Sender en standard musikk-kommando kryptert til en paret enhet (kobler til automatisk ved behov). */
    fun sendPayload(deviceId: String, payload: ConnectPayload, onResult: (Boolean) -> Unit = {}) = onLoop {
        if (!guard.validatePayload(payload)) return@onLoop deliver { onResult(false) }
        secureRequest(deviceId, ConnectMessage.Payload(payload)) { reply ->
            val ok = reply == ConnectMessage.Ack
            deliver { onResult(ok) }
        }
    }

    fun routeIncomingPayload(payload: ConnectPayload) {
        when (payload) {
            is ConnectPayload.PlayPlaylist -> {
                // Spillelister rutes alltid til AuraAmp-motoren
                AuraCortex.music.loadPlaylist(payload.playlistId)
            }
            is ConnectPayload.RemoteAction -> {
                // Fjernkontroll-kommandoer for avspilling
                handleRemoteAction(payload.action)
            }
            is ConnectPayload.SyncUser -> {
                // Synkronisering av generell brukerinfo
                onUserSync(payload.userInfo)
            }
        }
    }

    private fun handleRemoteAction(action: RemoteActionType) {
        when (action) {
            RemoteActionType.PLAY -> AuraCortex.music.play()
            RemoteActionType.PAUSE -> AuraCortex.music.pause()
            RemoteActionType.NEXT -> AuraCortex.music.next()
            RemoteActionType.PREV -> AuraCortex.music.prev()
            RemoteActionType.CLEAR_QUEUE -> AuraCortex.music.clearQueue()
        }
    }

    // --- Sikker forespørsel: håndtrykk ved behov, deretter kryptert melding (kjøres på loop-tråden) ---
    private fun secureRequest(
        deviceId: String,
        inner: ConnectMessage,
        address: String? = null,
        allowPendingUnpair: Boolean = false,
        retry: Boolean = true,
        onReply: (ConnectMessage?) -> Unit,
    ) {
        val device = (if (allowPendingUnpair) guard.anyDevice(deviceId) else guard.trustedDevice(deviceId))
            ?: return onReply(null)
        val target = address ?: device.lastAddress
        if (!guard.isAllowedPeer(target)) return onReply(null)

        val sid = if (address == null || address == device.lastAddress) guard.activeSessionFor(deviceId) else null
        if (sid != null) return sendSealed(deviceId, target, sid, inner, address, allowPendingUnpair, retry, onReply)

        val handshake = guard.startSession(deviceId) ?: return onReply(null)
        exchange(target, ConnectMessage.SessionHello(identity.deviceId, handshake.publicKeyHex)) { r ->
            val newSid = (r as? ConnectMessage.SessionAccept)?.let { handshake.complete(target, it.keyHex, it.sid, it.proofHex) }
            if (newSid == null) onReply(null)
            else sendSealed(deviceId, target, newSid, inner, address, allowPendingUnpair, retry = false, onReply = onReply)
        }
    }

    private fun sendSealed(
        deviceId: String, target: String, sid: String, inner: ConnectMessage,
        address: String?, allowPendingUnpair: Boolean, retry: Boolean, onReply: (ConnectMessage?) -> Unit,
    ) {
        val sealed = guard.seal(sid, ConnectCodec.encode(inner)) ?: return onReply(null)
        exchange(target, ConnectMessage.Sealed(sid, sealed.counter, sealed.cipherHex)) { r ->
            when (r) {
                is ConnectMessage.Sealed -> {
                    val opened = if (r.sid == sid) guard.open(target, r.sid, r.counter, r.cipherHex) else null
                    val answer = opened?.takeIf { it.first == deviceId }?.second?.let { ConnectCodec.decode(it) }
                    if (answer == ConnectMessage.Unpair && inner != ConnectMessage.Unpair) {
                        // Den andre enheten har slettet paringen – slett her også.
                        guard.forget(deviceId)
                        deliver { onDeviceUnpaired(deviceId) }
                        onReply(null)
                    } else {
                        onReply(answer)
                    }
                }
                ConnectMessage.Denied -> {
                    // Motparten kjenner ikke økten (f.eks. restart) – nytt håndtrykk én gang.
                    guard.dropSession(sid)
                    if (retry) secureRequest(deviceId, inner, address, allowPendingUnpair, retry = false, onReply = onReply)
                    else onReply(null)
                }
                else -> onReply(null)
            }
        }
    }

    private fun exchange(address: String, message: ConnectMessage, onReply: (ConnectMessage?) -> Unit) {
        transport.send(address, ConnectCodec.encode(message)) { raw -> onLoop { onReply(raw?.let { ConnectCodec.decode(it) }) } }
    }

    // --- Mottak av alle meldinger (alt går gjennom AuraGuard først; kjøres på loop-tråden) ---
    private fun handleIncoming(from: String, raw: String, reply: (String) -> Unit) {
        val replied = AtomicBoolean(false)
        fun respond(m: ConnectMessage) { if (replied.compareAndSet(false, true)) reply(ConnectCodec.encode(m)) }

        if (!guard.isConfigured || !guard.admitIncoming(from, raw.length)) return respond(ConnectMessage.Denied)
        val msg = ConnectCodec.decode(raw)
        if (msg == null) {
            guard.reportSuspicious(from, AuraGuard.EventType.MALFORMED_MESSAGE, "Ugyldig melding")
            return respond(ConnectMessage.Denied)
        }
        val me = AuraGuard.PeerInfo(identity.deviceId, identity.name, identity.type.name, transport.localEndpoint()?.second)
        when (msg) {
            is ConnectMessage.PairHello -> respond(when (val s = guard.receiverHello(from, msg.deviceId, msg.commitHex)) {
                is AuraGuard.Step.Ok -> ConnectMessage.PairKey(s.value.first, s.value.second)
                is AuraGuard.Step.Fail -> ConnectMessage.PairRejected(s.error)
            })
            is ConnectMessage.PairReveal -> respond(when (val s = guard.receiverReveal(from, msg.keyHex, msg.nonceHex)) {
                is AuraGuard.Step.Ok -> { val sas = s.value; deliver { onShowSas(sas) }; ConnectMessage.PairRevealOk }
                is AuraGuard.Step.Fail -> ConnectMessage.PairRejected(s.error)
            })
            is ConnectMessage.PairConfirm -> when (val s = guard.receiverVerify(from, msg.cipherHex)) {
                is AuraGuard.Step.Fail -> respond(ConnectMessage.PairRejected(s.error))
                is AuraGuard.Step.Ok -> askApproval(s.value) { approval ->
                    when (val c = guard.receiverComplete(s.value, approval, me)) {
                        is AuraGuard.Step.Ok -> {
                            val paired = c.value.first.toPaired()
                            deliver { onDevicePaired(paired) }
                            respond(ConnectMessage.PairDone(c.value.second))
                        }
                        is AuraGuard.Step.Fail -> respond(ConnectMessage.PairRejected(c.error))
                    }
                }
            }
            is ConnectMessage.SessionHello -> respond(
                guard.acceptSession(from, msg.deviceId, msg.keyHex)
                    ?.let { (key, sid, proof) -> ConnectMessage.SessionAccept(key, sid, proof) } ?: ConnectMessage.Denied,
            )
            is ConnectMessage.Sealed -> {
                val (peerId, plain) = guard.open(from, msg.sid, msg.counter, msg.cipherHex) ?: return respond(ConnectMessage.Denied)
                val inner = ConnectCodec.decode(plain)
                handleSecure(from, peerId, inner) { answer ->
                    val out = guard.seal(msg.sid, ConnectCodec.encode(answer)) ?: return@handleSecure respond(ConnectMessage.Denied)
                    if (answer == ConnectMessage.Unpair || (answer == ConnectMessage.Ack && inner == ConnectMessage.Unpair)) {
                        guard.forget(peerId)
                    }
                    respond(ConnectMessage.Sealed(msg.sid, out.counter, out.cipherHex))
                }
            }
            else -> respond(ConnectMessage.Denied)
        }
    }

    private fun askApproval(pending: AuraGuard.PendingApproval, then: (Approval) -> Unit) {
        val once = AtomicBoolean(false)
        val finish: (Approval) -> Unit = { a -> if (once.compareAndSet(false, true)) onLoop { then(a) } }
        scheduler.schedule(Runnable { finish(Approval.Deny) }, APPROVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val request = PairingRequest(
            pending.deviceId, pending.name, DeviceType.values().firstOrNull { it.name == pending.type } ?: DeviceType.PHONE,
            pending.fingerprint.take(16).uppercase().chunked(4).joinToString("-"),
        )
        deliver { onApprovePairing(request) { a -> finish(a) } }
    }

    /** Meldinger som har bestått dekryptering og autentisering. [done] kalles nøyaktig én gang på loop-tråden. */
    private fun handleSecure(from: String, peerId: String, inner: ConnectMessage?, done: (ConnectMessage) -> Unit) {
        val device = guard.anyDevice(peerId) ?: return done(ConnectMessage.Denied)
        if (device.pendingUnpair) {
            // Vi har slettet paringen mens den andre var borte – gi beskjed nå.
            return done(ConnectMessage.Unpair)
        }
        guard.updateAddress(peerId, from)
        when (inner) {
            ConnectMessage.Ping -> done(ConnectMessage.Ack)
            ConnectMessage.Unpair -> {
                deliver { onDeviceUnpaired(peerId) }
                done(ConnectMessage.Ack)
            }
            ConnectMessage.ArlRequest -> {
                val paired = device.toPaired()
                deliver {
                    val arl = try { arlProvider(paired) } catch (e: Exception) { null }
                    onLoop { done(ConnectMessage.ArlResponse(arl)) }
                }
            }
            is ConnectMessage.Payload -> {
                val needed = if (inner.payload is ConnectPayload.SyncUser) Permission.USER_SYNC else Permission.PLAYBACK_CONTROL
                when {
                    needed !in device.permissions -> {
                        guard.emit(AuraGuard.EventType.NOT_ALLOWED, from, "${device.name} mangler $needed")
                        done(ConnectMessage.Denied)
                    }
                    !guard.validatePayload(inner.payload) -> {
                        guard.reportSuspicious(from, AuraGuard.EventType.UNSAFE_CONTENT, "Ugyldig innhold fra ${device.name}")
                        done(ConnectMessage.Denied)
                    }
                    else -> {
                        val payload = inner.payload
                        deliver { routeIncomingPayload(payload) }
                        done(ConnectMessage.Ack)
                    }
                }
            }
            is ConnectMessage.Call -> handleCall(from, device, inner) { result -> done(ConnectMessage.CallResult.from(result)) }
            else -> done(ConnectMessage.Denied)
        }
    }

    private fun handleCall(from: String, device: AuraGuard.TrustedDevice, call: ConnectMessage.Call, done: (CommandResult) -> Unit) {
        val reg = commands[call.op] ?: return done(CommandResult.fail(CallError.UNKNOWN_OPERATION, "Ikke tilgjengelig på denne enheten"))
        if (reg.permission != null && reg.permission !in device.permissions) {
            guard.emit(AuraGuard.EventType.NOT_ALLOWED, from, "${device.name} mangler ${reg.permission} for ${call.op}")
            return done(CommandResult.fail(CallError.NOT_ALLOWED))
        }
        val args = try { CortexJson.decodeObject(call.argsJson) } catch (e: IllegalArgumentException) {
            guard.reportSuspicious(from, AuraGuard.EventType.MALFORMED_MESSAGE, "Ugyldige data i ${call.op}")
            return done(CommandResult.fail(CallError.BAD_REQUEST))
        }
        val ctx = CommandContext(device.toPaired(), device.profileId, call.op, args)
        val once = AtomicBoolean(false)
        val respond: (CommandResult) -> Unit = { r ->
            if (once.compareAndSet(false, true)) {
                val safe = r.copy(message = r.message?.let { guard.redact(it) })
                onLoop { done(safe) }
            }
        }
        scheduler.schedule(Runnable { respond(CommandResult.fail(CallError.TIMEOUT)) }, COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val run = {
            deliver {
                try { reg.handler.handle(ctx, respond) } catch (e: Exception) { respond(CommandResult.fail(CallError.FAILED, e.message)) }
            }
        }
        if (reg.requiresConsent) {
            deliver { onCommandConsent(ctx) { ok -> if (ok) run() else respond(CommandResult.fail(CallError.DENIED)) } }
        } else {
            run()
        }
    }

    private fun AuraGuard.TrustedDevice.toPaired() = PairedDevice(
        deviceId, name, lastAddress, DeviceType.values().firstOrNull { it.name == type } ?: DeviceType.PHONE, fingerprint,
        profileId, permissions, pairedAtMs,
    )

    private fun parseQr(qr: String): Map<String, String> {
        val text = qr.trim()
        if (text.length > 1024 || !text.startsWith("auraconnect://pair?")) return emptyMap()
        val params = text.substringAfter('?').split('&').mapNotNull { part ->
            val k = part.substringBefore('=', "")
            if (k.isEmpty()) null else k to decodeUri(part.substringAfter('='))
        }.toMap()
        return if (params["v"] == QR_VERSION.toString()) params else emptyMap()
    }

    private fun encodeUri(s: String): String = buildString {
        for (c in s) if (c.isLetterOrDigit() || c in "-_.~:") append(c) else c.toString().encodeToByteArray().forEach {
            append('%').append((it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase())
        }
    }

    private fun decodeUri(s: String): String {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < s.length) {
            val h = if (s[i] == '%' && i + 2 < s.length) s.substring(i + 1, i + 3).toIntOrNull(16) else null
            if (h != null) { bytes += h.toByte(); i += 3 } else { bytes += s[i].toString().encodeToByteArray().toList(); i++ }
        }
        return bytes.toByteArray().decodeToString()
    }

    companion object {
        const val QR_VERSION = 4
        private const val APPROVAL_TIMEOUT_MS = 60_000L
        private const val COMMAND_TIMEOUT_MS = 90_000L
        val DIRECT = Executor { it.run() }
    }

    private object NoOpTransport : ConnectTransport {
        override fun startAdvertising(identity: DeviceIdentity) {}
        override fun stopAdvertising() {}
        override fun startDiscovery(onFound: (DiscoveredDevice) -> Unit) {}
        override fun stopDiscovery() {}
        override fun send(address: String, message: String, onReply: (String?) -> Unit) = onReply(null)
        override fun setIncomingHandler(handler: (String, String, (String) -> Unit) -> Unit) {}
    }
}

// ---------------------------------------------------------
// Protokoll mellom enheter (tekstbasert)
// ---------------------------------------------------------
sealed class ConnectMessage {
    // Ukryptert (paring og håndtrykk – innholdet er beskyttet kryptografisk)
    data class PairHello(val deviceId: String, val commitHex: String) : ConnectMessage()
    data class PairKey(val keyHex: String, val nonceHex: String) : ConnectMessage()
    data class PairReveal(val keyHex: String, val nonceHex: String) : ConnectMessage()
    object PairRevealOk : ConnectMessage()
    data class PairConfirm(val cipherHex: String) : ConnectMessage()
    data class PairDone(val cipherHex: String) : ConnectMessage()
    data class PairRejected(val reason: AuraConnect.PairingError) : ConnectMessage()
    data class SessionHello(val deviceId: String, val keyHex: String) : ConnectMessage()
    data class SessionAccept(val keyHex: String, val sid: String, val proofHex: String) : ConnectMessage()
    data class Sealed(val sid: String, val counter: Long, val cipherHex: String) : ConnectMessage()
    object Denied : ConnectMessage()

    // Kun inne i krypterte meldinger
    object Ping : ConnectMessage()
    object Unpair : ConnectMessage()
    object ArlRequest : ConnectMessage()
    data class ArlResponse(val arl: String?) : ConnectMessage()
    data class Payload(val payload: ConnectPayload) : ConnectMessage()
    data class Call(val op: String, val argsJson: String) : ConnectMessage()
    data class CallResult(val ok: Boolean, val dataJson: String, val error: CallError?, val message: String?) : ConnectMessage() {
        fun toResult(): CommandResult {
            val data = try { CortexJson.decodeObject(dataJson) } catch (e: IllegalArgumentException) { return CommandResult.fail(CallError.FAILED) }
            return CommandResult(ok, data, error, message)
        }
        companion object {
            fun from(r: CommandResult): CallResult {
                val json = try { CortexJson.encode(r.data) } catch (e: IllegalArgumentException) {
                    return CallResult(false, "{}", CallError.FAILED, "Ugyldige svardata")
                }
                return CallResult(r.ok, json, r.error, r.message)
            }
        }
    }
    object Ack : ConnectMessage()
}

object ConnectCodec {
    private const val VERSION = "AC3"

    fun encode(msg: ConnectMessage): String {
        val fields: List<String> = when (msg) {
            is ConnectMessage.PairHello -> listOf("PAIR_HELLO", msg.deviceId, msg.commitHex)
            is ConnectMessage.PairKey -> listOf("PAIR_KEY", msg.keyHex, msg.nonceHex)
            is ConnectMessage.PairReveal -> listOf("PAIR_REVEAL", msg.keyHex, msg.nonceHex)
            ConnectMessage.PairRevealOk -> listOf("PAIR_REVEAL_OK")
            is ConnectMessage.PairConfirm -> listOf("PAIR_CONFIRM", msg.cipherHex)
            is ConnectMessage.PairDone -> listOf("PAIR_DONE", msg.cipherHex)
            is ConnectMessage.PairRejected -> listOf("PAIR_NO", msg.reason.name)
            is ConnectMessage.SessionHello -> listOf("SESSION_HELLO", msg.deviceId, msg.keyHex)
            is ConnectMessage.SessionAccept -> listOf("SESSION_OK", msg.keyHex, msg.sid, msg.proofHex)
            is ConnectMessage.Sealed -> listOf("SEALED", msg.sid, msg.counter.toString(), msg.cipherHex)
            ConnectMessage.Denied -> listOf("DENIED")
            ConnectMessage.Ping -> listOf("PING")
            ConnectMessage.Unpair -> listOf("UNPAIR")
            ConnectMessage.ArlRequest -> listOf("ARL_REQ")
            is ConnectMessage.ArlResponse -> if (msg.arl == null) listOf("ARL_RES") else listOf("ARL_RES", msg.arl)
            is ConnectMessage.Payload -> listOf("PAYLOAD") + encodePayload(msg.payload)
            is ConnectMessage.Call -> listOf("CALL", msg.op, msg.argsJson)
            is ConnectMessage.CallResult -> listOf("RESULT", if (msg.ok) "1" else "0", msg.dataJson, msg.error?.name ?: "", msg.message ?: "")
            ConnectMessage.Ack -> listOf("ACK")
        }
        return joinFields(listOf(VERSION) + fields)
    }

    /** Returnerer null for ugyldige/ukjente meldinger i stedet for å krasje. */
    fun decode(raw: String): ConnectMessage? = try {
        val f = splitFields(raw)
        if (f.firstOrNull() != VERSION) null else when (f[1]) {
            "PAIR_HELLO" -> ConnectMessage.PairHello(f[2], f[3])
            "PAIR_KEY" -> ConnectMessage.PairKey(f[2], f[3])
            "PAIR_REVEAL" -> ConnectMessage.PairReveal(f[2], f[3])
            "PAIR_REVEAL_OK" -> ConnectMessage.PairRevealOk
            "PAIR_CONFIRM" -> ConnectMessage.PairConfirm(f[2])
            "PAIR_DONE" -> ConnectMessage.PairDone(f[2])
            "PAIR_NO" -> ConnectMessage.PairRejected(AuraConnect.PairingError.valueOf(f[2]))
            "SESSION_HELLO" -> ConnectMessage.SessionHello(f[2], f[3])
            "SESSION_OK" -> ConnectMessage.SessionAccept(f[2], f[3], f[4])
            "SEALED" -> ConnectMessage.Sealed(f[2], f[3].toLong(), f[4])
            "DENIED" -> ConnectMessage.Denied
            "PING" -> ConnectMessage.Ping
            "UNPAIR" -> ConnectMessage.Unpair
            "ARL_REQ" -> ConnectMessage.ArlRequest
            "ARL_RES" -> ConnectMessage.ArlResponse(f.getOrNull(2))
            "PAYLOAD" -> decodePayload(f.drop(2))?.let { ConnectMessage.Payload(it) }
            "CALL" -> ConnectMessage.Call(f[2], f[3])
            "RESULT" -> ConnectMessage.CallResult(
                f[2] == "1", f[3], f[4].takeIf { it.isNotEmpty() }?.let { CallError.valueOf(it) }, f[5].takeIf { it.isNotEmpty() },
            )
            "ACK" -> ConnectMessage.Ack
            else -> null
        }
    } catch (e: IndexOutOfBoundsException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun encodePayload(p: ConnectPayload): List<String> = when (p) {
        is ConnectPayload.PlayPlaylist -> listOf("PLAYLIST", p.playlistId)
        is ConnectPayload.RemoteAction -> listOf("REMOTE", p.action.name)
        is ConnectPayload.SyncUser -> listOf("SYNC_USER") + p.userInfo.flatMap { (k, v) -> listOf(k, v) }
    }

    private fun decodePayload(f: List<String>): ConnectPayload? = when (f[0]) {
        "PLAYLIST" -> ConnectPayload.PlayPlaylist(f[1])
        "REMOTE" -> ConnectPayload.RemoteAction(RemoteActionType.valueOf(f[1]))
        "SYNC_USER" -> ConnectPayload.SyncUser(f.drop(1).chunked(2).filter { it.size == 2 }.associate { it[0] to it[1] })
        else -> null
    }

    /** Felter skilles med '|'; '%', '|' og linjeskift escapes. */
    fun joinFields(fields: List<String>): String = fields.joinToString("|") { escape(it) }

    /** Kaster IllegalArgumentException ved ugyldig escape. */
    fun splitFields(raw: String): List<String> = raw.split('|').map { unescape(it) }

    private fun escape(s: String) = s.replace("%", "%25").replace("|", "%7C").replace("\n", "%0A").replace("\r", "%0D")

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            if (s[i] == '%') {
                require(i + 2 < s.length) { "Ugyldig escape" }
                when (s.substring(i + 1, i + 3)) {
                    "25" -> append('%')
                    "7C" -> append('|')
                    "0A" -> append('\n')
                    "0D" -> append('\r')
                    else -> throw IllegalArgumentException("Ugyldig escape")
                }
                i += 3
            } else {
                append(s[i])
                i++
            }
        }
    }
}

// ---------------------------------------------------------
// Minimal JSON (for kommandodata). Tall: heltall -> Long, desimaltall -> Double.
// ---------------------------------------------------------
object CortexJson {
    private const val MAX_DEPTH = 32

    fun encode(value: Any?): String = StringBuilder().also { write(it, value, 0) }.toString()

    /** Kaster IllegalArgumentException ved ugyldig JSON eller hvis toppnivået ikke er et objekt. */
    fun decodeObject(text: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return decode(text) as? Map<String, Any?> ?: throw IllegalArgumentException("Forventet JSON-objekt")
    }

    fun decode(text: String): Any? {
        val p = Parser(text)
        val v = p.value(0)
        p.ws()
        require(p.pos == text.length) { "Ekstra tegn etter JSON" }
        return v
    }

    private fun write(sb: StringBuilder, v: Any?, depth: Int) {
        require(depth < MAX_DEPTH) { "For dyp JSON" }
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v)
            is Float, is Double -> {
                val d = (v as Number).toDouble()
                require(d.isFinite()) { "Ugyldig tall" }
                sb.append(d)
            }
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    require(k is String) { "JSON-nøkler må være tekst" }
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k); sb.append(':'); write(sb, value, depth + 1)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (item in v) { if (!first) sb.append(','); first = false; write(sb, item, depth + 1) }
                sb.append(']')
            }
            is Array<*> -> write(sb, v.toList(), depth)
            else -> throw IllegalArgumentException("Ikke JSON-kompatibel verdi: ${v::class.simpleName}")
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun ws() { while (pos < s.length && s[pos] in " \t\r\n") pos++ }

        fun value(depth: Int): Any? {
            require(depth < MAX_DEPTH) { "For dyp JSON" }
            ws()
            require(pos < s.length) { "Uventet slutt" }
            return when (s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, pos)) { "Ugyldig verdi" }
            pos += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            pos++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (pos < s.length && s[pos] == '}') { pos++; return m }
            while (true) {
                ws()
                require(pos < s.length && s[pos] == '"') { "Forventet nøkkel" }
                val k = str()
                ws()
                require(pos < s.length && s[pos] == ':') { "Forventet :" }
                pos++
                m[k] = value(depth + 1)
                ws()
                require(pos < s.length) { "Uventet slutt" }
                when (s[pos++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> throw IllegalArgumentException("Forventet , eller }")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            pos++
            val l = ArrayList<Any?>()
            ws()
            if (pos < s.length && s[pos] == ']') { pos++; return l }
            while (true) {
                l.add(value(depth + 1))
                ws()
                require(pos < s.length) { "Uventet slutt" }
                when (s[pos++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> throw IllegalArgumentException("Forventet , eller ]")
                }
            }
        }

        private fun str(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "Uavsluttet tekst" }
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        require(pos < s.length) { "Ugyldig escape" }
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 <= s.length) { "Ugyldig escape" }
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: throw IllegalArgumentException("Ugyldig escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("Ugyldig escape \\$e")
                        }
                    }
                    c < ' ' -> throw IllegalArgumentException("Kontrolltegn i tekst")
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            require(t.isNotEmpty() && t != "-") { "Ugyldig tall" }
            return if (t.any { it in ".eE" }) {
                t.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw IllegalArgumentException("Ugyldig tall")
            } else {
                t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw IllegalArgumentException("Ugyldig tall")
            }
        }
    }
}

// ---------------------------------------------------------
// Innebygd plattform: krypto, lagring og nettverk (Java-standardbibliotek, virker på Android 8+ og JVM)
// ---------------------------------------------------------

/** P-256 ECDH + AES-256-GCM + SHA-256/HMAC fra Java Cryptography Architecture. */
class JcaGuardCrypto : AuraGuard.GuardCrypto {
    private val rnd = SecureRandom()
    private val reference: ECPublicKey by lazy {
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), rnd) }.generateKeyPair().public as ECPublicKey
    }

    override fun randomBytes(size: Int) = ByteArray(size).also { rnd.nextBytes(it) }

    override fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), rnd) }.generateKeyPair()
        return kp.private.encoded to kp.public.encoded
    }

    override fun agree(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val keyFactory = KeyFactory.getInstance("EC")
        val priv = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKey))
        val pub = keyFactory.generatePublic(X509EncodedKeySpec(peerPublicKey)) as? ECPublicKey
            ?: throw IllegalArgumentException("Ikke en EC-nøkkel")
        // Kun P-256 godtas (hindrer nøkler på andre/svake kurver).
        require(pub.params.curve == reference.params.curve && pub.params.generator == reference.params.generator &&
            pub.params.order == reference.params.order) { "Feil kurve" }
        return KeyAgreement.getInstance("ECDH").run { init(priv); doPhase(pub, true); generateSecret() }
    }

    override fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    override fun seal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(plaintext)
        }

    override fun open(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray? = try {
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad); doFinal(ciphertext)
        }
    } catch (e: AEADBadTagException) {
        null
    }
}

/**
 * Lagrer nøkler og parede enheter i appens private mappe (kun lesbar for appen; Android-sandkassen beskytter den).
 * For maskinvarebeskyttede nøkler kan appen gi inn egen SecureStorage (Android Keystore) via AuraCortex.Config.storage.
 */
class FileSecureStorage(private val dir: File) : AuraGuard.SecureStorage {
    private val file = File(dir, "store.properties")
    private val props = Properties()

    init {
        dir.mkdirs()
        ownerOnly(dir, isDir = true)
        if (file.exists()) file.inputStream().use { props.load(it) }
    }

    @Synchronized override fun get(key: String): String? = props.getProperty(key)
    @Synchronized override fun put(key: String, value: String) { props.setProperty(key, value); save() }
    @Synchronized override fun remove(key: String) { if (props.remove(key) != null) save() }

    private fun save() {
        val tmp = File(dir, "store.properties.tmp")
        tmp.outputStream().use { props.store(it, null) }
        ownerOnly(tmp, isDir = false)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun ownerOnly(f: File, isDir: Boolean) {
        f.setReadable(false, false); f.setReadable(true, true)
        f.setWritable(false, false); f.setWritable(true, true)
        if (isDir) { f.setExecutable(false, false); f.setExecutable(true, true) }
    }
}

/**
 * Nettverk: TCP (én forespørsel/svar per tilkobling, lengdeprefiks) + UDP-kringkasting for å finne enheter.
 * Serveren lytter kun på hjemmenett-adressen. Kringkastingen inneholder bare id, navn, type og port – aldri hemmeligheter.
 * Android: krever INTERNET-tillatelse. Noen Wi-Fi-drivere filtrerer kringkasting i strømsparing; da finnes enheter
 * likevel via lagret adresse eller QR-koden.
 */
class TcpTransport(
    private val port: Int,
    private val bindAddress: InetAddress?,
    private val discoveryPort: Int,
    private val maxFrameBytes: () -> Int,
) : AuraConnect.ConnectTransport {
    constructor(port: Int, bindAddress: InetAddress?, maxFrameBytes: () -> Int) : this(port, bindAddress, port + 1, maxFrameBytes)

    @Volatile private var handler: ((String, String, (String) -> Unit) -> Unit)? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var boundAddress: InetAddress? = null
    @Volatile private var advertised: AuraConnect.DeviceIdentity? = null
    @Volatile private var responder: DatagramSocket? = null
    @Volatile private var scanning = false
    private val workers = ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, SynchronousQueue(), daemonThreads("aura-cortex-conn"))
    private val io = Executors.newCachedThreadPool(daemonThreads("aura-cortex-io"))

    override fun localEndpoint(): Pair<String, Int>? {
        val s = server ?: return null
        val a = boundAddress ?: return null
        return (a.hostAddress ?: return null) to s.localPort
    }

    @Synchronized
    override fun open() {
        if (server != null) return
        val addr = bindAddress ?: pickLanAddress() ?: return // ikke på hjemmenett: ingen server (utgående virker fortsatt)
        val s = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(addr, port), 16) }
        } catch (e: IOException) {
            return
        }
        server = s
        boundAddress = addr
        thread(isDaemon = true, name = "aura-cortex-accept") { acceptLoop(s) }
        if (advertised != null) startResponder()
    }

    @Synchronized
    override fun close() {
        scanning = false
        responder?.close(); responder = null
        server?.close(); server = null
        boundAddress = null
    }

    override fun setIncomingHandler(handler: (String, String, (String) -> Unit) -> Unit) { this.handler = handler }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val c = try { s.accept() } catch (e: IOException) { break }
            try { workers.execute { serve(c) } } catch (e: RejectedExecutionException) { closeQuietly(c) }
        }
    }

    private fun serve(c: Socket) {
        try {
            c.soTimeout = 15_000
            val input = DataInputStream(BufferedInputStream(c.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(c.getOutputStream()))
            val message = readFrame(input) ?: return
            val h = handler ?: return
            val from = c.inetAddress.hostAddress ?: return
            val answer = CompletableFuture<String>()
            h(from, message) { r -> answer.complete(r) }
            val r = try { answer.get(REPLY_WAIT_MS, TimeUnit.MILLISECONDS) } catch (e: Exception) { return }
            writeFrame(output, r)
        } catch (e: IOException) {
            // tilkoblingen ble brutt – ignorer
        } finally {
            closeQuietly(c)
        }
    }

    override fun send(address: String, message: String, onReply: (String?) -> Unit) {
        io.execute {
            val reply = try {
                val host = AuraGuard.hostOf(address)
                val p = AuraGuard.portOf(address) ?: port
                // Kun IP-adresser (aldri DNS-oppslag av vilkårlige navn).
                require(host.isNotEmpty() && host.all { it.isDigit() || it == '.' || it == ':' || it in 'a'..'f' })
                Socket().use { s ->
                    s.connect(InetSocketAddress(InetAddress.getByName(host), p), 4_000)
                    s.soTimeout = (REPLY_WAIT_MS + 5_000).toInt()
                    val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                    writeFrame(out, message)
                    readFrame(DataInputStream(BufferedInputStream(s.getInputStream())))
                }
            } catch (e: Exception) {
                null
            }
            onReply(reply)
        }
    }

    private fun readFrame(input: DataInputStream): String? {
        val len = input.readInt()
        if (len < 0 || len > maxFrameBytes()) return null
        val b = ByteArray(len)
        input.readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun writeFrame(out: DataOutputStream, text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        out.writeInt(b.size)
        out.write(b)
        out.flush()
    }

    // --- Oppdagelse på lokalnettet ---
    override fun startAdvertising(identity: AuraConnect.DeviceIdentity) {
        advertised = identity
        startResponder()
    }

    @Synchronized
    override fun stopAdvertising() {
        advertised = null
        responder?.close()
        responder = null
    }

    @Synchronized
    private fun startResponder() {
        if (responder != null || server == null) return
        val ds = try {
            DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(discoveryPort)) }
        } catch (e: IOException) {
            return
        }
        responder = ds
        thread(isDaemon = true, name = "aura-cortex-discovery") {
            val buf = ByteArray(256)
            var windowStart = 0L
            var count = 0
            while (!ds.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try { ds.receive(packet) } catch (e: IOException) { break }
                if (String(packet.data, 0, packet.length, Charsets.UTF_8) != PROBE) continue
                if (!AuraGuard.isLocalAddress(AuraGuard.hostOf(packet.address.hostAddress ?: continue))) continue
                val now = System.currentTimeMillis()
                if (now - windowStart > 1_000) { windowStart = now; count = 0 }
                if (++count > 20) continue
                val id = advertised ?: continue
                val tcpPort = server?.localPort ?: continue
                val reply = (HERE + ConnectCodec.joinFields(listOf("1", id.deviceId, id.name, id.type.name, tcpPort.toString()))).toByteArray(Charsets.UTF_8)
                try { ds.send(DatagramPacket(reply, reply.size, packet.address, packet.port)) } catch (e: IOException) { }
            }
        }
    }

    override fun startDiscovery(onFound: (AuraConnect.DiscoveredDevice) -> Unit) {
        if (scanning) return
        scanning = true
        thread(isDaemon = true, name = "aura-cortex-scan") {
            try {
                DatagramSocket().use { ds ->
                    ds.broadcast = true
                    ds.soTimeout = 400
                    val probe = PROBE.toByteArray(Charsets.UTF_8)
                    val targets = broadcastAddresses() + InetAddress.getByName("255.255.255.255")
                    val seen = HashSet<String>()
                    val deadline = System.currentTimeMillis() + SCAN_MS
                    var nextProbe = 0L
                    val buf = ByteArray(1024)
                    while (scanning && System.currentTimeMillis() < deadline) {
                        if (System.currentTimeMillis() >= nextProbe) {
                            targets.forEach { t -> try { ds.send(DatagramPacket(probe, probe.size, t, discoveryPort)) } catch (e: IOException) { } }
                            nextProbe = System.currentTimeMillis() + 1_500
                        }
                        val packet = DatagramPacket(buf, buf.size)
                        try { ds.receive(packet) } catch (e: SocketTimeoutException) { continue }
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        if (!text.startsWith(HERE)) continue
                        val f = try { ConnectCodec.splitFields(text.removePrefix(HERE)) } catch (e: IllegalArgumentException) { continue }
                        if (f.size != 5 || f[0] != "1" || !SAFE_ID.matches(f[1])) continue
                        val type = AuraConnect.DeviceType.values().firstOrNull { it.name == f[3] } ?: continue
                        val tcpPort = f[4].toIntOrNull()?.takeIf { it in 1..65535 } ?: continue
                        val host = packet.address.hostAddress ?: continue
                        val addr = AuraGuard.withPort(host, tcpPort)
                        val name = f[2].filter { !it.isISOControl() }.take(48)
                        if (seen.add(f[1] + "@" + addr)) onFound(AuraConnect.DiscoveredDevice(f[1], name, addr, type))
                    }
                }
            } catch (e: IOException) {
                // nettverk utilgjengelig
            } finally {
                scanning = false
            }
        }
    }

    override fun stopDiscovery() { scanning = false }

    private fun broadcastAddresses(): List<InetAddress> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses.mapNotNull { a -> a.broadcast } }
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        private const val PROBE = "AURA-DISCOVER|1"
        private const val HERE = "AURA-HERE|"
        private const val SCAN_MS = 6_000L
        /** Lengste ventetid på svar (godkjenning på skjermen kan ta opptil 60 s). */
        private const val REPLY_WAIT_MS = 95_000L
        private val SAFE_ID = Regex("^[A-Za-z0-9._:\\-]{1,128}$")

        /** Enhetens private (RFC 1918) IPv4-adresse på et aktivt grensesnitt, eller null. */
        fun pickLanAddress(): InetAddress? = try {
            NetworkInterface.getNetworkInterfaces().toList().asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }
                .flatMap { it.inetAddresses.toList().asSequence() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress && AuraGuard.isLocalAddress(it.hostAddress ?: "") }
        } catch (e: Exception) {
            null
        }
    }
}

// ---------------------------------------------------------
// Felles hjelpefunksjoner
// ---------------------------------------------------------
private fun daemonThreads(name: String) = ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }

private fun closeQuietly(c: Socket) { try { c.close() } catch (e: IOException) { } }

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun String.hexToBytes(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        out[i] = substring(2 * i, 2 * i + 2).toIntOrNull(16)?.toByte() ?: return null
    }
    return out
}

private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { (this[it].toInt() and 0xFF) == prefix[it] }

private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

private fun constantTimeEquals(a: String, b: String): Boolean = constantTimeEquals(a.encodeToByteArray(), b.encodeToByteArray())

// Støtteklasser for typer og payload
sealed class ConnectPayload {
    data class PlayPlaylist(val playlistId: String) : ConnectPayload()
    data class RemoteAction(val action: RemoteActionType) : ConnectPayload()
    data class SyncUser(val userInfo: Map<String, String>) : ConnectPayload()
}

enum class RemoteActionType {
    PLAY, PAUSE, NEXT, PREV, CLEAR_QUEUE
}

// Stub-klasser for øvrige systemer i AuraCortex
class AuraMusicEngine {
    fun play() {}
    fun pause() {}
    fun next() {}
    fun prev() {}
    fun clearQueue() {}
    fun loadPlaylist(playlistId: String) {}
    fun addToQueue(songId: String) {}
    fun playNext(songId: String) {}
}
class AuraVideoEngine
class AuraOSEngine
class AuraTVEngine
class AuraSports

/* =====================================================================================
   VALGFRITT FOR ANDROID: maskinvarebeskyttet lagring (Android Keystore)
   =====================================================================================
   Standard er FileSecureStorage i appens private mappe. For å kryptere innholdet med en nøkkel som aldri
   forlater Keystore, legg denne klassen i skallet og send den inn: AuraCortex.Config(..., storage = AndroidKeystoreStorage(context))

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import no.aura.cortex.AuraGuard

class AndroidKeystoreStorage(context: Context) : AuraGuard.SecureStorage {
    private val prefs = context.getSharedPreferences("aura_cortex_secure", Context.MODE_PRIVATE)
    private val alias = "aura_cortex_storage_key"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    @Synchronized override fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            cipher.updateAAD(key.toByteArray())
            String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) { null }
    }

    @Synchronized override fun put(key: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(key.toByteArray())
        val out = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(out, Base64.NO_WRAP)).commit()
    }

    @Synchronized override fun remove(key: String) { prefs.edit().remove(key).commit() }
}
===================================================================================== */
