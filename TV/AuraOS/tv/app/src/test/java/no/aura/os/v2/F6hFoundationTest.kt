package no.aura.os.v2

import no.aura.os.v2.livetv.M3uPlaylist
import no.aura.os.v2.livetv.LiveTvPreferences
import no.aura.os.v2.sports.Sport
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class F6hFoundationTest {
    private fun src(path: String) = File("src/main/java/no/aura/os/v2/$path").readText()

    @Test fun sportscore_is_real_only_and_has_no_invented_teams() {
        val feed = src("sports/SportScoreFeed.kt")
        assertTrue(feed.contains("https://sportscore.com/api/widget/matches/"))
        assertEquals(60_000L, no.aura.os.v2.sports.SportsLogic.REFRESH_MS)
        assertEquals(listOf("football", "basketball", "tennis", "cricket"), Sport.values().map { it.api })
        val shell = src("shell/AuraShell.kt")
        assertTrue(shell.contains("Route.SPORTS -> SportsPage(c!!.sports"))
        assertFalse(shell.contains("sportFeed?.fetch"))
    }

    @Test fun portrait_is_static_without_expansion_or_focus_backdrop() {
        val poster = src("cards/MediaCard.kt").substringAfter("fun PosterCard")
        assertFalse(poster.contains("Popup("))
        assertFalse(poster.contains("focusArtwork"))
        assertFalse(poster.contains("animateFloatAsState"))
        assertTrue(poster.contains("scale = 1f"))
    }

    @Test fun live_source_and_guide_are_independent_and_m3u_is_bounded() {
        assertTrue(LiveTvPreferences.safePublicHttps("https://example.com/list.m3u8"))
        assertFalse(LiveTvPreferences.safePublicHttps("http://localhost/list.m3u"))
        assertFalse(LiveTvPreferences.safePublicHttps("https://example.com/list.m3u?secret=token"))
        val content = "#EXTM3U\n#EXTINF:-1 tvg-id=\"test.no\",Example TV\nhttps://example.com/one.m3u8"
        assertEquals("test.no", M3uPlaylist.parse(content).single().epgId)
        val live = src("livetv/LiveTvSources.kt")
        assertTrue(live.contains("IPTV_PLAYLIST, INSTALLED_TV_APPS"))
        assertTrue(live.contains("fun epgUrl()"))
    }

    @Test fun tv_banner_and_google_oauth_are_configured_without_hardcoded_tokens() {
        assertTrue(src("apps/PackageManagerApps.kt").contains("getApplicationBanner"))
        assertTrue(src("pages/SecondaryPages.kt").contains("appBanner"))
        val auth = src("google/GoogleTvDeviceClient.kt")
        assertTrue(auth.contains("oauth2.googleapis.com/device/code"))
        assertTrue(auth.contains("youtube.readonly"))
        assertTrue(auth.contains("oauth2.googleapis.com/token"))
        assertFalse(auth.contains("AIza"))
        assertTrue(src("google/GoogleProfileTokens.kt").contains("AndroidKeyStore"))
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("GOOGLE_TV_OAUTH_CLIENT_ID"))
        assertTrue(gradle.contains("GOOGLE_TV_OAUTH_CLIENT_SECRET"))
    }
}
