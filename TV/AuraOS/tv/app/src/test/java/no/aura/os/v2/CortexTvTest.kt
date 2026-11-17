package no.aura.os.v2

import no.aura.cortex.*
import no.aura.os.v2.connect.CredentialGrantWindow
import no.aura.os.v2.connect.ErrorCode
import no.aura.os.v2.connect.ProtocolException
import no.aura.os.v2.connect.TvCommands
import org.junit.Assert.*
import org.junit.Test

/** TV-skallet over AuraCortex: paring med godkjenning, TV-kommandoer med tillatelser og "motta innlogging"-vinduet. */
class CortexTvTest {
    private class Loop(val nodes: MutableMap<String, Loop>, val address: String) : AuraConnect.ConnectTransport {
        var handler: ((String, String, (String) -> Unit) -> Unit)? = null
        override fun startAdvertising(identity: AuraConnect.DeviceIdentity) {}
        override fun stopAdvertising() {}
        override fun startDiscovery(onFound: (AuraConnect.DiscoveredDevice) -> Unit) {}
        override fun stopDiscovery() {}
        override fun send(address: String, message: String, onReply: (String?) -> Unit) {
            val h = nodes[AuraGuard.hostOf(address)]?.handler ?: return onReply(null)
            h(this.address, message, onReply)
        }
        override fun setIncomingHandler(handler: (String, String, (String) -> Unit) -> Unit) { this.handler = handler }
        override fun localEndpoint() = address to 47131
    }

    @Test fun phone_pairs_controls_and_sends_signin_only_when_armed() {
        val nodes = mutableMapOf<String, Loop>()
        val tvT = Loop(nodes, "192.168.1.20").also { nodes[it.address] = it }
        val phT = Loop(nodes, "192.168.1.30").also { nodes[it.address] = it }
        AuraCortex.guard.configure(JcaGuardCrypto())
        AuraCortex.connect.configure(tvT, AuraConnect.DeviceIdentity("tv", "AuraOS TV", AuraConnect.DeviceType.TV))
        val phoneGuard = AuraGuard().also { it.configure(JcaGuardCrypto()) }
        val phone = AuraConnect(phoneGuard).also { it.configure(phT, AuraConnect.DeviceIdentity("ph", "Phone", AuraConnect.DeviceType.PHONE)) }

        AuraCortex.connect.onApprovePairing = { _, decide -> decide(Approval.Approve("adult-a", Permission.DEFAULT_CONTROLLER)) }
        var paired: Result<AuraConnect.PairedDevice>? = null
        phone.pairWithQr(AuraCortex.connect.startAdvertisingAsReceiver().qrData) { paired = it }
        assertTrue(paired!!.isSuccess)
        assertEquals("adult-a", AuraCortex.connect.pairedDevice("ph")!!.profileId)

        val gate = java.util.concurrent.CountDownLatch(3)
        val results = java.util.concurrent.ConcurrentHashMap<String, CommandResult>()
        TvCommands.register(mapOf(
            "playback.pause" to { ctx: CommandContext -> mapOf("profile" to ctx.profileId) },
            "credential.offer" to { _: CommandContext -> mapOf("stored" to true) },
            "queue.add" to { _: CommandContext -> throw ProtocolException(ErrorCode.BAD_REQUEST, "trackId required") },
        ))
        phone.call("tv", "playback.pause") { results["pause"] = it; gate.countDown() }
        phone.call("tv", "credential.offer", mapOf("kind" to "deezer.arl")) { results["cred"] = it; gate.countDown() }
        phone.call("tv", "queue.add") { results["queue"] = it; gate.countDown() }
        assertTrue(gate.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("adult-a", results["pause"]!!.data["profile"])
        assertEquals(CallError.NOT_ALLOWED, results["cred"]!!.error)          // paring alene gir ikke lov til å sende innlogging
        assertEquals(CallError.BAD_REQUEST, results["queue"]!!.error)

        val window = CredentialGrantWindow()
        assertTrue(window.arm("ph"))
        val armed = java.util.concurrent.CountDownLatch(1)
        phone.call("tv", "credential.offer", mapOf("kind" to "deezer.arl")) { results["cred2"] = it; armed.countDown() }
        assertTrue(armed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(results["cred2"]!!.ok)
        window.disarm("ph")
        assertFalse(Permission.CREDENTIAL_RECEIVE in AuraCortex.connect.pairedDevice("ph")!!.permissions)

        AuraCortex.connect.unpair("ph")
        assertTrue(AuraCortex.connect.pairedDevices().isEmpty() && phone.pairedDevices().isEmpty())
        TvCommands.unregister(listOf("playback.pause", "credential.offer", "queue.add"))
    }
}
