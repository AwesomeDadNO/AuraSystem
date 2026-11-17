package no.aura.os.v2

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** F6g source-level checks; TV focus behaviour and remote OAuth still need real device testing. */
class F6gDetailsTest {
    private fun src(relative: String) = File("src/main/java/no/aura/os/v2/$relative").readText()

    @Test fun movie_and_series_details_keep_primary_navigation() {
        val details = src("overlay/TitleDetailsHost.kt")
        assertTrue(details.contains("onNavigate: ((Route) -> Unit)? = null"))
        assertTrue(details.contains("Route.HOME, Route.MOVIES, Route.TV_SHOWS, Route.SPORTS, Route.LIVE_TV"))
        assertTrue(details.contains("onNavigate(destination)"))
        assertTrue(src("shell/AuraShell.kt").contains("onNavigate = { destination ->"))
    }

    @Test fun real_watchlist_and_favorites_are_independent() {
        val details = src("overlay/TitleDetailsHost.kt")
        assertTrue(details.contains("watch.inWatchlist(profile.id"))
        assertTrue(details.contains("watch.setWatchlist(profile.id"))
        assertTrue(details.contains("watch.setFavorite(profile.id"))
        assertTrue(details.contains("DetailsLogic.watchlistLabel(inList)"))
    }

    @Test fun bigger_hero_and_clean_9x16_poster_are_not_demo_only() {
        val poster = src("cards/MediaCard.kt")
        assertTrue(poster.contains("val Width = PosterGeometry.WIDTH.dp"))
        assertFalse(poster.contains("Popup("))
        val layouts = src("pages/ShelfPage.kt")
        assertTrue(layouts.contains("HeroLayout(660.dp, 128.dp)"))
        assertTrue(layouts.contains("HeroLayout(645.dp"))
    }

    @Test fun episode_still_actions_and_real_synopsis_match_owner_layout() {
        val cards = src("details/DetailsComponents.kt")
        assertTrue(cards.contains("height(187.dp).focusRequester(playFocus)"))
        assertTrue(cards.contains("onClick = { onSelected(n); onPlay(n) }"))
        assertTrue(cards.contains("EpisodeMoreInfo(Modifier.weight(EpisodeActionGeometry.INFO_WEIGHT)"))
        assertTrue(cards.contains("EpisodeWatchCheckbox(watched, n, Modifier.weight(EpisodeActionGeometry.WATCH_WEIGHT)"))
        assertTrue(cards.contains("ep.overview.ifBlank"))
        assertTrue(cards.contains("ep.airDate?.takeIf"))
        assertTrue(cards.contains("Role.Checkbox"))
        assertTrue(cards.contains("toggleableState = if (watched)"))
        assertTrue(cards.contains("private fun AuraPlayGlyph"))
        assertFalse(cards.contains("drawLine("))
    }

    @Test fun episode_playback_retains_airdate_gate_and_single_image_target() {
        val cards = src("details/DetailsComponents.kt").substringAfter("internal fun EpisodeCard(")
            .substringBefore("/** Decorative glyph")
        assertEquals(1, Regex("focusRequester\\(playFocus\\)").findAll(cards).count())
        assertTrue(cards.contains("onFocused = { onSelected(n) }"))
        val details = src("overlay/TitleDetailsHost.kt")
        assertTrue(details.contains("!DetailsLogic.isAired(e.airDate"))
        assertTrue(details.contains("onPlayEp = { n -> start(null, season, n) }"))
    }

    @Test fun episode_dpad_goes_from_lower_controls_to_image_before_seasons() {
        val cards = src("details/DetailsComponents.kt")
        val details = src("overlay/TitleDetailsHost.kt")
        assertFalse(details.contains("focusRequester(episodeRow).verticalLinks(up = seasonRow)"))
        assertTrue(src("details/CinemaSeriesPage.kt").contains("focusProperties { up=playFocus; down=relatedEntry }"))
        assertTrue(cards.contains("focusProperties { up = upFocus; down = infoFocus }"))
        assertTrue(cards.contains("focusProperties { up = playFocus; right = watchedFocus }"))
        assertTrue(cards.contains("focusProperties { up = playFocus; left = infoFocus }"))
    }

