package no.aura.os.v2

import no.aura.connect.core.ClientConfig
import no.aura.connect.core.ClientSession
import no.aura.connect.core.ConnectClient
import no.aura.connect.core.ErrorCode
import no.aura.connect.core.MemoryTrustStore
import no.aura.connect.core.PairedReceiver
import no.aura.connect.core.PendingPeer
import no.aura.connect.core.Permission
import no.aura.connect.core.ProtocolException
import no.aura.connect.core.SoftwareIdentity
import no.aura.os.v2.amp.AmpAccess
import no.aura.os.v2.amp.AmpEclipseProtocol
import no.aura.os.v2.amp.AmpProviderFailure
import no.aura.os.v2.amp.AmpProviderStage
import no.aura.os.v2.amp.AmpVerification
import no.aura.os.v2.connect.ArlOffer
import no.aura.os.v2.connect.ArlOfferHandler
import no.aura.os.v2.connect.ArlOfferOutcome
import no.aura.os.v2.connect.ArlReceiveSession
import no.aura.os.v2.connect.ArlReceiveText
import no.aura.os.v2.connect.LanAddress
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CompletableFuture

/**
 * "Connect via phone": real AuraConnect pairing and transfer over loopback TLS between a software TV identity and a software phone identity.
 * Proves the protocol path and the TV-side rules; it is not a phone-to-TV test and uses no Android Keystore.
 */
class ArlReceiveTest {
    private val arl = "ab12".repeat(48)                         // 192 hex characters, shaped like an ARL; not a real credential
    private val loopback = InetAddress.getLoopbackAddress()

    private class Tv(pairing: Boolean = true, var allowed: Boolean = true, var approve: Boolean = true, val check: AmpVerification? = AmpVerification(AmpAccess.MUSIC_VERIFIED, null), var storeOk: Boolean = true) {
        val stored = mutableListOf<String>(); val outcomes = mutableListOf<ArlOfferOutcome>(); val prompts = mutableListOf<PendingPeer>()
        val handler = ArlOfferHandler("profile-adult", stillAllowed = { allowed }, store = { a -> if (storeOk) stored.add(a) else false }, verify = { check }, onOutcome = { outcomes.add(it) })
        val session = ArlReceiveSession(SoftwareIdentity.generate(), MemoryTrustStore(), "AuraOS TV", InetAddress.getLoopbackAddress(), 0, pairing, "profile-adult", handler) { peer ->
            prompts.add(peer); CompletableFuture.completedFuture(approve)
        }
        val port = session.start()
    }
    private fun phone() = ConnectClient(ClientConfig(SoftwareIdentity.generate(), "Arne's phone"))
    private fun offer(session: ClientSession, value: Any? = arl, kind: String = ArlOffer.KIND) = session.call(ArlOffer.OP, mapOf("kind" to kind, "value" to value))
    private fun refused(code: ErrorCode, block: () -> Unit) {
        try { block(); fail("expected $code") } catch (e: ClientSession.CommandError) { assertEquals(code, e.code) } catch (e: ProtocolException) { assertEquals(code, e.code) }
    }

    @Test fun a_paired_and_approved_phone_delivers_the_sign_in_once() {
        val tv = Tv(); val client = phone()
        val code = tv.session.openCode()!!
        assertTrue(Regex("[0-9]{6}").matches(code))
        val receiver: PairedReceiver = client.pair(loopback.hostAddress, tv.port, code)
        assertEquals("the phone gets the credential permission and nothing else", ArlOffer.GRANT, receiver.permissions)
        assertEquals(1, tv.prompts.size); assertEquals("Arne's phone", tv.prompts[0].name)
        client.connect(loopback.hostAddress, tv.port, receiver).use { s ->
            val result = offer(s)
            assertEquals(true, result["stored"]); assertEquals("MUSIC_VERIFIED", result["access"]); assertEquals(false, result["renew"])
            assertFalse("the credential is never echoed", result.values.any { it is String && it.contains(arl) })
            assertEquals(listOf(arl), tv.stored); assertEquals(1, tv.outcomes.size); assertTrue(tv.outcomes[0].stored)
            refused(ErrorCode.NOT_AUTHORIZED) { offer(s) }                                   // one sign-in per session
            refused(ErrorCode.NOT_AUTHORIZED) { s.call("playback.play") }                    // no other access to the TV
            refused(ErrorCode.NOT_AUTHORIZED) { s.call("sync.playlists") }
        }
        assertEquals(1, tv.stored.size)
        tv.session.close()
        try { client.connect(loopback.hostAddress, tv.port, receiver).close(); fail("nothing listens after the session is closed") } catch (_: Exception) { }
    }

