package no.aura.os.v2

import no.aura.os.v2.demo.ServiceState
import no.aura.os.v2.demo.ServicesDemo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicesDemoTest {
    @Test fun apps_and_sources_are_distinct_state_families() {
        assertTrue(ServicesDemo.apps.all { it.state == ServiceState.INSTALLED || it.state == ServiceState.NOT_INSTALLED })
        assertTrue(ServicesDemo.sources.all { it.state != ServiceState.INSTALLED && it.state != ServiceState.NOT_INSTALLED })
    }
    @Test fun toggling_round_trips_and_unavailable_is_fixed() {
        ServicesDemo.apps.forEach { assertEquals(it, it.toggled().toggled()) }
        ServicesDemo.sources.filter { it.state != ServiceState.UNAVAILABLE }.forEach { assertEquals(it, it.toggled().toggled()) }
        val u = ServicesDemo.sources.first { it.state == ServiceState.UNAVAILABLE }
        assertEquals(u, u.toggled())
    }
    @Test fun names_cover_the_approved_list() {
        val n = (ServicesDemo.apps + ServicesDemo.sources).map { it.name }
        listOf("Netflix", "Prime Video", "Disney+", "Max", "Plex", "Jellyfin", "Emby", "UltraMax").forEach { assertTrue(it, it in n) }
    }
}
