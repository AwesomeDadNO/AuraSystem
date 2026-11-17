package no.aura.os.v2

import no.aura.os.v2.sports.*
import org.junit.Assert.*
import org.junit.Test

class AuraSportFavoritesTest {
    @Test fun favorites_filter_for_you_but_not_other_views() {
        val prefs = AuraSportPrefs(favoriteTeams = setOf("Arsenal"))
        assertEquals(listOf("PL-0"), AuraSportDemo.matches(prefs, SportLeague.PL, SportView.FOR_YOU).map { it.id })
        assertEquals(3, AuraSportDemo.matches(prefs, SportLeague.PL, SportView.LIVE).size)
        assertTrue(AuraSportDemo.matches(prefs, SportLeague.UCL, SportView.FOR_YOU).isEmpty())
    }
    @Test fun widgets_keep_catalog_order_and_ignore_stale_ids() {
        val prefs = AuraSportPrefs(leagues = emptySet(), widgetFixtureIds = setOf("PL-1", "PL-0", "removed"))
        assertEquals(listOf("PL-0", "PL-1"), AuraSportDemo.widgets(prefs).map { it.id })
        assertEquals(AuraSportDemo.widgets(prefs), AuraSportDemo.matches(prefs, null, SportView.WIDGETS))
        assertTrue(AuraSportDemo.widgets(prefs.copy(widgetFixtureIds = emptySet())).isEmpty())
    }
    @Test fun team_catalog_has_each_available_team_once() {
        assertEquals(36, AuraSportDemo.teams.size)
        assertEquals(AuraSportDemo.teams.size, AuraSportDemo.teams.distinct().size)
        assertTrue(AuraSportDemo.teams.containsAll(listOf("Arsenal", "Brann", "Barcelona")))
    }
}
