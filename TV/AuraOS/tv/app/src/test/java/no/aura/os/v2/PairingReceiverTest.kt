package no.aura.os.v2

import no.aura.connect.core.ClientConfig
import no.aura.connect.core.ConnectClient
import no.aura.connect.core.ClientSession
import no.aura.connect.core.ErrorCode
import no.aura.connect.core.MemoryTrustStore
import no.aura.connect.core.PairedReceiver
import no.aura.connect.core.PendingPeer
import no.aura.connect.core.Permission
import no.aura.connect.core.ProtocolException
import no.aura.connect.core.SoftwareIdentity
import no.aura.os.v2.connect.PairingPayload
import no.aura.os.v2.connect.PairingReceiver
import no.aura.os.v2.connect.TrustedDevices
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CompletableFuture

/** Step 1 of the unified channel: QR/code pairing establishes a PERSISTENT trusted device that reconnects on its pinned key. Loopback TLS. */
class PairingReceiverTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private fun phone(name: String = "Arne's phone") = ConnectClient(ClientConfig(SoftwareIdentity.generate(), name))
    private fun refused(code: ErrorCode, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: ClientSession.CommandError) { assertEquals(code, e.code) } catch (e: ProtocolException) { assertEquals(code, e.code) }
    }

    private class Tv(val store: MemoryTrustStore = MemoryTrustStore(), pairing: Boolean = true, approve: Boolean = true, profile: String = "p1",
                     val identity: SoftwareIdentity = SoftwareIdentity.generate(),
                     handlers: Map<String, (no.aura.connect.core.CommandContext) -> Map<String, Any?>> = emptyMap()) {
        val prompts = mutableListOf<PendingPeer>()
        val receiver = PairingReceiver(identity, store, "Living room TV", InetAddress.getLoopbackAddress(), 0, pairing, { profile },
            { peer -> prompts.add(peer); CompletableFuture.completedFuture(approve) }, handlers = handlers)
        val port = receiver.start()
    }

    @Test fun pairing_is_durable_and_the_phone_reconnects_without_a_new_code() {
        val tv = Tv()
        val code = tv.receiver.openCode()!!
        val client = phone()
        val receiver: PairedReceiver = client.pair(loopback.hostAddress, tv.port, code)
        assertEquals("the pair grants everyday control, never credential-receive", Permission.DEFAULT_CONTROLLER, receiver.permissions)
        assertFalse(Permission.CREDENTIAL_RECEIVE in receiver.permissions)
        assertEquals(1, tv.prompts.size)
        // a durable record now exists for the chosen profile
        val rows = TrustedDevices.rows(tv.store)
        assertEquals(1, rows.size); assertEquals("p1", rows[0].profileId); assertFalse(rows[0].revoked); assertTrue(rows[0].fingerprint.contains("-"))
        // reconnect on the pinned key, no code, no new prompt
        client.connect(loopback.hostAddress, tv.port, receiver).use { assertTrue(it.isOpen) }
        client.connect(loopback.hostAddress, tv.port, receiver).close()
        assertEquals("no approval prompt on resume", 1, tv.prompts.size)
    }

    @Test fun a_fresh_receiver_on_the_same_store_still_trusts_the_phone() {
        val store = MemoryTrustStore()
        val tvIdentity = SoftwareIdentity.generate()                                     // the TV keeps its Keystore key across restarts
        val first = Tv(store, identity = tvIdentity)
        val client = phone()
        val receiver = client.pair(loopback.hostAddress, first.port, first.receiver.openCode()!!)
        first.receiver.close()
        // the TV app restarted: same identity + the same (persisted) trust store
        val second = Tv(store, identity = tvIdentity)
        client.connect(loopback.hostAddress, second.port, receiver).use { assertTrue(it.isOpen) }
        assertTrue("no new pairing prompt after a restart", second.prompts.isEmpty())
    }

    @Test fun granted_but_unimplemented_operations_answer_not_available_never_a_silent_ok() {
        val tv = Tv()
        val client = phone()
        val receiver = client.pair(loopback.hostAddress, tv.port, tv.receiver.openCode()!!)
        client.connect(loopback.hostAddress, tv.port, receiver).use { s ->
            refused(ErrorCode.HANDLER_FAILED) { s.call("sync.playlists") }          // granted by the pair, no engine yet
            refused(ErrorCode.NOT_AUTHORIZED) { s.call("credential.offer", mapOf("kind" to "deezer-arl", "value" to "x")) }   // never granted by a pair
            refused(ErrorCode.NOT_AUTHORIZED) { s.call("sync.watchlist") }           // not in the default grant
        }
    }

    @Test fun one_operation_can_be_implemented_and_reached() {
        val tv = Tv(handlers = mapOf("sync.playlists" to { _ -> mapOf("count" to 0L) }))
        val client = phone()
        val receiver = client.pair(loopback.hostAddress, tv.port, tv.receiver.openCode()!!)
        client.connect(loopback.hostAddress, tv.port, receiver).use { s ->
            assertEquals(0L, s.call("sync.playlists")["count"])
        }
    }

    @Test fun revoke_drops_trust_and_closes_the_device() {
        val tv = Tv()
        val client = phone()
        val receiver = client.pair(loopback.hostAddress, tv.port, tv.receiver.openCode()!!)
        val phoneId = TrustedDevices.rows(tv.store).single().deviceId                    // the TV stores the phone; PairedReceiver.deviceId is the TV's
        assertTrue(tv.receiver.revoke(phoneId))
        assertTrue(TrustedDevices.rows(tv.store).single().revoked)
        refused(ErrorCode.REVOKED) { client.connect(loopback.hostAddress, tv.port, receiver).use { it.call("sync.playlists") } }
    }

    @Test fun a_wrong_code_a_denied_prompt_and_a_disabled_build_pair_nothing() {
        val tv = Tv(); val code = tv.receiver.openCode()!!
        val wrong = "%06d".format((code.toInt() + 1) % 1_000_000)
        try { phone().pair(loopback.hostAddress, tv.port, wrong); fail("wrong code") } catch (_: ProtocolException) {}
        assertTrue(tv.prompts.isEmpty()); assertTrue(TrustedDevices.rows(tv.store).isEmpty()); tv.receiver.close()

        val denying = Tv(approve = false)
        refused(ErrorCode.PAIRING_DENIED) { phone().pair(loopback.hostAddress, denying.port, denying.receiver.openCode()!!) }
        assertTrue(TrustedDevices.rows(denying.store).isEmpty()); denying.receiver.close()

        val off = Tv(pairing = false)
        try { off.receiver.openCode(); fail("pairing disabled") } catch (_: IllegalStateException) {}
        off.receiver.close()
    }

    @Test fun pairing_payload_round_trips_and_rejects_junk() {
        val id = "0123456789abcdef0123456789abcdef"
        val p = PairingPayload("192.168.1.50", 47121, id, "123456")
        val uri = p.toUri()
        assertTrue(uri.startsWith("auraconnect://pair?")); assertTrue(uri.contains("v=3")); assertFalse("no credential in the payload", uri.contains("arl", true))
        assertEquals(p, PairingPayload.parse(uri))
        assertEquals(PairingPayload("host.local", 8080, id, null), PairingPayload.parse("auraconnect://pair?v=3&host=host.local&port=8080&id=$id"))
        // junk
        for (bad in listOf("", "http://pair?v=3", "auraconnect://pair?v=2&host=h&port=1&id=$id",
                "auraconnect://pair?v=3&host=h&port=0&id=$id", "auraconnect://pair?v=3&host=h&port=99999&id=$id",
                "auraconnect://pair?v=3&host=h&port=1&id=nope", "auraconnect://pair?v=3&host=h&port=1&id=$id&code=12345",
                "auraconnect://pair?v=3&host=h&port=1&id=$id&code=abcdef", "auraconnect://pair?v=3&port=1&id=$id"))
            assertNull(bad, PairingPayload.parse(bad))
    }

    @Test fun trusted_devices_lists_active_first_and_groups_revoked_last() {
        val store = MemoryTrustStore()
        val now = System.currentTimeMillis()
        fun add(id: String, at: Long) = store.put(no.aura.connect.core.TrustRecord(id, "d", ByteArray(32) { id.last().code.toByte() }, at, "p1", Permission.DEFAULT_CONTROLLER))
        add("%032x".format(1), now - 1000); add("%032x".format(2), now); add("%032x".format(3), now - 2000)
        store.revoke("%032x".format(2), now + 10)
        val rows = TrustedDevices.rows(store)
        assertEquals(listOf(false, false, true), rows.map { it.revoked })                       // revoked last
        assertEquals("%032x".format(1), rows[0].deviceId)                                        // newest active first
        assertEquals("only the two active devices", 2, TrustedDevices.forProfile(store, "p1").size)
    }
}
