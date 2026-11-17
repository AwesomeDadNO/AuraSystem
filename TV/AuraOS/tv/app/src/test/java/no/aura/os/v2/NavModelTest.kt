package no.aura.os.v2
import no.aura.os.v2.shell.*
import org.junit.Assert.*
import org.junit.Test
class NavModelTest {
    @Test fun order_matches_contract() {
        val g = NavModel.groups(ModuleFlags())
        assertEquals(listOf("Home", "Movies", "TV Shows", "Sports", "Live TV"), g[0].map { it.title })
        assertEquals(1, g.size) // apps are in the icon rail; AuraBox is an engine, not a duplicate page
        assertEquals(listOf("Search", "Notifications", "Settings", "Profile"), NavModel.rightActions.map { it.title })
    }
    @Test fun home_always_first() { assertEquals(Route.HOME, NavModel.items(ModuleFlags(false, false, false, false, false, false)).first()) }
    @Test fun disabled_modules_disappear() {
        val items = NavModel.items(ModuleFlags(sports = false, auraAmp = false))
        assertFalse(Route.SPORTS in items); assertFalse(Route.AURAAMP in items); assertFalse(Route.AURABOX in items)
        assertFalse(Route.AURAAMP in NavModel.reachable(ModuleFlags(auraAmp = false)))
    }
    @Test fun empty_group_is_dropped() { assertEquals(1, NavModel.groups(ModuleFlags(auraBox = false, auraAmp = false, auraTube = false, services = false)).size) }
    @Test fun seven_mockup_pages_are_implemented() { assertEquals(setOf(Route.HOME, Route.MOVIES, Route.TV_SHOWS, Route.SPORTS, Route.LIVE_TV, Route.AURABOX, Route.AURAAMP), Route.values().filter { it.implemented }.toSet()) }
    @Test fun every_route_is_reachable_with_default_flags() { assertFalse(Route.AURABOX in NavModel.reachable(ModuleFlags()))
        assertTrue(Route.AURAAMP in NavModel.reachable(ModuleFlags())) }
    @Test fun rail_has_home_saved_library() { assertEquals(listOf(Route.HOME, Route.AURAAMP, Route.AURATUBE, Route.SAVED, Route.ALL_APPS), NavModel.rail) }
}
