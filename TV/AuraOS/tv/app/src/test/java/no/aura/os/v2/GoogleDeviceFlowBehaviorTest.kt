package no.aura.os.v2
import no.aura.os.v2.google.*
import org.junit.Assert.*
import org.junit.Test
class GoogleDeviceFlowBehaviorTest {
    @Test fun slowdownAddsFiveSecondsAndNeverPollsBeforeDeadline() {
        val policy = GooglePolling(5000, 60000)
        assertFalse(policy.ready(4999))
        assertTrue(policy.ready(5000))
        policy.after(GoogleTvPoll.SlowDown, 5000)
        assertFalse(policy.ready(14999))
        assertTrue(policy.ready(15000))
    }
    @Test fun expiryStopsPollingEvenAfterPending() {
        val policy = GooglePolling(5000, 6000)
        policy.after(GoogleTvPoll.Pending, 5000)
        assertFalse(policy.ready(10000))
        assertTrue(policy.expired(6000))
    }
}

class GoogleDeviceSessionBehaviorTest {
    private class Vault : GoogleTokenVault {
        val data = mutableMapOf("a" to "opaque-token-a", "b" to "opaque-token-b")
        override fun get(profile: String) = data[profile]
        override fun save(profile: String, token: String): Boolean { data[profile] = token; return true }
        override fun clear(profile: String) { data.remove(profile) }
    }
    private class Api : GoogleTvApi {
        override val configured = true
        var refreshed: String? = null
        override suspend fun begin(): GoogleTvCode? = null
        override suspend fun check(code: GoogleTvCode): GoogleTvPoll = GoogleTvPoll.Pending
        override suspend fun refresh(saved: String): String? { refreshed = saved; return null }
        override suspend fun identity(saved: String): no.aura.os.v2.identity.GoogleIdentity? = null
        override suspend fun revoke(saved: String) = false
        override suspend fun myPlaylists(saved: String): List<Pair<String,String>>? = emptyList()
        override suspend fun playlistItems(saved: String, playlist: String): List<Pair<String,String>>? = emptyList()
    }
    @Test fun localSignoutClearsOnlyCapturedProfileWhenRemoteRevokeFails() = kotlinx.coroutines.runBlocking {
        val vault = Vault(); val api = Api()
        val profiles = no.aura.os.v2.profiles.ProfileService(no.aura.os.v2.profiles.MemoryProfileStore())
        var state = GoogleFlowState()
        GoogleDeviceFlow("a", api, vault, profiles, { "a" }).disconnect { state = it }
        assertNull(vault.get("a")); assertEquals("opaque-token-b", vault.get("b"))
        assertTrue(state.message.contains("revoke failed"))
    }
    @Test fun inactiveProfileCannotRefreshOrSignOutAnotherProfile() = kotlinx.coroutines.runBlocking {
        val vault = Vault(); val api = Api()
        val profiles = no.aura.os.v2.profiles.ProfileService(no.aura.os.v2.profiles.MemoryProfileStore())
        GoogleDeviceFlow("a", api, vault, profiles, { "b" }).disconnect { error("No inactive-profile update") }
        assertEquals("opaque-token-a", vault.get("a"))
    }
}
