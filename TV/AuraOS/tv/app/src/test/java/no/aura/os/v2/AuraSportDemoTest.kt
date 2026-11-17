package no.aura.os.v2
import no.aura.os.v2.sports.*
import org.junit.Assert.*
import org.junit.Test
class AuraSportDemoTest {
    @Test fun league_selection_includes_all_matches_and_filters_views() {
        val prefs=AuraSportPrefs(leagues=setOf(SportLeague.PL))
        val all=AuraSportDemo.matches(prefs, SportLeague.PL, SportView.FOR_YOU)
        assertEquals(6, all.size)
        assertEquals(3, AuraSportDemo.matches(prefs, SportLeague.PL, SportView.LIVE).size)
        assertEquals(2, AuraSportDemo.matches(prefs, SportLeague.PL, SportView.UPCOMING).size)
        assertEquals(1, AuraSportDemo.matches(prefs, SportLeague.PL, SportView.RESULTS).size)
        assertTrue(AuraSportDemo.matches(prefs, SportLeague.UCL, SportView.FOR_YOU).isEmpty())
        assertEquals(all.size, all.map { it.id }.distinct().size)
    }
    @Test fun news_selection_is_independent_and_empty_is_valid() {
        val prefs=AuraSportPrefs(leagues=setOf(SportLeague.PL), news=emptySet())
        assertEquals(6, AuraSportDemo.matches(prefs, SportLeague.PL, SportView.FOR_YOU).size)
        assertTrue(AuraSportDemo.matches(prefs.copy(leagues=emptySet()), null, SportView.FOR_YOU).isEmpty())
    }
    @Test fun demo_is_wired_to_profile_settings_and_has_no_network_claim() {
        fun src(name:String)=java.io.File("src/main/java/no/aura/os/v2/$name.kt").readText()
        val shell=src("shell/AuraShell")
        assertTrue(shell.contains("profileId = profileState.activeId, onSettings"))
        assertTrue(shell.contains("initialCategory = settingsStart"))
        assertTrue(src("pages/SettingsPage").contains("AuraSportSettings(state.profiles.activeId"))
        val page=src("pages/SportsPage")
        assertTrue(page.contains("Ingen live-oppdatering"))
        assertTrue(page.contains("Tilbyder ikke bekreftet"))
        assertFalse(page.contains("launchApp("))
        assertEquals(25f, no.aura.os.v2.shell.NavMetrics.FONT, 0f)
        val details=src("details/DetailsComponents")
        val season=details.substringAfter("internal fun SeasonTab").substringBefore("internal object EpisodeActionGeometry")
        assertFalse(season.contains("auraFocusable("))
        assertTrue(season.contains("auraNavigationFocusable"))
        assertTrue(details.substringAfter("private fun EpisodeWatchCheckbox").contains("Icon(Icons.Rounded.Check"))
    }
    @Test fun spoiler_mode_hides_scores_without_mutating_fixtures() {
        val match=AuraSportDemo.fixtures.first()
        assertEquals("Resultat skjult", match.result(AuraSportPrefs(spoilersHidden=true)))
        assertEquals("1 – 0", match.result(AuraSportPrefs()))
    }
}
