package no.aura.os.v2

import no.aura.connect.android.FileTrustStore
import no.aura.connect.core.ClientConfig
import no.aura.connect.core.ClientSession
import no.aura.connect.core.CommandContext
import no.aura.connect.core.ConnectClient
import no.aura.connect.core.ErrorCode
import no.aura.connect.core.MemoryTrustStore
import no.aura.connect.core.PendingPeer
import no.aura.connect.core.Permission
import no.aura.connect.core.ProtocolException
import no.aura.connect.core.SoftwareIdentity
import no.aura.os.v2.amp.AmpAccess
import no.aura.os.v2.amp.AmpVerification
import no.aura.os.v2.connect.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CompletableFuture

/** TV-side receive: serve playlists to a paired phone (pull with diff), and accept an ARL only while a grant is armed. Loopback TLS. */
class ReceiveSyncTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private fun phone() = ConnectClient(ClientConfig(SoftwareIdentity.generate(), "Arne's phone"))
    private fun refused(code: ErrorCode, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: ClientSession.CommandError) { assertEquals(code, e.code) } catch (e: ProtocolException) { assertEquals(code, e.code) }
    }
    private fun track(id: String, title: String = "T$id") = SyncTrack(id, title, "Artist", 200_000L, isrc = null)
    private fun pl(id: String, vararg t: String) = SyncPlaylist(id, "List $id", "desc", t.map { track(it) })
    private class Source(var lists: List<SyncPlaylist>) : PlaylistSource { override fun playlists() = lists }
    private class Sink(val receivable: Boolean = true, val limit: Int = Int.MAX_VALUE) : PlaylistSink {
        val imported = mutableListOf<SyncPlaylist>()
        override fun canReceive() = receivable
        override fun importOne(playlist: SyncPlaylist) {
            require(imported.none { it.id == playlist.id } && imported.size < limit || imported.any { it.id == playlist.id }) { "The local library supports $limit imported playlists." }
            imported.removeAll { it.id == playlist.id }; imported.add(playlist)
        }
    }
    private fun pushReceiver(sink: Sink): PairingReceiver {
        val handler = PlaylistPushHandler { sink }
        return PairingReceiver(SoftwareIdentity.generate(), MemoryTrustStore(), "TV", loopback, 0, true, { "p1" },
            { CompletableFuture.completedFuture(true) }, handlers = mapOf(PlaylistSync.PUSH_OP to { ctx: CommandContext -> handler.handle(ctx) }))
    }

    // ---- reconciliation (the "see differences") -----------------------------------------------------------------------------------------------
    @Test fun hash_changes_only_when_content_changes_and_reconcile_picks_the_right_ids() {
        val a = pl("1", "x", "y"); val aSame = pl("1", "x", "y"); val aReordered = pl("1", "y", "x"); val aChanged = pl("1", "x", "z")
        assertEquals(a.hash, aSame.hash); assertNotEquals(a.hash, aReordered.hash); assertNotEquals(a.hash, aChanged.hash)
        val server = listOf(pl("1", "x"), pl("2", "a"), pl("3", "b"))
        val have = mapOf("1" to server[0].hash, "2" to "stale", "9" to "gone")
        assertEquals(listOf("2", "3"), PlaylistReconcile.toFetch(have, server))        // 1 is up to date; 2 changed; 3 new
        assertEquals(listOf("9"), PlaylistReconcile.toRemove(have.keys, server.map { it.id }.toSet()))
    }

    @Test fun reply_sends_the_manifest_and_only_missing_or_changed_bodies_and_pages() {
        val src = Source((1..30).map { pl(it.toString(), "t$it") })
        val full = PlaylistSync.reply(src, emptyMap())
        assertEquals(30, (full["manifest"] as List<*>).size)
        assertEquals("bounded per call", PlaylistSync.MAX_BODIES, (full["bodies"] as List<*>).size); assertEquals(true, full["more"])
        // the phone already has everything, correctly hashed -> no bodies
        val have = src.lists.associate { it.id to it.hash }
        val none = PlaylistSync.reply(src, have); assertTrue((none["bodies"] as List<*>).isEmpty()); assertEquals(false, none["more"])
        // one stale -> exactly that body, carrying no stream url or credential
        val one = PlaylistSync.reply(src, have + ("5" to "stale"))
        val bodies = one["bodies"] as List<*>; assertEquals(1, bodies.size)
        val body = bodies[0] as Map<*, *>; assertEquals("5", body["id"]); assertTrue((body["tracks"] as List<*>).isNotEmpty())
        assertFalse(body.toString().contains("http")); assertFalse(body.toString().contains("arl", true))
        assertEquals(emptyMap<String, String>(), PlaylistSync.parseHave(null))
        assertEquals(mapOf("1" to "h"), PlaylistSync.parseHave(listOf(mapOf("id" to "1", "hash" to "h"), mapOf("id" to 2), "junk")))
    }

    @Test fun a_paired_phone_pulls_playlists_over_the_channel() {
        val src = Source(listOf(pl("1", "a", "b"), pl("2", "c")))
        val identity = SoftwareIdentity.generate(); val store = MemoryTrustStore(); val prompts = mutableListOf<PendingPeer>()
        val handler = PlaylistSyncHandler { src }
        val receiver = PairingReceiver(identity, store, "TV", loopback, 0, true, { "p1" },
            { prompts.add(it); CompletableFuture.completedFuture(true) }, handlers = mapOf(PlaylistSync.OP to { ctx: CommandContext -> handler.handle(ctx) }))
        val port = receiver.start()
        val client = phone(); val paired = client.pair(loopback.hostAddress, port, receiver.openCode()!!)
        assertTrue(Permission.PLAYLIST_SYNC in paired.permissions)
        client.connect(loopback.hostAddress, port, paired).use { s ->
            val reply = s.call(PlaylistSync.OP, mapOf("have" to emptyList<Any?>()))
            assertEquals(2, (reply["manifest"] as List<*>).size); assertEquals(2, (reply["bodies"] as List<*>).size)
            // second pull with what we now have -> nothing to re-send
            val have = (reply["bodies"] as List<*>).map { (it as Map<*, *>); mapOf("id" to it["id"], "hash" to it["hash"]) }
            assertTrue((s.call(PlaylistSync.OP, mapOf("have" to have))["bodies"] as List<*>).isEmpty())
        }
        receiver.close()
    }

    @Test fun a_child_or_empty_source_exposes_no_playlists() {
        assertTrue((PlaylistSync.reply(Source(emptyList()), emptyMap())["manifest"] as List<*>).isEmpty())
    }

    // ---- playlists the phone PUSHES to the TV (phone -> TV; building a list on the TV is hard, so this is the important direction) --------
    @Test fun parse_push_reads_bodies_defensively() {
        val parsed = PlaylistSync.parsePush(listOf(PlaylistSync.bodyMap(pl("1", "a", "b")), mapOf("id" to "2"), "junk", mapOf("no" to "id")))
        assertEquals(listOf("1", "2"), parsed.map { it.id })                       // id-less and non-map entries dropped
        assertEquals(2, parsed[0].tracks.size); assertEquals("a", parsed[0].tracks[0].id); assertTrue(parsed[1].tracks.isEmpty())
        assertTrue(PlaylistSync.parsePush(null).isEmpty())
    }

    @Test fun a_paired_phone_pushes_playlists_and_the_tv_imports_or_updates_them() {
        val sink = Sink(); val receiver = pushReceiver(sink); val port = receiver.start()
        val client = phone(); val paired = client.pair(loopback.hostAddress, port, receiver.openCode()!!)
        assertTrue("an ordinary pair already grants the push", Permission.PLAYLIST_SYNC in paired.permissions)
        client.connect(loopback.hostAddress, port, paired).use { s ->
            val reply = s.call(PlaylistSync.PUSH_OP, mapOf("playlists" to listOf(pl("1", "a", "b"), pl("2", "c")).map { PlaylistSync.bodyMap(it) }))
            assertEquals(2, (reply["imported"] as Number).toInt()); assertTrue((reply["skipped"] as List<*>).isEmpty())
            assertEquals(listOf("1", "2"), sink.imported.map { it.id })
            // re-pushing id "1" with new tracks updates in place (newest-wins by id), never a duplicate
            s.call(PlaylistSync.PUSH_OP, mapOf("playlists" to listOf(PlaylistSync.bodyMap(pl("1", "a", "b", "c")))))
            assertEquals(listOf("1", "2"), sink.imported.map { it.id }.sorted()); assertEquals(3, sink.imported.first { it.id == "1" }.tracks.size)
        }
        receiver.close()
    }

    @Test fun a_child_profile_is_refused_and_an_over_limit_playlist_is_skipped_without_failing_the_rest() {
        val child = pushReceiver(Sink(receivable = false)); val cp = child.start()
        val c1 = phone(); val p1 = c1.pair(loopback.hostAddress, cp, child.openCode()!!)
        c1.connect(loopback.hostAddress, cp, p1).use { s ->
            refused(ErrorCode.NOT_AUTHORIZED) { s.call(PlaylistSync.PUSH_OP, mapOf("playlists" to listOf(PlaylistSync.bodyMap(pl("1", "a"))))) }
        }
        child.close()
        val sink = Sink(limit = 1); val receiver = pushReceiver(sink); val port = receiver.start()
        val c2 = phone(); val p2 = c2.pair(loopback.hostAddress, port, receiver.openCode()!!)
        c2.connect(loopback.hostAddress, port, p2).use { s ->
            val reply = s.call(PlaylistSync.PUSH_OP, mapOf("playlists" to listOf(pl("1", "a"), pl("2", "b")).map { PlaylistSync.bodyMap(it) }))
            assertEquals(1, (reply["imported"] as Number).toInt()); assertEquals(1, (reply["skipped"] as List<*>).size)
            assertEquals("2", ((reply["skipped"] as List<*>)[0] as Map<*, *>)["id"])
        }
        receiver.close()
    }

    // ---- ARL over the persistent pair, only while armed ----------------------------------------------------------------------------------------
    private fun arl() = "ab12".repeat(48)
    @Test fun the_arl_is_accepted_only_while_the_tv_has_armed_the_grant() {
        val dir = java.nio.file.Files.createTempDirectory("tv").toFile()
        val store = FileTrustStore(File(dir, "trust.json"))
        val stored = mutableListOf<String>(); val outcomes = mutableListOf<ArlOfferOutcome>()
        val identity = SoftwareIdentity.generate()
        val window = CredentialGrantWindow(store)
        val handler = ArmedCredentialHandler(window, "p1", stillAllowed = { true }, store = { a -> stored.add(a); true },
            verify = { AmpVerification(AmpAccess.MUSIC_VERIFIED, null) }, onOutcome = { outcomes.add(it) })
        val receiver = PairingReceiver(identity, store, "TV", loopback, 0, true, { "p1" },
            { CompletableFuture.completedFuture(true) }, handlers = mapOf(ArlOffer.OP to { ctx: CommandContext -> handler.handle(ctx) }))
        val port = receiver.start()
        val client = phone(); val paired = client.pair(loopback.hostAddress, port, receiver.openCode()!!)
        val phoneId = store.list().single { !it.isRevoked }.deviceId
        assertFalse("a pair does not grant credential-receive", Permission.CREDENTIAL_RECEIVE in paired.permissions)
        client.connect(loopback.hostAddress, port, paired).use { s ->
            fun offer() = s.call(ArlOffer.OP, mapOf("kind" to ArlOffer.KIND, "value" to arl()))
            refused(ErrorCode.NOT_AUTHORIZED) { offer() }                       // not armed
            assertTrue(window.arm(phoneId))
            assertEquals(true, offer()["stored"])                              // armed: accepted once
            assertEquals(listOf(arl()), stored); assertEquals(AmpAccess.MUSIC_VERIFIED, outcomes.single().check!!.access)
            refused(ErrorCode.NOT_AUTHORIZED) { offer() }                       // auto-disarmed after one credential
        }
        assertNull(window.armedDevice())
        assertFalse("unknown device cannot be armed", window.arm("0123456789abcdef0123456789abcdef"))
        receiver.close()
    }
}
