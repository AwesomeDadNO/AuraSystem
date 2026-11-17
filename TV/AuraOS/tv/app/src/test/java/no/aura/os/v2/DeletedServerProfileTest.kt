package no.aura.os.v2

import kotlinx.coroutines.runBlocking
import no.aura.os.v2.mediaserver.*
import org.junit.Assert.*
import org.junit.Test

class DeletedServerProfileTest {
    @Test fun deleted_profile_cannot_be_restored_by_a_stale_connection_callback() = runBlocking {
        val store = MemoryMediaAccountStore()
        val service = MediaServerService(store, object : MediaHttp { override suspend fun send(request: MediaRequest) = MediaResponse(500, null) }, ClientIdentity("device", "1"))
        val account = ServerAccount(ServerKind.EMBY, "abcd1234", "Home", "https://media.example.com", false, "abcd5678", "Owner", "fixture-credential", "device")
        assertTrue(service.add("removed", account))
        service.purge("removed")
        assertFalse(service.add("removed", account))
        assertFalse(store.has("removed")); assertTrue(service.accounts("removed").isEmpty())
    }
    @Test fun bound_authorizer_is_checked_for_legacy_credentials_too() = runBlocking {
        val store = MemoryMediaAccountStore()
        val service = MediaServerService(store, object : MediaHttp { override suspend fun send(request: MediaRequest) = MediaResponse(500, null) }, ClientIdentity("device", "1"), mayManage = { false })
        val account = ServerAccount(ServerKind.EMBY, "abcd1234", "Home", "https://media.example.com", false, "abcd5678", "Owner", "fixture-credential", "device")
        assertFalse(service.add("owner", account)); assertFalse(store.has("owner"))
    }

}