    @Test fun a_wrong_code_a_denied_prompt_and_a_disabled_build_deliver_nothing() {
        val tv = Tv(); val code = tv.session.openCode()!!
        val wrong = "%06d".format((code.toInt() + 1) % 1_000_000)
        try { phone().pair(loopback.hostAddress, tv.port, wrong); fail("a wrong code must not pair") } catch (e: ProtocolException) { assertNotEquals(ErrorCode.PAIRING_DENIED, e.code) }
        assertTrue("no prompt is shown for a wrong code", tv.prompts.isEmpty()); assertTrue(tv.stored.isEmpty())
        tv.session.close()

        val denying = Tv(approve = false)
        refused(ErrorCode.PAIRING_DENIED) { phone().pair(loopback.hostAddress, denying.port, denying.session.openCode()!!) }
        assertEquals(1, denying.prompts.size); assertTrue(denying.stored.isEmpty()); denying.session.close()

        val off = Tv(pairing = false)
        try { off.session.openCode(); fail("pairing is disabled in this build") } catch (_: IllegalStateException) { }
        off.session.close()
    }

    @Test fun the_tv_side_rules_are_checked_when_the_sign_in_arrives() {
        val tv = Tv(); val client = phone()
        val receiver = client.pair(loopback.hostAddress, tv.port, tv.session.openCode()!!)
        client.connect(loopback.hostAddress, tv.port, receiver).use { s ->
            refused(ErrorCode.BAD_REQUEST) { offer(s, value = "not-an-arl") }
            refused(ErrorCode.BAD_REQUEST) { offer(s, value = 12345L) }
            refused(ErrorCode.BAD_REQUEST) { offer(s, value = "ab".repeat(400)) }
            refused(ErrorCode.BAD_REQUEST) { offer(s, kind = "password") }
            refused(ErrorCode.NOT_AUTHORIZED) { s.call(ArlOffer.OP, mapOf("kind" to ArlOffer.KIND, "value" to arl), profile = "another-profile") }
            tv.allowed = false                                                               // Administrator session ended / profile changed on the TV
            refused(ErrorCode.NOT_AUTHORIZED) { offer(s) }
            assertTrue(tv.stored.isEmpty())
            tv.allowed = true; tv.storeOk = false                                            // secure storage refuses
            refused(ErrorCode.HANDLER_FAILED) { offer(s) }
            assertEquals(listOf(false), tv.outcomes.map { it.stored })
            tv.storeOk = true
            assertEquals(true, offer(s)["stored"])                                           // a failed store did not use up the session
        }
        tv.session.close()
    }

    @Test fun a_rejected_arl_is_stored_but_reported_as_needing_renewal() {
        val rejected = AmpVerification(AmpAccess.MANIFEST_AVAILABLE, AmpProviderFailure(AmpProviderStage.SEARCH, AmpEclipseProtocol.httpProblem(401), 401))
        val tv = Tv(check = rejected); val client = phone()
        val receiver = client.pair(loopback.hostAddress, tv.port, tv.session.openCode()!!)
        client.connect(loopback.hostAddress, tv.port, receiver).use { s ->
            val result = offer(s)
            assertEquals("MANIFEST_AVAILABLE", result["access"]); assertEquals(true, result["renew"])
        }
        assertEquals(rejected, tv.outcomes.single().check); tv.session.close()
    }

