package no.aura.os.v2

import kotlinx.coroutines.runBlocking
import no.aura.os.v2.mediaserver.*
import no.aura.os.v2.settings.Sheet
import org.junit.Assert.*
import org.junit.Test

class MediaServerFlowTest {
    private class Harness {
        var profile = "p1"
        var admin = true
        var sheet: Sheet? = null
        val jobs = ArrayDeque<suspend () -> Unit>()
        val requests = mutableListOf<MediaRequest>()
        var responseFor: ((MediaRequest) -> MediaResponse)? = null
        var onRequest: (MediaRequest) -> Unit = {}
        val store = MemoryMediaAccountStore()
        val service = MediaServerService(store, object : MediaHttp {
            override suspend fun send(request: MediaRequest): MediaResponse {
                requests += request
                onRequest(request)
                responseFor?.let { return it(request) }
                if (request.url.endsWith("/Users/AuthenticateByName")) return MediaResponse(200,
                    """{"User":{"Id":"abcd5678","Name":"Owner"},"AccessToken":"abcdefghijklmnopqrstuvwx","ServerId":"abcd1234"}""")
                return MediaResponse(200, """{"Id":"abcd1234","ServerName":"Home"}""")
            }
        }, ClientIdentity("test-device", "1"))
        val flow = MediaServerFlow(service, { profile }, { sheet = it }, { jobs.addLast(it) }, { it() }, { admin }, {})
        fun drain() = runBlocking { while (jobs.isNotEmpty()) jobs.removeFirst().invoke() }
        fun address(): Sheet.TextEntry {
            flow.open(ServerKind.EMBY); drain()
            (sheet as Sheet.Choice).options.first().onPick()
            return sheet as Sheet.TextEntry
        }
    }

    @Test fun old_address_callback_cannot_contact_server_after_profile_switch() {
        val h = Harness(); val field = h.address()
        h.profile = "p2"
        field.onSubmit("https://media.example.com"); h.drain()
        assertTrue(h.requests.isEmpty())
        assertFalse(h.store.has("p2"))
    }

    @Test fun old_callback_cannot_resume_after_another_flow_opens() {
        val h = Harness(); val old = h.address()
        h.flow.open(ServerKind.JELLYFIN); h.drain()
        val current = h.sheet
        old.onSubmit("https://media.example.com"); h.drain()
        assertTrue(h.requests.isEmpty()); assertSame(current, h.sheet)
    }

    @Test fun http_warning_does_not_contact_server_until_approved() {
        val h = Harness(); h.address().onSubmit("http://192.168.1.20:8096"); h.drain()
        assertTrue(h.requests.isEmpty())
        val warning = h.sheet as Sheet.Choice
        assertEquals("Unencrypted connection", warning.title)
        warning.options.first().onPick(); h.drain()
        assertTrue(h.requests.first().url.startsWith("http://192.168.1.20:8096/"))
    }

    @Test fun expired_admin_session_cannot_start_a_sign_in() {
        val h = Harness(); val field = h.address(); h.admin = false
        field.onSubmit("https://media.example.com"); h.drain()
        assertTrue(h.requests.isEmpty())
    }
    @Test fun closing_the_flow_invalidates_saved_callbacks() {
        val h = Harness(); val field = h.address()
        h.flow.cancel(); field.onSubmit("https://media.example.com"); h.drain()
        assertTrue(h.requests.isEmpty())
    }

    @Test fun manual_sign_in_is_saved_only_to_the_initiating_profile() {
        val h = Harness(); h.address().onSubmit("https://media.example.com"); h.drain()
        (h.sheet as Sheet.TextEntry).onSubmit("Owner")
        (h.sheet as Sheet.TextEntry).onSubmit("password"); h.drain()
        assertTrue(h.store.has("p1")); assertFalse(h.store.has("p2"))
        assertTrue((h.sheet as Sheet.Message).title.startsWith("Connected"))
    }

