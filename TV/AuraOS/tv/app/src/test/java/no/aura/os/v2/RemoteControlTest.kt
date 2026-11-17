package no.aura.os.v2

import no.aura.connect.android.FileTrustStore
import no.aura.connect.core.ClientConfig
import no.aura.connect.core.ClientSession
import no.aura.connect.core.CommandContext
import no.aura.connect.core.ConnectClient
import no.aura.connect.core.ErrorCode
import no.aura.connect.core.MemoryTrustStore
import no.aura.connect.core.Permission
import no.aura.connect.core.ProtocolException
import no.aura.connect.core.SoftwareIdentity
import no.aura.os.v2.amp.AmpAccess
import no.aura.os.v2.amp.AmpVerification
import no.aura.os.v2.connect.*
import no.aura.os.v2.mediaserver.MediaResult
import no.aura.os.v2.mediaserver.ServerAccount
import no.aura.os.v2.mediaserver.ServerKind
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CompletableFuture

/** A paired phone controlling playback/queue (granted by the pair), and receiving a media-server sign-in behind the armed window. Loopback TLS. */
class RemoteControlTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private fun phone() = ConnectClient(ClientConfig(SoftwareIdentity.generate(), "Arne's phone"))
    private fun refused(code: ErrorCode, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: ClientSession.CommandError) { assertEquals(code, e.code) } catch (e: ProtocolException) { assertEquals(code, e.code) }
    }

    private class FakeTarget : PlaybackTarget {
        val log = mutableListOf<String>()
        var shuffle = false; var repeat = false; var queue = 0; var pos = 0L
        override fun resume() { log.add("resume") }
        override fun pause() { log.add("pause") }
        override fun next() { log.add("next") }
        override fun previous() { log.add("previous") }
        override fun setShuffle(on: Boolean?) { shuffle = on ?: !shuffle; log.add("shuffle=$shuffle") }
        override fun setRepeat(on: Boolean?) { repeat = on ?: !repeat; log.add("repeat=$repeat") }
        override fun seek(positionMs: Long) { pos = positionMs; log.add("seek=$positionMs") }
        override fun enqueue(track: SyncTrack) { queue++; log.add("enqueue=${track.id}") }
        override fun removeFromQueue(index: Int) { queue = (queue - 1).coerceAtLeast(0); log.add("remove=$index") }
        override fun moveInQueue(from: Int, to: Int) { log.add("move=$from>$to") }
        override fun clearQueue() { queue = 0; log.add("clear") }
        override fun snapshot() = PlaybackSnapshot(false, false, shuffle, repeat, pos, 0L, "t1", "Title", "Artist", queue)
    }

    private fun controlReceiver(target: PlaybackTarget): PairingReceiver {
        val handler = PlaybackCommandHandler { target }
        val handlers = buildMap<String, (CommandContext) -> Map<String, Any?>> { PlaybackCommandHandler.OPS.forEach { op -> put(op) { ctx -> handler.handle(ctx) } } }
        return PairingReceiver(SoftwareIdentity.generate(), MemoryTrustStore(), "TV", loopback, 0, true, { "p1" },
            { CompletableFuture.completedFuture(true) }, handlers = handlers)
    }

    @Test fun a_paired_phone_controls_playback_and_queue_and_gets_the_new_state_back() {
        val t = FakeTarget(); val receiver = controlReceiver(t); val port = receiver.start()
        val client = phone(); val paired = client.pair(loopback.hostAddress, port, receiver.openCode()!!)
        assertTrue("the pair grants playback and queue control", Permission.PLAYBACK_CONTROL in paired.permissions && Permission.QUEUE_EDIT in paired.permissions)
        client.connect(loopback.hostAddress, port, paired).use { s ->
            s.call("playback.play"); s.call("playback.pause"); s.call("playback.next"); s.call("playback.previous")
            s.call("playback.seek", mapOf("positionMs" to 45_000L))
            s.call("playback.shuffle", mapOf("on" to true)); s.call("playback.repeat")      // set, then toggle
            s.call("queue.add", mapOf("trackId" to "t9", "track" to mapOf("id" to "t9", "title" to "T", "artist" to "A", "durationMs" to 200_000L)))
            s.call("queue.move", mapOf("from" to 0L, "to" to 1L)); s.call("queue.remove", mapOf("index" to 0L))
            val status = s.call("queue.clear")                                               // every command answers with the fresh snapshot
            assertEquals(0L, (status["queueLength"] as Number).toLong()); assertEquals("t1", status["trackId"]); assertEquals(true, status["shuffle"])
            // a read-only status call mutates nothing
            assertEquals(false, s.call("playback.status")["playing"])
        }
        receiver.close()
        assertEquals(listOf("resume", "pause", "next", "previous", "seek=45000", "shuffle=true", "repeat=true", "enqueue=t9", "move=0>1", "remove=0", "clear"), t.log)
    }

    @Test fun control_needs_a_player_and_rejects_unknown_ops() {
        val receiver = PairingReceiver(SoftwareIdentity.generate(), MemoryTrustStore(), "TV", loopback, 0, true, { "p1" },
            { CompletableFuture.completedFuture(true) }, handlers = buildMap<String, (CommandContext) -> Map<String, Any?>> {
                val h = PlaybackCommandHandler { null }                                       // AuraAmp not running
                PlaybackCommandHandler.OPS.forEach { op -> put(op) { ctx -> h.handle(ctx) } }
            })
        val port = receiver.start(); val client = phone(); val paired = client.pair(loopback.hostAddress, port, receiver.openCode()!!)
        client.connect(loopback.hostAddress, port, paired).use { s ->
            refused(ErrorCode.HANDLER_FAILED) { s.call("playback.play") }                     // no player -> not available, never a silent ok
            refused(ErrorCode.UNKNOWN_OPERATION) { s.call("system.shell") }                   // not in the catalogue at all
        }
        receiver.close()
    }

    // ---- media-server sign-in over the armed window, through the credential.offer router -------------------------------------------------
    private fun plexAccount() = ServerAccount(ServerKind.PLEX, "srv-1", "Home Plex", "https://plex.example.com", false, "user-1", "Arne", "tok-".repeat(4), "dev-1")
    private fun offerArgs(acc: ServerAccount) = mapOf("kind" to acc.kind.name.lowercase(), "account" to mapOf(
        "kind" to acc.kind.name, "serverId" to acc.serverId, "serverName" to acc.serverName, "base" to acc.base, "lanHttp" to acc.lanHttpApproved,
        "userId" to acc.userId, "userName" to acc.userName, "token" to acc.token, "deviceId" to acc.deviceId))

    @Test fun a_media_server_sign_in_is_accepted_only_while_armed_and_the_window_is_one_shot() {
        val dir = java.nio.file.Files.createTempDirectory("tv").toFile()
        val store = FileTrustStore(File(dir, "trust.json"))
        val received = mutableListOf<ServerAccount>()
        val window = CredentialGrantWindow(store)
        val media = MediaServerOfferHandler("p1", stillAllowed = { true },
            receive = { acc -> received.add(acc); MediaServerOutcome(stored = true, verified = true, error = null) }, onOutcome = {})
        val arl = ArlOfferHandler("p1", stillAllowed = { true }, store = { true },
            verify = { AmpVerification(AmpAccess.MUSIC_VERIFIED, null) }, onOutcome = {})
        val router = CredentialReceiveHandler(window, arl, media)
        val receiver = PairingReceiver(SoftwareIdentity.generate(), store, "TV", loopback, 0, true, { "p1" },
            { CompletableFuture.completedFuture(true) }, handlers = mapOf(ArlOffer.OP to { ctx: CommandContext -> router.handle(ctx) }))
        val port = receiver.start()
        val client = phone(); val paired = client.pair(loopback.hostAddress, port, receiver.openCode()!!)
        val phoneId = store.list().single { !it.isRevoked }.deviceId
        assertFalse("a pair never grants credential-receive", Permission.CREDENTIAL_RECEIVE in paired.permissions)
        client.connect(loopback.hostAddress, port, paired).use { s ->
            refused(ErrorCode.NOT_AUTHORIZED) { s.call(ArlOffer.OP, offerArgs(plexAccount())) }     // not armed
            assertTrue(window.arm(phoneId))
            val reply = s.call(ArlOffer.OP, offerArgs(plexAccount()))
            assertEquals(true, reply["stored"]); assertEquals(true, reply["verified"])
            assertEquals(listOf("srv-1"), received.map { it.serverId }); assertEquals(ServerKind.PLEX, received.single().kind)
            refused(ErrorCode.NOT_AUTHORIZED) { s.call(ArlOffer.OP, offerArgs(plexAccount())) }     // auto-disarmed after one credential
            // re-arm: a bad kind fails but does not consume the window; a good ARL then still goes through the same router
            assertTrue(window.arm(phoneId))
            refused(ErrorCode.BAD_REQUEST) { s.call(ArlOffer.OP, mapOf("kind" to "nope")) }
            assertEquals(true, s.call(ArlOffer.OP, mapOf("kind" to ArlOffer.KIND, "value" to "ab12".repeat(48)))["stored"])
        }
        assertNull(window.armedDevice())
        receiver.close()
    }

    @Test fun a_tampered_media_server_address_is_rejected_before_anything_is_stored() {
        val received = mutableListOf<ServerAccount>()
        val media = MediaServerOfferHandler("p1", stillAllowed = { true }, receive = { received.add(it); MediaServerOutcome(true, true, null) }, onOutcome = {})
        // loopback base is never a valid media server (blocks rebinding tricks) -> parse fails -> BAD_REQUEST, nothing received
        val bad = ServerAccount(ServerKind.PLEX, "s", "S", "http://127.0.0.1:32400", false, "u", "U", "t", "d")
        refused(ErrorCode.BAD_REQUEST) { media.handle(ctx(offerArgs(bad))) }
        assertTrue(received.isEmpty())
    }
    private fun ctx(args: Map<String, Any?>) = CommandContext("dev-x", "p1", ArlOffer.OP, args)
}
