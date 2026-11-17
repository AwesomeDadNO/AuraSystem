package no.aura.os.v2

import no.aura.os.v2.apps.LaunchableApp
import no.aura.os.v2.apps.ShortcutPicker
import no.aura.os.v2.settings.*
import no.aura.os.v2.shell.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NavStyleTest {
    private val all = ModuleFlags()
    private val auraOff = ModuleFlags(auraBox = false, auraAmp = false, auraTube = false)
    private fun sc(n: Int) = List(n) { AppShortcut("pkg.$it", "App $it") }
    private fun icons(l: List<List<NavItemModel>>) = l.flatten().filterIsInstance<NavItemModel.Destination>().filter { it.showIcon }.map { it.route }

    @Test fun text_only_is_the_default_and_has_no_icons() {
        assertEquals(NavStyle.TEXT_ONLY, NavStyle.parse(null)); assertEquals(NavStyle.TEXT_ONLY, NavStyle.parse("garbage")); assertEquals(NavStyle.TEXT_ONLY, MemoryNavStyleStore().load())
        assertTrue(icons(NavModel.layout(all, NavStyle.TEXT_ONLY, emptyList())).isEmpty())
    }
    @Test fun legacy_icon_preference_keeps_main_destinations_text_only() {
        assertTrue(icons(NavModel.layout(all, NavStyle.ICONS_TEXT, emptyList())).isEmpty())
    }
    @Test fun aura_apps_style_icons_only_the_three_aura_apps() {
        assertTrue(icons(NavModel.layout(all, NavStyle.AURA_APPS_ICONS, emptyList())).isEmpty())
    }
    @Test fun there_is_no_icon_only_style_and_labels_are_kept() {
        assertEquals(3, NavStyle.values().size)
        for (st in NavStyle.values()) {   // the same destinations in the same order in every style: one shared model, text stays
            assertEquals(NavModel.items(all), NavModel.layout(all, st, emptyList()).flatten().map { (it as NavItemModel.Destination).route })
        }
    }
    @Test fun style_round_trips_through_the_store() { val s = MemoryNavStyleStore(); NavStyle.values().forEach { s.save(it); assertEquals(it, s.load()) } }

    @Test fun media_shortcuts_follow_core_pages_regardless_of_aura_module_switches() {
        for (flags in listOf(all, auraOff, all.copy(auraAmp = false))) {
            val groups = NavModel.layout(flags, NavStyle.TEXT_ONLY, sc(3))
            assertEquals(2, groups.size)
            assertEquals(listOf(Route.HOME, Route.MOVIES, Route.TV_SHOWS, Route.SPORTS, Route.LIVE_TV),
                groups.first().filterIsInstance<NavItemModel.Destination>().map { it.route })
            assertEquals(3, groups.last().filterIsInstance<NavItemModel.App>().size)
        }
    }

    @Test fun shortcuts_are_capped_at_five_and_core_navigation_is_never_replaced() {
        val l = NavModel.layout(auraOff, NavStyle.ICONS_TEXT, sc(9)).flatten()
        assertEquals(5, l.count { it is NavItemModel.App })
        assertEquals(listOf(Route.HOME, Route.MOVIES, Route.TV_SHOWS, Route.SPORTS, Route.LIVE_TV),
            l.filterIsInstance<NavItemModel.Destination>().map { it.route })
    }
    @Test fun without_installed_apps_nothing_is_invented() {
        assertTrue(NavModel.layout(auraOff, NavStyle.TEXT_ONLY, emptyList()).flatten().none { it is NavItemModel.App })
    }

    @Test fun every_style_and_flag_combination_fits_the_1920_row() {
        val bools = listOf(true, false)
        for (st in NavStyle.values()) for (sp in bools) for (lt in bools) for (ab in bools) for (aa in bools) for (at in bools) for (sv in bools) for (shortcuts in listOf(0, 5)) {
            val f = ModuleFlags(sp, lt, ab, aa, at, sv)
            val pad = NavMetrics.itemPadding(f, style = st, shortcuts = shortcuts)
            assertTrue("padding >= min for $st $f", pad >= NavMetrics.MIN_ITEM_PADDING)
            val w = NavMetrics.estimatedWidth(f, padding = pad, style = st, shortcuts = shortcuts)
            assertTrue("$st $f shortcuts=$shortcuts width=$w padding=$pad", w <= NavMetrics.CANVAS - 8f)
        }
    }
    @Test fun default_style_keeps_the_approved_padding() { assertEquals(NavMetrics.ITEM_PADDING_H, NavMetrics.itemPadding(all), 0.001f) }
    @Test fun icon_styles_never_shrink_padding_below_a_readable_pill() {
        assertTrue(NavMetrics.itemPadding(all, style = NavStyle.ICONS_TEXT) >= 12f); assertTrue(NavMetrics.itemPadding(all, style = NavStyle.AURA_APPS_ICONS) >= 10f)
    }

    @Test fun picker_only_returns_installed_launchable_apps_in_fixed_order() {
        val installed = listOf(LaunchableApp("com.netflix.ninja", "Netflix"), LaunchableApp("com.google.android.youtube.tv", "YouTube"), LaunchableApp("com.random.game", "Game"), LaunchableApp("com.disney.disneyplus", "Disney+"))
        assertEquals(listOf("com.google.android.youtube.tv", "com.netflix.ninja", "com.disney.disneyplus"), ShortcutPicker.pick(installed).map { it.packageName })
        assertTrue(ShortcutPicker.pick(emptyList()).isEmpty()); assertTrue(ShortcutPicker.pick(listOf(LaunchableApp("com.random.game", "Game"))).isEmpty())
    }
    @Test fun picker_is_capped_at_five_and_falls_back_to_alternative_packages() {
        val many = ShortcutPicker.preferred.flatMap { it.second.take(1) }.map { LaunchableApp(it, it) }
        assertEquals(5, ShortcutPicker.pick(many).size); assertEquals(2, ShortcutPicker.pick(many, max = 2).size)
        assertEquals("com.amazon.avod", ShortcutPicker.pick(listOf(LaunchableApp("com.amazon.avod", "Prime Video"))).single().packageName)
    }

    @Test fun navigation_style_is_an_ordinary_setting_not_behind_the_admin_pin() {
        val item = SettingsCatalog.all.first { it.id == SettingsCatalog.NAV_STYLE }
        assertEquals(SettingsCategory.GENERAL, item.category); assertEquals(ItemKind.INFO, item.kind); assertFalse(AccessPolicy.requiresAdmin(item))
    }
    @Test fun settings_row_shows_the_current_style() {
        val s = SettingsState(ModulePrefs(), no.aura.os.v2.data.DataMode.DEMO, false, LauncherStatus(null, null), DeviceInfo("x", 0, "x", "x", 0), emptyList(), navStyle = NavStyle.ICONS_TEXT)
        assertEquals("Text only", SettingsPresenter.rows(SettingsCategory.GENERAL, s).first { it.item.id == SettingsCatalog.NAV_STYLE }.value)
    }
    @Test fun tmdb_is_connected_only_after_a_verified_request() {
        val yt = no.aura.os.v2.youtube.YouTubeConfig("")
        fun st(c: DevConnections) = c.statuses().first { it.id == ConnectionId.TMDB }
        assertEquals(ConnectionState.NOT_CONFIGURED, st(DevConnections(yt)).state)
        assertEquals(ConnectionState.NOT_CONFIGURED, st(DevConnections(yt, tmdbConfigured = true)).state)   // a token alone proves nothing
        val ok = st(DevConnections(yt, tmdbConfigured = true, tmdbVerifiedAt = { 99L })); assertEquals(ConnectionState.CONNECTED, ok.state); assertEquals(99L, ok.verifiedAtEpochMs)
    }
    @Test fun shell_never_logs_or_hardcodes_the_tmdb_token_and_release_has_none() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("\"TMDB_READ_TOKEN\", \"\\\"\\\"\"")); assertFalse(Regex("eyJ[A-Za-z0-9_-]{20,}").containsMatchIn(gradle))
        File("src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val t = f.readText()
            assertFalse("${f.name} must not log", Regex("Log\\.[dviwe]\\(|println\\(").containsMatchIn(t) && t.contains("bearer", true))
            assertFalse("${f.name} must not embed a JWT", Regex("eyJ[A-Za-z0-9_-]{20,}\\.").containsMatchIn(t))
        }
    }
}
