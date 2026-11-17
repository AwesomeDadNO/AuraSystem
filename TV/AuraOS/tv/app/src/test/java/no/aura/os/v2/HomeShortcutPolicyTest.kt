package no.aura.os.v2

import no.aura.os.v2.launcher.HomeWindow
import no.aura.os.v2.launcher.HomeShortcutPolicy
import no.aura.os.v2.launcher.HomeShortcutPolicyGate
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HomeShortcutPolicyTest {
    private val home = HomeWindow("com.vendor.home", "com.vendor.home.HomeActivity")
    private val aura = HomeWindow("aura", "aura.MainActivity")
    @Test fun requiresResolvedSystemHomeAndExcludesUnsafeHandlers() {
        assertEquals(home, HomeShortcutPolicy.validatedHome(home, "aura", true, true))
        assertNull(HomeShortcutPolicy.validatedHome(home, "aura", false, true))
        assertNull(HomeShortcutPolicy.validatedHome(home, "aura", true, false))
        assertNull(HomeShortcutPolicy.validatedHome(null, "aura", true, true))
        for (pkg in listOf("android", "aura", "com.android.systemui", "com.android.settings", "com.android.tv.settings"))
            assertNull(HomeShortcutPolicy.validatedHome(HomeWindow(pkg, "Home"), "aura", true, true))
    }
    @Test fun exactComponentOnlyNeverPackageSubstringsOrOtherActivities() {
        for (window in listOf(home.copy(packageName = "com.vendor.home.fake"), home.copy(className = "com.vendor.home.Settings"), aura))
            assertFalse(HomeShortcutPolicyGate().shouldLaunch(true, home, window, 5000))
        assertFalse(HomeShortcutPolicyGate().shouldLaunch(true, null, home, 5000))
        assertFalse(HomeShortcutPolicyGate().shouldLaunch(false, home, home, 5000))
    }
    @Test fun repeatedHomeEventsNeverLoopAndFastTransitionsAreThrottled() {
        val gate = HomeShortcutPolicyGate()
        assertTrue(gate.shouldLaunch(true, home, home, 5000))
        assertFalse(gate.shouldLaunch(true, home, home, 9000))
        assertFalse(gate.shouldLaunch(true, home, aura, 9100))
        assertTrue(gate.shouldLaunch(true, home, home, 9200))
        assertFalse(gate.shouldLaunch(true, home, aura, 9300))
        assertFalse(gate.shouldLaunch(true, home, home, 9400))
        assertFalse(gate.shouldLaunch(true, home, home, 14000))
    }
    @Test fun disablingAndUnknownWindowsNeverLaunch() {
        val gate = HomeShortcutPolicyGate()
        assertTrue(gate.shouldLaunch(true, home, home, 5000))
        assertFalse(gate.shouldLaunch(false, home, home, 9000))
        assertFalse(gate.shouldLaunch(false, home, aura, 10000))
        assertFalse(gate.shouldLaunch(true, home, null, 11000))
        // Reappearing stock Home after Back is intentionally also eligible.
        assertTrue(gate.shouldLaunch(true, home, home, 12000))
    }
    @Test fun serviceDeclaresOnlyWindowMetadataCapability() {
        val base = File("src/main")
        val config = File(base, "res/xml/home_shortcut_service.xml").readText()
        assertTrue(config.contains("android:accessibilityEventTypes=\"typeWindowStateChanged\""))
        assertTrue(config.contains("android:canRetrieveWindowContent=\"false\""))
        for (capability in listOf("flagRetrieveInteractiveWindows", "canRequestFilterKeyEvents", "canPerformGestures", "flagRequestFilterKeyEvents"))
            assertFalse(config.contains(capability))
        val service = File(base, "java/no/aura/os/v2/launcher/AuraHomeShortcutService.kt").readText()
        for (api in listOf("rootInActiveWindow", "getRootInActiveWindow", "event.source", "onKeyEvent", "dispatchGesture", "performGlobalAction"))
            assertFalse(service.contains(api))
    }
}
