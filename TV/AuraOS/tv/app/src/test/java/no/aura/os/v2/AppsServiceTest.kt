package no.aura.os.v2

import kotlinx.coroutines.runBlocking
import no.aura.os.v2.apps.*
import no.aura.os.v2.data.DataMode
import no.aura.os.v2.demo.DemoRepositoryImpl
import no.aura.os.v2.demo.ServiceState
import no.aura.os.v2.demo.ServicesDemo
import org.junit.Assert.*
import org.junit.Test
import java.io.File

private class FakeSource(val apps: List<LaunchableApp> = emptyList(), val installed: Set<String> = emptySet(), val fail: Boolean = false) : InstalledAppsSource {
    var calls = 0
    override fun launchableApps(): List<LaunchableApp> { calls++; if (fail) throw SecurityException("no"); return apps }
    override fun isInstalled(packageName: String): Boolean { calls++; if (fail) throw SecurityException("no"); return packageName in installed }
}
private class FakeLauncher(val result: LaunchResult = LaunchResult.LAUNCHED, val throws: Boolean = false) : AppLauncher {
    val launched = mutableListOf<String>()
    override fun launch(packageName: String): LaunchResult { if (throws) error("boom"); launched += packageName; return result }
}

class AppsServiceTest {
    private val own = "no.aura.os.tv.v2demo"
    private fun svc(src: InstalledAppsSource, l: AppLauncher = FakeLauncher()) = AppsService(src, l, own)

    @Test fun installed_and_not_installed_detection() {
        val s = svc(FakeSource(installed = setOf("com.netflix.ninja", "com.hbo.hbonow"))).snapshot().streaming.associate { it.name to it.state }
        assertEquals(ServiceState.INSTALLED, s["Netflix"]); assertEquals(ServiceState.INSTALLED, s["Max"])   // alternate package counts
        assertEquals(ServiceState.NOT_INSTALLED, s["Prime Video"]); assertEquals(ServiceState.NOT_INSTALLED, s["Disney+"])
        assertEquals(listOf("Netflix", "Prime Video", "Disney+", "Max"), svc(FakeSource()).snapshot().streaming.map { it.name })
    }

    @Test fun invisible_or_failing_packages_are_not_installed_and_never_crash() {
        val snap = svc(FakeSource(fail = true)).snapshot()
        assertTrue(snap.streaming.all { it.state == ServiceState.NOT_INSTALLED }); assertTrue(snap.apps.isEmpty())
    }

    @Test fun no_false_connected_states() {
        val everything = FakeSource(apps = ServiceApps.allPackages.map { LaunchableApp(it, it) } + LaunchableApp("com.plexapp.android", "Plex"),
            installed = ServiceApps.allPackages + setOf("com.plexapp.android", "org.jellyfin.androidtv", "tv.emby.embyatv"))
        val snap = svc(everything).snapshot()
        assertEquals(listOf("Plex", "Jellyfin", "Emby", "UltraMax"), snap.sources.map { it.name })
        assertTrue(snap.sources.all { it.state == ServiceState.NOT_CONFIGURED })          // installed Plex is still not connected
        assertTrue((snap.streaming + snap.sources).none { it.state == ServiceState.CONNECTED })
        assertTrue(snap.sources.all { it.toggled() == it })                                 // selecting a tile cannot connect it
    }

    @Test fun all_apps_are_deduplicated_sorted_and_exclude_own_app() {
        val apps = svc(FakeSource(apps = listOf(
            LaunchableApp("b.app", "beta"), LaunchableApp("a.app", "Alpha"), LaunchableApp("b.app", "beta"), LaunchableApp(own, "AuraOS"), LaunchableApp("", "blank"),
        ))).launchableApps()
        assertEquals(listOf("a.app", "b.app"), apps.map { it.packageName })
    }

