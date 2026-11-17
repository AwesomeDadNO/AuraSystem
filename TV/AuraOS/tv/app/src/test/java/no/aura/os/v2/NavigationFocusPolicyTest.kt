package no.aura.os.v2

import java.io.File
import no.aura.os.v2.shell.*
import no.aura.os.v2.theme.*
import org.junit.Assert.*
import org.junit.Test

class NavigationFocusPolicyTest {
    private fun src(name:String)=File("src/main/java/no/aura/os/v2/$name.kt").readText()
    @Test fun orange_navigation_uses_underlines_without_selection_pills() {
        assertEquals(0xFF79D5FFL, AuraPalette.FOCUS_BLUE)
        assertEquals(0xFFE75200L, AuraPalette.BRAND_ORANGE)
        assertEquals(AuraColors.BrandOrange, AuraColors.Focus)
        assertEquals(AuraColors.BrandOrange, AuraColors.Progress)
        assertEquals(0,NavigationFocusPolicy.underlineDp(false,false))
        assertEquals(2,NavigationFocusPolicy.underlineDp(true,false))
        assertEquals(3,NavigationFocusPolicy.underlineDp(false,true))
        assertEquals(3,NavigationFocusPolicy.underlineDp(true,true))
        val helper=src("shell/NavigationFocus")
        assertFalse(helper.contains(".background("))
        assertFalse(helper.contains(".border("))
        assertTrue(helper.contains("this.selected = selected"))
    }
    @Test fun every_page_selects_only_its_own_destination() {
        Route.entries.forEach { current ->
            assertEquals(listOf(current), Route.entries.filter { NavModel.isSelected(it, current) })
        }
        listOf(Route.AURAAMP, Route.AURATUBE, Route.SAVED, Route.ALL_APPS).forEach { current ->
            assertTrue(NavModel.items(ModuleFlags()).none { NavModel.isSelected(it, current) })
        }
        assertTrue(src("shell/AuraTopNavigation").contains("NavModel.isSelected(item.route, active)"))
        assertTrue(src("shell/AuraSideRail").contains("NavModel.isSelected(r, active)"))
    }
    @Test fun focus_is_visible_independently_of_destination_selection() {
        assertFalse(NavigationFocusPolicy.accented(false, false))
        assertTrue(NavigationFocusPolicy.accented(true, false))
        assertTrue(NavigationFocusPolicy.accented(false, true))
        assertTrue(NavigationFocusPolicy.accented(true, true))
    }
    @Test fun chrome_uses_real_scroll_and_restores_transparency_at_top() {
        assertFalse(ChromePolicy.isScrolled(0,0))
        assertTrue(ChromePolicy.isScrolled(0,1))
        assertTrue(ChromePolicy.isScrolled(1,0))
        assertEquals(.8f, ChromePolicy.opacity(false), 0f)
        assertEquals(1f, ChromePolicy.opacity(true), 0f)
        assertTrue(src("pages/ShelfPage").contains("ReportChromeScroll(ui.page)"))
        assertTrue(src("pages/LiveHomePage").contains("ReportChromeScroll(page)"))
        assertTrue(src("overlay/TitleDetailsHost").contains("state = detailScroll"))
        assertTrue(src("overlay/CategoryCatalogHost").contains("LocalChromeScroll provides categoryChrome"))
        assertTrue(src("shell/AuraTopNavigation").contains("LocalReduceMotion.current"))
    }
    @Test fun destinations_are_text_only_for_every_legacy_preference_and_shortcuts_are_untinted() {
        NavStyle.entries.forEach { style -> Route.entries.forEach { assertFalse(style.showsIcon(it)) } }
        val top=src("shell/AuraTopNavigation")
        val item=top.substringAfter("private fun NavItem(").substringBefore("private fun AppShortcutItem")
        assertFalse(item.contains("Icon("))
        val shortcut=top.substringAfter("private fun AppShortcutItem").substringBefore("private fun AllAppsAction")
        assertTrue(shortcut.contains("ShortcutBadge(")); assertFalse(shortcut.contains("tint"))
        assertTrue(src("shell/AuraSideRail").contains(".size(52.dp)"))
    }
    @Test fun interactive_kotlin_uses_shared_accent_without_old_blue_or_yellow_override() {
        File("src/main/java/no/aura/os/v2").walkTopDown().filter { it.extension=="kt" && it.parentFile.name !in listOf("brand","theme") }.forEach { f ->
            val code=f.readText()
            assertFalse(f.path, code.contains("AuraColors.FocusBlue") || code.contains("0xFFFFD54F"))
        }
    }
}