    @Test fun trust_does_not_outlive_the_session() {
        val first = Tv(); val client = phone()
        val receiver = client.pair(loopback.hostAddress, first.port, first.session.openCode()!!)
        first.session.close()
        val second = Tv()                                                                    // the next "Connect via phone" on the TV
        try { client.connect(loopback.hostAddress, second.port, receiver).use { offer(it) }; fail("an earlier pairing must not be reusable") } catch (_: Exception) { }
        assertTrue(second.stored.isEmpty()); assertTrue(second.prompts.isEmpty()); second.session.close()
    }

    @Test fun screens_addresses_and_build_defaults() {
        val (title, note) = ArlReceiveText.waiting("192.168.1.50", LanAddress.PORT, "123456")
        assertEquals("Connect via phone", title); assertTrue(note.contains("192.168.1.50 : 47121")); assertTrue(note.contains("123 456")); assertTrue(note.contains("not your Deezer sign-in"))
        val (heading, body) = ArlReceiveText.approval(PendingPeer("0123456789abcdef0123456789abcdef", "Arne's phone", "AB12-CD34"), "Arne")
        assertTrue(heading.contains("Arne's phone")); assertTrue(body.contains("\"Arne\"")); assertTrue(body.contains("AB12-CD34"))
        assertTrue(LanAddress.isPrivate(byteArrayOf(192.toByte(), 168.toByte(), 1, 50))); assertTrue(LanAddress.isPrivate(byteArrayOf(10, 0, 0, 2))); assertTrue(LanAddress.isPrivate(byteArrayOf(172.toByte(), 20, 0, 2)))
        assertFalse(LanAddress.isPrivate(byteArrayOf(8, 8, 8, 8))); assertFalse(LanAddress.isPrivate(byteArrayOf(127, 0, 0, 1))); assertFalse(LanAddress.isPrivate(byteArrayOf(172.toByte(), 32, 0, 2)))
        assertEquals(setOf(Permission.CREDENTIAL_RECEIVE), ArlOffer.GRANT)
        val gradle = File("build.gradle.kts").readText()
        assertTrue("off by default", gradle.substringAfter("defaultConfig {").substringBefore("buildTypes").contains("\"AURACONNECT_PAIRING\", \"false\""))
        assertFalse("release can never turn pairing on", gradle.substringAfter("release {").substringBefore("proguardFiles").contains("AURACONNECT_PAIRING"))
        assertTrue("debug and perf only on the owner's explicit opt-in", gradle.contains("(devValue(\"AURACONNECT_PAIRING\") == \"true\").toString()"))
        val shell = File("src/main/java/no/aura/os/v2/shell/AuraShell.kt").readText()
        assertTrue("pairing is gated and launches the persistent phone-link flow", shell.contains("if(no.aura.os.v2.BuildConfig.AURACONNECT_PAIRING) add(SheetOption(\"Connect a phone (pair, playlists, sign-in)\") {adminFlow.ensure {startPhoneLink(owner)}})"))
        assertTrue("F6k pairing is durable: a persistent file-backed trust store, not a per-session one", shell.contains("no.aura.connect.android.FileTrustStore(no.aura.connect.android.AuraConnectFiles(appContext).trustStore)"))
        assertFalse("the persistent pair must not fall back to a per-session trust store", shell.contains("no.aura.connect.core.MemoryTrustStore()"))
        assertTrue("the session listens on the home-network address only", shell.contains("LanAddress.pick()"))
        // Every connect source file — including the AURA Connect v1 receiver under connect/v1 — must not log (codes / ARL never reach logcat).
        File("src/main/java/no/aura/os/v2/connect").walkTopDown().filter { it.isFile }.forEach { f -> assertFalse("${f.name} must not log", Regex("Log\\.[dviwe]\\(|println\\(").containsMatchIn(f.readText())) }
    }
}
