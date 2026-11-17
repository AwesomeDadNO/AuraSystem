package no.aura.os.v2

import kotlinx.coroutines.runBlocking
import no.aura.os.v2.amp.AmpEclipseProtocol
import no.aura.os.v2.launcher.HomeShortcutCrashGuard
import no.aura.os.v2.launcher.HomeShortcutState
import no.aura.os.v2.launcher.HomeWindow
import no.aura.os.v2.mediaserver.*
import no.aura.os.v2.notifications.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Fixes from the review of 7bfa87b..161c49d4: per-range verification, Home-shortcut crash loop, audio redirects, Keystore in composition, notification history. */
class F6hReviewFixTest {
    private val sid = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private val uid = "0123456789abcdef0123456789abcdef"
    private val account = ServerAccount(ServerKind.JELLYFIN, sid, "Home", "https://jf.example.com", false, uid, "Arne", "tokenTOKENtoken0123456789abcdef", "device-1")

    private class CountingHttp(var meCode: Int = 200) : MediaHttp {
        val urls = mutableListOf<String>()
        override suspend fun send(request: MediaRequest): MediaResponse {
            urls += request.url
            return when {
                request.url.endsWith("/System/Info/Public") -> MediaResponse(200, """{"Id":"a1b2c3d4e5f60718293a4b5c6d7e8f90","ServerName":"Home"}""")
                request.url.endsWith("/Users/Me") -> MediaResponse(meCode, if (meCode == 200) """{"Id":"0123456789abcdef0123456789abcdef"}""" else null)
                request.url.contains("/Views") -> MediaResponse(200, """{"Items":[]}""")
                else -> MediaResponse(404, null)
            }
        }
        fun identityChecks() = urls.count { it.endsWith("/Users/Me") }
    }

    // ---- 1: identity is re-proved once per window, not once per megabyte ---------------------------------------------------------------------
    @Test fun verification_is_reused_within_the_window_and_repeated_after_it() = runBlocking {
        var now = 1_000_000L
        val http = CountingHttp()
        val service = MediaServerService(MemoryMediaAccountStore().apply { save("p1", listOf(account)) }, http, ClientIdentity("device-1", "test"), clock = { now })
        repeat(25) { assertTrue(service.libraryPlayback.libraries("p1", account) is MediaResult.Ok) }
        assertEquals("25 reads within the window prove the identity once", 1, http.identityChecks())
        now += MediaServerService.VERIFY_FRESH_MS
        assertTrue(service.libraryPlayback.libraries("p1", account) is MediaResult.Ok)
        assertEquals(2, http.identityChecks())
        // the clock going backwards never extends trust
        now -= 10 * MediaServerService.VERIFY_FRESH_MS
        service.libraryPlayback.libraries("p1", account); assertEquals(3, http.identityChecks())
    }
    @Test fun a_failed_verification_is_never_reused_and_ownership_is_checked_every_time() = runBlocking {
        val http = CountingHttp(meCode = 401)
        val service = MediaServerService(MemoryMediaAccountStore().apply { save("p1", listOf(account)) }, http, ClientIdentity("device-1", "test"), clock = { 5_000L })
        repeat(3) { assertEquals(MediaResult.Failure(MediaError.AUTH_FAILED), service.libraryPlayback.libraries("p1", account)) }
        assertEquals("a rejected token is checked again on every call", 3, http.identityChecks())
        // success afterwards is trusted for the window, but another profile is still refused without any request
        http.meCode = 200
        assertTrue(service.libraryPlayback.libraries("p1", account) is MediaResult.Ok)
        val before = http.urls.size
        assertEquals(MediaResult.Failure(MediaError.FORBIDDEN), service.libraryPlayback.libraries("p2", account)); assertEquals(before, http.urls.size)
        // revocation is noticed at the latest when the window ends: an explicit verify always goes to the server
        http.meCode = 401
        assertTrue(service.verify("p1", account) is MediaResult.Failure)
        assertEquals(MediaResult.Failure(MediaError.AUTH_FAILED), service.libraryPlayback.libraries("p1", account))
    }
    @Test fun playback_ranges_go_through_the_reusable_check() {
        val src = File("src/main/java/no/aura/os/v2/mediaserver/ServerLibraryPlayback.kt").readText()
        assertTrue(src.contains("service.verifyFresh(profileId, account)")); assertFalse(src.contains("service.verify(profileId, account)"))
    }

