package no.aura.os.v2

import kotlinx.coroutines.runBlocking
import no.aura.os.v2.simkl.*
import org.junit.Assert.*
import org.junit.Test

class SimklSyncCoordinatorTest {
    @Test fun profile_switch_during_fetch_discards_import() = runBlocking {
        var active = true
        var applied = false
        val sync = SimklSyncCoordinator({ active }, { "account-one" }, {
            active = false; SimklSyncResult.Success(setOf("tmdb:movie:1"), null)
        }, { _, _ -> applied = true; 1 })
        assertEquals(SimklImportResult.Skipped, sync.sync())
        assertFalse(applied)
    }
    @Test fun disconnect_or_reconnect_discards_late_result_and_refresh() = runBlocking {
        var credentials: String? = "account-one"
        var applied = false
        val sync = SimklSyncCoordinator({ true }, { credentials }, {
            credentials = "account-two"; SimklSyncResult.Success(setOf("tmdb:movie:1"), "rotated-old-account")
        }, { _, _ -> applied = true; 1 })
        assertEquals(SimklImportResult.Skipped, sync.sync())
        assertFalse(applied)
        assertEquals("account-two", credentials)
    }
    @Test fun failure_preserves_local_records_and_reports_status() = runBlocking {
        var applied = false
        var status: SimklImportResult? = null
        val sync = SimklSyncCoordinator({ true }, { "fixture" }, { SimklSyncResult.Failed("Offline") },
            { _, _ -> applied = true; 0 }, { status = it })
        assertEquals(SimklImportResult.Failed("Offline"), sync.sync())
        assertFalse(applied)
        assertEquals(SimklImportResult.Failed("Offline"), status)
    }
    @Test fun import_success_reports_count() = runBlocking {
        val sync = SimklSyncCoordinator({ true }, { "fixture" }, { SimklSyncResult.Success(setOf("tmdb:tv:2"), null) },
            { credential, result -> assertEquals("fixture", credential); assertEquals(setOf("tmdb:tv:2"), result.tmdbKeys); 1 })
        assertEquals(SimklImportResult.Success(1), sync.sync())
    }
    @Test fun rotated_credentials_survive_a_later_library_failure() = runBlocking {
        var preserved: String? = null
        val sync = SimklSyncCoordinator({ true }, { "old" },
            { SimklSyncResult.Failed("Offline", "rotated") }, { _, _ -> fail("Must not import"); 0 },
            preserveRefresh = { initial, refreshed -> assertEquals("old", initial); preserved = refreshed; true })
        assertTrue(sync.sync() is SimklImportResult.Failed)
        assertEquals("rotated", preserved)
    }
}