    @Test fun episode_dialog_keeps_close_outside_scroll_and_restores_info_focus() {
        val cards = src("details/DetailsComponents.kt")
        assertTrue(cards.contains("CanvasDialog(onDismissRequest = dismissInfo)"))
        assertTrue(cards.contains("runCatching { infoFocus.requestFocus() }"))
        assertTrue(cards.contains("infoScroll.scrollBy(direction * 180f)"))
        assertTrue(cards.contains("Key.DirectionUp -> -1"))
        assertTrue(cards.contains("Key.DirectionDown -> 1"))
        assertTrue(cards.contains("if (atEdge) closeFocus.requestFocus()"))
        assertTrue(cards.contains("focusProperties { up = contentFocus; down = contentFocus }"))
        val outer = cards.substringAfter("Column(\n                Modifier.width(660.dp)")
            .substringBefore("// Focus lives on a stationary viewport")
        assertFalse(outer.contains("verticalScroll"))
        val scrollContent = cards.substringAfter(".verticalScroll(infoScroll)").substringBefore("HeroButton(\"Close\"")
        assertTrue(scrollContent.contains("ep.overview.ifBlank"))
        assertTrue(scrollContent.trimEnd().endsWith("}"))
    }

    @Test fun episode_action_geometry_reserves_exact_twenty_percent_with_usable_checkbox() {
        val geometry = no.aura.os.v2.details.EpisodeActionGeometry
        val checkbox = geometry.watchWidth(geometry.WIDTH)
        assertEquals(66.4f, checkbox, 0.001f)
        assertEquals(geometry.WIDTH * 0.8f, geometry.WIDTH - checkbox, 0.001f)
        assertTrue(checkbox >= geometry.MIN_WATCH_WIDTH)
        assertTrue(geometry.HEIGHT >= 48f)
        val row = src("details/DetailsComponents.kt").substringAfter("Row(Modifier.fillMaxWidth().height(EpisodeActionGeometry.HEIGHT.dp))")
            .substringBefore("if (infoOpen)")
        assertFalse(row.contains("spacedBy"))
    }

    @Test fun secondary_hero_focus_is_scoped_and_rail_has_no_background_plate() {
        val focus = src("hero/HeroFocus.kt")
        assertTrue(focus.contains("AuraColors.Focus"))
        assertTrue(src("cards/FocusStyle.kt").contains("AuraColors.Focus"))
        assertFalse(src("shell/AuraSideRail.kt").contains("BackgroundDeep.copy"))
        assertTrue(src("details/DetailsComponents.kt").contains("auraFocusable"))
        assertTrue(src("hero/CinematicHero.kt").contains("auraFocusable"))
    }

    @Test fun both_heroes_start_artwork_at_page_top_under_os_overlay() {
        val hero = src("hero/CinematicHero.kt")
        val details = src("overlay/TitleDetailsHost.kt")
        assertTrue(hero.contains("val ArtworkTop = 0.dp"))
        assertTrue(hero.contains("padding(top = HeroSpec.ArtworkTop).clipToBounds()"))
        assertTrue(details.contains("padding(top = no.aura.os.v2.hero.HeroSpec.ArtworkTop).clip("))
        assertTrue(hero.contains("alignment = Alignment.TopCenter"))
        assertTrue(details.contains("alignment = Alignment.TopCenter"))
    }

    @Test fun simkl_v2_device_flow_combines_form_encoding_and_mandatory_pkce() {
        val simkl = src("simkl/SimklV2Client.kt")
        assertTrue(simkl.contains("application/x-www-form-urlencoded"))
        assertTrue(simkl.contains("code_challenge_method"))
        assertTrue(simkl.contains("code_verifier"))
        assertTrue(simkl.contains("MessageDigest.getInstance(\"SHA-256\")"))
        assertTrue(simkl.contains("slow_down"))
        assertFalse(File("src/main/java/no/aura/os/v2/simkl/SimklLegacyClient.kt").exists())
    }
}
