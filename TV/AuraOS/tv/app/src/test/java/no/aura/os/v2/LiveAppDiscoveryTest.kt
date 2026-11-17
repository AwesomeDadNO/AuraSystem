package no.aura.os.v2

import no.aura.os.v2.apps.LaunchableApp
import no.aura.os.v2.livetv.*
import org.junit.Assert.*
import org.junit.Test

class LiveAppDiscoveryTest {
    @Test fun candidatesAreOnlyActuallyDiscoveredExactPackagesAndOnePerProvider() {
        val installed = listOf(LaunchableApp("no.tv2.sumo", "TV 2"), LaunchableApp("no.tv2.tv2play", "TV 2 alternate"),
            LaunchableApp("no.nrk.tv", "NRK"), LaunchableApp("clone.no.rikstv.app", "RiksTV"), LaunchableApp("com.netflix.ninja", "Netflix"))
        assertEquals(listOf("no.tv2.sumo", "no.nrk.tv"), LiveAppDiscovery.pick(installed).map { it.packageName })
        assertTrue(LiveAppDiscovery.pick(installed, child = true).isEmpty())
        assertNull(LiveAppDiscovery.appFor("Altibox", installed))
        assertEquals("no.nrk.tv", LiveAppDiscovery.appFor("NRK TV", installed)?.packageName)
    }
    @Test fun scheduleIsExplicitFixedPreviewAndDoesNotContainStreamsOrProgrammeDeepLinks() {
        assertEquals(3, LiveSchedulePreview.programmes.size)
        assertEquals("20:00", LiveSchedulePreview.programmes.first().time)
        assertEquals("TV 2 Play", LiveSchedulePreview.programmes.first().provider)
        assertTrue(LiveSchedulePreview.programmes.all { it.art in no.aura.os.v2.cards.CategoryArtwork.names })
        val shell = java.io.File("src/main/java/no/aura/os/v2/shell/AuraShell.kt").readText()
        assertTrue(shell.contains("Route.LIVE_TV -> LiveHomePage"))
        assertFalse(shell.contains("LiveStreamPlayerHost("))
        assertFalse(shell.contains("LiveTvLoad("))
    }
}
