package no.aura.os.v2

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Protect the explicitly locked demo boundary: native module, profile-local state, no implied audio. */
class AuraAmpDemoIntegrationTest {
    @Test fun realModeUsesOperationalAmpAndDemoRemainsIsolated() {
        val shell = File("src/main/java/no/aura/os/v2/shell/AuraShell.kt").readText()
        assertTrue(shell.contains("route in setOf(Route.LIVE_TV, Route.AURAAMP)"))
        assertTrue(shell.contains("else AuraAmpDemoPage"))
        assertTrue(shell.contains("AuraAmpPage(profileState.activeId,entry,ampPlayback!!"))
        assertTrue(shell.contains("remember(profileState.activeId) { AuraAmpDemoSession() }"))
        assertTrue(shell.contains("c != null && route != Route.AURAAMP"))
        assertTrue(shell.contains("AuraAmp · Demo · No audio"))
        assertTrue(File("src/main/assets/auraamp/approved_design_v5.png").isFile)
    }
    @Test fun preparingFocusIsReassignedWhenPlaybackBecomesReady() {
        val player = File("src/main/java/no/aura/os/v2/overlay/VideoPlayerHost.kt").readText()
        assertTrue(player.contains("LaunchedEffect(controls, menu, model.status)"))
        assertTrue(player.contains("model.status == PlayerStatus.PREPARING) root.requestFocus()"))
        assertTrue(player.contains("contentScale = androidx.compose.ui.layout.ContentScale.Crop"))
        assertTrue(player.contains("AuraBrandLabel(BrandLabels.AURABOX)"))
    }
}
