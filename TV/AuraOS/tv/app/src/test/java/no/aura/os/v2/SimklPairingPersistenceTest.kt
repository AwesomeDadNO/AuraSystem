package no.aura.os.v2

import kotlinx.coroutines.runBlocking
import no.aura.os.v2.simkl.*
import org.junit.Assert.*
import org.junit.Test

class SimklPairingPersistenceTest {
    private val pin = SimklPin("ABC123", "https://simkl.com/pin", Long.MAX_VALUE, 5, "fixture-device", "fixture-verifier")
    @Test fun pending_backoff_failure_and_expiry_keep_public_code_visible() = runBlocking {
        var now = 0L
        var reply: SimklPinStatus = SimklPinStatus.Pending
        val session = SimklPairingController(pin.copy(expiresAtMs = 900000L), { reply }, { true }, { true }, {}, { now })
        session.check(); assertTrue(session.state.value.visible); assertEquals("ABC123", session.state.value.userCode)
        now = 5000; reply = SimklPinStatus.SlowDown; session.check()
        assertTrue(session.state.value.visible); assertEquals(10000L, session.intervalMs)
        now = 15000; reply = SimklPinStatus.Failed("Offline"); session.check()
        assertTrue(session.state.value.visible); assertEquals("ABC123", session.state.value.userCode)
        now = 999999; session.check(); assertTrue(session.state.value.visible)
        assertTrue(session.state.value.message.contains("expired", true))
    }
    @Test fun only_approved_and_stored_or_explicit_close_finishes_the_session() = runBlocking {
        var saved = false; var approved = 0
        val session = SimklPairingController(pin, { SimklPinStatus.Approved("fixture-token") }, { saved = true; true }, { true }, { approved++ })
        session.check(); assertTrue(saved); assertEquals(1, approved); assertFalse(session.state.value.visible)
        session.check(); assertEquals(1, approved)
    }
    @Test fun closing_during_request_discards_late_approval() = runBlocking {
        var saved = false
        lateinit var session: SimklPairingController
        session = SimklPairingController(pin, { session.close(); SimklPinStatus.Approved("fixture-token") }, { saved = true; true }, { true }, {})
        session.check(); assertFalse(saved); assertFalse(session.state.value.visible)
    }
    @Test fun profile_change_and_failed_storage_cannot_report_connected() = runBlocking {
        var current = true; var approved = false
        val session = SimklPairingController(pin, { current = false; SimklPinStatus.Approved("fixture-token") }, { true }, { current }, { approved = true })
        session.check(); assertFalse(approved)
        val failed = SimklPairingController(pin, { SimklPinStatus.Approved("fixture-token") }, { false }, { true }, { approved = true })
        failed.check(); assertTrue(failed.state.value.visible); assertFalse(approved)
    }
}