    @Test fun profile_switch_during_authentication_discards_the_result() {
        val h = Harness(); h.address().onSubmit("https://media.example.com"); h.drain()
        (h.sheet as Sheet.TextEntry).onSubmit("Owner")
        h.onRequest = { if (it.url.endsWith("/Users/AuthenticateByName")) h.profile = "p2" }
        (h.sheet as Sheet.TextEntry).onSubmit("password"); h.drain()
        assertFalse(h.store.has("p1")); assertFalse(h.store.has("p2"))
        assertFalse((h.sheet as? Sheet.Message)?.title?.startsWith("Connected") == true)
    }

    @Test fun expiry_during_authentication_does_not_store_the_sign_in() {
        val h = Harness(); h.address().onSubmit("https://media.example.com"); h.drain()
        (h.sheet as Sheet.TextEntry).onSubmit("Owner")
        h.onRequest = { if (it.url.endsWith("/Users/AuthenticateByName")) h.admin = false }
        (h.sheet as Sheet.TextEntry).onSubmit("password"); h.drain()
        assertFalse(h.store.has("p1"))
        assertEquals("Administrator PIN needed", (h.sheet as Sheet.Message).title)
    }

    @Test fun switching_profile_after_discovery_does_not_start_quick_connect() {
        val h = Harness(); h.flow.open(ServerKind.JELLYFIN); h.drain()
        (h.sheet as Sheet.Choice).options.first().onPick()
        h.onRequest = { h.profile = "p2" }
        (h.sheet as Sheet.TextEntry).onSubmit("https://media.example.com"); h.drain()
        assertEquals(1, h.requests.size)
    }

    @Test fun cancelling_clears_the_connection_sheet() {
        val h = Harness(); h.address(); h.flow.cancel()
        assertNull(h.sheet)
    }

    @Test fun cancel_during_quick_connect_approval_does_not_finish_authentication() {
        val h = Harness()
        h.responseFor = { request -> when {
            request.url.endsWith("/System/Info/Public") -> MediaResponse(200, """{"Id":"abcd1234","ServerName":"Home"}""")
            request.url.endsWith("/QuickConnect/Enabled") -> MediaResponse(200, "true")
            request.url.endsWith("/QuickConnect/Initiate") -> MediaResponse(200, """{"Code":"123456","Secret":"abcdefghijklmnopqrstuvwx"}""")
            request.url.contains("/QuickConnect/Connect?") -> MediaResponse(200, """{"Authenticated":true}""")
            else -> MediaResponse(404, null)
        } }
        h.flow.open(ServerKind.JELLYFIN); h.drain()
        (h.sheet as Sheet.Choice).options.first().onPick()
        (h.sheet as Sheet.TextEntry).onSubmit("https://media.example.com"); h.drain()
        (h.sheet as Sheet.Choice).options.first().onPick(); h.drain()
        h.onRequest = { if (it.url.contains("/QuickConnect/Connect?")) h.flow.cancel() }
        (h.sheet as Sheet.Choice).options.first().onPick(); h.drain()
        assertTrue(h.requests.none { it.url.endsWith("/Users/AuthenticateWithQuickConnect") })
        assertFalse(h.store.has("p1")); assertNull(h.sheet)
    }

    @Test fun previously_unverified_accounts_are_checked_even_with_a_low_clock() = runBlocking {
        val store = MemoryMediaAccountStore()
        store.save("p1", listOf(ServerAccount(ServerKind.EMBY, "abcd1234", "Home", "https://media.example.com", false,
            "abcd5678", "Owner", "abcdefghijklmnopqrstuvwx", "test-device")))
        var calls = 0
        val service = MediaServerService(store, object : MediaHttp {
            override suspend fun send(request: MediaRequest): MediaResponse { calls++; return MediaResponse(404, null) }
        }, ClientIdentity("test-device", "1"), clock = { 0L })
        assertTrue(service.verifyStale("p1")); assertTrue(calls > 0)
        val before = calls
        assertFalse(service.verifyStale("p2")); assertEquals(before, calls)
    }

}
