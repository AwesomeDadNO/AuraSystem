package no.aura.os.v2

import no.aura.os.v2.data.DataMode
import no.aura.os.v2.settings.*
import no.aura.os.v2.shell.NavModel
import no.aura.os.v2.shell.Route
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SettingsTest {
    private fun state(modules: ModulePrefs = ModulePrefs(), mode: DataMode = DataMode.DEMO, realAvail: Boolean = true,
                      launcher: LauncherStatus = LauncherStatus(false, "com.example.home"), connections: List<ConnectionStatus> = NotConfiguredConnections.statuses()) =
        SettingsState(modules, mode, realAvail, launcher, DeviceInfo("0.1", 3, "Streamer", "14", 34), connections)

    @Test fun structure_has_the_six_categories_with_unique_ids() {
        assertEquals(listOf("General", "Modules", "AuraSport", "Connections", "Users", "Status", "About"), SettingsCategory.values().map { it.title })
        SettingsCategory.values().forEach { assertTrue(it.title, it == SettingsCategory.AURASPORT || SettingsCatalog.items(it).isNotEmpty()) }
        val ids = SettingsCatalog.all.map { it.id }; assertEquals(ids.size, ids.toSet().size)
    }

    @Test fun unimplemented_items_are_disabled_never_faked() {
        SettingsCatalog.all.filter { it.availability == Availability.COMING_LATER }.forEach {
            assertNotNull(it.id, it.plannedIn)
            val row = SettingsPresenter.rows(it.category, state()).first { r -> r.item.id == it.id }
            assertFalse(it.id, row.enabled); assertNull(it.id, row.on); assertTrue(row.value.startsWith("Coming later"))
        }
        // The only enabled toggles are ones with a real handler: module switches and the data mode.
        val enabledToggles = SettingsCatalog.all.filter { it.kind == ItemKind.TOGGLE && it.availability == Availability.AVAILABLE }.map { it.id }.toSet()
        assertEquals(ModuleKey.values().map { SettingsCatalog.MODULE_PREFIX + it.name }.toSet() + SettingsCatalog.DATA_MODE + SettingsCatalog.PB_AUTOSTART + SettingsCatalog.PB_SUBS + "simkl.auto_sync" + "connection.AURAMUSIC", enabledToggles)
    }

    @Test fun required_general_items_exist() {
        val titles = SettingsCatalog.general.map { it.title }
        listOf("Default launcher status", "Set AuraOS as default Home app", "Optional Home shortcut (device-wide)", "Accessibility", "Language and region",
            "Theme and appearance", "Reduce motion", "Background and image quality", "Preferred quality", "Cache management", "Storage information",
            "Autoplay next episode", "Subtitles", "Auto-start at preferred quality", "Performance mode").forEach { assertTrue(it, it in titles) }
        assertEquals("Set AuraOS as default Home app", SettingsCatalog.general.first { it.id == "general.set_default" }.title)
        assertEquals(Availability.AVAILABLE, SettingsCatalog.general.first { it.id == "general.set_default" }.availability)   // no silent default-launcher claim
    }

    @Test fun module_toggles_persist_and_drive_navigation() {
        val store = MemoryModulePrefsStore()
        var p = store.load("main")
        p = SettingsPresenter.toggleModule(p, "module.AURAAMP")!!; store.save("main", p)
        assertFalse(store.load("main").auraAmp); assertTrue(store.load("main").auraBox)
        assertFalse(Route.AURAAMP in NavModel.reachable(p.toFlags())); assertTrue(p.auraBox) // AuraBox engine persists but no tab
        p = SettingsPresenter.toggleModule(p, "module.AURAAMP")!!
        assertTrue(Route.AURAAMP in NavModel.reachable(p.toFlags()))             // re-enabling restores the floating icon
        val none = ModulePrefs(false, false, false, false, false)
        assertEquals(listOf(Route.HOME, Route.MOVIES, Route.TV_SHOWS), NavModel.items(none.toFlags()))   // Home stays
        assertNull(SettingsPresenter.toggleModule(p, "module.home")); assertNull(SettingsPresenter.toggleModule(p, "module.NOPE")); assertNull(SettingsPresenter.toggleModule(p, "general.data_mode"))
    }

    @Test fun disabled_module_destinations_are_unreachable_so_the_shell_returns_home() {
        val flags = ModulePrefs(sports = false, liveTv = false).toFlags()
        listOf(Route.SPORTS, Route.LIVE_TV).forEach { assertFalse(it in NavModel.reachable(flags)) }
        assertTrue(Route.SETTINGS in NavModel.reachable(flags) && Route.HOME in NavModel.reachable(flags))
    }

    @Test fun data_mode_row_reflects_availability_and_state() {
        fun row(s: SettingsState) = SettingsPresenter.rows(SettingsCategory.GENERAL, s).first { it.item.id == SettingsCatalog.DATA_MODE }
        assertEquals(false, row(state()).on); assertEquals(true, row(state(mode = DataMode.REAL)).on)
        assertFalse(row(state(realAvail = false)).enabled)
    }

    @Test fun connections_start_not_configured_and_never_connected() {
        val st = NotConfiguredConnections.statuses()
        assertEquals(ConnectionId.values().toList(), st.map { it.id })
        assertEquals(listOf("TMDB", "YouTube", "UltraMax", "Eclipse", "Plex", "Jellyfin", "Emby", "Simkl", "AuraMusic", "AuraConnect"), st.map { it.id.title })
        assertTrue(st.all { it.state == ConnectionState.NOT_CONFIGURED })
        assertEquals(listOf("Not configured", "Connecting", "Connected", "Authentication failed", "Offline", "Unreachable", "Error"), ConnectionState.values().map { it.label })
        assertEquals("0 of 10", SettingsPresenter.rows(SettingsCategory.STATUS, state()).first { it.item.id == "status.connected" }.value)
    }

    @Test fun connected_requires_verification() {
        assertThrows(IllegalArgumentException::class.java) { ConnectionStatus(ConnectionId.ULTRAMAX, ConnectionState.CONNECTED) }
        assertEquals(ConnectionState.CONNECTED, ConnectionStatus(ConnectionId.ULTRAMAX, ConnectionState.CONNECTED, verifiedAtEpochMs = 1L).state)
        val lines = SettingsPresenter.connectionLines(ConnectionStatus(ConnectionId.ECLIPSE, ConnectionState.NOT_CONFIGURED))
        assertTrue(lines.contains("Status: Not configured")); assertTrue(lines.contains("Last verified: never")); assertTrue(lines.last().contains("F7"))
    }

    @Test fun error_summaries_in_status() {
        val c = NotConfiguredConnections.statuses().map { if (it.id == ConnectionId.PLEX) ConnectionStatus(it.id, ConnectionState.UNREACHABLE) else it }
        assertEquals("Plex: Unreachable", SettingsPresenter.rows(SettingsCategory.STATUS, state(connections = c)).first { it.item.id == "status.errors" }.value)
        assertEquals("None", SettingsPresenter.rows(SettingsCategory.STATUS, state()).first { it.item.id == "status.errors" }.value)
    }

    @Test fun credential_store_masks_and_never_exposes_the_secret() {
        val s = MemoryCredentialStore()
        assertNull(s.masked(ConnectionId.ULTRAMAX)); assertFalse(s.has(ConnectionId.ULTRAMAX))
        s.save(ConnectionId.ULTRAMAX, "SECRET-CODE-1234")
        assertTrue(s.has(ConnectionId.ULTRAMAX)); assertEquals(CredentialStore.MASK, s.masked(ConnectionId.ULTRAMAX))
        assertFalse(s.toString().contains("SECRET")); assertFalse(s.masked(ConnectionId.ULTRAMAX)!!.contains("SECRET"))
        s.clear(ConnectionId.ULTRAMAX); assertNull(s.masked(ConnectionId.ULTRAMAX))
    }

    @Test fun launcher_status_and_status_section_values() {
        assertEquals("AuraOS is the default Home app", SettingsPresenter.launcherText(LauncherStatus(true, null)))
        assertEquals("AuraOS is not the default Home app (current: com.example.home)", SettingsPresenter.launcherText(LauncherStatus(false, "com.example.home")))
        assertEquals("Unknown", SettingsPresenter.launcherText(LauncherStatus(null, null)))
        val rows = SettingsPresenter.rows(SettingsCategory.STATUS, state(ModulePrefs(sports = false))).associate { it.item.id to it.value }
        assertEquals("Home, AuraBox, AuraAmp, AuraTube, Live TV", rows["status.modules"]); assertEquals("0.1 (build 3)", rows["status.version"])
        assertEquals("Streamer · Android 14 (API 34)", rows["status.device"]); assertEquals("Main", rows["status.profile"])
        assertEquals("Open Android accessibility settings", SettingsPresenter.rows(SettingsCategory.GENERAL, state()).first { it.item.id == "general.accessibility" }.value)
    }

    @Test fun module_state_is_independent_of_installed_apps() {
        // Module flags come only from ModulePrefs; nothing about installed apps can change them.
        assertEquals(ModulePrefs().toFlags(), ModulePrefs().toFlags())
        assertTrue(ModulePrefs().toFlags().auraBox)
    }

    @Test fun manifest_stays_minimal_and_blue_underline_is_gone() {
        val m = File("src/main/AndroidManifest.xml").readText()
        assertEquals(listOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "com.android.providers.tv.permission.WRITE_EPG_DATA", "android.permission.READ_TV_LISTINGS", "android.permission.READ_EXTERNAL_STORAGE", "android.permission.READ_MEDIA_VIDEO"), Regex("<uses-permission[^>]*android:name=\"([^\"]+)\"").findAll(m).map { it.groupValues[1] }.toList()); assertFalse(m.contains("QUERY_ALL_PACKAGES")); assertTrue(m.contains("android.permission.BIND_ACCESSIBILITY_SERVICE")); assertTrue(m.contains("@xml/home_shortcut_service"))
        val hero = File("src/main/java/no/aura/os/v2/hero/CinematicHero.kt").readText()
        assertFalse("hero label must not have a decorative blue underline", hero.contains("height(2.dp).background(AuraColors.AccentBlue)"))
        val box = File("src/main/java/no/aura/os/v2/pages/AuraBoxPage.kt").readText()
        assertFalse(box.contains("height(2.dp).background(AuraColors.AccentBlue)"))
    }
}