    // ---- 2: the Home shortcut cannot trap the TV in a crash loop ---------------------------------------------------------------------------------
    @Test fun home_shortcut_pauses_after_repeated_unconfirmed_starts() {
        assertTrue(HomeShortcutCrashGuard.mayLaunch(0)); assertTrue(HomeShortcutCrashGuard.mayLaunch(HomeShortcutCrashGuard.MAX_UNCONFIRMED - 1))
        assertFalse(HomeShortcutCrashGuard.mayLaunch(HomeShortcutCrashGuard.MAX_UNCONFIRMED)); assertFalse(HomeShortcutCrashGuard.mayLaunch(99))
        assertTrue(HomeShortcutCrashGuard.STABLE_AFTER_MS >= 5_000L)
        val home = HomeWindow("com.google.android.apps.tv.launcherx", "Home")
        assertTrue(HomeShortcutState(true, true, home).active)
        val paused = HomeShortcutState(true, true, home, paused = true)
        assertFalse(paused.active); assertTrue(paused.message.startsWith("Paused"))
        val service = File("src/main/java/no/aura/os/v2/launcher/AuraHomeShortcutService.kt").readText()
        assertTrue("the guard is consulted and the start is counted BEFORE the activity is started",
            service.indexOf("shortcut.launchAllowed()") in 0 until service.indexOf("shortcut.noteAutomaticLaunch()") && service.indexOf("shortcut.noteAutomaticLaunch()") < service.indexOf("startActivity("))
        val shortcut = File("src/main/java/no/aura/os/v2/launcher/HomeShortcut.kt").readText()
        assertTrue("the count must survive the crash it guards against", Regex("fun noteAutomaticLaunch\\(\\)[^\\n]*commit\\(\\)").containsMatchIn(shortcut))
        val activity = File("src/main/java/no/aura/os/v2/MainActivity.kt").readText()
        assertTrue(activity.contains("postDelayed(confirmStableStart")); assertTrue(activity.contains("removeCallbacks(confirmStableStart)"))
    }

    // ---- 3: audio may follow a CDN redirect, but a credential never leaves Eclipse's host ---------------------------------------------------------
    @Test fun audio_redirects_to_another_public_host_are_allowed_without_leaking_the_credential() {
        val arl = "a".repeat(192)
        assertTrue(AmpEclipseProtocol.redirectAllowed("https://cdn-audio.example.net/track/123.flac?sig=abc", arl))
        assertTrue(AmpEclipseProtocol.redirectAllowed("https://eclipse.ultramax.vip/$arl/stream/5", arl))                 // the credential may stay on Eclipse's own host
        assertFalse("the credential must not travel to another host", AmpEclipseProtocol.redirectAllowed("https://cdn-audio.example.net/$arl/track.flac", arl))
        assertFalse(AmpEclipseProtocol.redirectAllowed("https://cdn-audio.example.net/x?k=${arl.uppercase()}", arl))
        assertFalse(AmpEclipseProtocol.redirectAllowed("http://cdn-audio.example.net/track.flac", arl))                   // never to plain http
        assertFalse(AmpEclipseProtocol.redirectAllowed("https://192.168.1.10/track.flac", arl))                           // never to an address literal
        assertFalse(AmpEclipseProtocol.redirectAllowed("https://localhost/track.flac", arl))
        assertFalse(AmpEclipseProtocol.redirectAllowed("https://cdn-audio.example.net:8443/track.flac", arl))
        assertTrue(AmpEclipseProtocol.redirectAllowed("https://cdn-audio.example.net/track.flac", null))
        val transport = File("src/main/java/no/aura/os/v2/amp/AmpPlayback.kt").readText()
        assertFalse(transport.contains("URI(next).host == URI(target).host")); assertTrue(transport.contains("AmpEclipseProtocol.redirectAllowed(next, secret)"))
        assertTrue("every hop is still DNS-validated", transport.contains("AmpProviderSafety.validateNetwork(target)"))
        assertTrue(transport.contains("instanceFollowRedirects = false"))
    }

