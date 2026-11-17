package no.aura.os.v2

import no.aura.os.v2.launcher.HomeSetupPolicy
import org.junit.Assert.*
import org.junit.Test

class HomeSetupBehaviorTest {
    @Test fun actuallyAttemptsRoleBeforeFallbackAndStopsAfterSuccess() {
        val attempted = mutableListOf<String>()
        assertTrue(HomeSetupPolicy.attempt(listOf("role", "home", "settings")) {
            attempted.add(it)
            if (it == "role") throw SecurityException("OEM blocked")
        })
        assertEquals(listOf("role", "home"), attempted)
    }
    @Test fun noLaunchableSettingsReturnsFailure() {
        assertFalse(HomeSetupPolicy.attempt(listOf("role", "home", "settings")) { throw IllegalStateException() })
    }

    @Test fun denialOrCancellationCannotClaimDefaultHome() {
        val result = HomeSetupPolicy.state("aura", "stock.launcher", true)
        assertFalse(result.isDefault)
        assertEquals("stock.launcher", result.currentPackage)
    }
    @Test fun unavailableSettingsGiveManualInstructions() {
        val result = HomeSetupPolicy.state("aura", null, false)
        assertFalse(result.canRequest)
        assertFalse(result.isDefault)
        assertTrue(result.message.contains("Android Settings"))
    }
    @Test fun actualHandlerEstablishesSuccessAndResolverDoesNot() {
        assertTrue(HomeSetupPolicy.state("aura", "aura", true).isDefault)
        assertFalse(HomeSetupPolicy.state("aura", "android", true).isDefault)
        assertNull(HomeSetupPolicy.state("aura", "android", true).currentPackage)
    }
}
