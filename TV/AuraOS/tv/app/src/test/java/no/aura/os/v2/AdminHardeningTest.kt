package no.aura.os.v2

import no.aura.os.v2.admin.*
import no.aura.os.v2.profiles.PinCredential
import no.aura.os.v2.profiles.PinHasher
import no.aura.os.v2.settings.AdminFlow
import no.aura.os.v2.settings.Sheet
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Regression tests for the F3b security corrections: monotonic time, reboot behaviour, failed/corrupt storage, UI never blocked. */
class AdminHardeningTest {
    private var elapsed = 10_000L
    private var boot = "boot-1"
    private fun gate(store: AdminPinStore) = AdminGate(store, FakeHasher, { elapsed }, { boot }, sessionTtlMs = 60_000L)
    private fun denied(r: AdminResult) = r as AdminResult.Denied
    private fun lockedGate(store: MemoryAdminPinStore): AdminGate {
        val g = gate(store); g.setUp("2468"); g.revoke(); repeat(5) { g.unlock("0000") }; return g
    }

    // ---- time ---------------------------------------------------------------------------------------------------------------------
    @Test fun gate_never_reads_the_wall_clock() {
        val root = File("src/main/java/no/aura/os/v2/admin").takeIf { it.isDirectory } ?: File("app/src/main/java/no/aura/os/v2/admin")
        val src = File(root, "AdminGate.kt").readText().lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/**") }.joinToString("\n")
        assertFalse(src.contains("currentTimeMillis")); assertFalse(src.contains("Instant.now")); assertFalse(src.contains("LocalDate"))
        // MainActivity wires the monotonic Android clock (counts deep sleep) rather than relying on the JVM default
        val main = File(root.parentFile, "MainActivity.kt").readText()
        assertTrue(main.contains("elapsed = SystemClock::elapsedRealtime")); assertFalse(main.contains("currentTimeMillis"))
    }

    @Test fun session_and_lockout_follow_the_monotonic_clock_only() {
        val st = MemoryAdminPinStore(); val g = lockedGate(st)
        assertEquals(30L, g.lockedForSeconds())
        elapsed += 10_000; assertEquals(20L, g.lockedForSeconds()); assertEquals(AdminDenial.LOCKED, denied(g.unlock("2468")).reason)
        elapsed += 20_001; assertEquals(0L, g.lockedForSeconds()); assertEquals(AdminResult.Ok, g.unlock("2468"))
        assertTrue(g.isAuthorized()); elapsed += 60_001; assertFalse(g.isAuthorized()); assertEquals(0L, g.secondsLeft())
    }

    // ---- restart / reboot ---------------------------------------------------------------------------------------------------------
    @Test fun lockout_survives_a_process_restart_in_the_same_boot_with_its_remaining_time() {
        val st = MemoryAdminPinStore(); lockedGate(st)
        elapsed += 20_000
        val g2 = gate(st)                                                         // process restart, same boot
        assertEquals(10L, g2.lockedForSeconds()); assertEquals(AdminDenial.LOCKED, denied(g2.unlock("2468")).reason)
        elapsed += 10_001; assertEquals(AdminResult.Ok, g2.unlock("2468"))
    }

    @Test fun after_a_reboot_a_pending_lockout_restarts_in_full_and_is_never_shortened() {
        val st = MemoryAdminPinStore(); lockedGate(st)
        boot = "boot-2"; elapsed = 3_000                                          // reboot: monotonic clock restarted near zero
        val g2 = gate(st)
        assertEquals(30L, g2.lockedForSeconds()); assertEquals(AdminDenial.LOCKED, denied(g2.unlock("2468")).reason)
        elapsed += 29_000; assertEquals(AdminDenial.LOCKED, denied(g2.unlock("2468")).reason)
        elapsed += 1_001; assertEquals(AdminResult.Ok, g2.unlock("2468"))
        // even if the new boot's clock happens to read past the OLD deadline, the old deadline is not trusted
        val st2 = MemoryAdminPinStore(); boot = "boot-1"; elapsed = 10_000; lockedGate(st2)
        boot = "boot-9"; elapsed = 500_000; assertEquals(30L, gate(st2).lockedForSeconds())
    }

    @Test fun unknown_boot_id_is_treated_as_a_reboot_and_escalation_level_persists() {
        val st = MemoryAdminPinStore(); boot = "unknown"; lockedGate(st)
        elapsed += 5_000; assertEquals(30L, gate(st).lockedForSeconds())          // cannot prove same boot: restart in full (safe side)
        boot = "boot-1"; val g = gate(st); elapsed += 31_000; repeat(5) { g.unlock("0000") }
        assertEquals(60L, g.lockedForSeconds())                                   // second lockout doubled: the level survived the restarts
    }

    @Test fun tampered_or_impossible_deadlines_are_clamped_and_expired_locks_do_not_come_back() {
        val st = MemoryAdminPinStore(); lockedGate(st)
        st.save(st.load().copy(lockedUntilElapsedMs = elapsed + 10_000_000))     // edited far into the future, same boot
        assertEquals(30L, gate(st).lockedForSeconds())
        val st2 = MemoryAdminPinStore(); val g = lockedGate(st2)
        elapsed += 31_000; assertEquals(AdminResult.Ok, g.unlock("2468"))         // expired lock is cleared and stored cleared
        boot = "boot-2"; elapsed = 100; assertEquals(0L, gate(st2).lockedForSeconds())
    }

    // ---- storage failures ---------------------------------------------------------------------------------------------------------
    @Test fun failed_setup_save_changes_nothing_and_opens_no_session() {
        val st = MemoryAdminPinStore(failWrites = true); val g = gate(st)
        assertEquals(AdminDenial.STORAGE_ERROR, denied(g.setUp("2468")).reason)
        assertFalse(g.isSetUp); assertFalse(g.isAuthorized())
        st.failWrites = false; assertEquals(AdminResult.Ok, g.setUp("2468")); assertTrue(g.isSetUp)       // can simply retry
    }

    @Test fun failed_change_pin_save_keeps_the_old_pin() {
        val st = MemoryAdminPinStore(); val g = gate(st); g.setUp("2468"); g.revoke()
        st.failWrites = true; assertEquals(AdminDenial.STORAGE_ERROR, denied(g.changePin("2468", "1357")).reason); assertFalse(g.isAuthorized())
        st.failWrites = false; assertEquals(AdminDenial.WRONG_PIN, denied(g.unlock("1357")).reason); assertEquals(AdminResult.Ok, g.unlock("2468"))
    }

    @Test fun if_a_failed_attempt_cannot_be_stored_the_gate_locks_immediately() {
        val st = MemoryAdminPinStore(); val g = gate(st); g.setUp("2468"); g.revoke()
        st.failWrites = true
        assertEquals(AdminDenial.LOCKED, denied(g.unlock("0000")).reason)         // cannot trust a counter that a restart would forget
        assertTrue(g.lockedForSeconds() > 0); assertEquals(AdminDenial.LOCKED, denied(g.unlock("2468")).reason)
    }

    @Test fun corrupt_or_implausible_storage_fails_closed_and_never_reads_as_no_pin() {
        val bad = listOf(
            AdminPinRecord(corrupt = true), AdminPinRecord(credential = PinCredential("00", "h2468"), failures = 99),
            AdminPinRecord(credential = PinCredential("00", "h2468"), lockoutMs = -5), AdminPinRecord(credential = PinCredential("00", "h2468"), lockoutLevel = -1),
            AdminPinRecord(credential = null, failures = 3), AdminPinRecord(credential = null, lockoutMs = 30_000),
            AdminPinRecord(credential = PinCredential("00", "h2468"), lockoutMs = 10_000_000_000),
        )
        for (r in bad) {
            val g = gate(MemoryAdminPinStore(r))
            assertTrue("$r", g.isCorrupt); assertFalse(g.isSetUp); assertFalse(g.isAuthorized())
            assertEquals(AdminDenial.STORAGE_CORRUPT, denied(g.setUp("2468")).reason)      // cannot be used to create a fresh PIN
            assertEquals(AdminDenial.STORAGE_CORRUPT, denied(g.unlock("2468")).reason)
            assertEquals(AdminDenial.STORAGE_CORRUPT, denied(g.changePin("2468", "1357")).reason)
            assertEquals(AdminStatus.LOCKED, g.status())
        }
        assertFalse(gate(MemoryAdminPinStore()).isCorrupt)                                  // a genuinely fresh store is fine
    }

    @Test fun flows_explain_corruption_instead_of_asking_for_a_pin() {
        val g = gate(MemoryAdminPinStore(AdminPinRecord(corrupt = true))); var sheet: Sheet? = null; var ran = false
        val flow = AdminFlow(g, { sheet = it }, {})
        flow.ensure { ran = true }
        assertFalse(ran); assertTrue((sheet as Sheet.Message).lines.single().contains("Clear AuraOS data"))
        flow.manage(); assertTrue(sheet is Sheet.Message)
    }

    // ---- disk writes & main thread ------------------------------------------------------------------------------------------------
    @Test fun the_common_paths_do_not_write_to_disk() {
        val st = MemoryAdminPinStore(); val g = gate(st); g.setUp("2468"); assertEquals(1, st.writes)
        repeat(10) { g.revoke(); assertEquals(AdminResult.Ok, g.unlock("2468")); g.touch(); g.isAuthorized(); g.lockedForSeconds(); g.secondsLeft() }
        assertEquals(1, st.writes)                                                // unlocking, touching and status reads never write
        g.revoke(); g.unlock("0000"); assertEquals(2, st.writes)                  // one write per wrong attempt
        g.unlock("2468"); assertEquals(3, st.writes)                              // clearing the counter after a miss: one write, then none
        g.revoke(); g.unlock("2468"); assertEquals(3, st.writes)
    }

    @Test fun ui_reads_never_wait_for_a_pin_verification_in_progress() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val slow = object : PinHasher {
            override fun create(pin: String) = PinCredential("00", "h$pin")
            override fun verify(pin: String, credential: PinCredential): Boolean { entered.countDown(); release.await(5, TimeUnit.SECONDS); return credential.hash == "h$pin" }
        }
        val g = AdminGate(MemoryAdminPinStore(), slow, { elapsed }, { boot }); g.setUp("2468"); g.revoke()
        val t = Thread { g.unlock("2468") }.also { it.start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val reader = Thread { g.isSetUp; g.isCorrupt; g.isAuthorized(); g.lockedForSeconds(); g.secondsLeft(); g.status(); g.touch(); g.revoke() }.also { it.start() }
        reader.join(1000)
        val finished = !reader.isAlive
        release.countDown(); t.join(5000)
        assertTrue("composition-time reads must not block while a PIN is being verified", finished)
    }
}
