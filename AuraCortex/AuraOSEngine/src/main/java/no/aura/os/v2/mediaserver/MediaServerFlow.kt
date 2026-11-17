package no.aura.os.v2.mediaserver

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import no.aura.os.v2.settings.Sheet
import no.aura.os.v2.settings.SheetOption

/**
 * Settings → Connections → Plex / Jellyfin / Emby. Plain Kotlin (sheets are data, no Compose), so connection paths can be unit-tested without Android.
 *
 * Rules:
 * - adding or removing a server sign-in is a protected action: [ensureAdmin] asks for the Administrator PIN first and [isAdmin] is checked
 *   again at the moment the credential is stored or removed (fail closed);
 * - plain HTTP is offered only for a home-network address and only after the person confirms the warning for THAT server;
 * - nothing is reported as connected before the server confirmed the sign-in; codes are shown, secrets never are;
 * - a password is passed straight to the sign-in call and is not kept in this object.
 * [launch] runs a suspending step (the shell uses its coroutine scope; tests run it inline).
 */
class MediaServerFlow(
    private val service: MediaServerService, private val profileId: () -> String,
    private val show: (Sheet?) -> Unit, private val launch: (suspend () -> Unit) -> Unit,
    private val ensureAdmin: (then: () -> Unit) -> Unit, private val isAdmin: () -> Boolean, private val changed: () -> Unit,
    private val browse: ((ServerAccount) -> Unit)? = null,
) {
    private var generation = 0L
    private var activeSession: Session? = null

    /** Invalidate callbacks and pending results when the host closes or switches profile. */
    fun cancel() {
        generation++
        activeSession?.stop()
        if (activeSession != null) show(null)
        activeSession = null
    }

    fun open(kind: ServerKind) {
        cancel()
        val session = Session(++generation, profileId())
        activeSession = session
        session.open(kind)
    }

    private inner class Session(private val id: Long, private val owner: String) {
        private val jobs = mutableSetOf<Job>()
        fun stop() { jobs.toList().forEach { it.cancel() } }
        private fun current() = generation == id && this@MediaServerFlow.profileId() == owner
        private fun profileId() = owner
        private fun isAdmin() = current() && this@MediaServerFlow.isAdmin()
        private fun launch(step: suspend () -> Unit) = this@MediaServerFlow.launch {
            try {
                coroutineScope {
                    val job = coroutineContext.job
                    jobs.add(job)
                    try { if (current()) step() } finally { jobs.remove(job) }
                }
            } catch (e: CancellationException) {
                if (current()) throw e
            }
        }
        private fun ensureAdmin(then: () -> Unit) {
            if (current()) this@MediaServerFlow.ensureAdmin { if (isAdmin()) then() }
        }
        private fun show(sheet: Sheet?) {
            if (!current()) return
            val guarded = when (sheet) {
                is Sheet.Choice -> sheet.copy(options = sheet.options.map { option ->
                    option.copy(onPick = { if (current()) option.onPick() })
                })
                is Sheet.TextEntry -> sheet.copy(onSubmit = { value -> if (isAdmin()) sheet.onSubmit(value) })
                else -> sheet
            }
            this@MediaServerFlow.show(guarded)
        }
        private fun message(title: String, vararg lines: String) = show(Sheet.Message(title, lines.toList()))
        private fun busy(kind: ServerKind, text: String) = message(kind.title, text)
        private fun client(kind: ServerKind) = if (kind == ServerKind.EMBY) service.emby else service.jellyfin

        /** The menu of one service for the active profile: its servers (with their real state), Add, Check all. */
        fun open(kind: ServerKind): Unit = launch {
            val pid = profileId()
            val mine = service.accounts(pid).filter { it.kind == kind }
            val status = service.status(pid, kind)
            show(Sheet.Choice("${kind.title} · ${status.state.label}", buildList {
                mine.forEach { a -> add(SheetOption("${a.serverName} · ${a.userName} · " + (if (service.verifiedAt(pid, a) != null) "verified" else "not verified")) { account(a) }) }
                add(SheetOption(if (mine.isEmpty()) "Connect a ${kind.title} server" else "Connect another server") { ensureAdmin { add(kind) } })
                if (mine.isNotEmpty()) add(SheetOption("Check all connections") { launch { busy(kind, "Checking…"); service.verifyAll(pid); changed(); open(kind) } })
            }, note = status.message))
        }

        private fun account(a: ServerAccount): Unit = show(Sheet.Choice("${a.serverName} · ${a.userName}", listOf(
            SheetOption("Browse libraries") { if (current()) browse?.invoke(a) },
            SheetOption("Check connection") { launch {
                busy(a.kind, "Checking ${a.serverName}…")
                val r = service.verify(profileId(), a); changed()
                message(a.serverName, if (r is MediaResult.Ok) "Connected. The server confirmed this sign-in." else (r as MediaResult.Failure).error.message())
            } },
            SheetOption("Remove from this profile") { ensureAdmin { launch {
                if (!isAdmin()) { message("Administrator PIN needed", "The Administrator session ended. Try again."); return@launch }
                val ok = service.remove(profileId(), a); changed()
                message(a.serverName, if (ok) "Removed. The sign-in was deleted from this device. For Plex remote revocation, remove AuraOS in Plex Settings → Authorized Devices." else "Could not remove the stored sign-in.")
            } } },
        ), note = (if (a.base.startsWith("http://")) "Unencrypted connection on the home network. " else "") + a.base))

        private fun add(kind: ServerKind): Unit = if (kind == ServerKind.PLEX) plexMethod() else askAddress(kind)
        private var modern: PlexModernClient? = null
        private fun plexMethod(): Unit = show(Sheet.Choice("Plex sign-in method", buildList {
            if (service.modernPlex != null) add(SheetOption("Device-key JWT sign-in (recommended)") { ensureAdmin { modernStart() } })
            add(SheetOption("Legacy PIN compatibility sign-in") { modern = null; plexStart() })
        }, note = "Device-key sign-in uses a profile-specific protected key and renewable credentials. Legacy PIN is an explicit compatibility choice. To revoke remotely use Plex Settings → Authorized Devices."))
        private fun modernStart(): Unit = launch {
            if (!isAdmin()) return@launch
            val client = service.modernClient(owner,true)
            if (client == null) { message("Plex device key unavailable", "Protected device keys are unavailable, or Administrator authorization ended. Choose legacy compatibility explicitly if needed."); return@launch }
            modern = client
            when(val pin = client.createPin()) { is MediaResult.Failure -> message("Plex",pin.error.message()); is MediaResult.Ok -> modernWait(client,pin.value,null) }
        }
        private fun modernWait(client: PlexModernClient, pin: PlexPin, note: String?): Unit = show(Sheet.Choice("Plex approval code: ${pin.code}", listOf(
            SheetOption("I approved Plex access") { launch {
                if (!isAdmin()) { message("Administrator PIN needed","Unlock Administrator and start again."); return@launch }
                when(val token = client.checkPin(pin)) {
                    is MediaResult.Failure -> if (token.error == MediaError.PENDING) modernWait(client,pin,"Not approved yet.") else message("Plex",token.error.message())
                    is MediaResult.Ok -> when(val resources = service.modernResources(client,token.value)) {
                        is MediaResult.Failure -> message("Plex",resources.error.message())
                        is MediaResult.Ok -> modernServers(resources.value.first,resources.value.second)
                    }
                }
            } }
        ), note = note ?: "On your phone, open ${client.approvalUrl(pin)} and approve AuraOS. This key belongs only to this AuraOS profile."))
        private fun modernServers(user: PlexUser, servers: List<PlexServer>) {
            if (!isAdmin()) return
            if (servers.isEmpty()) { message("Plex","This account has no authorized media servers."); return }
            show(Sheet.Choice("Choose a Plex server", servers.map { server -> SheetOption(server.name) { launch { plexConnect(user,server,false) } } }, note="Signed in as ${user.name}. Device-key JWT sign-in."))
        }

        // ---- Jellyfin / Emby ------------------------------------------------------------------------------------------------------------------------
        private fun askAddress(kind: ServerKind): Unit = show(Sheet.TextEntry("${kind.title} server address", hint = "https://media.example.com or http://192.168.1.20:8096",
            note = "The address of your own server. Do not include a user name or password.", keyboard = Sheet.Keyboard.URL) { raw -> address(kind, raw, lanHttpApproved = false) })

        private fun address(kind: ServerKind, raw: String, lanHttpApproved: Boolean) {
            when (val p = ServerAddress.parse(raw, lanHttpApproved)) {
                is ServerAddress.Parse.Rejected -> show(Sheet.Choice("Address not accepted", listOf(SheetOption("Enter another address") { askAddress(kind) }), note = p.reason))
                is ServerAddress.Parse.NeedsLanHttpApproval -> show(Sheet.Choice("Unencrypted connection", listOf(
                    SheetOption("Use http for ${p.host} on my home network") { address(kind, raw, lanHttpApproved = true) },
                    SheetOption("Enter another address") { askAddress(kind) },
                ), note = "This address uses http, which is not encrypted: anyone on the same network could read the sign-in and what is played. " +
                    "Only continue for a server on your own home network. https is recommended."))
                is ServerAddress.Parse.Ok -> launch {
                    busy(kind, "Contacting ${p.address.host}…")
                    when (val info = client(kind).publicInfo(p.address)) {
                        is MediaResult.Failure -> show(Sheet.Choice("No ${kind.title} server answered", listOf(SheetOption("Enter another address") { askAddress(kind) }), note = info.error.message()))
                        is MediaResult.Ok -> method(kind, p.address, lanHttpApproved, info.value)
                    }
                }
            }
        }

        private suspend fun method(kind: ServerKind, a: ServerAddress, lan: Boolean, info: ServerInfo) {
            if (!current() || !isAdmin()) return
            val quick = kind == ServerKind.JELLYFIN && (client(kind).quickConnectEnabled(a) as? MediaResult.Ok)?.value == true
            if (!quick) { askUser(kind, a, lan, info, if (kind == ServerKind.JELLYFIN) "Quick Connect is turned off on this server." else null); return }
            show(Sheet.Choice("Sign in to ${info.name}", listOf(
                SheetOption("Quick Connect (code, no password on the TV)") { launch { quickStart(a, lan, info) } },
                SheetOption("User name and password") { askUser(kind, a, lan, info, null) },
            ), note = "${kind.title} ${info.version ?: ""}".trim()))
        }

        private suspend fun quickStart(a: ServerAddress, lan: Boolean, info: ServerInfo) {
            if (!current() || !isAdmin()) return
            when (val qc = service.jellyfin.quickConnectStart(a)) {
                is MediaResult.Failure -> askUser(ServerKind.JELLYFIN, a, lan, info, qc.error.message())
                is MediaResult.Ok -> quickWait(a, lan, info, qc.value, null)
            }
        }

        private fun quickWait(a: ServerAddress, lan: Boolean, info: ServerInfo, qc: QuickConnect, note: String?): Unit = show(Sheet.Choice("Quick Connect code: ${qc.code}", listOf(
            SheetOption("I have approved the code") { launch {
                when (val ok = service.jellyfin.quickConnectApproved(a, qc)) {
                    is MediaResult.Failure -> message("Jellyfin", ok.error.message())
                    is MediaResult.Ok -> if (!ok.value) quickWait(a, lan, info, qc, "Not approved yet. Enter the code in Jellyfin first.")
                        else if (isAdmin()) finish(service.jellyfin.quickConnectFinish(a, lan, info, qc))
                }
            } },
        ), note = note ?: "On a phone or computer where you are signed in to ${info.name}: open Jellyfin → your user → Quick Connect, and enter this code."))

        private fun askUser(kind: ServerKind, a: ServerAddress, lan: Boolean, info: ServerInfo, note: String?): Unit =
            show(Sheet.TextEntry("User name on ${info.name}", hint = "User name", note = note, keyboard = Sheet.Keyboard.TEXT) { user ->
                show(Sheet.TextEntry("Password for ${user.take(40)}", hint = "Password", note = "Sent once to ${a.host} to sign in. It is not stored on this device.", keyboard = Sheet.Keyboard.PASSWORD, allowEmpty = true) { password ->
                    launch { busy(kind, "Signing in…"); finish(client(kind).signIn(a, lan, info, user.trim(), password)) }
                })
            })

        // ---- Plex -----------------------------------------------------------------------------------------------------------------------------------
        private fun plexStart(): Unit = launch {
            busy(ServerKind.PLEX, "Asking Plex for a code…")
            when (val pin = service.plex.createPin()) {
                is MediaResult.Failure -> message("Plex", pin.error.message())
                is MediaResult.Ok -> plexWait(pin.value, null)
            }
        }

        private fun plexWait(pin: PlexPin, note: String?): Unit = show(Sheet.Choice("Plex code: ${pin.code}", listOf(
            SheetOption("I have entered the code") { launch {
                when (val user = service.plex.checkPin(pin)) {
                    is MediaResult.Failure -> if (user.error == MediaError.PENDING) plexWait(pin, "Not linked yet. Enter the code at plex.tv/link first.") else message("Plex", user.error.message())
                    is MediaResult.Ok -> plexServers(user.value)
                }
            } },
        ), note = note ?: "On a phone or computer: go to ${PlexPin.LINK_URL}, sign in to your own Plex account and enter this code."))

        private suspend fun plexServers(user: PlexUser) {
            if (!current() || !isAdmin()) return
            busy(ServerKind.PLEX, "Looking for your Plex servers…")
            when (val list = service.plex.servers(user)) {
                is MediaResult.Failure -> message("Plex", list.error.message())
                is MediaResult.Ok -> if (list.value.isEmpty()) message("Plex", "This Plex account has no media server it can use.")
                    else show(Sheet.Choice("Choose a Plex server", list.value.map { s -> SheetOption(s.name + if (s.owned) "" else " (shared with you)") { launch { plexConnect(user, s, false) } } },
                        note = "Signed in as ${user.name}."))
            }
        }

        private suspend fun plexConnect(user: PlexUser, server: PlexServer, allowLanHttp: Boolean) {
            if (!current() || !isAdmin()) return
            busy(ServerKind.PLEX, "Connecting to ${server.name}…")
            val r = modern?.let { service.connectModern(owner,it,user,server,allowLanHttp) } ?: service.plex.connect(user, server, allowLanHttp)
            val plainLan = server.connections.any { !it.https && it.local }
            if (r is MediaResult.Failure && !allowLanHttp && plainLan && r.error != MediaError.IDENTITY_MISMATCH) {
                show(Sheet.Choice("Unencrypted connection", listOf(SheetOption("Use http for ${server.name} on my home network") { launch { plexConnect(user, server, true) } }),
                    note = "No encrypted connection to ${server.name} worked. Plex also lists an http address on the home network. http is not encrypted: " +
                        "anyone on the same network could read what is sent. Only continue on your own home network."))
                return
            }
            finish(r)
        }

        // ---- shared ending ---------------------------------------------------------------------------------------------------------------------------
        private suspend fun finish(r: MediaResult<ServerAccount>) {
            if (!current()) return
            when (r) {
                is MediaResult.Failure -> message("Not connected", r.error.message())
                is MediaResult.Ok -> {
                    if (!isAdmin()) { message("Administrator PIN needed", "The Administrator session ended before the sign-in could be saved. Try again."); return }
                    val saved = service.add(profileId(), r.value); changed()
                    if (saved) message("Connected to ${r.value.serverName}", "Signed in as ${r.value.userName}. The server confirmed this sign-in.", "It is stored encrypted for this profile only.")
                    else message("Could not save the sign-in", "This profile already has ${AccountCodec.MAX_ACCOUNTS} server sign-ins, or secure storage is unavailable.")
                }
            }
        }
    }
}