    @Test fun launch_uses_only_discovered_packages() {
        val l = FakeLauncher()
        val s = svc(FakeSource(apps = listOf(LaunchableApp("a.app", "Alpha"))), l)
        assertEquals(LaunchResult.LAUNCHED, s.launchApp("a.app"))
        assertEquals(LaunchResult.NOT_INSTALLED, s.launchApp("evil.app"))      // arbitrary package names are refused
        assertEquals(LaunchResult.NOT_LAUNCHABLE, s.launchApp(own))
        assertEquals(listOf("a.app"), l.launched)
    }

    @Test fun service_launch_uses_constant_package_and_only_when_installed() {
        val l = FakeLauncher()
        val s = svc(FakeSource(installed = setOf("com.amazon.avod")), l)
        assertEquals(LaunchResult.LAUNCHED, s.launchService("Prime Video")); assertEquals(listOf("com.amazon.avod"), l.launched)
        assertEquals(LaunchResult.NOT_INSTALLED, s.launchService("Netflix"))
        assertEquals(LaunchResult.NOT_LAUNCHABLE, s.launchService("com.netflix.ninja"))     // names only, never packages from input
        assertEquals(LaunchResult.NOT_LAUNCHABLE, s.launchService("Plex"))
    }

    @Test fun launch_failure_is_safe_and_reported() {
        assertEquals(LaunchResult.FAILED, svc(FakeSource(installed = setOf("com.netflix.ninja")), FakeLauncher(throws = true)).launchService("Netflix"))
        assertEquals(LaunchResult.NOT_LAUNCHABLE, svc(FakeSource(installed = setOf("com.netflix.ninja")), FakeLauncher(LaunchResult.NOT_LAUNCHABLE)).launchService("Netflix"))
        assertNull(LaunchFeedback.message(LaunchResult.LAUNCHED, "X"))
        LaunchResult.values().filter { it != LaunchResult.LAUNCHED }.forEach { assertTrue(LaunchFeedback.message(it, "Netflix")!!.startsWith("Netflix")) }
    }

    @Test fun focus_is_restored_once_to_the_launching_tile() {
        val t = LaunchFocusTracker()
        assertNull(t.consumeOnResume())              // first resume of the activity: nothing to restore
        t.onLaunched("svc:Netflix")
        assertEquals("svc:Netflix", t.consumeOnResume()); assertNull(t.consumeOnResume())
    }

    @Test fun demo_and_real_are_isolated() {
        val src = FakeSource(installed = setOf("com.netflix.ninja"))
        // Demo fixtures are independent of any apps source and still reproduce the approved demo.
        assertEquals(DataMode.DEMO, runBlocking { DemoRepositoryImpl.load() }.mode)
        assertEquals(ServiceState.NOT_INSTALLED, ServicesDemo.apps.first { it.name == "Disney+" }.state)
        assertTrue(ServicesDemo.sources.any { it.state == ServiceState.CONNECTED })
        svc(src).snapshot()
        assertEquals(listOf("Home", "Movies", "TV Shows", "Sports", "Live TV", "AuraBox", "AuraAmp", "Services"), runBlocking { DemoRepositoryImpl.load() }.apps)
        assertTrue(src.calls > 0)
        // Mode defaults to Demo and persists only when saved.
        val store = MemoryModeStore(); assertEquals(DataMode.DEMO, store.load()); store.save(DataMode.REAL); assertEquals(DataMode.REAL, store.load())
    }

    @Test fun manifest_is_minimal() {
        val m = File("src/main/AndroidManifest.xml").readText()
        assertFalse(m.contains("QUERY_ALL_PACKAGES")); assertEquals(listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "com.android.providers.tv.permission.WRITE_EPG_DATA", "android.permission.READ_TV_LISTINGS", "android.permission.READ_EXTERNAL_STORAGE", "android.permission.READ_MEDIA_VIDEO"), Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(m).map { it.groupValues[1] }.toList())
        assertTrue(m.contains("LEANBACK_LAUNCHER"))
        ServiceApps.allPackages.forEach { assertTrue("queries must list $it", m.contains("<package android:name=\"$it\"")) }
    }
}
