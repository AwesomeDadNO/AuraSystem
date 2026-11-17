package no.aura.os.v2

import no.aura.os.v2.shell.*
import no.aura.os.v2.theme.AuraPalette
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * No network or Android Keystore involved: static contract checks keep accidental V1 reintroduction,
 * unsafe secret logging and old navigation assumptions out of the TV build.
 */
class F6fContractTest {
    private fun src(relative: String) = File("src/main/java/no/aura/os/v2/$relative").readText()

    @Test fun simkl_v2_device_grant_requires_form_encoding_and_s256_pkce() {
        val api = src("simkl/SimklV2Client.kt")
        assertTrue(api.contains("/oauth2/device"))
        assertTrue(api.contains("/oauth2/token"))
        assertTrue(api.contains("application/x-www-form-urlencoded"))
        assertTrue(api.contains("urn:ietf:params:oauth:grant-type:device_code"))
        assertTrue(api.contains("code_challenge_method"))
        assertTrue(api.contains("code_verifier"))
        assertTrue(api.contains("MessageDigest.getInstance(\"SHA-256\")"))
        assertTrue(api.contains("refresh_token"))
        assertTrue(api.contains("media:read"))
        assertTrue(api.contains("/sync/activities"))
        assertTrue(api.contains("/sync/all-items/"))
        assertFalse(api.contains("request(\"/oauth/pin"))
        assertFalse(File("src/main/java/no/aura/os/v2/simkl/SimklLegacyClient.kt").exists())
        assertFalse(api.contains("20d325c177"))
    }

    @Test fun simkl_pairing_and_tokens_are_profile_isolated() {
        val main = src("MainActivity.kt")
        val shell = src("shell/AuraShell.kt")
        val tokens = src("simkl/SimklProfileTokens.kt")
        assertTrue(main.contains("SimklV2Client(BuildConfig.SIMKL_CLIENT_ID)"))
        assertTrue(tokens.contains("AndroidKeyStore"))
        assertTrue(tokens.contains("AES/GCM/NoPadding"))
        assertTrue(tokens.contains("profile:\$id"))
        assertTrue(shell.contains("simklTokens?.clear(id)"))
        assertTrue(shell.contains("simklClient?.check(pin)"))
        assertTrue(shell.contains("SimklAutoSync.sync(appContext,ownerProfile)"))
        assertTrue(src("simkl/SimklAutoSync.kt").contains("watchlist(it)"))
        assertTrue(shell.contains("client.revoke(raw)"))
    }

    @Test fun chrome_matches_approved_tv_navigation() {
        assertEquals(listOf(Route.HOME, Route.MOVIES, Route.TV_SHOWS, Route.SPORTS, Route.LIVE_TV),
            NavModel.items(ModuleFlags()))
        assertEquals(listOf(Route.HOME, Route.AURAAMP, Route.AURATUBE, Route.SAVED, Route.ALL_APPS), NavModel.rail)
        assertFalse(Route.AURABOX in NavModel.reachable(ModuleFlags()))
        val apps = listOf(AppShortcut("com.netflix.ninja", "Netflix"))
        val layout = NavModel.layout(ModuleFlags(), NavStyle.TEXT_ONLY, apps)
        assertEquals(2, layout.size)
        assertEquals(1, layout.last().filterIsInstance<NavItemModel.App>().size)
        assertFalse(src("shell/AuraSideRail.kt").contains("RoundedCornerShape(32.dp)"))   // Owner removed rail plate.
        assertTrue(src("shell/AuraShell.kt").contains("mediaRecents?.order"))
        assertTrue(src("shell/AuraShell.kt").contains("mediaRecents?.launched"))
    }

    @Test fun tmdb_poster_and_16x9_continue_formats_are_kept_distinct() {
        val media = src("cards/MediaCard.kt")
        val catalog = src("tmdb/RealCatalog.kt")
        assertTrue(media.contains("val Width = PosterGeometry.WIDTH.dp"))
        assertTrue(media.contains("val ContinueWidth = 298.dp; val ContinueHeight = 168.dp"))
        assertTrue(media.contains("AuraColors.Focus"))
        assertFalse(media.contains("Popup("))
        assertTrue(catalog.contains("val poster: Boolean = true"))
        assertTrue(src("details/CinemaSeriesPage.kt").contains("Text(\"Seasons:\""))
        assertTrue(src("details/CinemaSeriesPage.kt").contains("items(episodes, key={it.number})"))
    }
}