    // ---- 4: no Keystore work while composing the shell ----------------------------------------------------------------------------------------------
    @Test fun shell_composition_only_asks_whether_an_arl_entry_exists() {
        val shell = File("src/main/java/no/aura/os/v2/shell/AuraShell.kt").readText()
        val state = shell.substringAfter("val settingsState = SettingsState(").substringBefore("profiles = profileState")
        assertFalse("ampVault.has() decrypts with the Keystore", state.contains("ampVault.has(")); assertTrue(state.contains("ampVault.isSet("))
        val vault = File("src/main/java/no/aura/os/v2/amp/AmpProvider.kt").readText()
        assertTrue(Regex("fun isSet\\(profile: String\\)[^\\n]*prefs\\.contains").containsMatchIn(vault))
    }

    // ---- 5: stored system notifications survive a transient disconnect -------------------------------------------------------------------------------
    private class Memory : CenterStore {
        val data = mutableMapOf<String, CenterSnapshot>()
        override fun load(profile: String) = data[profile] ?: CenterSnapshot()
        override fun save(profile: String, value: CenterSnapshot) { data[profile] = value }
    }
    @Test fun a_transient_disconnect_keeps_history_and_a_withdrawn_permission_removes_it() {
        val center = NotificationCenter(Memory()) { 1000L }
        center.configure("a", CenterPreferences(enabled = setOf(NotificationCategory.SYSTEM, NotificationCategory.AURA_UPDATE)))
        center.setSystemAccess("a", SystemAccess.CONNECTED)
        assertTrue(center.ingest("a", CenterEvent("system:1", NotificationCategory.SYSTEM, "Android", "Title", "", 900)))
        assertTrue(center.ingest("a", CenterEvent("update:1", NotificationCategory.AURA_UPDATE, "feed", "Update", "", 900)))
        center.setSystemAccess("a", SystemAccess.DISCONNECTED)                                   // app start before the listener binds, profile switch, pause
        assertEquals(2, center.snapshot("a").items.size)
        assertFalse("nothing new is accepted while disconnected", center.ingest("a", CenterEvent("system:2", NotificationCategory.SYSTEM, "Android", "Title", "", 900)))
        center.setSystemAccess("a", SystemAccess.CONNECTED); assertEquals(2, center.snapshot("a").items.size)
        for (gone in listOf(SystemAccess.REVOKED, SystemAccess.NOT_GRANTED, SystemAccess.UNAVAILABLE)) {
            val c = NotificationCenter(Memory()) { 1000L }
            c.configure("a", CenterPreferences(enabled = setOf(NotificationCategory.SYSTEM, NotificationCategory.AURA_UPDATE)))
            c.setSystemAccess("a", SystemAccess.CONNECTED)
            c.ingest("a", CenterEvent("system:1", NotificationCategory.SYSTEM, "Android", "Title", "", 900)); c.ingest("a", CenterEvent("update:1", NotificationCategory.AURA_UPDATE, "feed", "Update", "", 900))
            c.setSystemAccess("a", gone)
            assertEquals("$gone removes mirrored system content only", listOf(NotificationCategory.AURA_UPDATE), c.snapshot("a").items.map { it.event.category })
        }
    }
}
